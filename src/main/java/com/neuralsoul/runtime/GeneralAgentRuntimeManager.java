package com.neuralsoul.runtime;

import com.neuralsoul.config.*;
import com.neuralsoul.hardware.*;
import com.neuralsoul.model.*;
import com.neuralsoul.service.api.*;
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
 * Owns one llama.cpp server per enabled GENERAL model.
 * GENERAL is model-scoped: each model gets its own port and can be addressed independently.
 */
public final class GeneralAgentRuntimeManager implements AutoCloseable {
  public static final int BASE_PORT = 8090;

  public record GeneralLaunch(String modelKey, String displayName, ModelLaunchConfig config, int port) {}

  private final LinkedHashMap<String, Process> processes = new LinkedHashMap<>();

  /** OS PIDs of separate GENERAL models; reused Main LLM models have no extra process. */
  public synchronized Map<String, Long> activePids() {
    Map<String, Long> result = new LinkedHashMap<>();
    for (var entry : processes.entrySet()) {
      Process p = entry.getValue();
      if (p != null && p.isAlive()) {
        GeneralLaunch model = active.get(entry.getKey());
        result.put(model == null ? entry.getKey() : model.displayName(), p.pid());
      }
    }
    return result;
  }
  private final LinkedHashMap<String, OpenAiLlmService> clients = new LinkedHashMap<>();
  private final LinkedHashMap<String, GeneralLaunch> active = new LinkedHashMap<>();

  public synchronized List<GeneralLaunch> snapshot(AppState state) {
    return snapshot(state, AutomaticModelPlacement.plan(state));
  }

  public synchronized List<GeneralLaunch> snapshot(AppState state, AutomaticModelPlacement.Plan placementPlan) {
    Profile profile = state.activeProfile.get() == null ? Profile.NORMAL : state.activeProfile.get();
    List<GeneralLaunch> result = new ArrayList<>();
    int port = BASE_PORT;
    for (ModelAssignment model : state.models) {
      if (!model.generalModel()) continue;
      ModelAssignment.ProfileConfig cfg = state.modelProfileConfig(profile, model);
      if (!cfg.enabled() || !cfg.roles().contains(ModelRole.GENERAL)) continue;
      // If the same physical model is MAIN_LLM, GenerationPipeline reuses the already-running :8080 server.
      if (cfg.roles().contains(ModelRole.MAIN_LLM)) continue;

      String device = state.automatic.get()
          ? placementPlan.device(AutomaticModelPlacement.Purpose.GENERAL, model.fileName, cfg.device())
          : cfg.device();
      if (device == null || device.isBlank())
        throw new IllegalStateException("Не выбрано устройство для General: " + model.name);

      Path modelPath = Path.of(System.getProperty("user.dir"), "model")
          .resolve(model.fileName).toAbsolutePath().normalize();
      Path loraRoot = modelPath.getParent().resolve("lora").normalize();
      List<LoraLaunchConfig> loras = cfg.loras().entrySet().stream()
          .filter(e -> e.getValue() != null && e.getValue().enabled())
          .map(e -> new LoraLaunchConfig(loraRoot.resolve(e.getKey()).normalize(), e.getValue().scale()))
          .filter(l -> l.path().startsWith(loraRoot) && Files.isRegularFile(l.path()))
          .toList();

      ModelLaunchConfig launch = new ModelLaunchConfig(
          model.name, modelPath, device, Set.copyOf(cfg.roles()),
          Math.max(1, (int)Math.round(state.threads.get())),
          Math.max(256, cfg.ctxSize()), Math.max(64, cfg.maxOutputTokens()), Math.max(1, cfg.parallel()),
          Math.max(32, cfg.batchSize()), Math.max(32, cfg.ubatchSize()),
          cfg.cacheTypeK(), cfg.cacheTypeV(), cfg.isolation(),
          Math.max(0, (int)Math.round(state.vram.get())),
          state.automatic.get(), state.allowSwap.get(), state.allowRamOffload.get(), loras);

      result.add(new GeneralLaunch(normalize(model.fileName), model.name, launch, port++));
    }
    return List.copyOf(result);
  }

  public synchronized void startAll(List<GeneralLaunch> launches) throws Exception {
    stop();
    if (launches == null || launches.isEmpty()) return;
    for (GeneralLaunch launch : launches) startOne(launch);
  }

  /** Clear previous agents before executing individual ModelLaunch descriptors. */
  public synchronized void prepareStart() {
    stop();
  }

  /** Start one descriptor; used by the centralized startup plan for exact per-model logs. */
  public synchronized void start(GeneralLaunch launch) throws Exception {
    if (launch == null) throw new IllegalArgumentException("General launch is null");
    startOne(launch);
  }

  public synchronized boolean isReady(String modelKey) {
    OpenAiLlmService client = clients.get(normalize(modelKey));
    return client != null && client.isRunning();
  }

  public synchronized Set<String> modelKeys() {
    return Set.copyOf(active.keySet());
  }

