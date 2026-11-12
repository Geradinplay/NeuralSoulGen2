package com.neuralsoul.service.tts;

import com.neuralsoul.config.SpeechGenerationSettingsStore;
import com.neuralsoul.config.SpeechGenerationSettingsStore.Cosy;
import com.neuralsoul.config.SpeechGenerationSettingsStore.Settings;
import com.neuralsoul.service.api.AudioPlayer;
import com.neuralsoul.service.api.TtsService;
import com.neuralsoul.service.http.Json;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

/**
 * A persistent cosyvoice.cpp HTTP server process, never restarted for individual
 * speech chunks. Speaks OpenAI Speech API: POST /v1/audio/speech with streaming
 * WAV bytes (a static 44-byte RIFF header in the first chunk, then raw PCM16).
 */
public final class CosyVoiceTtsService implements TtsService {
  public record AllowedDevice(int index, String name, String vendor, long vramBytes,
                              boolean enabled, String uuid) {
    public AllowedDevice(int index, String name, String vendor, long vramBytes, boolean enabled) {
      this(index, name, vendor, vramBytes, enabled, "");
    }
  }
  private record Placement(String backend, String packFolder, AllowedDevice device,
                           int nativeIndex, String selector) {}
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

  public CosyVoiceTtsService(SpeechGenerationSettingsStore store, Supplier<List<AllowedDevice>> hardware) {
    this.store = Objects.requireNonNull(store);
    this.hardware = hardware == null ? List::of : hardware;
  }

  @Override public void start() throws Exception {
    Settings settings = store.load();
    SpeechGenerationSettingsStore.validate(settings);
    TtsTrace.log("SERVICE_START_REQUEST", "enabled=" + settings.common().enabled()
        + " backend=" + settings.cosy().backend()
        + " endpoint=" + settings.cosy().host() + ":" + settings.cosy().httpPort());
    if (!settings.common().enabled()) throw new IllegalStateException("CosyVoice отключён в настройках генерации речи");
    if (settings.cosy().voicePromptPath().isBlank())
      throw new IllegalStateException("CosyVoice: не выбран prompt_speech GGUF. "
          + "Создай его через cosyvoice-cli --frontend-only и укажи путь в настройках Голоса.");
    synchronized (lock) {
      String desired = signature(settings);
      if (ready && desired.equals(fingerprint) && (ownedProcess == null || ownedProcess.isAlive())) {
        TtsTrace.log("SERVICE_REUSE", ownedProcess == null ? "externalServer=true" : "pid=" + ownedProcess.pid());
        return;
      }
      stopInternal();
      Cosy f = settings.cosy();
      if (tcpReady(f.host(), f.httpPort())) {
        // A foreign server could be using a different model, GPU, or backend.
        // Do not report the selected GUI device as active when we do not own it.
        String message = "CosyVoice: порт " + f.host() + ":" + f.httpPort()
            + " уже занят внешним сервером. Его GPU неизвестна. Останови чужой cosyvoice-server.exe "
            + "или выбери другой HTTP-порт; NeuralSoul не будет подключаться к неизвестной GPU.";
        System.err.println("[CosyVoice/GPU] " + message);
        TtsTrace.log("SERVICE_PORT_CONFLICT", "port=" + f.httpPort()
            + " refusingUnverifiedExternalServer=true");
        throw new IllegalStateException(message);
      }
      if (!isLocal(f.host())) throw new IllegalStateException("Автозапуск CosyVoice разрешён только на localhost");
      Placement placement = choosePlacement(f);
      String backend = placement.backend();
      Path model = resolveFile(f.modelPath());
      Path voicePrompt = resolveFile(f.voicePromptPath());
      // CUDA packs: the user's choice goes first; if it dies at startup the other
      // CUDA pack is tried silently before reporting a failure.
      List<String> packFolders = resolvePackFolders(backend, f.cudaPack());
      Throwable lastFailure = null;
      for (int attempt = 0; attempt < packFolders.size(); attempt++) {
        String pack = packFolders.get(attempt);
        Path exe = root.resolve(f.executablePath().replace('\\', '/')
            .replace("{backend}", pack)).normalize();
        if (!exe.startsWith(root) || !Files.isRegularFile(exe)) {
          lastFailure = new FileNotFoundException("CosyVoice: сборка " + pack
              + " не найдена: " + exe);
          continue;
        }
        try {
          launch(exe, model, voicePrompt, f, placement, backend, pack);
          ready = true;
          fingerprint = desired;
          activeSettings = settings;
          return;
        } catch (ProcessDiedException e) {
          lastFailure = e.getCause() != null ? e.getCause() : e;
          System.out.println("[CosyVoice] pack " + pack + " failed: " + lastFailure.getMessage()
              + (attempt + 1 < packFolders.size() ? "; trying next CUDA pack" : ""));
          TtsTrace.log("SERVICE_PACK_RETRY", "pack=" + pack + " error=" + lastFailure.getMessage());
          stopInternal();
        }
      }
      if (lastFailure instanceof IOException io) throw io;
      throw new IOException("CosyVoice не удалось запустить: " + lastFailure, lastFailure);
    }
  }

