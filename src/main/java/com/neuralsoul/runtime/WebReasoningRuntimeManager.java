package com.neuralsoul.runtime;

import com.neuralsoul.config.*;
import com.neuralsoul.hardware.*;
import com.neuralsoul.model.*;
import com.neuralsoul.service.api.LlmService;
import com.neuralsoul.service.http.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Evidence-judge runtime used by Web Search.
 *
 * <p>MAIN_LLM mode only creates an isolated HTTP client for the already-running :8080 server.
 * A separately selected model gets its own llama.cpp process on :8083. The judge has no web
 * tools and receives a fresh prompt for every fetched-evidence decision.</p>
 */
public final class WebReasoningRuntimeManager implements AutoCloseable {
  public static final int PORT = 8083;

  public record ReasoningTarget(boolean mainLlm, String modelKey, String displayName,
                                ModelLaunchConfig config, int maxOutputTokens) {}

  private Process process;

  /** Actual operating system process owned by this runtime; -1 if absent. */
  public synchronized long pid() { return process != null && process.isAlive() ? process.pid() : -1L; }
  private Thread logDrainer;
  private OpenAiLlmService client;
  private ReasoningTarget active;

  public synchronized ReasoningTarget snapshot(AppState state) {
    return snapshot(state, AutomaticModelPlacement.plan(state));
  }

  public synchronized ReasoningTarget snapshot(AppState state, AutomaticModelPlacement.Plan placementPlan) {
    Profile profile = state.activeProfile.get() == null ? Profile.NORMAL : state.activeProfile.get();
    ModelAssignment webModel = null;
    for (ModelAssignment model : state.models) {
      if (!model.webSearchOnly) continue;
      ModelAssignment.ProfileConfig cfg = state.modelProfileConfig(profile, model);
      if (cfg.enabled() && cfg.roles().contains(ModelRole.WEB_SEARCH)) {
        if (webModel != null)
          throw new IllegalStateException("Для профиля " + profile.label() + " выбрано несколько Web Search моделей");
        webModel = model;
      }
    }
    if (webModel == null) return null;

    String selected = normalize(state.webReasoningModelValue(profile, webModel.fileName));
    if (selected.isBlank() || ProfileState.MAIN_LLM_REASONING.equals(selected))
      return mainTarget(state, profile);

    ModelAssignment reasoningModel = state.models.stream()
        .filter(m -> m.generalModel() && normalize(m.fileName).equals(selected))
        .findFirst().orElse(null);
    if (reasoningModel == null) {
      System.out.println("[WebReasoning] Selected model is missing; falling back to Main LLM: " + selected);
      return mainTarget(state, profile);
    }
    if (reasoningModel == webModel) {
      System.out.println("[WebReasoning] Web Search model cannot judge itself; falling back to Main LLM");
      return mainTarget(state, profile);
    }

    ModelAssignment.ProfileConfig cfg = state.modelProfileConfig(profile, reasoningModel);
    if (!cfg.enabled()) {
      System.out.println("[WebReasoning] Selected model is disabled; falling back to Main LLM: " + reasoningModel.name);
      return mainTarget(state, profile);
    }
    if (!cfg.roles().contains(ModelRole.REASONING)) {
      System.out.println("[WebReasoning] Selected model no longer has Reasoning role; falling back to Main LLM: "
          + reasoningModel.name);
      return mainTarget(state, profile);
    }
    if (cfg.roles().contains(ModelRole.MAIN_LLM)) return mainTarget(state, profile);

    String device = state.automatic.get()
        ? placementPlan.device(AutomaticModelPlacement.Purpose.WEB_REASONING, reasoningModel.fileName, cfg.device())
        : cfg.device();
    if (device == null || device.isBlank())
      throw new IllegalStateException("Не выбрано устройство для Web Reasoning: " + reasoningModel.name);

    Path modelPath = Path.of(System.getProperty("user.dir"), "model")
        .resolve(reasoningModel.fileName).toAbsolutePath().normalize();
    Path loraRoot = modelPath.getParent().resolve("lora").normalize();
    List<LoraLaunchConfig> loras = cfg.loras().entrySet().stream()
        .filter(e -> e.getValue() != null && e.getValue().enabled())
        .map(e -> new LoraLaunchConfig(loraRoot.resolve(e.getKey()).normalize(), e.getValue().scale()))
        .filter(l -> l.path().startsWith(loraRoot) && Files.isRegularFile(l.path()))
        .toList();

    ModelLaunchConfig launch = new ModelLaunchConfig(
        reasoningModel.name, modelPath, device, Set.copyOf(cfg.roles()),
        Math.max(1, (int)Math.round(state.threads.get())), Math.max(256, cfg.ctxSize()),
        Math.max(64, cfg.maxOutputTokens()),
        Math.max(1, cfg.parallel()), Math.max(32, cfg.batchSize()), Math.max(32, cfg.ubatchSize()),
        cfg.cacheTypeK(), cfg.cacheTypeV(), cfg.isolation(), Math.max(0, (int)Math.round(state.vram.get())),
        state.automatic.get(), state.allowSwap.get(), state.allowRamOffload.get(), loras);
    return new ReasoningTarget(false, normalize(reasoningModel.fileName), reasoningModel.name, launch,
        launch.maxOutputTokens());
  }

