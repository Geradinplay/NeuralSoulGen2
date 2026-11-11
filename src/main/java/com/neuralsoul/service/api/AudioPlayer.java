package com.neuralsoul.service.api;

public interface AudioPlayer extends ManagedService {
  /** Native streaming PCM16, without collecting a full WAV in RAM. */
  default void playPcm(java.io.InputStream stream, int sampleRate, int channels, int initialBufferMs)
      throws Exception {
    throw new UnsupportedOperationException("PCM streaming is not supported by this player");
  }

  /** Prepare one persistent PCM output for an enabled TTS engine. */
  default void preparePcmOutput(int sampleRate, int channels) throws Exception {}

  /** Called after the last phrase of a reply; keeps the underlying output device open. */
  default void finishSpeech() throws Exception {}

  /** Cancel audible PCM, but do not close the output device or stop the player service. */
  default void cancelSpeech() { stop(); }

  void play(byte[] wav) throws Exception;
}
