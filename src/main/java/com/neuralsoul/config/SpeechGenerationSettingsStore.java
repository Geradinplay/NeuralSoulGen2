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
 * remain available when CosyVoice is disabled.
 * Keys are separated into common.* and engine.cosy.* namespaces, so new engines
 * can own their own settings without changing the common streaming contract.
 */
public final class SpeechGenerationSettingsStore {
  public static final String COSYVOICE = "cosyvoice";
  /** Legacy engine id read from old configs; mapped to COSYVOICE on load. */
  public static final String LEGACY_FISH_S2 = "fish-s2";
  public static final String HTTP = "http";
  public static final String CHUNKS = "chunks";
  public static final String INCREMENTAL = "incremental";
  // Default path is relative to the NeuralSoul working directory; never hard-code drive letters.
  // {backend} resolves at runtime to a portable pack folder: cpu, cuda-12.4, cuda-13.5 or vulkan.
  public static final String DEFAULT_COSY_EXECUTABLE = "runtime/cosyvoice/{backend}/cosyvoice-server.exe";
  public static final String DEFAULT_MODEL = "model/tts/cosyvoice/cosyvoice-0.5b-q4_k_m.gguf";
  public static final String DEFAULT_VOICE = "default";
  public static final String SERVED_MODEL_NAME = "cosyvoice";

  public record Common(
      boolean enabled, String engineId, String transport, String textInputMode,
      int minChunkCharacters, int maxChunkCharacters, int flushDelayMs, int queueLimit,
      int playbackStartBufferMs) {
    public static Common defaults() {
      return new Common(false, COSYVOICE, HTTP, CHUNKS, 24, 180, 220, 8, 400);
    }
  }

  public record Cosy(
      String executablePath, String modelPath, String voice, String voicePromptPath,
      String referenceWavPath, String referenceText,
      String backend, String cudaPack, int deviceIndex, int cpuThreads, String host, int httpPort,
      double temperature, double topP, int topK, double speed, int chunkTokens) {
    public static Cosy defaults() {
      return new Cosy(
          DEFAULT_COSY_EXECUTABLE,
          DEFAULT_MODEL,
          DEFAULT_VOICE, "", "", "",
          "AUTO", "auto", 0, 8, "127.0.0.1", 5020,
          0.8, 0.8, 50, 1.0, 0);
    }
  }