  private ReasoningTarget mainTarget(AppState state, Profile profile) {
    ModelAssignment main = state.models.stream()
        .filter(m -> m.generalModel())
        .filter(m -> {
          ModelAssignment.ProfileConfig cfg = state.modelProfileConfig(profile, m);
          return cfg.enabled() && cfg.roles().contains(ModelRole.MAIN_LLM);
        })
        .findFirst().orElseThrow(() -> new IllegalStateException("Не выбрана Main LLM для Web Reasoning"));
    ModelAssignment.ProfileConfig cfg = state.modelProfileConfig(profile, main);
    return new ReasoningTarget(true, normalize(main.fileName), "Main LLM — " + main.name, null,
        Math.max(64, cfg.maxOutputTokens()));
  }

  public synchronized void start(ReasoningTarget target) throws Exception {
    stop();
    if (target == null) return;
    active = target;
    if (target.mainLlm()) {
      client = createClient(8080, target.maxOutputTokens());
      client.start();
      System.out.println("[WebReasoning] Using already-running " + target.displayName() + " on :8080");
      return;
    }

    ModelLaunchConfig cfg = target.config();
    Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    Path executable = root.resolve("runtime").resolve("llama").resolve("llama.exe");
    if (!Files.isRegularFile(executable))
      throw new FileNotFoundException("Не найден runtime/llama/llama.exe: " + executable);
    if (!Files.isRegularFile(cfg.modelPath()))
      throw new FileNotFoundException("Не найдена Web Reasoning GGUF: " + cfg.modelPath());

    List<String> command = new ArrayList<>();
    Collections.addAll(command, executable.toString(), "serve", "-m", cfg.modelPath().toString());
    Path workingDirectory = executable.getParent().toAbsolutePath().normalize();
    if (!cfg.loras().isEmpty()) {
      List<String> args = new ArrayList<>();
      for (LoraLaunchConfig l : cfg.loras())
        args.add(workingDirectory.relativize(l.path().toAbsolutePath().normalize()) + ":" + l.scale());
      command.add("--lora-scaled");
      command.add(String.join(",", args));
    }
    Collections.addAll(command,
        "--host", "127.0.0.1", "--port", Integer.toString(PORT),
        "-t", Integer.toString(cfg.threads()),
        "--ctx-size", Integer.toString(cfg.ctxSize()),
        "--parallel", "1",
        "--batch-size", Integer.toString(cfg.batchSize()),
        "--ubatch-size", Integer.toString(cfg.ubatchSize()),
        "--cache-type-k", cfg.cacheTypeK(), "--cache-type-v", cfg.cacheTypeV());

    if (cfg.device().startsWith("GPU")) {
      command.add("-ngl"); command.add(cfg.automatic() && cfg.allowRamOffload() ? "auto" : "all");
      int index = cudaIndex(cfg.device());
      if (index >= 0) {
        command.add("--main-gpu");
        command.add(isolateGpu(cfg) ? "0" : Integer.toString(index));
      }
    } else {
      command.add("-ngl"); command.add("0");
    }

    System.out.println("[WebReasoning/Runtime] Starting " + target.displayName() + " on :" + PORT
        + " -> " + String.join(" ", command));
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.environment().put("CUDA_DEVICE_ORDER", "PCI_BUS_ID");
    if (isolateGpu(cfg) && cfg.device().startsWith("GPU")) {
      GpuInfo gpu = selectedGpu(cfg.device());
      if (gpu != null) {
        String selector = gpu.id() == null || gpu.id().isBlank()
            ? Integer.toString(cudaIndex(cfg.device())) : gpu.id();
        pb.environment().put("CUDA_VISIBLE_DEVICES", selector);
      }
    }
    pb.directory(workingDirectory.toFile());
    pb.redirectErrorStream(true);
    process = pb.start();
    Process current = process;
    logDrainer = new Thread(() -> {
      try (BufferedReader r = new BufferedReader(
          new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = r.readLine()) != null) System.out.println("[web-reasoning-llama] " + line);
      } catch (IOException ignored) {}
    }, "web-reasoning-runtime-output");
    logDrainer.setDaemon(true);
    logDrainer.start();
    waitUntilReady(current, PORT, target.displayName());
    client = createClient(PORT, target.maxOutputTokens());
    client.start();
    System.out.println("[WebReasoning/Runtime] Ready " + target.displayName()
        + " on http://127.0.0.1:" + PORT);
  }

  private OpenAiLlmService createClient(int port, int maxOutputTokens) {
    EndpointConfig ep = new EndpointConfig("http://127.0.0.1:" + port,
        "/v1/chat/completions", "/v1/models", "", 90);
    LlmConfig cfg = new LlmConfig(ep, "", "", 0.10, Math.max(64, maxOutputTokens));
    return new OpenAiLlmService(() -> cfg, new HttpTransport());
  }

  public synchronized LlmService service() { return client; }
  public synchronized ReasoningTarget active() { return active; }
  public synchronized boolean isReady() { return client != null && client.isRunning(); }

  private void waitUntilReady(Process current, int port, String name) throws Exception {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    URI uri = URI.create("http://127.0.0.1:" + port + "/v1/models");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
    while (System.nanoTime() < deadline) {
      if (!current.isAlive()) throw new IllegalStateException("Web Reasoning llama.exe завершился при загрузке: " + name);
      try {
        HttpResponse<Void> r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(),
            HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() >= 200 && r.statusCode() < 500) return;
      } catch (Exception ignored) {}
      Thread.sleep(350);
    }
    throw new TimeoutException("Web Reasoning llama-server не открыл API на порту " + port + ": " + name);
  }

  private boolean isolateGpu(ModelLaunchConfig cfg) {
    return cfg.automatic() || "По устройству".equals(cfg.isolation());
  }

  private String chooseAutomaticDevice(AppState state) {
    for (Device d : state.devices)
      if (d.enabled.get() && d.id.startsWith("GPU") && !d.name.toLowerCase(Locale.ROOT).contains("intel"))
        return d.id;
    return "CPU";
  }

  private int guiGpuIndex(String deviceId) {
    try { return Integer.parseInt(deviceId.substring(deviceId.indexOf(' ') + 1).trim()); }
    catch (Exception e) { return -1; }
  }

  private GpuInfo selectedGpu(String deviceId) {
    int idx = guiGpuIndex(deviceId);
    HardwareInfo info = HardwareLocator.getInstance().info();
    if (idx < 0 || info == null) return null;
    return info.gpus().stream().filter(g -> g.index() == idx).findFirst().orElse(null);
  }

  private int cudaIndex(String deviceId) {
    GpuInfo selected = selectedGpu(deviceId);
    if (selected == null) return guiGpuIndex(deviceId);
    HardwareInfo info = HardwareLocator.getInstance().info();
    if (info == null) return guiGpuIndex(deviceId);
    List<GpuInfo> nvidia = info.gpus().stream().filter(g -> "NVIDIA".equalsIgnoreCase(g.vendor()))
        .sorted(Comparator.comparing(g -> g.pciBusId() == null ? "" : g.pciBusId().trim().toLowerCase(Locale.ROOT)))
        .toList();
    for (int i = 0; i < nvidia.size(); i++)
      if (Objects.equals(nvidia.get(i).id(), selected.id())) return i;
    return guiGpuIndex(deviceId);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.replace('\\', '/');
  }

  public synchronized void stop() {
    if (client != null) {
      try { client.stop(); } catch (Exception ignored) {}
      client = null;
    }
    if (process != null) {
      try {
        process.destroy();
        if (!process.waitFor(4, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          process.waitFor(2, TimeUnit.SECONDS);
        }
      } catch (Exception ignored) {}
      process = null;
    }
    active = null;
  }

  @Override public void close() { stop(); }
}
