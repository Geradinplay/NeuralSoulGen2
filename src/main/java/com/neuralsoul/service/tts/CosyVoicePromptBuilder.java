package com.neuralsoul.service.tts;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * One-shot creation of prompt_speech GGUF files with cosyvoice-cli --frontend-only.
 * Runs the CLI shipped with the selected runtime pack; no daemon process is kept.
 */
public final class CosyVoicePromptBuilder {
  private CosyVoicePromptBuilder() {}

  public static final String PROMPT_DIR = "model/tts/cosyvoice/prompts";
  public static final String FRONTEND_LOG = "logs/tts-cosyvoice-frontend.log";
  private static final String HF_BASE =
      "https://huggingface.co/Lourdle/Fun-CosyVoice3-0.5B-2512-GGUF/resolve/main/frontend-onnx/";

  /** Packs to probe for cosyvoice-cli.exe, CUDA newest first. */
  private static final List<String> PACK_FOLDERS = List.of("cuda-13.5", "cuda-12.4", "cpu", "vulkan");

  /**
   * Download the two frontend models only when absent. Files go directly into
   * model/tts/cosyvoice/frontend-onnx; a .part file is atomically renamed after
   * a complete download, so interrupted downloads never masquerade as models.
   */
  public static void ensureFrontendModels(Path root, Consumer<String> status) throws IOException {
    Path dir = root.resolve("model/tts/cosyvoice/frontend-onnx");
    Files.createDirectories(dir);
    downloadIfMissing(dir, "speech_tokenizer_v3.onnx", 900L * 1024 * 1024, status);
    downloadIfMissing(dir, "campplus.onnx", 20L * 1024 * 1024, status);
  }

