package com.neuralsoul.service.audio;

import com.neuralsoul.config.ConfigLocator;
import com.neuralsoul.service.api.AudioPlayer;
import com.neuralsoul.service.tts.TtsTrace;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.*;

/**
 * Reusable PCM16 output for Fish Speech. The hardware SourceDataLine remains OPEN and
 * STARTED between LLM replies; a single writer thread maintains a very short silence
 * keepalive while idle. HTTP reading never writes directly to the sound driver.
 *
 * finishSpeech() waits for the reply to finish playing but DOES NOT release the line.
 * cancelSpeech() drops only the current reply; stop() is the explicit system shutdown.
 */
public final class JavaSoundPlayer implements AudioPlayer {
  private static final int INPUT_BLOCK = 8192;
  private static final int DEVICE_BUFFER_MS = 850;
  private static final int SILENCE_BLOCK_MS = 10;
  private static final int IDLE_BUFFER_MS = 40;
  private static final int AUDIO_WRITE_BLOCK = 4096;

  @FunctionalInterface interface LineFactory {
    SourceDataLine open(ConfigLocator configs, AudioFormat format) throws LineUnavailableException;
  }
  private final ConfigLocator configs;
  private final LineFactory lineFactory;
  private final Object sessionLock = new Object();
  private final ThreadLocal<Long> speechEpoch = new ThreadLocal<>();
  private volatile boolean running;
  private volatile SourceDataLine wavLine;
  private volatile PcmOutput pcmOutput;

  public JavaSoundPlayer() { this(ConfigLocator.getInstance()); }
  public JavaSoundPlayer(ConfigLocator configs) { this(configs, AudioDeviceManager::sourceLine); }
  JavaSoundPlayer(ConfigLocator configs, LineFactory lineFactory) {
    this.configs = configs;
    this.lineFactory = lineFactory;
  }

  @Override public void start() { running = true; }
  @Override public boolean isRunning() { return running; }

  /** Opens the sound device once when Fish Speech comes online, even before the first reply. */
  @Override public void preparePcmOutput(int sampleRate, int channels) throws Exception {
    start();
    ensureOutput(pcmFormat(sampleRate, channels));
  }

  @Override public void stop() {
    running = false;
    speechEpoch.remove();
    PcmOutput old;
    synchronized (sessionLock) {
      old = pcmOutput;
      pcmOutput = null;
    }
    if (old != null) old.shutdown();
    SourceDataLine legacy = wavLine;
    wavLine = null;
    if (legacy != null) {
      try { legacy.stop(); legacy.flush(); legacy.close(); }
      catch (RuntimeException ignored) {}
    }
    TtsTrace.log("PLAYER_DEVICE_STOP", "reason=system_shutdown");
  }

  /** Stop the CURRENT speech without destroying the long-lived audio device. */
  @Override public void cancelSpeech() {
    speechEpoch.remove();
    PcmOutput current = pcmOutput;
    if (current != null) current.cancelCurrent();
    SourceDataLine legacy = wavLine;
    if (legacy != null) {
      try { legacy.stop(); legacy.flush(); }
      catch (RuntimeException ignored) {}
    }
    TtsTrace.log("PLAYER_SPEECH_CANCEL", "deviceRemainsOpen=" + (current != null && !current.closed));
  }

  private static AudioFormat pcmFormat(int sampleRate, int channels) {
    if (sampleRate < 8000 || sampleRate > 192000 || channels < 1 || channels > 2)
      throw new IllegalArgumentException("Invalid PCM format " + sampleRate + "Hz " + channels + "ch");
    return new AudioFormat(sampleRate, 16, channels, true, false);
  }

  private PcmOutput ensureOutput(AudioFormat format) throws Exception {
    synchronized (sessionLock) {
      if (!running) throw new IllegalStateException("Audio player stopped");
      PcmOutput current = pcmOutput;
      if (current != null && !current.closed && current.format.matches(format)) return current;
      if (current != null) {
        TtsTrace.log("PLAYER_FORMAT_CHANGED", "old=" + current.format + " new=" + format);
        current.shutdown(); // A format change is the only non-shutdown reason to reopen.
      }
      pcmOutput = null;
      PcmOutput opened = new PcmOutput(format);
      pcmOutput = opened;
      return opened;
    }
  }

