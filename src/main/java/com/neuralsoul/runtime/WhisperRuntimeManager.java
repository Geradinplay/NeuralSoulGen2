package com.neuralsoul.runtime;

import com.neuralsoul.config.SttConfig;
import com.neuralsoul.hardware.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Owns the bundled persistent whisper.cpp server so the model is loaded once and reused. */
public final class WhisperRuntimeManager implements AutoCloseable {
  private Process process;

  /** Actual operating system process owned by this runtime; -1 if absent. */
  public synchronized long pid() { return process != null && process.isAlive() ? process.pid() : -1L; }
  private SttConfig active;

  public synchronized boolean isRunning() { return process != null && process.isAlive(); }
  public synchronized SttConfig activeConfig() { return active; }

  public synchronized void start(SttConfig cfg) throws Exception {
    if (cfg == null || !cfg.runtimeEnabled()) return;
    if (isRunning()) return;

    Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    Path executable = resolve(root, cfg.executablePath());
    Path model = resolve(root, cfg.modelPath());
    if (!Files.isRegularFile(executable))
      throw new FileNotFoundException("Не найден Whisper runtime: " + executable);
    if (!Files.isRegularFile(model))
      throw new FileNotFoundException("Не найдена Whisper модель: " + model);

    URI base = URI.create(cfg.endpoint().baseUrl());
    String host = base.getHost() == null ? "127.0.0.1" : base.getHost();
    int port = base.getPort() > 0 ? base.getPort() : 8085;

    GpuInfo selectedGpu = selectedGpu(cfg.runtimeDevice());
    boolean useGpu = selectedGpu != null && !"CPU".equalsIgnoreCase(cfg.runtimeDevice());
    int backendIndex = useGpu ? cudaBackendIndex(selectedGpu) : -1;

    // HardwareLocator already detected the CPU at application startup. Clamp stale settings when
    // the same config is moved to a machine with fewer logical CPU threads.
    int logicalCpuThreads = detectedLogicalCpuThreads();
    int effectiveThreads = Math.max(1, Math.min(cfg.threads(), logicalCpuThreads));
    int effectiveProcessors = Math.max(1, Math.min(cfg.processors(),
        Math.max(1, logicalCpuThreads / effectiveThreads)));

    List<String> cmd = new ArrayList<>();
    Collections.addAll(cmd, executable.toString(),
        "--model", model.toString(),
        "--host", host,
        "--port", Integer.toString(port),
        "--inference-path", cfg.endpoint().path(),
        "--threads", Integer.toString(effectiveThreads),
        "--processors", Integer.toString(effectiveProcessors),
        "--max-context", Integer.toString(cfg.maxContext()),
        "--max-len", Integer.toString(cfg.maxLen()),
        "--best-of", Integer.toString(cfg.bestOf()),
        "--beam-size", Integer.toString(cfg.beamSize()),
        "--audio-ctx", Integer.toString(cfg.audioContext()),
        "--word-thold", Double.toString(cfg.wordThreshold()),
        "--entropy-thold", Double.toString(cfg.entropyThreshold()),
        "--logprob-thold", Double.toString(cfg.logProbThreshold()),
        "--no-speech-thold", Double.toString(cfg.noSpeechThreshold()),
        "--language", cfg.language().isBlank() ? "auto" : cfg.language());
    if (cfg.splitOnWord()) cmd.add("--split-on-word");
    if (cfg.translate()) cmd.add("--translate");
    if (cfg.noFallback()) cmd.add("--no-fallback");
    if (cfg.noTimestamps()) cmd.add("--no-timestamps");
    if (cfg.suppressNonSpeech()) cmd.add("--suppress-nst");
    if (!useGpu) {
      cmd.add("--no-gpu");
    } else {
      // CUDA_VISIBLE_DEVICES remaps an isolated physical adapter to device 0 inside whisper.cpp.
      Collections.addAll(cmd, "--device",
          Integer.toString("По устройству".equals(cfg.isolation()) ? 0 : Math.max(0, backendIndex)));
      cmd.add(cfg.flashAttention() ? "--flash-attn" : "--no-flash-attn");
    }
    if (cfg.vad()) {
      cmd.add("--vad");
      if (!cfg.vadModelPath().isBlank()) {
        Path vadModel = resolve(root, cfg.vadModelPath());
        if (!Files.isRegularFile(vadModel)) throw new FileNotFoundException("Не найдена VAD модель: " + vadModel);
        Collections.addAll(cmd, "--vad-model", vadModel.toString());
      }
      Collections.addAll(cmd,
          "--vad-threshold", Double.toString(cfg.vadThreshold()),
          "--vad-min-speech-duration-ms", Integer.toString(cfg.vadMinSpeechMs()),
          "--vad-min-silence-duration-ms", Integer.toString(cfg.vadMinSilenceMs()),
          "--vad-max-speech-duration-s", Double.toString(cfg.vadMaxSpeechSeconds()),
          "--vad-speech-pad-ms", Integer.toString(cfg.vadSpeechPadMs()),
          "--vad-samples-overlap", Double.toString(cfg.vadOverlapSeconds()));
    }

    System.out.println("[Whisper] ===== START MODEL =====");
    System.out.println("[Whisper] Model: " + model);
    System.out.println("[Whisper] Device: " + (useGpu ? cfg.runtimeDevice() + " · " + selectedGpu.name() : "CPU"));
    System.out.println("[Whisper] Isolation: " + cfg.isolation());
    System.out.println("[Whisper] CPU logical threads detected: " + logicalCpuThreads);
    System.out.println("[Whisper] Threads/processors: " + effectiveThreads + "/" + effectiveProcessors
        + ((effectiveThreads != cfg.threads() || effectiveProcessors != cfg.processors())
            ? " (clamped from " + cfg.threads() + "/" + cfg.processors() + ")" : ""));
    System.out.println("[Whisper] Starting: " + String.join(" ", cmd));
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.environment().put("CUDA_DEVICE_ORDER", "PCI_BUS_ID");
    if (useGpu && "По устройству".equals(cfg.isolation())) {
      String selector = selectedGpu.id();
      if (selector == null || selector.isBlank()) selector = Integer.toString(Math.max(0, backendIndex));
      pb.environment().put("CUDA_VISIBLE_DEVICES", selector);
      System.out.println("[Whisper] Device isolation: " + selectedGpu.name() + " | CUDA_VISIBLE_DEVICES=" + selector);
    }
    pb.directory(executable.getParent().toFile());
    pb.redirectErrorStream(true);
    process = pb.start();
    active = cfg;
    Process current = process;
    Thread drainer = new Thread(() -> {
      try (BufferedReader r = new BufferedReader(new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = r.readLine()) != null) System.out.println("[whisper] " + line);
      } catch (IOException ignored) {}
    }, "whisper-runtime-output");
    drainer.setDaemon(true);
    drainer.start();
    waitReady(current, cfg.endpoint().baseUrl());
    System.out.println("[Whisper] Ready: " + cfg.endpoint().baseUrl() + cfg.endpoint().path());
  }

  public synchronized void restart(SttConfig cfg) throws Exception {
    stop();
    start(cfg);
  }

  private int detectedLogicalCpuThreads() {
    try {
      CpuInfo cpu = HardwareLocator.getInstance().cpu();
      return cpu == null ? 1 : Math.max(1, cpu.logicalCores());
    } catch (Exception ignored) {
      return 1;
    }
  }

  private GpuInfo selectedGpu(String deviceId) {
    if (deviceId == null || !deviceId.startsWith("GPU")) return null;
    int index = guiGpuIndex(deviceId);
    HardwareInfo info = HardwareLocator.getInstance().info();
    if (info == null) return null;
    return info.gpus().stream().filter(g -> g.index() == index).findFirst().orElse(null);
  }

  /** whisper.cpp CUDA builds enumerate NVIDIA adapters in PCI order. */
  private int cudaBackendIndex(GpuInfo selected) {
    HardwareInfo info = HardwareLocator.getInstance().info();
    if (info == null) return selected.index();
    List<GpuInfo> nvidia = info.gpus().stream()
        .filter(g -> "NVIDIA".equalsIgnoreCase(g.vendor()))
        .sorted(Comparator.comparing(g -> normalizeBusId(g.pciBusId())))
        .toList();
    for (int i = 0; i < nvidia.size(); i++)
      if (Objects.equals(nvidia.get(i).id(), selected.id())) return i;
    return selected.index();
  }

  private static int guiGpuIndex(String id) {
    try { return Integer.parseInt(id.replace("GPU", "").trim()); }
    catch (Exception e) { return -1; }
  }

  private static String normalizeBusId(String busId) {
    return busId == null ? "" : busId.trim().toLowerCase(Locale.ROOT);
  }

  private void waitReady(Process current, String baseUrl) throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    URI uri = URI.create(baseUrl + "/");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
    Exception last = null;
    while (System.nanoTime() < deadline) {
      if (!current.isAlive()) {
        int code;
        try { code = current.exitValue(); } catch (Exception ignored) { code = -1; }
        throw new IllegalStateException("whisper-server завершился при загрузке модели (exit=" + code + ")");
      }
      try {
        HttpResponse<Void> response = client.send(
            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(),
            HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() >= 200 && response.statusCode() < 500) return;
      } catch (Exception e) { last = e; }
      Thread.sleep(250);
    }
    throw new java.util.concurrent.TimeoutException("Whisper не открыл API: " + baseUrl
        + (last == null ? "" : " · " + last.getClass().getSimpleName()));
  }

  private static Path resolve(Path root, String value) {
    Path p = Path.of(value);
    return (p.isAbsolute() ? p : root.resolve(p)).toAbsolutePath().normalize();
  }

  public synchronized void stop() {
    Process p = process;
    process = null;
    active = null;
    if (p == null) return;
    p.destroy();
    try {
      if (!p.waitFor(4, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        p.waitFor(2, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      p.destroyForcibly();
    }
    System.out.println("[Whisper] stopped");
  }

  @Override public void close() { stop(); }
}
