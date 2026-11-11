package com.neuralsoul.service.tts;

import com.neuralsoul.config.SpeechGenerationSettingsStore;
import com.neuralsoul.config.SpeechGenerationSettingsStore.Fish;
import com.neuralsoul.config.SpeechGenerationSettingsStore.Settings;
import com.neuralsoul.service.api.AudioPlayer;
import com.neuralsoul.service.api.TtsService;
import com.neuralsoul.service.http.Json;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/** A persistent s2.cpp HTTP server process, never restarted for individual speech chunks. */
public final class FishSpeechTtsService implements TtsService {
  public record AllowedDevice(int index, String name, String vendor, long vramBytes,
                              boolean enabled, String uuid) {
    public AllowedDevice(int index, String name, String vendor, long vramBytes, boolean enabled) {
      this(index, name, vendor, vramBytes, enabled, "");
    }
  }
  private record Placement(String backend, AllowedDevice device, int nativeIndex, String selector) {}
  private final SpeechGenerationSettingsStore store;
  private final Supplier<List<AllowedDevice>> hardware;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
  private final Object lock = new Object();
  private volatile Process ownedProcess;
  /** -1 means no process owned (including attachment to an external HTTP server). */
  public long ownedPid() {
    Process p = ownedProcess;
    return p != null && p.isAlive() ? p.pid() : -1L;
  }
  private volatile boolean ready;
  private volatile InputStream activeStream;
  private volatile String fingerprint = "";
  private volatile Settings activeSettings;

  public FishSpeechTtsService(SpeechGenerationSettingsStore store, Supplier<List<AllowedDevice>> hardware) {
    this.store = Objects.requireNonNull(store);
    this.hardware = hardware == null ? List::of : hardware;
  }