  private static void downloadIfMissing(Path dir, String name, long minimumSize,
                                        Consumer<String> status) throws IOException {
    Path target = dir.resolve(name);
    if (Files.isRegularFile(target) && Files.size(target) >= minimumSize) return;
    Path part = target.resolveSibling(name + ".part");
    Files.deleteIfExists(part);
    if (status != null) status.accept("Скачивание " + name + "…");
    HttpURLConnection connection = (HttpURLConnection) URI.create(HF_BASE + name).toURL().openConnection();
    connection.setConnectTimeout(15_000);
    connection.setReadTimeout(60_000);
    connection.setInstanceFollowRedirects(true);
    connection.setRequestProperty("User-Agent", "NeuralSoul/1.0");
    try {
      int code = connection.getResponseCode();
      if (code < 200 || code >= 300)
        throw new IOException("HuggingFace HTTP " + code + " для " + name);
      long expected = connection.getContentLengthLong();
      try (InputStream in = connection.getInputStream()) {
        Files.copy(in, part);
      }
      long received = Files.size(part);
      if (received < minimumSize || expected > 0 && received != expected)
        throw new IOException("Файл " + name + " скачан не полностью: " + received
            + (expected > 0 ? " из " + expected : "") + " байт");
      try {
        Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException e) {
        Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      connection.disconnect();
      if (!Files.isRegularFile(target)) Files.deleteIfExists(part);
    }
  }

  /** Locate cosyvoice-cli.exe next to the configured server executable ({backend} template). */
  public static Path resolveCli(Path root, String executableTemplate) throws IOException {
    String template = executableTemplate.replace('\\', '/');
    List<String> candidates = new ArrayList<>();
    if (template.contains("{backend}")) {
      for (String pack : PACK_FOLDERS) {
        Path server = Path.of(template.replace("{backend}", pack)).normalize();
        Path parent = server.getParent();
        if (parent != null) candidates.add(parent.resolve("cosyvoice-cli.exe").toString());
      }
    } else {
      // An explicit path was configured; the CLI sits next to it.
      Path server = Path.of(template).normalize();
      candidates.add(server.getParent().resolve("cosyvoice-cli.exe").toString());
    }
    for (String relative : candidates) {
      Path cli = root.resolve(relative).normalize();
      if (cli.startsWith(root) && Files.isRegularFile(cli)) return cli;
    }
    throw new IOException("CosyVoice: не найден cosyvoice-cli.exe ни в одной папке рантайма "
        + PACK_FOLDERS + " (шаблон: " + executableTemplate + ")");
  }

  /** Nearest speech-tokenizer ONNX under model/tts/cosyvoice; float build preferred over int8. */
  public static String findSpeechTokenizer(Path root) throws IOException {
    return findOnnx(root, "speech_tokenizer", "int8");
  }

  /** Nearest campplus ONNX under model/tts/cosyvoice; float build preferred over int8. */
  public static String findCampplus(Path root) throws IOException {
    return findOnnx(root, "campplus", "int8");
  }

  private static String findOnnx(Path root, String namePart, String avoidPart) throws IOException {
    Path base = root.resolve("model/tts/cosyvoice");
    if (!Files.isDirectory(base)) {
      throw new IOException("CosyVoice: папки model/tts/cosyvoice нет — скачай веса и ONNX-фронтенд "
          + "(speech_tokenizer_v3.onnx, campplus.onnx) с huggingface.co/Lourdle/Fun-CosyVoice3-0.5B-2512-GGUF");
    }
    List<Path> found = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(base)) {
      walk.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".onnx"))
          .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).contains(namePart))
          .forEach(found::add);
    }
    if (found.isEmpty()) {
      throw new IOException("CosyVoice: ONNX '" + namePart + "' не найден в model/tts/cosyvoice. "
          + "Скачай frontend-onnx/speech_tokenizer_v3.onnx и campplus.onnx с "
          + "huggingface.co/Lourdle/Fun-CosyVoice3-0.5B-2512-GGUF");
    }
    found.sort(Comparator.comparingInt((Path p) ->
        p.getFileName().toString().toLowerCase(Locale.ROOT).contains(avoidPart) ? 1 : 0));
    return root.relativize(found.get(0)).toString().replace('\\', '/');
  }

  /**
   * Encode one reference WAV into a prompt_speech GGUF.
   *
   * @param voiceName  voice name; used for the output file name
   * @return relative (slash-separated) path of the created GGUF
   */
  public static String encode(Path root, Path cliExe, String tokenizerRel, String campplusRel,
                              String refWavRel, String promptText, String voiceName) throws Exception {
    if (promptText == null || promptText.isBlank())
      throw new IllegalArgumentException("Текст референса пуст — транскрипция должна совпадать с аудио");
    if (refWavRel == null || refWavRel.isBlank())
      throw new IllegalArgumentException("Не выбран референс WAV");
    Path refWav = root.resolve(refWavRel).normalize();
    if (!refWav.startsWith(root) || !Files.isRegularFile(refWav))
      throw new IOException("CosyVoice: референс не найден: " + refWavRel);
    if (Files.size(refWav) > 100L * 1024 * 1024)
      throw new IOException("Референс больше 100 МБ — обрежь до 5–10 секунд речи");

    String name = sanitizeVoiceName(voiceName);
    Path packageDir = root.resolve(PROMPT_DIR).resolve(name).normalize();
    Path output = packageDir.resolve(name + ".gguf").normalize();
    if (!output.startsWith(root)) throw new IOException("CosyVoice: некорректное имя голоса");
    Files.createDirectories(packageDir);
    Files.createDirectories(root.resolve(FRONTEND_LOG).getParent());

    List<String> cmd = List.of(cliExe.toString(),
        "--frontend-only",
        "--speech-tokenizer", root.resolve(tokenizerRel).normalize().toString(),
        "--campplus", root.resolve(campplusRel).normalize().toString(),
        "--prompt-audio", refWav.toString(),
        "--prompt-text", promptText,
        "--prompt-speech-output", output.toString());

    Path log = root.resolve(FRONTEND_LOG);
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(cliExe.getParent().toFile());
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(log.toFile()));
    System.out.println("[CosyVoice/Prompt] encoding voice '" + name + "' from " + refWavRel
        + " -> " + root.relativize(output).toString().replace('\\', '/'));
    Process process = pb.start();
    boolean finished = process.waitFor(15, TimeUnit.MINUTES);
    if (!finished) {
      process.destroyForcibly();
      throw new IOException("Кодировка голоса не завершилась за 15 минут; смотри " + FRONTEND_LOG);
    }
    String logTail = tail(log);
    if (process.exitValue() != 0) {
      throw new IOException("cosyvoice-cli завершился с кодом " + process.exitValue()
          + ": " + logTail);
    }
    if (!Files.isRegularFile(output) || Files.size(output) < 1024) {
      throw new IOException("cosyvoice-cli не создал prompt_speech GGUF: " + logTail);
    }
    // Keep a self-contained voice package: generated prompt, playable source and
    // exact transcript. Replacing a voice name intentionally replaces its package.
    Files.copy(refWav, packageDir.resolve("reference.wav"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    Files.writeString(packageDir.resolve("prompt.txt"), promptText, StandardCharsets.UTF_8);
    System.out.println("[CosyVoice/Prompt] created " + output.getFileName()
        + " (" + Files.size(output) + " bytes)");
    return root.relativize(output).toString().replace('\\', '/');
  }

  /** Output file names stay ASCII so the --voice-prompt mapping is always safe. */
  public static String sanitizeVoiceName(String raw) {
    String name = raw == null ? "" : raw.strip();
    if (name.isBlank()) name = "voice";
    name = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    if (name.isBlank() || name.equals("_")) name = "voice";
    return name;
  }

  private static String tail(Path log) {
    try {
      byte[] bytes = Files.readAllBytes(log);
      int from = (int) Math.max(0, bytes.length - 4096);
      return new String(bytes, from, bytes.length - from, StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      return "(лог недоступен: " + e.getMessage() + ")";
    }
  }
}
