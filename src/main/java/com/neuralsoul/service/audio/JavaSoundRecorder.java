package com.neuralsoul.service.audio;

import com.neuralsoul.service.api.AudioRecorder;
import com.neuralsoul.config.ConfigLocator;
import java.io.*;
import java.util.function.IntSupplier;
import javax.sound.sampled.*;

public final class JavaSoundRecorder implements AudioRecorder {
  private static final AudioFormat FORMAT = new AudioFormat(16000, 16, 1, true, false);
  private final IntSupplier seconds;
  private final ConfigLocator configs;
  private volatile boolean running;
  private volatile TargetDataLine line;
  private volatile Throwable failure;
  private ByteArrayOutputStream captured;
  private Thread reader;

  public JavaSoundRecorder(IntSupplier seconds) {
    this(seconds, ConfigLocator.getInstance());
  }

  public JavaSoundRecorder(IntSupplier seconds, ConfigLocator configs) {
    this.seconds = seconds;
    this.configs = configs;
  }

  @Override
  public synchronized void start() throws Exception {
    if (running) throw new IllegalStateException("Запись уже идёт");
    captured = new ByteArrayOutputStream();
    failure = null;
    TargetDataLine input = AudioDeviceManager.targetLine(configs, FORMAT);
    input.open(FORMAT);
    input.start();
    line = input;
    running = true;
    // Manual chat recording is intentionally open-ended: the user decides when to stop.
    // The old microphoneSeconds cap made the button stop recording by itself.
    reader =
        new Thread(
            () -> {
              try {
                byte[] buffer = new byte[3200];
                while (running) {
                  int n = input.read(buffer, 0, buffer.length);
                  if (n > 0) captured.write(buffer, 0, n);
                  else if (!running) break;
                }
              } catch (Throwable e) {
                if (running) failure = e;
              } finally {
                running = false;
                input.close();
              }
            },
            "microphone-capture");
    reader.setDaemon(true);
    reader.start();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public void stop() {
    running = false;
    TargetDataLine current = line;
    if (current != null) {
      current.stop();
      current.close();
    }
  }

  @Override
  public synchronized byte[] finishRecording() throws Exception {
    stop();
    if (reader != null) {
      reader.join(2000);
      if (reader.isAlive()) throw new IOException("Микрофон не завершил запись");
    }
    if (failure != null) throw new IOException("Ошибка записи микрофона", failure);
    if (captured == null || captured.size() < 3200)
      throw new IOException("Запись слишком короткая");
    byte[] pcm = captured.toByteArray();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (AudioInputStream audio =
        new AudioInputStream(
            new ByteArrayInputStream(pcm), FORMAT, pcm.length / FORMAT.getFrameSize())) {
      AudioSystem.write(audio, AudioFileFormat.Type.WAVE, output);
    }
    return output.toByteArray();
  }
}
