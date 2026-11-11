package com.neuralsoul.service;

import com.neuralsoul.config.ConfigLocator;
import com.neuralsoul.service.api.*;
import com.neuralsoul.service.audio.*;
import com.neuralsoul.service.http.*;
import java.util.List;
import java.util.function.Supplier;
import com.neuralsoul.service.tts.FishSpeechTtsService;
import com.neuralsoul.service.tts.RoutingTtsService;
import com.neuralsoul.config.SpeechGenerationSettingsStore;

/**
 * Global runtime service registry.
 *
 * <p>The locator stores service interfaces only. Concrete implementations are created once during
 * application bootstrap and can then be obtained from anywhere through getInstance().
 */
public final class ServiceLocator implements AutoCloseable {
  private static final ServiceLocator INSTANCE = new ServiceLocator();

  private volatile LlmService llm;
  private volatile SttService stt;
  private volatile TtsService tts;
  private volatile AudioPlayer player;
  private volatile AudioRecorder recorder;
  private volatile boolean initialized;
  private volatile Supplier<List<FishSpeechTtsService.AllowedDevice>> ttsDevices = List::of;
  public synchronized void setTtsDevices(Supplier<List<FishSpeechTtsService.AllowedDevice>> supplier) {
    this.ttsDevices = supplier == null ? List::of : supplier;
  }

  /** Per-active-model completion limit supplied by the Models page. */
  private volatile int llmMaxOutputTokens = -1;

  private ServiceLocator() {}

  public static ServiceLocator getInstance() {
    return INSTANCE;
  }

  public synchronized void initialize() {
    initialize(ConfigLocator.getInstance());
  }

  public synchronized void initialize(ConfigLocator configs) {
    if (initialized) return;

    HttpTransport http = new HttpTransport();

    llm = new OpenAiLlmService(() -> {
      com.neuralsoul.config.LlmConfig base = configs.llm();
      int maxTokens = llmMaxOutputTokens > 0 ? llmMaxOutputTokens : base.maxTokens();
      return new com.neuralsoul.config.LlmConfig(
          base.endpoint(), base.model(), base.systemPrompt(), base.temperature(), maxTokens);
    }, http);
    stt = new MultipartSttService(configs::stt, http);
    SpeechGenerationSettingsStore ttsStore = new SpeechGenerationSettingsStore(configs.directory());
    tts = new RoutingTtsService(
        new HttpTtsService(configs::tts, http),
        new FishSpeechTtsService(ttsStore, ttsDevices), ttsStore);
    player = new JavaSoundPlayer(configs);
    recorder = new JavaSoundRecorder(() -> configs.pipeline().microphoneSeconds(), configs);

    initialized = true;
  }

  public boolean isInitialized() {
    return initialized;
  }

  /** Applies the active Main LLM's per-model max output token setting without duplicating it in llm.properties. */
  public void setLlmMaxOutputTokens(int maxOutputTokens) {
    llmMaxOutputTokens = Math.max(64, Math.min(131072, maxOutputTokens));
  }


  public LlmService llm() {
    requireInitialized();
    return llm;
  }

  public SttService stt() {
    requireInitialized();
    return stt;
  }

  public TtsService tts() {
    requireInitialized();
    return tts;
  }

  public AudioPlayer player() {
    requireInitialized();
    return player;
  }

  public AudioRecorder recorder() {
    requireInitialized();
    return recorder;
  }

  @Override
  public synchronized void close() {
    if (!initialized) return;

    for (ManagedService service : List.of(recorder, player, tts, stt, llm)) {
      service.stop();
    }

    recorder = null;
    player = null;
    tts = null;
    stt = null;
    llm = null;
    initialized = false;
  }

  private void requireInitialized() {
    if (!initialized) {
      throw new IllegalStateException(
          "ServiceLocator is not initialized. Call ServiceLocator.getInstance().initialize() first.");
    }
  }
}
