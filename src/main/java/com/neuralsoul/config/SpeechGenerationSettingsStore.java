package com.neuralsoul.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Configuration for the pluggable TTS runtime.
 *
 * Kept separate from tts.properties so existing Coqui/OpenAI speech settings
 * remain available when Fish Speech is disabled.
 * Keys are separated into common.* and engine.fish.* namespaces, so new engines
 * can own their own settings without changing the common streaming contract.
 */
public final class SpeechGenerationSettingsStore {
  public static final String FISH_S2 = "fish-s2";
  public static final String HTTP = "http";
  public static final String GRPC = "grpc";
  public static final String CHUNKS = "chunks";
  public static final String INCREMENTAL = "incremental";
  // Default path is relative to the NeuralSoul working directory; never hard-code drive letters.
  // {backend} resolves at runtime to cuda, vulkan or cpu, never to an absolute path.
  public static final String DEFAULT_FISH_EXECUTABLE = "runtime/fish_speech_s2/s2-windows-{backend}/s2.exe";
  public static final String LEGACY_FISH_EXECUTABLE = "runtime/tts/fish_speech/s2.exe";
  public static final String OLD_FISH_EXECUTABLE = "runtime/fish_speech_s2-runtime-windows-x64/s2.exe";
  public static final String DEFAULT_FISH_TOKENIZER = "auto";
  public static final String LEGACY_FISH_TOKENIZER = "model/tts/fish_speech/tokenizer.json";

  public record Common(
      boolean enabled, String engineId, String transport, String textInputMode,
      int minChunkCharacters, int maxChunkCharacters, int flushDelayMs, int queueLimit,
      int playbackStartBufferMs, boolean lowLatency, int decodeStrideFrames,
      int holdbackFrames, boolean segmentSentences, int sentencePauseMs) {
    public static Common defaults() {
      return new Common(false, FISH_S2, HTTP, CHUNKS, 24, 180, 220, 8,
          400, true, 4, 0, false, 180);
    }
  }

  public record Fish(
      String executablePath, String modelPath, String tokenizerPath, String backend,
      int deviceIndex, int cpuThreads, String host, int httpPort, int grpcPort,
      String voice, String referenceAudio, String referenceText,
      double temperature, double topP, int topK, int maxTokens, String runtimePreference) {
    public static Fish defaults() {
      return new Fish(
          DEFAULT_FISH_EXECUTABLE,
          "model/tts/fish_speech/s2-pro-q4_k_m.gguf",
          DEFAULT_FISH_TOKENIZER,
          "AUTO", 0, 8, "127.0.0.1", 3030, 3031, "", "", "", 0.8, 0.8, 30, 1024,
          "VULKAN");
    }
  }

  public record Settings(Common common, Fish fish) {
    public static Settings defaults() { return new Settings(Common.defaults(), Fish.defaults()); }
  }

  private final Path path;

  public SpeechGenerationSettingsStore(Path configDirectory) {
    this.path = configDirectory.resolve("tts-generation.properties");
  }

  public Path path() { return path; }

  public synchronized Settings load() throws IOException {
    Properties props = new Properties();
    if (Files.isRegularFile(path)) {
      try (InputStream in = Files.newInputStream(path)) { props.load(in); }
    }
    Common d = Common.defaults();
    Common common = new Common(
        bool(props, "common.enabled", d.enabled()),
        str(props, "common.engine", d.engineId()),
        str(props, "common.transport", d.transport()),
        str(props, "common.text-input", d.textInputMode()),
        integer(props, "common.min-chunk-chars", d.minChunkCharacters()),
        integer(props, "common.max-chunk-chars", d.maxChunkCharacters()),
        integer(props, "common.flush-delay-ms", d.flushDelayMs()),
        integer(props, "common.queue-limit", d.queueLimit()),
        integer(props, "common.playback-start-buffer-ms", d.playbackStartBufferMs()),
        bool(props, "common.low-latency", d.lowLatency()),
        integer(props, "common.decode-stride-frames", d.decodeStrideFrames()),
        integer(props, "common.holdback-frames", d.holdbackFrames()),
        bool(props, "common.segment-sentences", d.segmentSentences()),
        integer(props, "common.sentence-pause-ms", d.sentencePauseMs()));
    Fish f = Fish.defaults();
    Fish fish = new Fish(
        migrateLegacyExecutable(str(props, "engine.fish.executable", f.executablePath())),
        str(props, "engine.fish.model", f.modelPath()),
        migrateLegacyTokenizer(str(props, "engine.fish.tokenizer", f.tokenizerPath())),
        str(props, "engine.fish.backend", f.backend()),
        integer(props, "engine.fish.device-index", f.deviceIndex()),
        integer(props, "engine.fish.cpu-threads", f.cpuThreads()),
        str(props, "engine.fish.host", f.host()),
        integer(props, "engine.fish.http-port", f.httpPort()),
        integer(props, "engine.fish.grpc-port", f.grpcPort()),
        str(props, "engine.fish.voice", f.voice()),
        str(props, "engine.fish.reference-audio", f.referenceAudio()),
        str(props, "engine.fish.reference-text", f.referenceText()),
        decimal(props, "engine.fish.temperature", f.temperature()),
        decimal(props, "engine.fish.top-p", f.topP()),
        integer(props, "engine.fish.top-k", f.topK()),
        integer(props, "engine.fish.max-tokens", f.maxTokens()),
        str(props, "engine.fish.runtime", legacyRuntime(props, f.runtimePreference())));
    return new Settings(common, fish);
  }

