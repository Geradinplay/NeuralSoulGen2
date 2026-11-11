package com.neuralsoul.service.audio;

import com.neuralsoul.config.SttConfig;
import com.neuralsoul.config.ConfigLocator;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import javax.sound.sampled.*;

/**
 * Local endpoint detector for hands-free chat.
 *
 * <p>No model call is made while listening. RMS energy + adaptive noise floor decide when speech
 * begins and when a completed utterance is ready. Only the completed WAV is sent to Whisper.
 *
 * <p>The capture line stays open continuously while completed utterances are processed elsewhere.
 * A rolling history keeps both the configured pre-roll and the frames used to confirm speech start,
 * so the beginning of the next phrase is not lost when the previous phrase has just been dispatched.
 */
public final class ContinuousSpeechListener implements AutoCloseable {
  private static final int FRAME_BYTES = 640; // 20 ms at 16 kHz / 16-bit mono
  private static final int FRAME_MS = 20;
  // 2 seconds of device-side buffering. The old 6400-byte buffer held only ~200 ms and could
  // overflow when the JVM/FX/LLM startup briefly stole the capture thread after dispatching a turn.
  private static final int CAPTURE_BUFFER_BYTES = 64_000;

  private volatile boolean running;
  private volatile TargetDataLine line;
  private Thread worker;

  public synchronized boolean isRunning() { return running; }

  public synchronized void start(
      SttConfig cfg,
      Runnable speechStarted,
      Consumer<byte[]> utteranceReady,
      Consumer<String> status) throws Exception {
    start(ConfigLocator.getInstance(), cfg, speechStarted, utteranceReady, status);
  }

