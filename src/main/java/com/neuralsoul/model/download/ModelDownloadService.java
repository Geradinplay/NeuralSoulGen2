package com.neuralsoul.model.download;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Installs a single GGUF/Whisper file without buffering it in RAM or touching model settings. */
public final class ModelDownloadService {
  private ModelDownloadService() {}

  public enum RuntimeKind {
    LLAMA("llama.cpp", "model/main/llama", ".gguf"),
    WHISPER("Whisper", "model/stt/whisper", ".bin");

    private final String label, directory, extension;
    RuntimeKind(String label, String directory, String extension) {
      this.label = label;
      this.directory = directory;
      this.extension = extension;
    }
    public String label() { return label; }
    public String directory() { return directory; }
    public String extension() { return extension; }
    @Override public String toString() { return label; }
  }

  @FunctionalInterface
  public interface Progress {
    /** totalBytes = -1 when the server doesn't report Content-Length. */
    void update(long bytes, long totalBytes);
  }

  public static String nameFromUrl(String url) {
    try {
      URI uri = URI.create(url.strip());
      String path = uri.getPath();
      if (path == null || path.endsWith("/")) return "";
      return path.substring(path.lastIndexOf('/') + 1);
    } catch (Exception e) {
      return "";
    }
  }

  public static Path download(RuntimeKind kind, String url, String requestedName,
                              Progress progress, BooleanSupplier cancelled)
      throws IOException, InterruptedException {
    Objects.requireNonNull(kind);
    URI uri;
    try { uri = URI.create(url == null ? "" : url.strip()); }
    catch (IllegalArgumentException e) { throw new IOException("Некорректная ссылка", e); }
    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null)
      throw new IOException("Для скачивания нужна прямая HTTPS-ссылка на файл модели");
    String name = checkedName(kind,
        requestedName == null || requestedName.isBlank() ? nameFromUrl(url) : requestedName);
    Path directory = targetDirectory(kind, name);
    Path destination = directory.resolve(name);
    ensureNewDestination(destination);

    HttpClient client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(25)).build();
    HttpRequest request = HttpRequest.newBuilder(uri)
        .GET().timeout(Duration.ofHours(8))
        .header("User-Agent", "NeuralSoul-Gen2/1.0")
        .build();
    HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream input = response.body()) {
      if (response.statusCode() != 200)
        throw new IOException("Сервер вернул HTTP " + response.statusCode()
            + ". Проверь ссылку и доступ к модели");
      String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
      if (contentType.contains("text/html") || contentType.contains("application/json")
          || contentType.contains("application/xml") || contentType.contains("text/plain"))
        throw new IOException("Ссылка вернула страницу/ошибку вместо файла модели. Нужен URL /resolve/, не /blob/");
      long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
      return copyToDestination(input, directory, destination, kind, total, progress, cancelled);
    }
  }

  public static Path importFile(RuntimeKind kind, Path source, String requestedName,
                                Progress progress, BooleanSupplier cancelled) throws IOException {
    Objects.requireNonNull(kind);
    if (source == null || !Files.isRegularFile(source))
      throw new IOException("Файл модели не найден");
    String name = checkedName(kind,
        requestedName == null || requestedName.isBlank() ? source.getFileName().toString() : requestedName);
    Path directory = targetDirectory(kind, name);
    Path destination = directory.resolve(name);
    ensureNewDestination(destination);
    try (InputStream input = Files.newInputStream(source)) {
      return copyToDestination(input, directory, destination, kind, Files.size(source), progress, cancelled);
    }
  }

  private static Path targetDirectory(RuntimeKind kind, String filename) throws IOException {
    // One subfolder per installed model, named after the file without its extension.
    // Paths remain relative to the NeuralSoul working directory.
    String folderName = filename.substring(0, filename.length() - kind.extension().length());
    Path projectRoot = Path.of("").toAbsolutePath().normalize();
    Path directory = projectRoot.resolve(kind.directory()).resolve(folderName).normalize();
    if (!directory.startsWith(projectRoot)) throw new IOException("Недопустимая папка модели");
    Path current = projectRoot;
    for (Path part : projectRoot.relativize(directory)) {
      current = current.resolve(part);
      if (Files.isSymbolicLink(current))
        throw new IOException("Папка моделей не должна быть символической ссылкой: " + current);
    }
    Files.createDirectories(directory);
    return directory;
  }

  private static void ensureNewDestination(Path destination) throws IOException {
    if (Files.exists(destination) || Files.isSymbolicLink(destination))
      throw new FileAlreadyExistsException("Модель уже существует: " + destination.getFileName());
  }

  public static String checkedName(RuntimeKind kind, String raw) throws IOException {
    String name = raw == null ? "" : raw.strip();
    if (name.isBlank() || name.equals(".") || name.equals("..")
        || name.contains("/") || name.contains("\\") || name.matches(".*[<>:\"|?*\\p{Cntrl}].*"))
      throw new IOException("Некорректное имя файла модели");
    if (name.endsWith(".") || name.endsWith(" "))
      throw new IOException("Имя файла не может заканчиваться пробелом или точкой");
    String stem = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
    if (stem.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))
      throw new IOException("Зарезервированное имя файла Windows");
    if (!name.toLowerCase(Locale.ROOT).endsWith(kind.extension()))
      throw new IOException(kind.label() + " ожидает файл " + kind.extension());
    return name;
  }

  private static Path copyToDestination(InputStream input, Path dir, Path destination, RuntimeKind kind,
                                        long total, Progress progress, BooleanSupplier cancelled) throws IOException {
    Path temporary = Files.createTempFile(dir, ".neuralsoul-download-", ".part");
    boolean installed = false;
    long count = 0;
    try {
      try (OutputStream out = Files.newOutputStream(temporary)) {
        byte[] buffer = new byte[256 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
          if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new IOException("Загрузка отменена");
          out.write(buffer, 0, read);
          count += read;
          if (progress != null) progress.update(count, total);
        }
      }
      if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
        throw new IOException("Загрузка отменена");
      if (total >= 0 && count != total)
        throw new IOException("Файл скачан не полностью: " + count + " из " + total + " байт");
      validateSignature(temporary, kind);
      // Normal move must not replace an existing model, even if another task created it while downloading.
      Files.move(temporary, destination);
      installed = true;
      return destination;
    } finally {
      if (!installed) Files.deleteIfExists(temporary);
    }
  }

  /** Reject HTML, corrupt/truncated downloads and formats not accepted by these runtimes. */
  private static void validateSignature(Path file, RuntimeKind kind) throws IOException {
    if (Files.size(file) < 1024) throw new IOException("Скачанный файл слишком мал для модели");
    byte[] magic = new byte[4];
    try (InputStream in = Files.newInputStream(file)) {
      if (in.readNBytes(magic, 0, 4) != 4) throw new IOException("Неполный заголовок модели");
    }
    if (kind == RuntimeKind.LLAMA
        && !(magic[0] == 'G' && magic[1] == 'G' && magic[2] == 'U' && magic[3] == 'F'))
      throw new IOException("Файл не является GGUF (ожидается сигнатура GGUF)");
    if (kind == RuntimeKind.WHISPER
        && !(magic[0] == 'l' && magic[1] == 'm' && magic[2] == 'g' && magic[3] == 'g'))
      throw new IOException("Файл не является моделью GGML для whisper.cpp");
  }
}
