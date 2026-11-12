package com.neuralsoul.runtime;

import com.neuralsoul.hardware.*;
import com.neuralsoul.model.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Dedicated llama.cpp runtime for the WEB_SEARCH role. */
public final class WebSearchRuntimeManager implements AutoCloseable {
  public static final int PORT = 8082;
  private Process process;

  /** Actual operating system process owned by this runtime; -1 if absent. */
  public synchronized long pid() { return process != null && process.isAlive() ? process.pid() : -1L; }
  private Thread logDrainer;
  private ModelLaunchConfig active;

  public synchronized boolean isRunning() { return process != null && process.isAlive(); }
  public synchronized ModelLaunchConfig active() { return active; }

  public synchronized ModelLaunchConfig snapshot(AppState state) {
    return snapshot(state, AutomaticModelPlacement.plan(state));
  }

  public synchronized ModelLaunchConfig snapshot(AppState state, AutomaticModelPlacement.Plan placementPlan) {
    Profile profile = state.activeProfile.get() == null ? Profile.NORMAL : state.activeProfile.get();
    List<ModelAssignment> matches = new ArrayList<>();
    for (ModelAssignment model : state.models) {
      if (!model.webSearchOnly) continue;
      ModelAssignment.ProfileConfig cfg = state.modelProfileConfig(profile, model);
      if (cfg.enabled() && cfg.roles().contains(ModelRole.WEB_SEARCH)) matches.add(model);
    }
    if (matches.isEmpty()) return null;
    if (matches.size() > 1)
      throw new IllegalStateException("Для профиля " + profile.label() + " выбрано несколько Web Search моделей");

    ModelAssignment model = matches.get(0);
    ModelAssignment.ProfileConfig cfg = state.modelProfileConfig(profile, model);
    String device = state.automatic.get()
        ? placementPlan.device(AutomaticModelPlacement.Purpose.WEB_SEARCH, model.fileName, cfg.device())
        : cfg.device();
    if (device == null || device.isBlank()) throw new IllegalStateException("Не выбрано устройство для Web Search");

    Path modelPath = Path.of(System.getProperty("user.dir"), "model").resolve(model.fileName).toAbsolutePath().normalize();
    Path loraRoot = modelPath.getParent().resolve("lora").normalize();
    List<LoraLaunchConfig> loras = cfg.loras().entrySet().stream()
        .filter(e -> e.getValue() != null && e.getValue().enabled())
        .map(e -> new LoraLaunchConfig(loraRoot.resolve(e.getKey()).normalize(), e.getValue().scale()))
        .filter(l -> l.path().startsWith(loraRoot) && Files.isRegularFile(l.path()))
        .toList();

    return new ModelLaunchConfig(model.name, modelPath, device, Set.copyOf(cfg.roles()),
        Math.max(1, (int)Math.round(state.threads.get())), Math.max(256, cfg.ctxSize()),
        Math.max(64, cfg.maxOutputTokens()),
        Math.max(1, cfg.parallel()), Math.max(32, cfg.batchSize()), Math.max(32, cfg.ubatchSize()),
        cfg.cacheTypeK(), cfg.cacheTypeV(), cfg.isolation(), Math.max(0, (int)Math.round(state.vram.get())),
        state.automatic.get(), state.allowSwap.get(), state.allowRamOffload.get(), loras);
  }