  private static final class ProcessDiedException extends RuntimeException {
    ProcessDiedException(Throwable cause) { super(cause == null ? null : cause.getMessage(), cause); }
  }

  /** Launch one pack build and wait until the HTTP port opens or the process dies/times out. */
  private void launch(Path exe, Path model, Path voicePrompt, Cosy f, Placement placement,
                      String backend, String pack) throws Exception {
    List<String> cmd = new ArrayList<>(List.of(exe.toString(), "-m", model.toString(),
        "--api", "--stream", "--served-model-name", SpeechGenerationSettingsStore.SERVED_MODEL_NAME,
        "--voice-prompt", f.voice() + "=" + voicePrompt.toString(),
        "-H", f.host(), "-P", Integer.toString(f.httpPort())));
    int index = placement.nativeIndex();
    if ("CPU".equals(backend)) {
      cmd.add("--cpu");
      cmd.add("-j"); cmd.add(Integer.toString(f.cpuThreads()));
    } else if ("CUDA".equals(backend)) {
      cmd.add("--cuda");
    } else {
      cmd.add("--backend"); cmd.add("vulkan" + index);
    }
    Path log = root.resolve("logs/tts-cosyvoice.log");
    Files.createDirectories(log.getParent());
    long nativeLogOffset = Files.exists(log) ? Files.size(log) : 0L;
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(exe.getParent().toFile());
    if ("CUDA".equals(backend)) {
      // NVIDIA documents UUID-based CUDA_VISIBLE_DEVICES as the stable way to
      // select one physical GPU. The isolated process always sees it as CUDA 0.
      pb.environment().put("CUDA_DEVICE_ORDER", "PCI_BUS_ID");
      pb.environment().put("CUDA_VISIBLE_DEVICES", placement.selector());
      System.out.println("[CosyVoice/GPU] CUDA isolation: " + placement.device().name()
          + " UUID=" + placement.selector() + " -> CUDA 0");
      TtsTrace.log("GPU_ISOLATION", "physical=" + placement.device().name()
          + " uuid=" + placement.selector() + " visibleDevice=CUDA0");
    } else if ("VULKAN".equals(backend)) {
      System.out.println("[CosyVoice/GPU] Vulkan physical device " + index
          + " name=" + placement.device().name() + " -> --backend vulkan" + index);
      TtsTrace.log("GPU_MAPPING", "physical=" + placement.device().name()
          + " vulkanIndex=" + index);
    }
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
    ownedProcess = pb.start();
    System.out.println("[CosyVoice] starting pack=" + pack + " backend=" + backend
        + " device=" + index
        + " selectedGPU=" + (placement.device() == null ? "CPU" : placement.device().name())
        + ", PID=" + ownedProcess.pid() + ", log=" + log);
    System.out.println("[CosyVoice] command: " + String.join(" ", cmd));
    TtsTrace.log("SERVICE_NATIVE_LAUNCHED", "pid=" + ownedProcess.pid()
        + " backend=" + backend + " pack=" + pack + " nativeIndex=" + index
        + " selectedGpu=" + (placement.device() == null ? "CPU" : placement.device().name())
        + " model=" + model.getFileName() + " voicePrompt=" + voicePrompt.getFileName()
        + " exe=" + exe + " nativeLog=" + log);
    long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
    long graceNanos = Duration.ofSeconds(10).toNanos();
    long startedNanos = System.nanoTime();
    while (System.nanoTime() < deadline) {
      if (Thread.currentThread().isInterrupted()) {
        stopInternal();
        throw new InterruptedException("TTS startup interrupted");
      }
      Process process = ownedProcess;
      if (process == null || !process.isAlive()) {
        stopInternal();
        // A quick death usually means the CUDA runtime does not match the driver.
        if (System.nanoTime() - startedNanos < graceNanos) {
          System.out.println("[CosyVoice/GPU] native log: " + tail(log));
          throw new ProcessDiedException(
              new IOException("CosyVoice завершился при запуске; смотри logs/tts-cosyvoice.log"));
        }
        throw new IOException("CosyVoice завершился при запуске; смотри logs/tts-cosyvoice.log");
      }
      if (tcpReady(f.host(), f.httpPort())) {
        // A listening TCP socket does NOT establish GPU offload or actual model residency.
        // Show the backend's own layer allocation report instead of claiming VRAM was used.
        printNativeOffloadDiagnostics(log, nativeLogOffset, backend);
        System.out.println("[CosyVoice] HTTP port open; verify GPU layer allocation in " + log);
        TtsTrace.log("SERVICE_PORT_READY", "pid=" + process.pid()
            + " nativeGpuOffloadNotProven=true log=" + log);
        return;
      }
      Thread.sleep(250);
    }
    stopInternal();
    throw new IOException("CosyVoice не открыл порт за 120 секунд; смотри logs/tts-cosyvoice.log");
  }