  @Override public void start() throws Exception {
    Settings settings = store.load();
    SpeechGenerationSettingsStore.validate(settings);
    TtsTrace.log("SERVICE_START_REQUEST", "enabled=" + settings.common().enabled()
        + " transport=" + settings.common().transport()
        + " backend=" + settings.fish().backend() + " runtime=" + settings.fish().runtimePreference()
        + " endpoint=" + settings.fish().host() + ":" + settings.fish().httpPort());
    if (!settings.common().enabled()) throw new IllegalStateException("Fish Speech отключён в настройках генерации речи");
    if (!SpeechGenerationSettingsStore.HTTP.equals(settings.common().transport()))
      throw new UnsupportedOperationException("s2.cpp не предоставляет gRPC API. Выбери HTTP PCM16; gRPC потребует отдельный совместимый серверный адаптер.");
    synchronized (lock) {
      String desired = signature(settings);
      if (ready && desired.equals(fingerprint) && (ownedProcess == null || ownedProcess.isAlive())) {
        TtsTrace.log("SERVICE_REUSE", ownedProcess == null ? "externalServer=true" : "pid=" + ownedProcess.pid());
        return;
      }
      stopInternal();
      Fish f = settings.fish();
      if (tcpReady(f.host(), f.httpPort())) {
        // A foreign server could be using a different model, GPU, or backend.
        // Do not report the selected GUI device as active when we do not own it.
        String message = "Fish Speech: порт " + f.host() + ":" + f.httpPort()
            + " уже занят внешним сервером. Его GPU неизвестна. Останови чужой s2.exe "
            + "или выбери другой HTTP-порт; NeuralSoul не будет подключаться к неизвестной GPU.";
        System.err.println("[FishSpeech/GPU] " + message);
        TtsTrace.log("SERVICE_PORT_CONFLICT", "port=" + f.httpPort()
            + " refusingUnverifiedExternalServer=true");
        throw new IllegalStateException(message);
      }
      if (!isLocal(f.host())) throw new IllegalStateException("Автозапуск Fish Speech разрешён только на localhost");
      Placement placement = choosePlacement(f);
      String backend = placement.backend();
      Path exe = resolveExecutable(f, backend);
      Path model = resolveFile(f.modelPath());
      Path tokenizer = resolveTokenizer(f, exe);
      System.out.println("[FishSpeech] runtime=" + exe + ", tokenizer=" + tokenizer);
      List<String> cmd = new ArrayList<>(List.of(exe.toString(), "-m", model.toString(), "-t", tokenizer.toString(),
          "--server", "-H", f.host(), "-P", Integer.toString(f.httpPort())));
      int index = placement.nativeIndex();
      if ("CUDA".equals(backend)) { cmd.add("-c"); cmd.add("0"); }
      else if ("VULKAN".equals(backend)) { cmd.add("-v"); cmd.add(Integer.toString(index)); }
      else { cmd.add("-threads"); cmd.add(Integer.toString(f.cpuThreads())); }
      Path log = root.resolve("logs/tts-fish-s2.log");
      Files.createDirectories(log.getParent());
      long nativeLogOffset = Files.exists(log) ? Files.size(log) : 0L;
      ProcessBuilder pb = new ProcessBuilder(cmd).directory(exe.getParent().toFile());
      if ("CUDA".equals(backend)) {
        // NVIDIA documents UUID-based CUDA_VISIBLE_DEVICES as the stable way to
        // select one physical GPU. The isolated s2 process always sees it as CUDA 0.
        pb.environment().put("CUDA_DEVICE_ORDER", "PCI_BUS_ID");
        pb.environment().put("CUDA_VISIBLE_DEVICES", placement.selector());
        System.out.println("[FishSpeech/GPU] CUDA isolation: " + placement.device().name()
            + " UUID=" + placement.selector() + " -> CUDA 0");
        TtsTrace.log("GPU_ISOLATION", "physical=" + placement.device().name()
            + " uuid=" + placement.selector() + " visibleDevice=CUDA0");
      } else if ("VULKAN".equals(backend)) {
        System.out.println("[FishSpeech/GPU] Vulkan enumerated index " + index
            + " name=" + placement.device().name() + " (s2 native allocation must be verified in native log)");
        TtsTrace.log("GPU_MAPPING", "physical=" + placement.device().name()
            + " vulkanIndex=" + index + " nativeOffloadStillUnverified=true");
      }
      pb.redirectErrorStream(true);
      pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
      ownedProcess = pb.start();
      System.out.println("[FishSpeech] starting " + backend + " device=" + index
          + " selectedGPU=" + (placement.device() == null ? "CPU" : placement.device().name()) + ", PID="
          + ownedProcess.pid() + ", log=" + log);
      System.out.println("[FishSpeech] command: " + String.join(" ", cmd));
      TtsTrace.log("SERVICE_NATIVE_LAUNCHED", "pid=" + ownedProcess.pid()
          + " backend=" + backend + " nativeIndex=" + index
          + " selectedGpu=" + (placement.device() == null ? "CPU" : placement.device().name())
          + " model=" + model.getFileName()
          + " exe=" + exe + " nativeLog=" + log);
      long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
      while (System.nanoTime() < deadline) {
        if (Thread.currentThread().isInterrupted()) {
          stopInternal();
          throw new InterruptedException("TTS startup interrupted");
        }
        if (ownedProcess == null || !ownedProcess.isAlive()) {
          stopInternal();
          throw new IOException("Fish Speech завершился при запуске; смотри logs/tts-fish-s2.log");
        }
        if (tcpReady(f.host(), f.httpPort())) {
          ready = true;
          fingerprint = desired;
          activeSettings = settings;
          // A listening TCP socket does NOT establish GPU offload or actual model residency.
          // Show the backend's own layer allocation report instead of claiming VRAM was used.
          printNativeOffloadDiagnostics(log, nativeLogOffset, backend);
          System.out.println("[FishSpeech] HTTP port open; verify GPU layer allocation in " + log);
          TtsTrace.log("SERVICE_PORT_READY", "pid=" + ownedProcess.pid()
              + " nativeGpuOffloadNotProven=true log=" + log);
          return;
        }
        Thread.sleep(250);
      }
      stopInternal();
      throw new IOException("Fish Speech не открыл порт за 90 секунд; смотри logs/tts-fish-s2.log");
    }
  }

