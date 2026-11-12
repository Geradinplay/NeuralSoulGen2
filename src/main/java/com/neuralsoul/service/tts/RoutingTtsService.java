package com.neuralsoul.service.tts;

import com.neuralsoul.config.SpeechGenerationSettingsStore;
import com.neuralsoul.service.api.AudioPlayer;
import com.neuralsoul.service.api.TtsService;
import java.util.Objects;

/** Chooses between pre-existing Coqui/OpenAI TTS and enabled CosyVoice without breaking legacy configs. */
public final class RoutingTtsService implements TtsService {
  public long cosyOwnedPid() { return cosy.ownedPid(); }
  private final TtsService legacy;
  private final CosyVoiceTtsService cosy;
  private final SpeechGenerationSettingsStore store;
  public RoutingTtsService(TtsService legacy, CosyVoiceTtsService cosy, SpeechGenerationSettingsStore store) {
    this.legacy = Objects.requireNonNull(legacy);
    this.cosy = Objects.requireNonNull(cosy);
    this.store = Objects.requireNonNull(store);
  }
  private TtsService selected() {
    try {
      var c = store.load().common();
      if (!c.enabled()) return legacy;
      if (SpeechGenerationSettingsStore.COSYVOICE.equals(c.engineId())) return cosy;
      throw new IllegalStateException("Неизвестный TTS-движок: " + c.engineId());
    }
    catch (Exception e) { throw new IllegalStateException("Не удалось прочитать настройки TTS", e); }
  }
  @Override public void start() throws Exception { selected().start(); }
  @Override public boolean isRunning() { return selected().isRunning(); }
  @Override public void stop() { cosy.stop(); legacy.stop(); }
  @Override public void cancelSpeech() { cosy.cancelSpeech(); }
  @Override public byte[] convertTextToSpeech(String text) throws Exception { return selected().convertTextToSpeech(text); }
  @Override public void speak(String text, AudioPlayer player) throws Exception { selected().speak(text, player); }
  public boolean isCosyEnabled() {
    try { return store.load().common().enabled(); } catch (Exception e) { return false; }
  }
}
