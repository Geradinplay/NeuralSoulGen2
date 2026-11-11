package com.neuralsoul.service.api;

public interface TtsService extends ManagedService {
  /** Stream to the audio device; legacy TTS falls back to one completed WAV. */
  default void speak(String text, AudioPlayer player) throws Exception {
    player.start();
    player.play(convertTextToSpeech(text));
  }

  /** Cancel only the current audio request, leaving a persistent runtime loaded. */
  default void cancelSpeech() {}

  byte[] convertTextToSpeech(String text) throws Exception;

  default byte[] convertTextToSpeak(String text) throws Exception {
    return convertTextToSpeech(text);
  }
}