  public synchronized void start(ModelLaunchConfig cfg) throws Exception {
    if (cfg == null) return;
    if (isRunning()) return;
    Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    Path executable = root.resolve("runtime").resolve("llama").resolve("llama.exe");
    if (!Files.isRegularFile(executable)) throw new FileNotFoundException("Не найден runtime/llama/llama.exe: " + executable);
    if (!Files.isRegularFile(cfg.modelPath())) throw new FileNotFoundException("Не найдена Web Search GGUF: " + cfg.modelPath());

    List<String> command = new ArrayList<>();
    command.add(executable.toString()); command.add("serve"); command.add("-m"); command.add(cfg.modelPath().toString());
    Path workingDirectory = executable.getParent().toAbsolutePath().normalize();
    if (!cfg.loras().isEmpty()) {
      List<String> args = new ArrayList<>();
      for (LoraLaunchConfig l : cfg.loras()) args.add(workingDirectory.relativize(l.path().toAbsolutePath().normalize()) + ":" + l.scale());
      command.add("--lora-scaled"); command.add(String.join(",", args));
    }
    Collections.addAll(command, "--host", "127.0.0.1", "--port", Integer.toString(PORT), "-t", Integer.toString(cfg.threads()),
        "--ctx-size", Integer.toString(cfg.ctxSize()), "--parallel", Integer.toString(cfg.parallel()),
        "--batch-size", Integer.toString(cfg.batchSize()), "--ubatch-size", Integer.toString(cfg.ubatchSize()),
        "--cache-type-k", cfg.cacheTypeK(), "--cache-type-v", cfg.cacheTypeV(),
        "--reasoning-budget", "0");

    if (cfg.device().startsWith("GPU")) {
      command.add("-ngl"); command.add(cfg.automatic() && cfg.allowRamOffload() ? "auto" : "all");
      int index = cudaIndex(cfg.device());
      if (index >= 0) { command.add("--main-gpu"); command.add(isolateGpu(cfg) ? "0" : Integer.toString(index)); }
    } else { command.add("-ngl"); command.add("0"); }

    System.out.println("[WebSearch/Runtime] Starting: " + String.join(" ", command));
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.environment().put("CUDA_DEVICE_ORDER", "PCI_BUS_ID");
    if (isolateGpu(cfg) && cfg.device().startsWith("GPU")) {
      GpuInfo gpu = selectedGpu(cfg.device());
      if (gpu != null) {
        String selector = gpu.id() == null || gpu.id().isBlank() ? Integer.toString(cudaIndex(cfg.device())) : gpu.id();
        pb.environment().put("CUDA_VISIBLE_DEVICES", selector);
      }
    }
    pb.directory(workingDirectory.toFile()); pb.redirectErrorStream(true);
    process = pb.start(); active = cfg;
    Process current = process;
    logDrainer = new Thread(() -> {
      try (BufferedReader r = new BufferedReader(new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
        String line; while ((line = r.readLine()) != null) System.out.println("[web-search-llama] " + line);
      } catch (IOException ignored) {}
    }, "web-search-runtime-output");
    logDrainer.setDaemon(true); logDrainer.start();
    waitUntilReady(current);
    System.out.println("[WebSearch/Runtime] Ready on http://127.0.0.1:" + PORT);
  }

  private void waitUntilReady(Process current) throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    URI uri = URI.create("http://127.0.0.1:" + PORT + "/v1/models");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
    while (System.nanoTime() < deadline) {
      if (!current.isAlive()) throw new IllegalStateException("Web Search llama.exe завершился при загрузке модели");
      try {
        HttpResponse<Void> r = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() >= 200 && r.statusCode() < 500) return;
      } catch (Exception ignored) {}
      Thread.sleep(350);
    }
    throw new TimeoutException("Web Search llama-server не открыл API на порту " + PORT);
  }

  private boolean isolateGpu(ModelLaunchConfig cfg) {
    return cfg.automatic() || "По устройству".equals(cfg.isolation());
  }

  private String chooseAutomaticDevice(AppState state) {
    for (Device d : state.devices) if (d.enabled.get() && d.id.startsWith("GPU")) return d.id;
    return "CPU";
  }
  private int guiGpuIndex(String deviceId) {
    try { return Integer.parseInt(deviceId.substring(deviceId.indexOf(' ') + 1).trim()); }
    catch (Exception e) { return -1; }
  }
  private GpuInfo selectedGpu(String deviceId) {
    int idx = guiGpuIndex(deviceId); HardwareInfo info = HardwareLocator.getInstance().info();
    if (idx < 0 || info == null) return null;
    return info.gpus().stream().filter(g -> g.index() == idx).findFirst().orElse(null);
  }
  private int cudaIndex(String deviceId) {
    GpuInfo selected = selectedGpu(deviceId); if (selected == null) return guiGpuIndex(deviceId);
    HardwareInfo info = HardwareLocator.getInstance().info(); if (info == null) return guiGpuIndex(deviceId);
    List<GpuInfo> nvidia = info.gpus().stream().filter(g -> "NVIDIA".equalsIgnoreCase(g.vendor()))
        .sorted(Comparator.comparing(g -> g.pciBusId() == null ? "" : g.pciBusId().trim().toLowerCase(Locale.ROOT))).toList();
    for (int i=0;i<nvidia.size();i++) if (Objects.equals(nvidia.get(i).id(), selected.id())) return i;
    return guiGpuIndex(deviceId);
  }

  public synchronized void stop() {
    if (process == null) return;
    try { process.destroy(); if (!process.waitFor(4, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS); } }
    catch (Exception ignored) {} finally { process = null; active = null; }
  }
  @Override public void close() { stop(); }
}
