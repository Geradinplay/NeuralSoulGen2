package com.neuralsoul.service.audio;

import java.io.*;
import javax.sound.sampled.*;

final class WavAudio {
  static final AudioFormat FORMAT = new AudioFormat(16000, 16, 1, true, false);
  private WavAudio() {}

  static byte[] wrapPcm(byte[] pcm) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (AudioInputStream audio = new AudioInputStream(
        new ByteArrayInputStream(pcm), FORMAT, pcm.length / FORMAT.getFrameSize())) {
      AudioSystem.write(audio, AudioFileFormat.Type.WAVE, output);
    }
    return output.toByteArray();
  }
}