  /** Summarize model-placement lines from THIS start only, leaving the native log intact.
   * This is diagnostic, not a guarantee: missing lines must never be described as GPU load. */
  private static void printNativeOffloadDiagnostics(Path log, long startOffset, String backend) {
    if ("CPU".equals(backend)) {
      System.out.println("[FishSpeech/GPU] CPU backend selected; no video-memory allocation expected");
      return;
    }
    try (java.nio.channels.FileChannel file = java.nio.channels.FileChannel.open(log, StandardOpenOption.READ)) {
      long end = file.size();
      long begin = Math.max(startOffset, end - 262144L);
      if (begin >= end) {
        System.out.println("[FishSpeech/GPU] native log still empty; GPU offload unverified");
        return;
      }
      byte[] bytes = new byte[(int) (end - begin)];
      java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes);
      file.position(begin);
      while (buf.hasRemaining() && file.read(buf) != -1) { /* read current startup excerpt */ }
      String nativeOutput = new String(bytes, 0, buf.position(), StandardCharsets.UTF_8);
      int matches = 0;
      for (String line : nativeOutput.split("\\R")) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("gpu layers") || lower.contains("layers on gpu")
            || lower.contains("offload") || lower.contains("fallback")
            || lower.contains("cuda") || lower.contains("vulkan")) {
          if (matches++ < 12) System.out.println("[FishSpeech/GPU/native] " + line.strip());
        }
      }
      if (matches == 0)
        System.out.println("[FishSpeech/GPU] no GPU-layer diagnostics found in native log; offload not verified");
    } catch (IOException e) {
      System.out.println("[FishSpeech/GPU] unable to read native log: " + e);
    }
  }

  private static boolean isLocal(String host) {
    return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
  }
  /** Select the portable native build for the actual compute backend. */
  private Path resolveExecutable(Fish f, String backend) throws IOException {
    String configured = f.executablePath().replace('\\', '/');
    String label = backend.toLowerCase(Locale.ROOT);
    if (configured.contains("{backend}")) {
      Path candidate = root.resolve(configured.replace("{backend}", label)).normalize();
      if (candidate.startsWith(root) && Files.isRegularFile(candidate)) return candidate;
      // Older releases used runtime/tts/fish_speech; keep those working if present.
      if (configured.equals(SpeechGenerationSettingsStore.DEFAULT_FISH_EXECUTABLE)) {
        for (String relative : List.of(
            "runtime/tts/fish_speech/s2-windows-" + label + "/s2.exe",
            "runtime/tts/fish_speech/" + label + "/s2.exe")) {
          Path fallback = root.resolve(relative).normalize();
          if (Files.isRegularFile(fallback)) return fallback;
        }
      }
      throw new FileNotFoundException("Fish Speech: сборка " + backend
          + " не найдена: " + candidate + " (нужна папка s2-windows-" + label + ")");
    }
    // A user-specified executable path is honored exactly, without silent backend substitution.
    return resolveFile(f.executablePath());
  }

  /** Runtime packages contain tokenizer.json; 'auto' follows the selected backend. */
  private Path resolveTokenizer(Fish f, Path executable) throws IOException {
    String configured = f.tokenizerPath().trim();
    if (configured.isEmpty() || configured.equalsIgnoreCase("auto")
        || configured.replace('\\', '/').equals(SpeechGenerationSettingsStore.LEGACY_FISH_TOKENIZER)) {
      Path adjacent = executable.getParent().resolve("tokenizer.json").normalize();
      if (Files.isRegularFile(adjacent)) return adjacent;
      Path modelAdjacent = resolveModelTokenizer(f);
      if (modelAdjacent != null) return modelAdjacent;
      Path legacy = root.resolve(SpeechGenerationSettingsStore.LEGACY_FISH_TOKENIZER).normalize();
      if (Files.isRegularFile(legacy)) return legacy;
      throw new FileNotFoundException("Fish Speech: tokenizer.json не найден рядом с "
          + executable + " и моделью " + f.modelPath());
    }
    if (configured.contains("{backend}")) {
      String label = executable.getParent().getFileName().toString()
          .replaceFirst("^s2-windows-", "");
      return resolveFile(configured.replace("{backend}", label));
    }
    return resolveFile(configured);
  }

  private Path resolveModelTokenizer(Fish f) {
    try {
      Path model = resolveFile(f.modelPath());
      Path tokenizer = model.getParent().resolve("tokenizer.json");
      return Files.isRegularFile(tokenizer) ? tokenizer : null;
    } catch (IOException e) { return null; }
  }

  private static String signature(Settings s) {
    Fish f = s.fish();
    return f.executablePath()+"|"+f.modelPath()+"|"+f.tokenizerPath()+"|"+f.host()+":"+f.httpPort()+"|"+
        f.backend()+"|"+f.deviceIndex()+"|"+f.runtimePreference()+"|"+s.common().transport();
  }
  private static boolean tcpReady(String host, int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 350);
      return true;
    } catch (IOException e) { return false; }
  }
  private Path resolveFile(String relative) throws IOException {
    Path p = root.resolve(relative).normalize();
    if (!p.startsWith(root) || !Files.isRegularFile(p))
      throw new FileNotFoundException("Fish Speech: отсутствует файл " + relative + " (каталог приложения: " + root + ")");
    return p;
  }
  private List<AllowedDevice> eligible(String backend) {
    return hardware.get().stream().filter(d -> d.index() >= 0)
        .filter(d -> !"CUDA".equals(backend) || d.vendor().equalsIgnoreCase("NVIDIA")
            || d.name().toUpperCase(Locale.ROOT).contains("NVIDIA"))
        .sorted(Comparator.comparingInt(AllowedDevice::index)).toList();
  }
  /** Returns the physical GPU selected in GUI, not an assumed native API ordinal. */
  private Placement choosePlacement(Fish f) {
    String backend = chooseBackend(f);
    if ("CPU".equals(backend)) {
      if (hardware.get().stream().noneMatch(d -> d.index() == -1 && d.enabled()))
        throw new IllegalStateException("CPU запрещён в настройках устройств");
      return new Placement("CPU", null, 0, "");
    }
    List<AllowedDevice> eligible = eligible(backend);
    AllowedDevice chosen;
    if ("AUTO".equals(f.backend())) {
      chosen = eligible.stream().filter(AllowedDevice::enabled)
          .max(Comparator.comparingLong(AllowedDevice::vramBytes)).orElseThrow(
              () -> new IllegalStateException("Нет разрешённой GPU для TTS"));
    } else {
      chosen = eligible.stream().filter(d -> d.index() == f.deviceIndex() && d.enabled())
          .findFirst().orElseThrow(() -> new IllegalStateException(
              "Выбранная GPU отключена или не поддерживает " + backend));
    }
    if ("CUDA".equals(backend)) {
      String uuid = chosen.uuid() == null ? "" : chosen.uuid().strip();
      if (!uuid.matches("(?i)GPU-[0-9a-f-]{8,}")) {
        throw new IllegalStateException("Не могу безопасно выбрать CUDA GPU '" + chosen.name()
            + "': отсутствует NVIDIA UUID из nvidia-smi. Отказываюсь использовать порядковый индекс, "
            + "который может указывать на другую видеокарту.");
      }
      System.out.println("[FishSpeech/GPU] GUI GPU " + chosen.index() + " = "
          + chosen.name() + " UUID=" + uuid + " -> isolated CUDA 0");
      return new Placement(backend, chosen, 0, uuid);
    }
    // The Vulkan physical-device order is unrelated to Windows or CUDA indices.
    List<VulkanDeviceSelector.Device> vulkan = VulkanDeviceSelector.enumerate();
    int vulkanIndex = VulkanDeviceSelector.selectByName(chosen.name(), vulkan);
    System.out.println("[FishSpeech/GPU] Vulkan physical devices: " + vulkan);
    System.out.println("[FishSpeech/GPU] GUI GPU " + chosen.index() + " = " + chosen.name()
        + " -> Vulkan " + vulkanIndex);
    return new Placement(backend, chosen, vulkanIndex, "");
  }

  private String chooseBackend(Fish f) {
    if (!"AUTO".equals(f.backend())) return f.backend();
    if (eligible(f.runtimePreference()).stream().anyMatch(AllowedDevice::enabled)) return f.runtimePreference();
    boolean cpuAllowed = hardware.get().stream().anyMatch(d -> d.index() == -1 && d.enabled());
    if (!cpuAllowed) throw new IllegalStateException("Нет разрешённого CPU/GPU для Fish Speech");
    return "CPU";
  }

  @Override public boolean isRunning() {
    if (!ready || (ownedProcess != null && !ownedProcess.isAlive())) return false;
    try {
      Settings s = store.load();
      if (!s.common().enabled() || !signature(s).equals(fingerprint)) return false;
      // Externally owned servers can disappear; check readiness without altering their lifecycle.
      if (ownedProcess == null && !tcpReady(s.fish().host(), s.fish().httpPort())) {
        ready = false;
        return false;
      }
      return true;
    }
    catch (Exception e) { return false; }
  }
  @Override public void stop() {
    synchronized (lock) { stopInternal(); }
  }
  private void stopInternal() {
    ready = false;
    cancelSpeech();
    Process process = ownedProcess;
    ownedProcess = null;
    activeSettings = null;
    fingerprint = "";
    if (process != null) {
      process.destroy();
      try { if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly(); }
      catch (InterruptedException e) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
    }
  }
  @Override public void cancelSpeech() {
    TtsTrace.log("SERVICE_CANCEL_AUDIO", "activeHttpStream=" + (activeStream != null));
    InputStream in = activeStream;
    if (in != null) try { in.close(); } catch (IOException ignored) {}
  }

  @Override public void speak(String text, AudioPlayer player) throws Exception {
    Objects.requireNonNull(player);
    Settings s = requireReady();
    long started = System.nanoTime();
    TtsTrace.log("TTS_SPEAK", "chars=" + (text == null ? 0 : text.length())
        + " running=" + isRunning() + " backend=" + s.fish().backend());
    try (Response stream = request(text, s)) {
      int sampleRate = headerInt(stream.response(), "X-Audio-Sample-Rate", 44100);
      int channels = headerInt(stream.response(), "X-Audio-Channels", 1);
      TtsTrace.log("TTS_AUDIO_FORMAT", "sampleRate=" + sampleRate + " channels=" + channels);
      player.start();
      player.playPcm(stream.body(), sampleRate, channels, s.common().playbackStartBufferMs());
      TtsTrace.log("TTS_SPEAK_DONE", "durationMs="
          + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    } catch (Exception | Error e) {
      TtsTrace.log("TTS_SPEAK_FAILED", e.getClass().getSimpleName() + ": " + e.getMessage()
          + " durationMs=" + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
      throw e;
    }
  }
  @Override public byte[] convertTextToSpeech(String text) throws Exception {
    Settings s = requireReady();
    try (Response response = request(text, s)) {
      byte[] pcm = response.body().readAllBytes();
      int rate = headerInt(response.response(), "X-Audio-Sample-Rate", 44100);
      int channels = headerInt(response.response(), "X-Audio-Channels", 1);
      return wav(pcm, rate, channels);
    }
  }
  private Settings requireReady() throws IOException {
    if (!isRunning()) throw new IllegalStateException("Fish Speech не подключён");
    // Sampling, voice and output buffering are hot-reloadable; no model restart necessary.
    return store.load();
  }
  private record Response(HttpResponse<InputStream> response, InputStream body) implements AutoCloseable {
    @Override public void close() throws IOException { body.close(); }
  }
  private static int headerInt(HttpResponse<?> res, String name, int fallback) {
    return res.headers().firstValue(name).map(x -> {
      try { return Integer.parseInt(x.trim()); } catch (NumberFormatException e) { return fallback; }
    }).orElse(fallback);
  }
  private Response request(String text, Settings s) throws Exception {
    if (text == null || text.isBlank()) throw new IllegalArgumentException("Нет текста для TTS");
    Fish f = s.fish();
    String boundary = "NeuralSoulTts" + UUID.randomUUID().toString().replace("-", "");
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    field(b, boundary, "text", text);
    Map<String,Object> params = new LinkedHashMap<>();
    params.put("stream", true);
    params.put("chunked", true);
    params.put("output_format", "pcm_s16le");
    params.put("max_new_tokens", f.maxTokens());
    params.put("temperature", f.temperature());
    params.put("top_p", f.topP());
    params.put("top_k", f.topK());
    params.put("n_threads", f.cpuThreads());
    params.put("low_latency", s.common().lowLatency());
    params.put("stream_decode_stride_frames", s.common().decodeStrideFrames());
    if (s.common().holdbackFrames() >= 0) params.put("stream_holdback_frames", s.common().holdbackFrames());
    params.put("stream_start_buffer_ms", 0); // Java player's startup buffer owns timing, avoid double buffering.
    params.put("segment_sentences", s.common().segmentSentences());
    params.put("sentence_pause_ms", s.common().sentencePauseMs());
    field(b, boundary, "params", Json.write(params));
    if (!f.voice().isBlank()) field(b, boundary, "voice", f.voice());
    if (!f.referenceAudio().isBlank()) {
      if (f.referenceText().isBlank()) throw new IllegalArgumentException("Для голосового референса нужен текст");
      Path ref = resolveFile(f.referenceAudio());
      if (Files.size(ref) > 40L * 1024 * 1024) throw new IOException("Референс голоса превышает 40 МБ");
      field(b, boundary, "reference_text", f.referenceText());
      b.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"reference\"; filename=\"" +
          ref.getFileName().toString().replace('"', '_') + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
      Files.copy(ref, b);
      b.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
    b.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
    URI uri = URI.create("http://" + f.host() + ":" + f.httpPort() + "/generate");
    long httpStartNs = System.nanoTime();
    System.out.println("[FishSpeech/HTTP] POST /generate textChars=" + text.length()
        + " format=pcm_s16le");
    TtsTrace.log("HTTP_REQUEST", "method=POST endpoint=" + uri + " chars=" + text.length()
        + " format=pcm_s16le stream=true chunked=true");
    HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .header("Accept", "audio/L16, application/octet-stream")
        .POST(HttpRequest.BodyPublishers.ofByteArray(b.toByteArray())).build();
    HttpResponse<InputStream> response = null;
    for (int attempt = 0; attempt < 6; attempt++) {
      response = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 503) break;
      TtsTrace.log("HTTP_BUSY", "attempt=" + (attempt + 1) + "/6 status=503");
      try (InputStream busy = response.body()) { busy.readNBytes(256); }
      if (attempt == 5) throw new IOException("Fish Speech HTTP 503: сервер занят слишком долго");
      Thread.sleep(200);
    }
    InputStream stream = Objects.requireNonNull(response).body();
    System.out.println("[FishSpeech/HTTP] status=" + response.statusCode()
        + " contentType=" + response.headers().firstValue("Content-Type").orElse("unknown"));
    TtsTrace.log("HTTP_HEADERS", "status=" + response.statusCode()
        + " contentType=" + response.headers().firstValue("Content-Type").orElse("unknown")
        + " sampleRate=" + headerInt(response, "X-Audio-Sample-Rate", 44100)
        + " channels=" + headerInt(response, "X-Audio-Channels", 1)
        + " responseHeadersMs=" + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - httpStartNs));
    if (response.statusCode() != 200) {
      String error;
      try (stream) { error = new String(stream.readNBytes(2048), StandardCharsets.UTF_8); }
      TtsTrace.log("HTTP_ERROR", "status=" + response.statusCode() + " bodyBytes=" + error.length());
      throw new IOException("Fish Speech HTTP " + response.statusCode() + ": " + error);
    }
    activeStream = stream;
    return new Response(response, new FilterInputStream(stream) {
      @Override public void close() throws IOException {
        super.close();
        if (activeStream == stream) activeStream = null;
      }
    });
  }
  private static void field(OutputStream b, String boundary, String key, String value) throws IOException {
    b.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"" + key +
        "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
  }
  private static byte[] wav(byte[] pcm, int rate, int channels) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(pcm.length + 44);
    java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    h.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length);
    h.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1);
    h.putShort((short)channels).putInt(rate).putInt(rate * channels * 2);
    h.putShort((short)(channels*2)).putShort((short)16);
    h.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length);
    out.write(h.array()); out.write(pcm); return out.toByteArray();
  }
}
