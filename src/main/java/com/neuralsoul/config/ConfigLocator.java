package com.neuralsoul.config;

import java.nio.file.Path;

/**
 * Global typed configuration registry.
 *
 * <p>Loaders are an implementation detail. Runtime code receives typed configuration objects
 * directly from this locator. Reload/save methods atomically replace the corresponding object.
 */
public final class ConfigLocator {
  private static final ConfigLocator INSTANCE = new ConfigLocator(defaultDirectory());

  private final Path directory;
  private final LlmConfigLoader llmLoader;
  private final SttConfigLoader sttLoader;
  private final TtsConfigLoader ttsLoader;
  private final PipelineConfigLoader pipelineLoader;
  private final GuiConfigLoader guiLoader;
  private final WebSearchConfigLoader webSearchLoader;

  private volatile LlmConfig llm;
  private volatile SttConfig stt;
  private volatile TtsConfig tts;
  private volatile PipelineConfig pipeline;
  private volatile GuiConfig gui;
  private volatile WebSearchConfig webSearch;

  private ConfigLocator(Path directory) {
    this.directory = directory.toAbsolutePath();
    llmLoader = new LlmConfigLoader(this.directory.resolve("llm.properties"));
    sttLoader = new SttConfigLoader(this.directory.resolve("stt.properties"));
    ttsLoader = new TtsConfigLoader(this.directory.resolve("tts.properties"));
    pipelineLoader = new PipelineConfigLoader(this.directory.resolve("pipeline.properties"));
    guiLoader = new GuiConfigLoader(this.directory.resolve("gui.properties"));
    webSearchLoader = new WebSearchConfigLoader(this.directory.resolve("web-search.properties"));
  }

  public static ConfigLocator getInstance() {
    return INSTANCE;
  }

  public synchronized void initialize() {
    reloadAll();

    // Preserve the old behaviour: create/default all config files on first launch.
    llmLoader.save(llm);
    sttLoader.save(stt);
    ttsLoader.save(tts);
    pipelineLoader.save(pipeline);
    guiLoader.save(gui);
    webSearchLoader.save(webSearch);
  }

  public synchronized void reloadAll() {
    reloadLlm();
    reloadStt();
    reloadTts();
    reloadPipeline();
    reloadGui();
    reloadWebSearch();
  }

  public synchronized LlmConfig reloadLlm() {
    return llm = llmLoader.reload();
  }

  public synchronized SttConfig reloadStt() {
    return stt = sttLoader.reload();
  }

  public synchronized TtsConfig reloadTts() {
    return tts = ttsLoader.reload();
  }

  public synchronized PipelineConfig reloadPipeline() {
    return pipeline = pipelineLoader.reload();
  }

  public synchronized GuiConfig reloadGui() {
    return gui = guiLoader.reload();
  }

  public synchronized WebSearchConfig reloadWebSearch() {
    return webSearch = webSearchLoader.reload();
  }

  public synchronized void saveLlm(LlmConfig config) {
    llmLoader.save(config);
    llm = config;
  }

  public synchronized void saveStt(SttConfig config) {
    sttLoader.save(config);
    stt = config;
  }

  public synchronized void saveTts(TtsConfig config) {
    ttsLoader.save(config);
    tts = config;
  }

  public synchronized void savePipeline(PipelineConfig config) {
    pipelineLoader.save(config);
    pipeline = config;
  }

  public synchronized void saveGui(GuiConfig config) {
    guiLoader.save(config);
    gui = config;
  }

  public synchronized void saveWebSearch(WebSearchConfig config) {
    webSearchLoader.save(config);
    webSearch = config;
  }

  public LlmConfig llm() {
    requireInitialized(llm, "LLM");
    return llm;
  }

  public SttConfig stt() {
    requireInitialized(stt, "STT");
    return stt;
  }

  public TtsConfig tts() {
    requireInitialized(tts, "TTS");
    return tts;
  }

  public PipelineConfig pipeline() {
    requireInitialized(pipeline, "Pipeline");
    return pipeline;
  }

  public GuiConfig gui() {
    requireInitialized(gui, "GUI");
    return gui;
  }

  public WebSearchConfig webSearch() {
    requireInitialized(webSearch, "Web Search");
    return webSearch;
  }

  public Path directory() {
    return directory;
  }

  private static Path defaultDirectory() {
    return Path.of(
        System.getProperty(
            "neuralsoul.configDir",
            Path.of(System.getProperty("user.home"), ".neuralsoul", "config").toString()));
  }

  private static void requireInitialized(Object value, String name) {
    if (value == null) {
      throw new IllegalStateException(
          name + " config is not initialized. Call ConfigLocator.getInstance().initialize() first.");
    }
  }
}