  /** The chosen CUDA pack first, then the other one as a silent fallback; other backends have exactly one folder. */
  private static List<String> resolvePackFolders(String backend, String cudaPack) {
    if ("CUDA".equals(backend)) {
      if ("cuda-12.4".equals(cudaPack)) return List.of("cuda-12.4", "cuda-13.5");
      return List.of("cuda-13.5", "cuda-12.4");
    }
    if ("VULKAN".equals(backend)) return List.of("vulkan");
    if ("CPU".equals(backend)) return List.of("cpu");
    throw new IllegalArgumentException("Unknown backend: " + backend);
  }

  private static String tail(Path log) {
    try {
      byte[] bytes = Files.readAllBytes(log);
      int from = (int) Math.max(0, bytes.length - 8192);
      return new String(bytes, from, bytes.length - from, StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      return "(unable to read log: " + e.getMessage() + ")";
    }
  }

  /** Summarize model-placement lines from THIS start only, leaving the native log intact.
   * This is diagnostic, not a guarantee: missing lines must never be described as GPU load. */
  private static void printNativeOffloadDiagnostics(Path log, long startOffset, String backend) {
    if ("CPU".equals(backend)) {
      System.out.println("[CosyVoice/GPU] CPU backend selected; no video-memory allocation expected");
      return;
    }
    try (java.nio.channels.FileChannel file = java.nio.channels.FileChannel.open(log, StandardOpenOption.READ)) {
      long end = file.size();
      long begin = Math.max(startOffset, end - 262144L);
      if (begin >= end) {
        System.out.println("[CosyVoice/GPU] native log still empty; GPU offload unverified");
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
          if (matches++ < 12) System.out.println("[CosyVoice/GPU/native] " + line.strip());
        }
      }
      if (matches == 0)
        System.out.println("[CosyVoice/GPU] no GPU-layer diagnostics found in native log; offload not verified");
    } catch (IOException e) {
      System.out.println("[CosyVoice/GPU] unable to read native log: " + e);
    }
  }

  private static boolean isLocal(String host) {
    return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
  }

