package com.neuralsoul.service.audio;

import com.neuralsoul.config.ConfigLocator;
import java.util.function.DoubleConsumer;
import javax.sound.sampled.*;

/** Low-latency selected microphone -> selected output monitor used only for device testing. */
public final class MicrophoneMonitor implements AutoCloseable {
  private volatile boolean running;
  private volatile TargetDataLine input;
  private volatile SourceDataLine output;
  private Thread worker;

  public synchronized boolean isRunning() { return running; }

  public synchronized void start() throws Exception {
    start(ConfigLocator.getInstance(), level -> {});
  }

  public synchronized void start(ConfigLocator configs, DoubleConsumer levelConsumer) throws Exception {
    if (running) return;
    DoubleConsumer levels = levelConsumer == null ? level -> {} : levelConsumer;
    TargetDataLine in = AudioDeviceManager.targetLine(configs, WavAudio.FORMAT);
    SourceDataLine out = AudioDeviceManager.sourceLine(configs, WavAudio.FORMAT);
    // Small explicit buffers keep monitoring latency low without busy-spinning.
    in.open(WavAudio.FORMAT, 3200);
    out.open(WavAudio.FORMAT, 3200);
    in.start();
    out.start();
    input = in;
    output = out;
    running = true;
    worker = new Thread(() -> runLoop(in, out, levels), "microphone-monitor");
    worker.setDaemon(true);
    worker.start();
  }

  private void runLoop(TargetDataLine in, SourceDataLine out, DoubleConsumer levelConsumer) {
    byte[] buffer = new byte[640]; // 20 ms at 16 kHz mono PCM16
    try {
      while (running) {
        int read = in.read(buffer, 0, buffer.length);
        if (read <= 0) continue;
        out.write(buffer, 0, read);
        try { levelConsumer.accept(rms(buffer, read)); } catch (Throwable ignored) {}
      }
    } catch (Throwable ignored) {
    } finally {
      running = false;
      try { in.stop(); in.close(); } catch (Throwable ignored) {}
      try { out.drain(); out.stop(); out.close(); } catch (Throwable ignored) {}
      input = null;
      output = null;
    }
  }

  private static double rms(byte[] pcm, int length) {
    double sum = 0;
    int count = 0;
    for (int i = 0; i + 1 < length; i += 2) {
      int sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
      double v = sample / 32768.0;
      sum += v * v;
      count++;
    }
    return count == 0 ? 0 : Math.sqrt(sum / count);
  }

  public synchronized void stop() {
    running = false;
    TargetDataLine in = input;
    SourceDataLine out = output;
    input = null;
    output = null;
    if (in != null) try { in.stop(); in.close(); } catch (Throwable ignored) {}
    if (out != null) try { out.stop(); out.flush(); out.close(); } catch (Throwable ignored) {}
  }

  @Override public void close() { stop(); }
}
