package com.neuralsoul.config;

/** Complete whisper.cpp STT configuration. */
public record SttConfig(
    EndpointConfig endpoint,
    String model,
    String language,
    String fileField,
    boolean runtimeEnabled,
    String executablePath,
    String modelPath,
    String runtimeDevice,
    String isolation,
    int threads,
    int processors,
    boolean flashAttention,
    int maxContext,
    int maxLen,
    boolean splitOnWord,
    int bestOf,
    int beamSize,
    int audioContext,
    double wordThreshold,
    double entropyThreshold,
    double logProbThreshold,
    double noSpeechThreshold,
    double temperature,
    double temperatureIncrement,
    boolean noFallback,
    boolean suppressNonSpeech,
    boolean noTimestamps,
    boolean translate,
    boolean vad,
    String vadModelPath,
    double vadThreshold,
    int vadMinSpeechMs,
    int vadMinSilenceMs,
    double vadMaxSpeechSeconds,
    int vadSpeechPadMs,
    double vadOverlapSeconds,
    double listenThreshold,
    double noiseMultiplier,
    int listenSpeechStartMs,
    int listenSilenceMs,
    int listenPreRollMs,
    int listenMinSendDelayMs,
    int listenMinUtteranceMs,
    int listenMaxUtteranceSeconds) {

  public SttConfig {
    if (endpoint == null) throw new IllegalArgumentException("Whisper endpoint не задан");
    if (fileField == null || !fileField.matches("[a-zA-Z0-9_]+"))
      throw new IllegalArgumentException("Некорректное имя multipart поля");
    model = clean(model);
    language = clean(language);
    executablePath = clean(executablePath);
    modelPath = clean(modelPath);
    runtimeDevice = clean(runtimeDevice);
    if (runtimeDevice.isBlank()) runtimeDevice = "CPU";
    isolation = "По устройству".equals(clean(isolation)) ? "По устройству" : "Нет";
    vadModelPath = clean(vadModelPath);
    if (threads < 1 || threads > 128) throw new IllegalArgumentException("Whisper threads: 1–128");
    if (processors < 1 || processors > 16) throw new IllegalArgumentException("Whisper processors: 1–16");
    if (maxContext < -1 || maxContext > 32768) throw new IllegalArgumentException("max-context: -1–32768");
    if (maxLen < 0 || maxLen > 10000) throw new IllegalArgumentException("max-len: 0–10000");
    if (bestOf < 1 || bestOf > 20) throw new IllegalArgumentException("best-of: 1–20");
    if (beamSize < -1 || beamSize > 20) throw new IllegalArgumentException("beam-size: -1–20");
    if (audioContext < 0 || audioContext > 16384) throw new IllegalArgumentException("audio-ctx: 0–16384");
    range(wordThreshold, 0, 1, "word-thold");
    range(entropyThreshold, -20, 20, "entropy-thold");
    range(logProbThreshold, -20, 20, "logprob-thold");
    range(noSpeechThreshold, 0, 1, "no-speech-thold");
    range(temperature, 0, 2, "temperature");
    range(temperatureIncrement, 0, 2, "temperature-inc");
    range(vadThreshold, 0, 1, "vad-threshold");
    if (vadMinSpeechMs < 0 || vadMinSpeechMs > 10000) throw new IllegalArgumentException("VAD min speech: 0–10000 ms");
    if (vadMinSilenceMs < 0 || vadMinSilenceMs > 10000) throw new IllegalArgumentException("VAD min silence: 0–10000 ms");
    if (!Double.isFinite(vadMaxSpeechSeconds) || vadMaxSpeechSeconds < 1 || vadMaxSpeechSeconds > 3600)
      throw new IllegalArgumentException("VAD max speech: 1–3600 sec");
    if (vadSpeechPadMs < 0 || vadSpeechPadMs > 5000) throw new IllegalArgumentException("VAD speech pad: 0–5000 ms");
    range(vadOverlapSeconds, 0, 10, "VAD overlap");
    range(listenThreshold, 0.0001, 1, "Listen threshold");
    range(noiseMultiplier, 1, 20, "Noise multiplier");
    if (listenSpeechStartMs < 20 || listenSpeechStartMs > 5000) throw new IllegalArgumentException("Speech start: 20–5000 ms");
    if (listenSilenceMs < 100 || listenSilenceMs > 10000) throw new IllegalArgumentException("Silence to send: 100–10000 ms");
    if (listenPreRollMs < 0 || listenPreRollMs > 5000) throw new IllegalArgumentException("Pre-roll: 0–5000 ms");
    if (listenMinSendDelayMs < 0 || listenMinSendDelayMs > 10000) throw new IllegalArgumentException("Min send delay: 0–10000 ms");
    if (listenMinUtteranceMs < 100 || listenMinUtteranceMs > 10000) throw new IllegalArgumentException("Min utterance: 100–10000 ms");
    if (listenMaxUtteranceSeconds < 1 || listenMaxUtteranceSeconds > 600) throw new IllegalArgumentException("Max utterance: 1–600 sec");
    if (listenMinSendDelayMs > listenMaxUtteranceSeconds * 1000L)
      throw new IllegalArgumentException("Min send delay не может быть больше Max utterance");
  }

  /** Compatibility constructor for the old Services & API editor. */
  public SttConfig(EndpointConfig endpoint, String model, String language, String fileField) {
    this(copyDefaults(endpoint, model, language, fileField));
  }

  private SttConfig(SttConfig c) {
    this(c.endpoint, c.model, c.language, c.fileField, c.runtimeEnabled, c.executablePath, c.modelPath,
        c.runtimeDevice, c.isolation, c.threads, c.processors, c.flashAttention, c.maxContext, c.maxLen,
        c.splitOnWord, c.bestOf, c.beamSize, c.audioContext, c.wordThreshold, c.entropyThreshold,
        c.logProbThreshold, c.noSpeechThreshold, c.temperature, c.temperatureIncrement, c.noFallback,
        c.suppressNonSpeech, c.noTimestamps, c.translate, c.vad, c.vadModelPath, c.vadThreshold,
        c.vadMinSpeechMs, c.vadMinSilenceMs, c.vadMaxSpeechSeconds, c.vadSpeechPadMs,
        c.vadOverlapSeconds, c.listenThreshold, c.noiseMultiplier, c.listenSpeechStartMs,
        c.listenSilenceMs, c.listenPreRollMs, c.listenMinSendDelayMs, c.listenMinUtteranceMs,
        c.listenMaxUtteranceSeconds);
  }

  public static SttConfig speedDefaults() {
    int cpu = Runtime.getRuntime().availableProcessors();
    int threads = Math.max(1, Math.min(8, Math.max(1, cpu / 2)));
    return new SttConfig(
        new EndpointConfig("http://127.0.0.1:8085", "/inference", "/", "", 180),
        "whisper-1", "ru", "file",
        true, "runtime/whisper/whisper-server.exe", "model/stt/whisper/ggml-large-v3-turbo.bin",
        "GPU 0", "По устройству", threads, 1, true,
        -1, 0, false, 1, 1, 0,
        0.01, 2.40, -1.0, 0.60, 0.0, 0.20,
        true, true, true, false,
        false, "", 0.50, 250, 100, 30.0, 30, 0.10,
        0.012, 3.0, 160, 650, 250, 1200, 350, 30);
  }

  public SttConfig withApi(EndpointConfig endpoint, String model, String language, String fileField) {
    return new SttConfig(endpoint, model, language, fileField, runtimeEnabled, executablePath, modelPath,
        runtimeDevice, isolation, threads, processors, flashAttention, maxContext, maxLen, splitOnWord,
        bestOf, beamSize, audioContext, wordThreshold, entropyThreshold, logProbThreshold,
        noSpeechThreshold, temperature, temperatureIncrement, noFallback, suppressNonSpeech,
        noTimestamps, translate, vad, vadModelPath, vadThreshold, vadMinSpeechMs, vadMinSilenceMs,
        vadMaxSpeechSeconds, vadSpeechPadMs, vadOverlapSeconds, listenThreshold, noiseMultiplier,
        listenSpeechStartMs, listenSilenceMs, listenPreRollMs, listenMinSendDelayMs,
        listenMinUtteranceMs, listenMaxUtteranceSeconds);
  }

  public SttConfig withRuntimeModel(boolean enabled, String executable, String modelFile, String device,
                                    String modelIsolation, int threadCount, int processorCount,
                                    boolean flashAttn) {
    return new SttConfig(endpoint, model, language, fileField, enabled, executable, modelFile,
        device, modelIsolation, threadCount, processorCount, flashAttn, maxContext, maxLen,
        splitOnWord, bestOf, beamSize, audioContext, wordThreshold, entropyThreshold, logProbThreshold,
        noSpeechThreshold, temperature, temperatureIncrement, noFallback, suppressNonSpeech,
        noTimestamps, translate, vad, vadModelPath, vadThreshold, vadMinSpeechMs, vadMinSilenceMs,
        vadMaxSpeechSeconds, vadSpeechPadMs, vadOverlapSeconds, listenThreshold, noiseMultiplier,
        listenSpeechStartMs, listenSilenceMs, listenPreRollMs, listenMinSendDelayMs,
        listenMinUtteranceMs, listenMaxUtteranceSeconds);
  }

  /** Legacy overload retained for older callers/config migrations. */
  public SttConfig withRuntimeModel(boolean enabled, String executable, String modelFile, int threadCount,
                                    int processorCount, boolean gpu, int gpuIndex, boolean flashAttn,
                                    String modelIsolation) {
    return withRuntimeModel(enabled, executable, modelFile, gpu ? "GPU " + gpuIndex : "CPU",
        modelIsolation, threadCount, processorCount, flashAttn);
  }

  public SttConfig withListener(double threshold, double multiplier, int speechStartMs, int silenceMs,
                                int preRollMs, int minSendDelayMs, int minUtteranceMs,
                                int maxUtteranceSeconds) {
    return new SttConfig(endpoint, model, language, fileField, runtimeEnabled, executablePath, modelPath,
        runtimeDevice, isolation, threads, processors, flashAttention, maxContext, maxLen, splitOnWord,
        bestOf, beamSize, audioContext, wordThreshold, entropyThreshold, logProbThreshold,
        noSpeechThreshold, temperature, temperatureIncrement, noFallback, suppressNonSpeech,
        noTimestamps, translate, vad, vadModelPath, vadThreshold, vadMinSpeechMs, vadMinSilenceMs,
        vadMaxSpeechSeconds, vadSpeechPadMs, vadOverlapSeconds, threshold, multiplier, speechStartMs,
        silenceMs, preRollMs, minSendDelayMs, minUtteranceMs, maxUtteranceSeconds);
  }

  private static SttConfig copyDefaults(EndpointConfig endpoint, String model, String language, String fileField) {
    return speedDefaults().withApi(endpoint, model, language, fileField);
  }

  private static String clean(String value) { return value == null ? "" : value.strip(); }
  private static void range(double value, double min, double max, String name) {
    if (!Double.isFinite(value) || value < min || value > max)
      throw new IllegalArgumentException(name + ": " + min + "–" + max);
  }
}