  /** A previously saved default must not override the new project-relative runtime layout. */
  private static String migrateLegacyExecutable(String configured) {
    if (configured == null) return DEFAULT_FISH_EXECUTABLE;
    String normalized = configured.replace('\\', '/');
    if (normalized.equals(LEGACY_FISH_EXECUTABLE) || normalized.equals(OLD_FISH_EXECUTABLE))
      return DEFAULT_FISH_EXECUTABLE;
    return configured;
  }

  /** Older versions kept tokenizer.json alongside the GGUF; new portable runtimes bundle it. */
  private static String migrateLegacyTokenizer(String configured) {
    if (configured == null || configured.isBlank()) return DEFAULT_FISH_TOKENIZER;
    if (configured.replace('\\', '/').equals(LEGACY_FISH_TOKENIZER)) return DEFAULT_FISH_TOKENIZER;
    return configured;
  }

  public synchronized void save(Settings settings) throws IOException {
    validate(settings);
    Common c = settings.common();
    Fish f = settings.fish();
    Properties p = new Properties();
    put(p, "common.enabled", c.enabled());
    put(p, "common.engine", c.engineId());
    put(p, "common.transport", c.transport());
    put(p, "common.text-input", c.textInputMode());
    put(p, "common.min-chunk-chars", c.minChunkCharacters());
    put(p, "common.max-chunk-chars", c.maxChunkCharacters());
    put(p, "common.flush-delay-ms", c.flushDelayMs());
    put(p, "common.queue-limit", c.queueLimit());
    put(p, "common.playback-start-buffer-ms", c.playbackStartBufferMs());
    put(p, "common.low-latency", c.lowLatency());
    put(p, "common.decode-stride-frames", c.decodeStrideFrames());
    put(p, "common.holdback-frames", c.holdbackFrames());
    put(p, "common.segment-sentences", c.segmentSentences());
    put(p, "common.sentence-pause-ms", c.sentencePauseMs());
    put(p, "engine.fish.executable", f.executablePath());
    put(p, "engine.fish.model", f.modelPath());
    put(p, "engine.fish.tokenizer", f.tokenizerPath());
    put(p, "engine.fish.backend", f.backend());
    put(p, "engine.fish.runtime", f.runtimePreference());
    put(p, "engine.fish.device-index", f.deviceIndex());
    put(p, "engine.fish.cpu-threads", f.cpuThreads());
    put(p, "engine.fish.host", f.host());
    put(p, "engine.fish.http-port", f.httpPort());
    put(p, "engine.fish.grpc-port", f.grpcPort());
    put(p, "engine.fish.voice", f.voice());
    put(p, "engine.fish.reference-audio", f.referenceAudio());
    put(p, "engine.fish.reference-text", f.referenceText());
    put(p, "engine.fish.temperature", f.temperature());
    put(p, "engine.fish.top-p", f.topP());
    put(p, "engine.fish.top-k", f.topK());
    put(p, "engine.fish.max-tokens", f.maxTokens());

    Files.createDirectories(path.getParent());
    Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
    try {
      try (OutputStream out = Files.newOutputStream(tmp)) {
        p.store(out, "NeuralSoul TTS settings");
      }
      try {
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException e) {
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  public static void validate(Settings s) {
    Common c = s.common();
    Fish f = s.fish();
    if (!FISH_S2.equals(c.engineId())) throw new IllegalArgumentException("Неизвестный TTS-движок");
    if (!HTTP.equals(c.transport()) && !GRPC.equals(c.transport()))
      throw new IllegalArgumentException("Неизвестный транспорт");
    if (!CHUNKS.equals(c.textInputMode()) && !INCREMENTAL.equals(c.textInputMode()))
      throw new IllegalArgumentException("Неизвестный режим входного текста");
    if (c.minChunkCharacters() < 1 || c.maxChunkCharacters() < c.minChunkCharacters() || c.maxChunkCharacters() > 10000)
      throw new IllegalArgumentException("Размер чанка: минимум >= 1, максимум >= минимума (не более 10000)");
    inRange("Задержка отправки чанка", c.flushDelayMs(), 0, 30000);
    inRange("Очередь чанков", c.queueLimit(), 1, 256);
    inRange("Буфер воспроизведения", c.playbackStartBufferMs(), 0, 30000);
    inRange("Шаг аудиодекодера", c.decodeStrideFrames(), 0, 128);
    inRange("Удержание кадров", c.holdbackFrames(), -1, 128);
    inRange("Пауза между предложениями", c.sentencePauseMs(), 0, 5000);
    if (!java.util.Set.of("AUTO", "CPU", "CUDA", "VULKAN").contains(f.backend()))
      throw new IllegalArgumentException("Неизвестное устройство Fish Speech");
    if (!java.util.Set.of("CUDA", "VULKAN").contains(f.runtimePreference()))
      throw new IllegalArgumentException("TTS runtime должен быть CUDA или Vulkan");
    inRange("GPU index", f.deviceIndex(), 0, 32);
    inRange("CPU threads", f.cpuThreads(), 1, 256);
    inRange("HTTP port", f.httpPort(), 1, 65535);
    inRange("gRPC port", f.grpcPort(), 1, 65535);
    if (f.host().isBlank()) throw new IllegalArgumentException("Адрес сервера не указан");
    if (!Double.isFinite(f.temperature()) || f.temperature() <= 0 || f.temperature() > 4)
      throw new IllegalArgumentException("Temperature: 0–4 (не включая 0)");
    if (!Double.isFinite(f.topP()) || f.topP() <= 0 || f.topP() > 1)
      throw new IllegalArgumentException("Top-p: 0–1 (не включая 0)");
    inRange("Top-k", f.topK(), 1, 1000);
    inRange("Max tokens", f.maxTokens(), 1, 32768);
    requireRelative("EXE", f.executablePath());
    requireRelative("GGUF", f.modelPath());
    requireRelative("Tokenizer", f.tokenizerPath());
    if (!f.referenceAudio().isBlank()) requireRelative("Reference audio", f.referenceAudio());
  }

  private static void requireRelative(String name, String raw) {
    if (raw.isBlank()) throw new IllegalArgumentException(name + ": путь не указан");
    Path path = Path.of(raw);
    if (path.isAbsolute() || raw.matches("(?i)^[a-z]:.*") || path.normalize().startsWith(".."))
      throw new IllegalArgumentException(name + ": укажи путь относительно каталога NeuralSoul");
  }

  private static String legacyRuntime(Properties props, String fallback) {
    String legacy = props.getProperty("engine.fish.backend", "").strip();
    return "CUDA".equals(legacy) || "VULKAN".equals(legacy) ? legacy : fallback;
  }

  private static void inRange(String label, int v, int lo, int hi) {
    if (v < lo || v > hi) throw new IllegalArgumentException(label + ": допустимо " + lo + "–" + hi);
  }
  private static String str(Properties p, String key, String fallback) {
    return p.getProperty(key, fallback).strip();
  }
  private static boolean bool(Properties p, String key, boolean fallback) {
    return Boolean.parseBoolean(p.getProperty(key, Boolean.toString(fallback)));
  }
  private static int integer(Properties p, String key, int fallback) {
    try { return Integer.parseInt(p.getProperty(key, "").strip()); }
    catch (NumberFormatException e) { return fallback; }
  }
  private static double decimal(Properties p, String key, double fallback) {
    try { return Double.parseDouble(p.getProperty(key, "").strip()); }
    catch (NumberFormatException e) { return fallback; }
  }
  private static void put(Properties p, String key, Object value) { p.setProperty(key, String.valueOf(value)); }
}