  /** Each native HTTP response is read independently but goes into the SAME audio output. */
  @Override public void playPcm(InputStream stream, int sampleRate, int channels, int initialBufferMs)
      throws Exception {
    PcmOutput current = ensureOutput(pcmFormat(sampleRate, channels));
    long epoch = current.epoch();
    // The same worker thread can be reused after a cancelled answer. Its old
    // ThreadLocal is stale, but the new request must be allowed to speak.
    speechEpoch.set(epoch);
    current.setStartupBuffer(initialBufferMs, epoch);
    String traceId = TtsTrace.id();
    long beginNs = System.nanoTime();
    long networkBytes = 0;
    long nextProgress = 65536;
    int reads = 0;
    PcmFrameAligner aligner = new PcmFrameAligner(channels, (data, offset, length) -> {
      current.enqueue(Arrays.copyOfRange(data, offset, offset + length), epoch);
    });
    try {
      byte[] buf = new byte[INPUT_BLOCK];
      int n;
      while (running && !Thread.currentThread().isInterrupted() && (n = stream.read(buf)) != -1) {
        if (n == 0) continue;
        networkBytes += n;
        reads++;
        if (reads == 1) TtsTrace.log(traceId, "PCM_FIRST_BYTES", "bytes=" + n
            + " elapsedMs=" + elapsedMs(beginNs));
        aligner.accept(Arrays.copyOf(buf, n));
        if (networkBytes >= nextProgress) {
          TtsTrace.log(traceId, "PCM_NETWORK_PROGRESS", "bytes=" + networkBytes
              + " blocks=" + reads + " audioQueuedBytes=" + current.pendingBytes()
              + " elapsedMs=" + elapsedMs(beginNs));
          nextProgress = networkBytes + 65536;
        }
      }
      if (!running || Thread.currentThread().isInterrupted())
        throw new InterruptedException("TTS audio was cancelled");
      current.assertEpoch(epoch);
      int partial = aligner.finish();
      if (partial != 0) TtsTrace.log(traceId, "PCM_TRUNCATED_FRAME", "orphanBytes=" + partial);
      TtsTrace.log(traceId, "PCM_PHRASE_QUEUED", "networkBytes=" + networkBytes
          + " alignedBytes=" + aligner.deliveredBytes() + " pendingAudioBytes=" + current.pendingBytes()
          + " elapsedMs=" + elapsedMs(beginNs));
    } catch (Exception | Error e) {
      TtsTrace.log(traceId, "PCM_PHRASE_FAILED", e.getClass().getSimpleName() + ": " + e.getMessage());
      current.cancelIfEpoch(epoch);
      throw e;
    } finally {
      try { stream.close(); } catch (IOException ignored) {}
    }
  }

  /** Wait for the last audible frame but DO NOT stop, drain-and-close, or reopen the device. */
  @Override public void finishSpeech() throws Exception {
    PcmOutput current = pcmOutput;
    Long epoch = speechEpoch.get();
    speechEpoch.remove();
    if (current != null && epoch != null) current.finishTurn(epoch);
  }

  /** One sound device and one writer thread for the entire enabled TTS lifetime. */
  private final class PcmOutput {
    private record Packet(byte[] audio, CompletableFuture<Void> finished, long epoch) {}

    private final AudioFormat format;
    private final SourceDataLine output;
    private final ArrayBlockingQueue<Packet> packets = new ArrayBlockingQueue<>(192);
    private final AtomicLong pendingBytes = new AtomicLong();
    private final AtomicBoolean deviceReleased = new AtomicBoolean();
    private final Thread writer;
    private final String selectedOutput;
    private final byte[] silence;
    private final long begunNs = System.nanoTime();
    private volatile boolean closed;
    private volatile Throwable error;
    private volatile CompletableFuture<Void> activeFence;
    private volatile long generation = 1;
    private volatile boolean finishing;
    private volatile int startupBytes;
    private volatile long playedBytes;
    private long underruns;

    private PcmOutput(AudioFormat format) throws Exception {
      this.format = format;
      this.selectedOutput = AudioDeviceManager.selectedOutputId(configs);
      int rate = (int) format.getSampleRate();
      int frame = format.getFrameSize();
      startupBytes = alignedBytes(rate, frame, 240);
      silence = new byte[alignedBytes(rate, frame, SILENCE_BLOCK_MS)];
      output = lineFactory.open(configs, format);
      try {
        try {
          output.open(format, alignedBytes(rate, frame, DEVICE_BUFFER_MS));
        } catch (LineUnavailableException | IllegalArgumentException oversized) {
          if (output.isOpen()) output.close();
          output.open(format);
        }
        output.start(); // START ONCE: keepalive maintains the hardware stream while idle.
      } catch (Exception | Error failure) {
        try { output.close(); } catch (RuntimeException ignored) {}
        throw failure;
      }
      TtsTrace.log("PLAYER_DEVICE_OPEN", "sampleRate=" + rate + " channels=" + format.getChannels()
          + " bufferBytes=" + output.getBufferSize() + " selectedOutput=" + selectedOutput
          + " keepOpenAcrossReplies=true");
      writer = new Thread(this::writeLoop, "fish-tts-persistent-audio");
      writer.setDaemon(true);
      writer.start();
    }