  private static String signature(Settings s) {
    Cosy f = s.cosy();
    return f.executablePath()+"|"+f.modelPath()+"|"+f.voice()+"|"+f.voicePromptPath()+"|"
        +f.host()+":"+f.httpPort()+"|"+f.backend()+"|"+f.deviceIndex();
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
      throw new FileNotFoundException("CosyVoice: отсутствует файл " + relative + " (каталог приложения: " + root + ")");
    return p;
  }
  private List<AllowedDevice> eligible(String backend) {
    return hardware.get().stream().filter(d -> d.index() >= 0)
        .filter(d -> !"CUDA".equals(backend) || d.vendor().equalsIgnoreCase("NVIDIA")
            || d.name().toUpperCase(Locale.ROOT).contains("NVIDIA"))
        .sorted(Comparator.comparingInt(AllowedDevice::index)).toList();
  }
  /** Returns the physical GPU selected in GUI, not an assumed native API ordinal. */
  private Placement choosePlacement(Cosy f) {
    String backend = chooseBackend(f);
    if ("CPU".equals(backend)) {
      if (hardware.get().stream().noneMatch(d -> d.index() == -1 && d.enabled()))
        throw new IllegalStateException("CPU запрещён в настройках устройств");
      return new Placement("CPU", "cpu", null, 0, "");
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
      System.out.println("[CosyVoice/GPU] GUI GPU " + chosen.index() + " = "
          + chosen.name() + " UUID=" + uuid + " -> isolated CUDA 0");
      return new Placement(backend, "cuda", chosen, 0, uuid);
    }
    // The Vulkan physical-device order is unrelated to Windows or CUDA indices.
    List<VulkanDeviceSelector.Device> vulkan = VulkanDeviceSelector.enumerate();
    int vulkanIndex = VulkanDeviceSelector.selectByName(chosen.name(), vulkan);
    System.out.println("[CosyVoice/GPU] Vulkan physical devices: " + vulkan);
    System.out.println("[CosyVoice/GPU] GUI GPU " + chosen.index() + " = " + chosen.name()
        + " -> Vulkan " + vulkanIndex);
    return new Placement(backend, "vulkan", chosen, vulkanIndex, "");
  }

  private String chooseBackend(Cosy f) {
    if (!"AUTO".equals(f.backend())) return f.backend();
    // AUTO placement: prefer CUDA on an enabled NVIDIA GPU, then any enabled GPU via
    // Vulkan, and only then CPU (which still requires the CPU device to be allowed).
    if (eligible("CUDA").stream().anyMatch(AllowedDevice::enabled)) return "CUDA";
    if (eligible("VULKAN").stream().anyMatch(AllowedDevice::enabled)) return "VULKAN";
    boolean cpuAllowed = hardware.get().stream().anyMatch(d -> d.index() == -1 && d.enabled());
    if (!cpuAllowed) throw new IllegalStateException("Нет разрешённого CPU/GPU для CosyVoice");
    return "CPU";
  }

