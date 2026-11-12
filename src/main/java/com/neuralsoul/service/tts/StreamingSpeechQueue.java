package com.neuralsoul.service.tts;

import com.neuralsoul.config.SpeechGenerationSettingsStore.Common;
import com.neuralsoul.service.api.AudioPlayer;
import com.neuralsoul.service.api.TtsService;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sequential, bounded, cancellable TTS queue fed by the LLM token stream. */
public final class StreamingSpeechQueue implements AutoCloseable {
  private final ArrayBlockingQueue<String> chunks;
  private final TtsService tts;
  private final AudioPlayer player;
  private final Common config;
  private final StringBuilder pending = new StringBuilder();
  private final Thread worker;
  private final String traceId;
  private final long createdNs = System.nanoTime();
  private int receivedFragments;
  private int receivedCharacters;
  private int processed;
  private boolean firstFragmentLogged;
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private volatile boolean completed;
  private volatile Exception error;
  private volatile boolean overflow;
  private volatile int submitted;

  public StreamingSpeechQueue(TtsService tts, AudioPlayer player, Common config) {
    this(tts, player, config, "untracked");
  }

  public StreamingSpeechQueue(TtsService tts, AudioPlayer player, Common config, String traceId) {
    this.traceId = traceId == null ? "untracked" : traceId;
    this.tts = tts;
    this.player = player;
    this.config = config;
    chunks = new ArrayBlockingQueue<>(config.queueLimit());
    TtsTrace.log(this.traceId, "QUEUE_CREATED", "minChars=" + config.minChunkCharacters()
        + " maxChars=" + config.maxChunkCharacters() + " limit=" + config.queueLimit()
        + " flushDelayMs=" + config.flushDelayMs());
    worker = new Thread(this::work, "tts-stream-queue");
    worker.setDaemon(true);
    worker.start();
  }
  public synchronized void accept(String token) {
    if (completed || cancelled.get() || token == null || token.isEmpty()) return;
    pending.append(token);
    receivedFragments++;
    receivedCharacters += token.length();
    if (!firstFragmentLogged) {
      firstFragmentLogged = true;
      TtsTrace.log(traceId, "QUEUE_FIRST_TEXT", "fragmentChars=" + token.length()
          + " elapsedMs=" + elapsedMs());
    }
    if (receivedFragments % 40 == 0) {
      TtsTrace.log(traceId, "QUEUE_INPUT_PROGRESS", "fragments=" + receivedFragments
          + " chars=" + receivedCharacters + " pendingChars=" + pending.length());
    }
    drainSentences(false);
  }
  private void drainSentences(boolean force) {
    int max = config.maxChunkCharacters();
    int min = config.minChunkCharacters();
    while (pending.length() >= min) {
      int split = -1;
      for (int i = min - 1; i < Math.min(max, pending.length()); i++) {
        char c = pending.charAt(i);
        if (".!?\n…。！？".indexOf(c) >= 0 && (i+1 == pending.length() || Character.isWhitespace(pending.charAt(i+1)))) {
          split = i + 1; break;
        }
      }
      if (split < 0 && pending.length() >= max) {
        for (int i = max - 1; i >= min - 1; i--) {
          if (Character.isWhitespace(pending.charAt(i)) || ",;:，；：".indexOf(pending.charAt(i)) >= 0) { split = i + 1; break; }
        }
        if (split < 0) split = max;
      }
      if (split < 0) break;
      offer(pending.substring(0, split));
      pending.delete(0, split);
    }
    if (force && !pending.isEmpty()) {
      offer(pending.toString());
      pending.setLength(0);
    }
  }
  private void offer(String raw) {
    String text = raw.strip();
    if (text.isBlank() || cancelled.get()) return;
    // Never block LLM decoding: if TTS falls too far behind, report and stop speech for this turn.
    if (!chunks.offer(text)) {
      overflow = true;
      TtsTrace.log(traceId, "QUEUE_OVERFLOW", "limit=" + config.queueLimit()
          + " pendingChars=" + pending.length() + " submitted=" + submitted);
      cancelled.set(true);
      chunks.clear();
      tts.cancelSpeech();
      player.cancelSpeech();
      return;
    }
    submitted++;
    TtsTrace.log(traceId, "PHRASE_ENQUEUED", "phrase=" + submitted
        + " chars=" + text.length() + " queueDepth=" + chunks.size()
        + " elapsedMs=" + elapsedMs());
  }
  public synchronized void finish() {
    if (completed) return;
    drainSentences(true);
    completed = true;
    TtsTrace.log(traceId, "QUEUE_INPUT_FINISHED", "fragments=" + receivedFragments
        + " chars=" + receivedCharacters + " submittedPhrases=" + submitted
        + " queued=" + chunks.size() + " elapsedMs=" + elapsedMs());
  }
  public void await() throws Exception {
    finish();
    TtsTrace.log(traceId, "QUEUE_AWAIT", "submitted=" + submitted + " processed=" + processed);
    worker.join();
    TtsTrace.log(traceId, "QUEUE_JOINED", "submitted=" + submitted
        + " processed=" + processed + " elapsedMs=" + elapsedMs()
        + " cancelled=" + cancelled.get() + " failed=" + (error != null));
    if (overflow) throw new IllegalStateException("Очередь TTS переполнена; увеличь лимит или используй более быструю модель");
    if (error != null) throw error;
    if (cancelled.get()) throw new CancellationException("TTS отменён");
  }
  public int submitted() { return submitted; }
  private void work() {
    try (TtsTrace.Scope ignored = TtsTrace.with(traceId)) {
      TtsTrace.log("QUEUE_WORKER_START", "thread=" + Thread.currentThread().getName());
      while (!cancelled.get()) {
        String next = chunks.poll(Math.max(10, config.flushDelayMs()), TimeUnit.MILLISECONDS);
        if (next == null) {
          if (completed && chunks.isEmpty()) {
            // Keep one Java Sound line open across all phrases; drain it only now.
            TtsTrace.log("QUEUE_AUDIO_FINISH", "processed=" + processed);
            player.finishSpeech();
            TtsTrace.log("QUEUE_WORKER_END", "processed=" + processed + " elapsedMs=" + elapsedMs());
            return;
          }
          // Flush a partial, well-sized phrase when the LLM briefly pauses.
          synchronized (this) {
            if (!completed && pending.length() >= config.minChunkCharacters()) {
              TtsTrace.log("QUEUE_FLUSH_TIMEOUT", "pendingChars=" + pending.length());
              drainSentences(true);
            }
          }
          continue;
        }
        int phrase = processed + 1;
        TtsTrace.log("PHRASE_DEQUEUED", "phrase=" + phrase + " chars=" + next.length()
            + " queuedRemaining=" + chunks.size() + " elapsedMs=" + elapsedMs());
        if (!tts.isRunning()) {
          TtsTrace.log("TTS_RECONNECT", "service not running; attempting start");
          tts.start();
        }
        if (cancelled.get()) return;
        long synthStartNs = System.nanoTime();
        TtsTrace.log("PHRASE_SYNTH_START", "phrase=" + phrase + " chars=" + next.length());
        tts.speak(next, player);
        processed++;
        TtsTrace.log("PHRASE_DONE", "phrase=" + phrase + " synthesisAndEnqueueMs="
            + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - synthStartNs)
            + " completed=" + processed + "/" + submitted);
      }
      TtsTrace.log("QUEUE_WORKER_CANCELLED", "processed=" + processed + " elapsedMs=" + elapsedMs());
    } catch (InterruptedException | CancellationException e) {
      TtsTrace.log(traceId, "QUEUE_WORKER_INTERRUPTED", e.getClass().getSimpleName()
          + " processed=" + processed + " elapsedMs=" + elapsedMs());
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      if (!cancelled.get()) {
        error = e;
        TtsTrace.log(traceId, "QUEUE_WORKER_FAILED", e.getClass().getSimpleName()
            + ": " + e.getMessage() + " processed=" + processed);
        System.err.println("[CosyVoice/TTS] synthesis/playback failed: " + e);
        player.cancelSpeech();
      }
    }
  }
  private long elapsedMs() {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - createdNs);
  }
  public void cancel() {
    TtsTrace.log(traceId, "QUEUE_CANCEL", "submitted=" + submitted + " processed=" + processed
        + " queued=" + chunks.size() + " elapsedMs=" + elapsedMs());
    cancelled.set(true);
    completed = true;
    chunks.clear();
    tts.cancelSpeech();
    player.cancelSpeech();
    worker.interrupt();
  }
  @Override public void close() { cancel(); }
}