    private long epoch() { return generation; }
    private long pendingBytes() { return pendingBytes.get(); }

    private void assertEpoch(long epoch) throws IOException, InterruptedException {
      if (!running || closed || generation != epoch) throw new InterruptedException("TTS speech cancelled");
      if (error != null) throw new IOException("Persistent audio writer failed", error);
    }
    private void setStartupBuffer(int ms, long epoch) throws InterruptedException {
      if (closed || generation != epoch) throw new InterruptedException("TTS speech cancelled");
      startupBytes = alignedBytes((int) format.getSampleRate(), format.getFrameSize(),
          Math.max(120, Math.min(1200, ms)));
    }

    private void enqueue(byte[] data, long epoch) throws InterruptedException {
      if (data.length == 0) return;
      while (running && !Thread.currentThread().isInterrupted()) {
        if (error != null) throw new IllegalStateException("TTS audio writer failed", error);
        // The epoch check + insertion must be atomic with cancelCurrent(), or a
        // stale producer could enqueue cancelled audio after the queue was cleared.
        synchronized (this) {
          if (closed || generation != epoch) throw new InterruptedException("TTS speech cancelled");
          if (packets.offer(new Packet(data, null, epoch))) {
            pendingBytes.addAndGet(data.length);
            return;
          }
        }
        Thread.sleep(15);
      }
      throw new InterruptedException("TTS playback cancelled while queueing PCM");
    }

