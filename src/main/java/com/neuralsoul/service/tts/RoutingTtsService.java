package com.neuralsoul.service.tts;

import com.neuralsoul.config.SpeechGenerationSettingsStore;
import com.neuralsoul.service.api.AudioPlayer;
import com.neuralsoul.service.api.TtsService;
import java.util.Objects;

/** Chooses between pre-existing Coqui/OpenAI TTS and enabled Fish S2 without breaking legacy configs. */
public final class RoutingTtsService implements TtsService {
  public long fishOwnedPid() { return fish.ownedPid(); }
  private final TtsService legacy;
  private final FishSpeechTtsService fish;
  private final SpeechGenerationSettingsStore store;
  public RoutingTtsService(TtsService legacy, FishSpeechTtsService fish, SpeechGenerationSettingsStore store) {
    this.legacy = Objects.requireNonNull(legacy);
    this.fish = Objects.requireNonNull(fish);
    this.store = Objects.requireNonNull(store);
  }
  private TtsService selected() {
    try {
      var c = store.load().common();
      if (!c.enabled()) return legacy;
      if (SpeechGenerationSettingsStore.FISH_S2.equals(c.engineId())) return fish;
      throw new IllegalStateException("Неизвестный TTS-движок: " + c.engineId());
    }
    catch (Exception e) { throw new IllegalStateException("Не удалось прочитать настройки TTS", e); }
  }
  @Override public void start() throws Exception { selected().start(); }
  @Override public boolean isRunning() { return selected().isRunning(); }
  @Override public void stop() { fish.stop(); legacy.stop(); }
  @Override public void cancelSpeech() { fish.cancelSpeech(); }
  @Override public byte[] convertTextToSpeech(String text) throws Exception { return selected().convertTextToSpeech(text); }
  @Override public void speak(String text, AudioPlayer player) throws Exception { selected().speak(text, player); }
  public boolean isFishEnabled() {
    try { return store.load().common().enabled(); } catch (Exception e) { return false; }
  }
}