  @Override public boolean isRunning() {
    if (!ready || (ownedProcess != null && !ownedProcess.isAlive())) return false;
    try {
      Settings s = store.load();
      if (!s.common().enabled() || !signature(s).equals(fingerprint)) return false;
      // Externally owned servers can disappear; check readiness without altering their lifecycle.
      if (ownedProcess == null && !tcpReady(s.cosy().host(), s.cosy().httpPort())) {
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
        + " running=" + isRunning() + " backend=" + s.cosy().backend());
    try (Response stream = request(text, s, true)) {
      PcmFormat format = readWavHeader(stream.body());
      TtsTrace.log("TTS_AUDIO_FORMAT", "sampleRate=" + format.sampleRate()
          + " channels=" + format.channels() + " bits=" + format.bitsPerSample());
      player.start();
      player.playPcm(stream.body(), format.sampleRate(), format.channels(), s.common().playbackStartBufferMs());
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
    try (Response response = request(text, s, false)) {
      byte[] wav = response.body().readAllBytes();
      if (wav.length < 44 || !"RIFF".equals(new String(wav, 0, 4, StandardCharsets.US_ASCII)))
        throw new IOException("CosyVoice вернул не WAV (" + wav.length + " байт)");
      return wav;
    }
  }
  private Settings requireReady() throws IOException {
    if (!isRunning()) throw new IllegalStateException("CosyVoice не подключён");
    // Sampling, voice and output buffering are hot-reloadable; no model restart necessary.
    return store.load();
  }
  private record Response(HttpResponse<InputStream> response, InputStream body) implements AutoCloseable {
    @Override public void close() throws IOException { body.close(); }
  }

  private record PcmFormat(int sampleRate, int channels, int bitsPerSample) {}

  /**
   * Consume the static RIFF header that arrives in the first HTTP chunk and
   * return the audio format. The remaining stream is raw PCM16 payload.
   */
  private static PcmFormat readWavHeader(InputStream in) throws IOException {
    byte[] prefix = in.readNBytes(12);
    if (prefix.length < 12 || !"RIFF".equals(new String(prefix, 0, 4, StandardCharsets.US_ASCII))
        || !"WAVE".equals(new String(prefix, 8, 4, StandardCharsets.US_ASCII)))
      throw new IOException("CosyVoice: ожидается поток WAV, получено " + describe(prefix));
    int sampleRate = 24000, channels = 1, bits = 16;
    boolean fmtSeen = false;
    boolean dataSeen = false;
    while (!dataSeen) {
      byte[] id = in.readNBytes(8);
      if (id.length < 8) throw new IOException("CosyVoice: обрывок WAV-заголовка");
      String chunkId = new String(id, 0, 4, StandardCharsets.US_ASCII);
      long size = ByteBuffer.wrap(id, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
      if ("fmt ".equals(chunkId)) {
        byte[] fmt = in.readNBytes((int) Math.min(size, 16));
        if (fmt.length < 16) throw new IOException("CosyVoice: неполный fmt-чанк");
        ByteBuffer b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN);
        channels = b.getShort(2);
        sampleRate = b.getInt(4);
        bits = b.getShort(14);
        fmtSeen = true;
        long skipped = size - fmt.length;
        for (long i = 0; i < skipped; i++) in.read();
      } else if ("data".equals(chunkId)) {
        // data size may be a placeholder (0 or max) during streaming; payload follows now.
        dataSeen = true;
      } else {
        for (long i = 0; i < size; i++) in.read();
      }
    }
    if (!fmtSeen) throw new IOException("CosyVoice: в WAV-заголовке нет fmt-чанка");
    return new PcmFormat(sampleRate, channels, bits);
  }

  private static String describe(byte[] prefix) {
    return "первые " + prefix.length + " байт: " + Base64.getEncoder().encodeToString(prefix);
  }

  private Response request(String text, Settings s, boolean stream) throws Exception {
    if (text == null || text.isBlank()) throw new IllegalArgumentException("Нет текста для TTS");
    Cosy f = s.cosy();
    Map<String,Object> body = new LinkedHashMap<>();
    body.put("model", SpeechGenerationSettingsStore.SERVED_MODEL_NAME);
    body.put("input", text);
    body.put("voice", f.voice().isBlank() ? SpeechGenerationSettingsStore.DEFAULT_VOICE : f.voice());
    body.put("response_format", "wav");
    body.put("stream", stream);
    if (stream) {
      if (f.chunkTokens() > 0) body.put("chunk_tokens", f.chunkTokens());
      body.put("temperature", f.temperature());
      body.put("top_p", f.topP());
      if (f.topK() > 0) body.put("top_k", f.topK());
    }
    body.put("speed", f.speed());
    byte[] payload = Json.write(body).getBytes(StandardCharsets.UTF_8);
    URI uri = URI.create("http://" + f.host() + ":" + f.httpPort() + "/v1/audio/speech");
    long httpStartNs = System.nanoTime();
    System.out.println("[CosyVoice/HTTP] POST /v1/audio/speech textChars=" + text.length()
        + " stream=" + stream);
    TtsTrace.log("HTTP_REQUEST", "method=POST endpoint=" + uri + " chars=" + text.length()
        + " format=wav stream=" + stream);
    HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10))
        .header("Content-Type", "application/json")
        .header("Accept", "audio/wav")
        .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
    HttpResponse<InputStream> response = null;
    for (int attempt = 0; attempt < 6; attempt++) {
      response = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 503) break;
      TtsTrace.log("HTTP_BUSY", "attempt=" + (attempt + 1) + "/6 status=503");
      try (InputStream busy = response.body()) { busy.readNBytes(256); }
      if (attempt == 5) throw new IOException("CosyVoice HTTP 503: сервер занят слишком долго");
      Thread.sleep(200);
    }
    InputStream raw = Objects.requireNonNull(response).body();
    System.out.println("[CosyVoice/HTTP] status=" + response.statusCode()
        + " contentType=" + response.headers().firstValue("Content-Type").orElse("unknown"));
    TtsTrace.log("HTTP_HEADERS", "status=" + response.statusCode()
        + " contentType=" + response.headers().firstValue("Content-Type").orElse("unknown")
        + " responseHeadersMs=" + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - httpStartNs));
    if (response.statusCode() != 200) {
      String error;
      try (raw) { error = new String(raw.readNBytes(2048), StandardCharsets.UTF_8); }
      TtsTrace.log("HTTP_ERROR", "status=" + response.statusCode() + " bodyBytes=" + error.length());
      throw new IOException("CosyVoice HTTP " + response.statusCode() + ": " + error);
    }
    activeStream = raw;
    return new Response(response, new FilterInputStream(raw) {
      @Override public void close() throws IOException {
        super.close();
        if (activeStream == raw) activeStream = null;
      }
    });
  }
}