  public record Settings(Common common, Cosy cosy) {
    public static Settings defaults() { return new Settings(Common.defaults(), Cosy.defaults()); }
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
        migrateEngine(str(props, "common.engine", d.engineId())),
        HTTP,
        str(props, "common.text-input", d.textInputMode()),
        integer(props, "common.min-chunk-chars", d.minChunkCharacters()),
        integer(props, "common.max-chunk-chars", d.maxChunkCharacters()),
        integer(props, "common.flush-delay-ms", d.flushDelayMs()),
        integer(props, "common.queue-limit", d.queueLimit()),
        integer(props, "common.playback-start-buffer-ms", d.playbackStartBufferMs()));
    Cosy c = Cosy.defaults();
    Cosy cosy = new Cosy(
        str(props, "engine.cosy.executable", c.executablePath()),
        str(props, "engine.cosy.model", c.modelPath()),
        str(props, "engine.cosy.voice", c.voice()),
        str(props, "engine.cosy.voice-prompt", c.voicePromptPath()),
        str(props, "engine.cosy.reference-wav", c.referenceWavPath()),
        str(props, "engine.cosy.reference-text", c.referenceText()),
        str(props, "engine.cosy.backend", c.backend()),
        str(props, "engine.cosy.cuda-pack", c.cudaPack()),
        integer(props, "engine.cosy.device-index", c.deviceIndex()),
        integer(props, "engine.cosy.cpu-threads", c.cpuThreads()),
        str(props, "engine.cosy.host", c.host()),
        integer(props, "engine.cosy.http-port", c.httpPort()),
        decimal(props, "engine.cosy.temperature", c.temperature()),
        decimal(props, "engine.cosy.top-p", c.topP()),
        integer(props, "engine.cosy.top-k", c.topK()),
        decimal(props, "engine.cosy.speed", c.speed()),
        integer(props, "engine.cosy.chunk-tokens", c.chunkTokens()));
    return new Settings(common, cosy);
  }

  /** Fish Speech was removed; a saved fish engine id now means CosyVoice. */
  private static String migrateEngine(String configured) {
    if (configured == null || configured.isBlank() || LEGACY_FISH_S2.equals(configured))
      return COSYVOICE;
    return configured;
  }

  public synchronized void save(Settings settings) throws IOException {
    validate(settings);
    Common c = settings.common();
    Cosy cosy = settings.cosy();
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
    put(p, "engine.cosy.executable", cosy.executablePath());
    put(p, "engine.cosy.model", cosy.modelPath());
    put(p, "engine.cosy.voice", cosy.voice());
    put(p, "engine.cosy.voice-prompt", cosy.voicePromptPath());
    put(p, "engine.cosy.reference-wav", cosy.referenceWavPath());
    put(p, "engine.cosy.reference-text", cosy.referenceText());
    put(p, "engine.cosy.backend", cosy.backend());
    put(p, "engine.cosy.cuda-pack", cosy.cudaPack());
    put(p, "engine.cosy.device-index", cosy.deviceIndex());
    put(p, "engine.cosy.cpu-threads", cosy.cpuThreads());
    put(p, "engine.cosy.host", cosy.host());
    put(p, "engine.cosy.http-port", cosy.httpPort());
    put(p, "engine.cosy.temperature", cosy.temperature());
    put(p, "engine.cosy.top-p", cosy.topP());
    put(p, "engine.cosy.top-k", cosy.topK());
    put(p, "engine.cosy.speed", cosy.speed());
    put(p, "engine.cosy.chunk-tokens", cosy.chunkTokens());

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
    Cosy f = s.cosy();
    if (!COSYVOICE.equals(c.engineId())) throw new IllegalArgumentException("Неизвестный TTS-движок");
    if (!HTTP.equals(c.transport())) throw new IllegalArgumentException("Неизвестный транспорт");
    if (!CHUNKS.equals(c.textInputMode()) && !INCREMENTAL.equals(c.textInputMode()))
      throw new IllegalArgumentException("Неизвестный режим входного текста");
    if (c.minChunkCharacters() < 1 || c.maxChunkCharacters() < c.minChunkCharacters() || c.maxChunkCharacters() > 10000)
      throw new IllegalArgumentException("Размер чанка: минимум >= 1, максимум >= минимума (не более 10000)");
    inRange("Задержка отправки чанка", c.flushDelayMs(), 0, 30000);
    inRange("Очередь чанков", c.queueLimit(), 1, 256);
    inRange("Буфер воспроизведения", c.playbackStartBufferMs(), 0, 30000);
    if (!java.util.Set.of("AUTO", "CPU", "CUDA", "VULKAN").contains(f.backend()))
      throw new IllegalArgumentException("Неизвестное устройство CosyVoice");
    if (!java.util.Set.of("auto", "cuda-13.5", "cuda-12.4").contains(f.cudaPack()))
      throw new IllegalArgumentException("Неизвестная сборка CUDA для CosyVoice");
    inRange("GPU index", f.deviceIndex(), 0, 32);
    inRange("CPU threads", f.cpuThreads(), 1, 256);
    inRange("HTTP port", f.httpPort(), 1, 65535);
    if (f.host().isBlank()) throw new IllegalArgumentException("Адрес сервера не указан");
    if (!Double.isFinite(f.temperature()) || f.temperature() <= 0 || f.temperature() > 4)
      throw new IllegalArgumentException("Temperature: 0–4 (не включая 0)");
    if (!Double.isFinite(f.topP()) || f.topP() <= 0 || f.topP() > 1)
      throw new IllegalArgumentException("Top-p: 0–1 (не включая 0)");
    inRange("Top-k", f.topK(), 0, 1000);
    if (!Double.isFinite(f.speed()) || f.speed() <= 0 || f.speed() > 4)
      throw new IllegalArgumentException("Speed: 0–4 (не включая 0)");
    inRange("Chunk tokens", f.chunkTokens(), 0, 8192);
    requireRelative("EXE", f.executablePath());
    requireRelative("GGUF", f.modelPath());
    if (f.voice().isBlank()) throw new IllegalArgumentException("Имя голоса не указано");
    if (!f.voicePromptPath().isBlank()) requireRelative("Prompt speech", f.voicePromptPath());
    if (!f.referenceWavPath().isBlank()) requireRelative("Reference WAV", f.referenceWavPath());
  }

  private static void requireRelative(String name, String raw) {
    if (raw.isBlank()) throw new IllegalArgumentException(name + ": путь не указан");
    Path path = Path.of(raw);
    if (path.isAbsolute() || raw.matches("(?i)^[a-z]:.*") || path.normalize().startsWith(".."))
      throw new IllegalArgumentException(name + ": укажи путь относительно каталога NeuralSoul");
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