  public String generate(String modelKey, String task) throws Exception {
    return generate(modelKey, task, com.neuralsoul.config.AgentPrompts.defaultPrompt(com.neuralsoul.model.ModelRole.GENERAL));
  }
  public String generate(String modelKey, String task, String systemPrompt) throws Exception {
    OpenAiLlmService client;
    GeneralLaunch launch;
    synchronized (this) {
      String key = normalize(modelKey);
      client = clients.get(key);
      launch = active.get(key);
    }
    if (client == null || launch == null || !client.isRunning())
      throw new IllegalStateException("General Agent не запущен: " + modelKey);

    List<ConversationTurn> prompt = List.of(
        new ConversationTurn("system", systemPrompt),
        new ConversationTurn("user", task));
    return client.generate(prompt, "");
  }

  private void startOne(GeneralLaunch launch) throws Exception {
    ModelLaunchConfig cfg = launch.config();
    Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    Path executable = root.resolve("runtime").resolve("llama").resolve("llama.exe");
    if (!Files.isRegularFile(executable))
      throw new FileNotFoundException("Не найден runtime/llama/llama.exe: " + executable);
    if (!Files.isRegularFile(cfg.modelPath()))
      throw new FileNotFoundException("Не найдена General GGUF: " + cfg.modelPath());

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
        "--host", "127.0.0.1",
        "--port", Integer.toString(launch.port()),
        "-t", Integer.toString(cfg.threads()),
        "--ctx-size", Integer.toString(cfg.ctxSize()),
        "--parallel", Integer.toString(cfg.parallel()),
        "--batch-size", Integer.toString(cfg.batchSize()),
        "--ubatch-size", Integer.toString(cfg.ubatchSize()),
        "--cache-type-k", cfg.cacheTypeK(),
        "--cache-type-v", cfg.cacheTypeV());

    if (cfg.device().startsWith("GPU")) {
      command.add("-ngl");
      command.add(cfg.automatic() && cfg.allowRamOffload() ? "auto" : "all");
      int index = cudaIndex(cfg.device());
      if (index >= 0) {
        command.add("--main-gpu");
        command.add(isolateGpu(cfg) ? "0" : Integer.toString(index));
      }
    } else {
      command.add("-ngl");
      command.add("0");
    }

    System.out.println("[General/Runtime] Starting " + launch.displayName()
        + " on :" + launch.port() + " -> " + String.join(" ", command));

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
    Process process = pb.start();
    String key = normalize(launch.modelKey());
    processes.put(key, process);
    active.put(key, launch);

    Thread logDrainer = new Thread(() -> {
      try (BufferedReader r = new BufferedReader(
          new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = r.readLine()) != null)
          System.out.println("[general-" + launch.port() + "] " + line);
      } catch (IOException ignored) {}
    }, "general-runtime-" + launch.port());
    logDrainer.setDaemon(true);
    logDrainer.start();

    waitUntilReady(process, launch.port(), launch.displayName());

    EndpointConfig ep = new EndpointConfig("http://127.0.0.1:" + launch.port(),
        "/v1/chat/completions", "/v1/models", "", 90);
    LlmConfig llmCfg = new LlmConfig(ep, "", "", 0.6, cfg.maxOutputTokens());
    OpenAiLlmService client = new OpenAiLlmService(() -> llmCfg, new HttpTransport());
    client.start();
    clients.put(key, client);

    System.out.println("[General/Runtime] Ready " + launch.displayName()
        + " on http://127.0.0.1:" + launch.port());
  }

  private void waitUntilReady(Process process, int port, String name) throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    URI uri = URI.create("http://127.0.0.1:" + port + "/v1/models");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
    while (System.nanoTime() < deadline) {
      if (!process.isAlive())
        throw new IllegalStateException("General llama.exe завершился при загрузке: " + name);
      try {
        HttpResponse<Void> r = client.send(
            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(),
            HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() >= 200 && r.statusCode() < 500) return;
      } catch (Exception ignored) {}
      Thread.sleep(350);
    }
    throw new TimeoutException("General llama-server не открыл API на порту " + port + ": " + name);
  }

  private boolean isolateGpu(ModelLaunchConfig cfg) {
    return cfg.automatic() || "По устройству".equals(cfg.isolation());
  }

  private String chooseAutomaticDevice(AppState state) {
    for (Device d : state.devices)
      if (d.enabled.get() && d.id.startsWith("GPU")
          && !d.name.toLowerCase(Locale.ROOT).contains("intel")) return d.id;
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
    List<GpuInfo> nvidia = info.gpus().stream()
        .filter(g -> "NVIDIA".equalsIgnoreCase(g.vendor()))
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
    for (OpenAiLlmService client : clients.values()) {
      try { client.stop(); } catch (Exception ignored) {}
    }
    clients.clear();
    for (Process process : processes.values()) {
      try {
        process.destroy();
        if (!process.waitFor(4, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          process.waitFor(2, TimeUnit.SECONDS);
        }
      } catch (Exception ignored) {}
    }
    processes.clear();
    active.clear();
  }

  @Override public void close() { stop(); }
}