  public synchronized void start(
      ConfigLocator configs,
      SttConfig cfg,
      Runnable speechStarted,
      Consumer<byte[]> utteranceReady,
      Consumer<String> status) throws Exception {
    if (running) return;
    TargetDataLine input = AudioDeviceManager.targetLine(configs, WavAudio.FORMAT);
    input.open(WavAudio.FORMAT, CAPTURE_BUFFER_BYTES);
    input.start();
    line = input;
    running = true;
    worker = new Thread(() -> capture(cfg, input, speechStarted, utteranceReady, status), "continuous-voice-listener");
    worker.setDaemon(true);
    // Audio capture should not lose scheduling to generation/HTTP worker activity.
    worker.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1));
    worker.start();
  }

  private void capture(SttConfig cfg, TargetDataLine input, Runnable speechStarted,
                       Consumer<byte[]> utteranceReady, Consumer<String> status) {
    final int preFrames = Math.max(0, ceilFrames(cfg.listenPreRollMs()));
    final int startFrames = Math.max(1, ceilFrames(cfg.listenSpeechStartMs()));
    final int silenceFrames = Math.max(1, ceilFrames(cfg.listenSilenceMs()));
    final int minSendFrames = Math.max(0, ceilFrames(cfg.listenMinSendDelayMs()));
    // Keep the requested audio BEFORE speech plus every frame used to confirm that speech started.
    // Previously the start-confirmation frames consumed the pre-roll budget and could cut the first
    // syllable, especially on the phrase immediately following a dispatched request.
    final int historyFrames = Math.max(1, preFrames + startFrames);
    final int minBytes = cfg.listenMinUtteranceMs() * 32;
    final int maxBytes = cfg.listenMaxUtteranceSeconds() * 32000;

    Deque<byte[]> history = new ArrayDeque<>();
    Deque<byte[]> trailingSilence = new ArrayDeque<>();
    ByteArrayOutputStream utterance = null;
    int hotFrames = 0;
    int quietFrames = 0;
    int phraseFramesFromOnset = 0;
    double noiseFloor = Math.max(0.001, cfg.listenThreshold() / cfg.noiseMultiplier());

    try {
      status.accept("Всегда слушаю · ожидание речи");
      byte[] frame = new byte[FRAME_BYTES];
      while (running) {
        int n = input.read(frame, 0, frame.length);
        if (n <= 0) continue;
        byte[] chunk = java.util.Arrays.copyOf(frame, n);
        double rms = rms(chunk);
        double threshold = Math.max(cfg.listenThreshold(), noiseFloor * cfg.noiseMultiplier());
        boolean voiced = rms >= threshold;

        if (utterance == null) {
          // Learn the room only while we believe there is no speech.
          if (!voiced) noiseFloor = noiseFloor * 0.97 + rms * 0.03;
          hotFrames = voiced ? hotFrames + 1 : 0;

          // Rolling history is maintained independently from speech-start confirmation. This is the
          // key difference from the old implementation: no onset frames are discarded from pre-roll.
          history.addLast(chunk);
          while (history.size() > historyFrames) history.removeFirst();

          if (hotFrames >= startFrames) {
            utterance = new ByteArrayOutputStream();
            for (byte[] buffered : history) utterance.write(buffered);
            history.clear();
            trailingSilence.clear();
            quietFrames = 0;
            // The first voiced frame was startFrames frames ago. Count from that onset rather than
            // from the moment speech was confirmed, so the setting means "from the first word".
            phraseFramesFromOnset = startFrames;
            try { speechStarted.run(); } catch (Throwable ignored) {}
            status.accept("Слышу речь…");
          }
          continue;
        }

        utterance.write(chunk);
        phraseFramesFromOnset++;
        if (voiced) {
          quietFrames = 0;
          trailingSilence.clear();
        } else {
          quietFrames++;
          trailingSilence.addLast(chunk);
          while (trailingSilence.size() > preFrames) trailingSilence.removeFirst();
        }

        // A short pause near the beginning of a sentence must not split it into tiny requests.
        // Silence can finish the phrase only after the configured no-send window has elapsed from
        // the first detected speech frame. Max utterance remains a hard safety limit.
        boolean minimumWindowElapsed = phraseFramesFromOnset >= minSendFrames;
        boolean endpoint = minimumWindowElapsed && quietFrames >= silenceFrames;
        boolean forced = utterance.size() >= maxBytes;
        if (endpoint || forced) {
          byte[] pcm = utterance.toByteArray();
          utterance = null;
          hotFrames = 0;
          quietFrames = 0;
          phraseFramesFromOnset = 0;

          // Seed the next rolling history with the silence that ended this phrase. If the user starts
          // talking again immediately after dispatch, capture already has pre-roll available instead
          // of beginning from an empty buffer.
          history.clear();
          if (endpoint) {
            for (byte[] buffered : trailingSilence) history.addLast(buffered);
          }
          trailingSilence.clear();

          if (pcm.length >= minBytes) {
            try { utteranceReady.accept(WavAudio.wrapPcm(pcm)); }
            catch (Exception e) { status.accept("Ошибка подготовки WAV: " + e.getMessage()); }
          }
          status.accept("Всегда слушаю · ожидание речи");
        }
      }
    } catch (Throwable e) {
      if (running) status.accept("Микрофон: " + e.getMessage());
    } finally {
      running = false;
      try { input.stop(); input.close(); } catch (Throwable ignored) {}
    }
  }

  private static int ceilFrames(int milliseconds) {
    if (milliseconds <= 0) return 0;
    return (milliseconds + FRAME_MS - 1) / FRAME_MS;
  }

  private static double rms(byte[] pcm) {
    if (pcm.length < 2) return 0;
    double sum = 0;
    int count = 0;
    for (int i = 0; i + 1 < pcm.length; i += 2) {
      int sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
      double v = sample / 32768.0;
      sum += v * v;
      count++;
    }
    return count == 0 ? 0 : Math.sqrt(sum / count);
  }

  public synchronized void stop() {
    running = false;
    TargetDataLine current = line;
    line = null;
    if (current != null) try { current.stop(); current.close(); } catch (Throwable ignored) {}
  }

  @Override public void close() { stop(); }
}