    private void finishTurn(long epoch) throws Exception {
      assertEpoch(epoch);
      CompletableFuture<Void> fence = new CompletableFuture<>();
      finishing = true;
      Packet packet = new Packet(null, fence, epoch);
      boolean enqueued = false;
      while (running && !closed && generation == epoch) {
        synchronized (this) {
          if (closed || generation != epoch) break;
          if (packets.offer(packet)) { enqueued = true; break; }
        }
        Thread.sleep(15);
      }
      assertEpoch(epoch);
      if (!enqueued) throw new InterruptedException("TTS finish cancelled");
      TtsTrace.log("PLAYER_TURN_FINISH_REQUEST", "epoch=" + epoch + " pendingBytes=" + pendingBytes.get());
      try { fence.get(); }
      catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof Exception ex) throw ex;
        throw new IOException("Persistent audio playback failed", cause);
      }
      TtsTrace.log("PLAYER_TURN_FINISHED", "epoch=" + epoch + " deviceRemainsOpen=true"
          + " playedBytes=" + playedBytes);
    }

    private void cancelIfEpoch(long epoch) {
      if (generation == epoch) cancelCurrent();
    }
    private void cancelCurrent() {
      List<Packet> cleared = new ArrayList<>();
      synchronized (this) {
        generation++;
        packets.drainTo(cleared);
        pendingBytes.set(0);
        finishing = false;
        try { output.flush(); } catch (RuntimeException ignored) {}
      }
      for (Packet packet : cleared) if (packet.finished != null)
        packet.finished.completeExceptionally(new CancellationException("TTS audio cancelled"));
      CompletableFuture<Void> inFlightFence = activeFence;
      if (inFlightFence != null)
        inFlightFence.completeExceptionally(new CancellationException("TTS audio cancelled"));
      TtsTrace.log("PLAYER_TURN_CANCEL", "clearedPackets=" + cleared.size()
          + " deviceRemainsOpen=" + !closed + " newEpoch=" + generation);
    }

    private void fillIdleSilence() throws InterruptedException {
      if (closed || !running) return;
      // Never prefill the 850-ms output buffer with silence: future speech must be
      // audible quickly. Only keep ~40 ms pending in the native device.
      int allowance = output.getBufferSize() - alignedBytes((int) format.getSampleRate(),
          format.getFrameSize(), IDLE_BUFFER_MS);
      if (output.available() >= Math.max(0, allowance)) {
        output.write(silence, 0, silence.length);
      } else {
        Thread.sleep(4);
      }
    }

    private void writeLoop() {
      try {
        boolean speechStarted = false;
        long lastBlockNs = System.nanoTime();
        while (running && !closed && !Thread.currentThread().isInterrupted()) {
          if (!speechStarted && pendingBytes.get() < startupBytes && !finishing) {
            fillIdleSilence();
            continue;
          }
          Packet packet = packets.poll(12, TimeUnit.MILLISECONDS);
          if (packet == null) {
            if (speechStarted && !finishing
                && System.nanoTime() - lastBlockNs > TimeUnit.MILLISECONDS.toNanos(70)) {
              underruns++;
              speechStarted = false;
              TtsTrace.log("PLAYER_UNDERRUN", "count=" + underruns
                  + " nextPCMPrebuffer=true deviceRemainsOpen=true");
            }
            fillIdleSilence();
            continue;
          }
          if (packet.epoch != generation) {
            if (packet.finished != null)
              packet.finished.completeExceptionally(new CancellationException("Stale TTS turn"));
            continue;
          }
          if (packet.finished != null) {
            // The drain is a TURN boundary, not a device close. It waits for the
            // last actual sample before completing the LLM response.
            activeFence = packet.finished;
            try {
              output.drain();
              if (packet.epoch == generation) packet.finished.complete(null);
              else packet.finished.completeExceptionally(new CancellationException("TTS audio cancelled"));
            } finally {
              activeFence = null;
            }
            speechStarted = false;
            finishing = false;
            TtsTrace.log("PLAYER_TURN_DRAIN", "epoch=" + generation + " lineOpen=" + output.isOpen());
            continue;
          }
          pendingBytes.addAndGet(-packet.audio.length);
          if (!speechStarted) {
            speechStarted = true;
            TtsTrace.log("PLAYER_BUFFER_READY", "epoch=" + generation
                + " pendingBytes=" + pendingBytes.get() + " sourceLineReused=true");
          }
          lastBlockNs = System.nanoTime();
          int offset = 0;
          while (offset < packet.audio.length && running && !closed && generation == packet.epoch) {
            int writable = Math.min(AUDIO_WRITE_BLOCK, packet.audio.length - offset);
            writable -= writable % format.getFrameSize();
            if (writable == 0) throw new IOException("PCM block is not frame aligned");
            int n = output.write(packet.audio, offset, writable);
            if (n <= 0) throw new IOException("Java Sound stopped accepting PCM");
            offset += n;
            playedBytes += n;
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (Throwable e) {
        if (!closed && running) {
          error = e;
          TtsTrace.log("PLAYER_DEVICE_FAILED", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
      } finally {
        closed = true;
        CompletableFuture<Void> inFlightFence = activeFence;
        if (inFlightFence != null)
          inFlightFence.completeExceptionally(new IOException("Audio device stopped", error));
        List<Packet> rest = new ArrayList<>();
        packets.drainTo(rest);
        for (Packet p : rest) if (p.finished != null)
          p.finished.completeExceptionally(new IOException("Audio device stopped", error));
        releaseDevice();
        TtsTrace.log("PLAYER_DEVICE_CLOSED", "playedBytes=" + playedBytes
            + " uptimeMs=" + elapsedMs(begunNs));
      }
    }

    private void releaseDevice() {
      if (deviceReleased.compareAndSet(false, true)) {
        try { output.stop(); output.flush(); output.close(); } catch (RuntimeException ignored) {}
      }
    }

    private void shutdown() {
      closed = true;
      CompletableFuture<Void> inFlightFence = activeFence;
      if (inFlightFence != null)
        inFlightFence.completeExceptionally(new CancellationException("Audio device shut down"));
      writer.interrupt();
      releaseDevice();
      List<Packet> rest = new ArrayList<>();
      packets.drainTo(rest);
      for (Packet p : rest) if (p.finished != null)
        p.finished.completeExceptionally(new CancellationException("Audio device shut down"));
    }
  }

  private static int alignedBytes(int sampleRate, int frameBytes, int durationMs) {
    return Math.max(frameBytes, (int) (((long) sampleRate * durationMs) / 1000L) * frameBytes);
  }
  private static long elapsedMs(long ns) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - ns); }

  @Override public void play(byte[] wav) throws Exception {
    if (!running) throw new IllegalStateException("Audio player stopped");
    // Legacy Coqui/OpenAI WAV playback is intentionally kept separate from Fish PCM.
    try (AudioInputStream input = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
      SourceDataLine output = lineFactory.open(configs, input.getFormat());
      wavLine = output;
      try {
        output.open(input.getFormat());
        if (!running || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        output.start();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
          if (!running || Thread.currentThread().isInterrupted()) throw new InterruptedException();
          output.write(buffer, 0, read);
        }
        output.drain();
      } finally {
        output.close();
        if (wavLine == output) wavLine = null;
      }
    }
  }
}
