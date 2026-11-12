package com.neuralsoul.runtime;

import com.neuralsoul.model.*;
import com.neuralsoul.hardware.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class ModelRuntimeManager implements AutoCloseable {
  private static final int PORT = 8080;
  private Process process;

  /** Actual operating system process owned by this runtime; -1 if absent. */
  public synchronized long pid() { return process != null && process.isAlive() ? process.pid() : -1L; }
  private Thread logDrainer;
  private ModelLaunchConfig active;

  public synchronized boolean isRunning() {
    return process != null && process.isAlive();
  }

  public synchronized ModelLaunchConfig active() {
    return active;
  }

  public synchronized ModelLaunchConfig snapshot(AppState state) {
    return snapshot(state, AutomaticModelPlacement.plan(state));
  }

  public synchronized ModelLaunchConfig snapshot(AppState state, AutomaticModelPlacement.Plan placementPlan) {
    List<ModelAssignment> mains = state.models.stream()
        .filter(m -> m.enabled.get() && m.generalModel() && m.roles.contains(ModelRole.MAIN_LLM))
        .toList();
    if (mains.isEmpty())
      throw new IllegalStateException("Для профиля " + state.activeProfile.get().label() + " не выбрана Main LLM");
    if (mains.size() > 1)
      throw new IllegalStateException("Для профиля " + state.activeProfile.get().label() + " выбрано несколько Main LLM");
    ModelAssignment model = mains.get(0);

    String device = state.automatic.get()
        ? placementPlan.device(AutomaticModelPlacement.Purpose.MAIN, model.fileName, model.device.get())
        : model.device.get();
    if (device == null || device.isBlank())
      throw new IllegalStateException("Не выбрано устройство для Main LLM");

    Path modelPath = Path.of(System.getProperty("user.dir"), "model")
        .resolve(model.fileName).toAbsolutePath().normalize();

    Path loraRoot = modelPath.getParent().resolve("lora").normalize();
    List<LoraLaunchConfig> loras = model.loras.stream()
        .filter(l -> l.enabled.get())
        .map(l -> new LoraLaunchConfig(loraRoot.resolve(l.relativePath).normalize(), l.scale.get()))
        .filter(l -> l.path().startsWith(loraRoot) && Files.isRegularFile(l.path()))
        .toList();

    return new ModelLaunchConfig(
        model.name,
        modelPath,
        device,
        Set.copyOf(model.roles),
        Math.max(1, (int) Math.round(state.threads.get())),
        Math.max(256, model.ctxSize.get()),
        Math.max(64, model.maxOutputTokens.get()),
        Math.max(1, model.parallel.get()),
        Math.max(32, model.batchSize.get()),
        Math.max(32, model.ubatchSize.get()),
        model.cacheTypeK.get(),
        model.cacheTypeV.get(),
        model.isolation.get(),
        Math.max(0, (int) Math.round(state.vram.get())),
        state.automatic.get(),
        state.allowSwap.get(),
        state.allowRamOffload.get(),
        loras);
  }

  public synchronized void start(ModelLaunchConfig cfg) throws Exception {
    if (isRunning()) {
      System.out.println("[Runtime] llama.exe already running");
      return;
    }

    System.out.println("[Runtime] ===== START MODEL =====");
    System.out.println("[Runtime] Model: " + cfg.name());
    System.out.println("[Runtime] GGUF: " + cfg.modelPath());
    System.out.println("[Runtime] Device: " + cfg.device());
    System.out.println("[Runtime] Roles: " + cfg.roles());
    System.out.println("[Runtime] Threads: " + cfg.threads());
    System.out.println("[Runtime] Automatic: " + cfg.automatic());
    System.out.println("[Runtime] Swap: " + cfg.allowSwap());
    System.out.println("[Runtime] RAM offload: " + cfg.allowRamOffload());
    System.out.println("[Runtime] ctx-size: " + cfg.ctxSize());
    System.out.println("[Runtime] max output tokens: " + cfg.maxOutputTokens());
    System.out.println("[Runtime] parallel: " + cfg.parallel());
    System.out.println("[Runtime] batch-size: " + cfg.batchSize());
    System.out.println("[Runtime] ubatch-size: " + cfg.ubatchSize());
    System.out.println("[Runtime] cache K/V: " + cfg.cacheTypeK() + " / " + cfg.cacheTypeV());
    System.out.println("[Runtime] Model isolation: " + cfg.isolation());
    System.out.println("[Runtime] LoRA adapters: " + cfg.loras().size());

    Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    Path executable = root.resolve("runtime").resolve("llama").resolve("llama.exe");

    if (!Files.isRegularFile(executable))
      throw new FileNotFoundException("Не найден runtime/llama/llama.exe: " + executable);
    if (!Files.isRegularFile(cfg.modelPath()))
      throw new FileNotFoundException("Не найдена GGUF модель: " + cfg.modelPath());

    List<String> command = new ArrayList<>();
    command.add(executable.toString());
    command.add("serve");
    command.add("-m");
    command.add(cfg.modelPath().toString());
    Path workingDirectory = executable.getParent().toAbsolutePath().normalize();
    if (!cfg.loras().isEmpty()) {
      List<String> loraArguments = new ArrayList<>();
      for (LoraLaunchConfig lora : cfg.loras()) {
        Path loraPath = lora.path().toAbsolutePath().normalize();
        String launchPath = workingDirectory.relativize(loraPath).toString();
        loraArguments.add(launchPath + ":" + Double.toString(lora.scale()));
      }
      command.add("--lora-scaled");
      command.add(String.join(",", loraArguments));
    }
    command.add("--host");
    command.add("127.0.0.1");
    command.add("--port");
    command.add(Integer.toString(PORT));
    command.add("-t");
    command.add(Integer.toString(cfg.threads()));
    // GenerationPipeline is serial. A single llama-server slot keeps one continuous KV cache
    // instead of rotating the conversation through four default slots.
    command.add("--ctx-size");
    command.add(Integer.toString(cfg.ctxSize()));
    command.add("--parallel");
    command.add(Integer.toString(cfg.parallel()));
    command.add("--batch-size");
    command.add(Integer.toString(cfg.batchSize()));
    command.add("--ubatch-size");
    command.add(Integer.toString(cfg.ubatchSize()));
    command.add("--cache-type-k");
    command.add(cfg.cacheTypeK());
    command.add("--cache-type-v");
    command.add(cfg.cacheTypeV());

    if (cfg.device().startsWith("GPU")) {
      command.add("-ngl");
      command.add(cfg.automatic() && cfg.allowRamOffload() ? "auto" : "all");
      int index = cudaIndex(cfg.device());
      if (index >= 0) {
        command.add("--main-gpu");
        // Automatic placement isolates every process to the GPU selected by the planner.
        command.add(isolateGpu(cfg) ? "0" : Integer.toString(index));
      }
    } else {
      command.add("-ngl");
      command.add("0");
    }

    System.out.println("[Runtime] Executable: " + executable);
    System.out.println("[Runtime] Command: " + String.join(" ", command));

    ProcessBuilder pb = new ProcessBuilder(command);
    // Make CUDA numbering deterministic when more than one NVIDIA adapter is installed.
    pb.environment().put("CUDA_DEVICE_ORDER", "PCI_BUS_ID");
    if (isolateGpu(cfg) && cfg.device().startsWith("GPU")) {
      GpuInfo gpu = selectedGpu(cfg.device());
      if (gpu != null) {
        // UUID is stable and does not depend on Windows / nvidia-smi / CUDA index ordering.
        String selector = gpu.id() == null || gpu.id().isBlank() ? Integer.toString(cudaIndex(cfg.device())) : gpu.id();
        pb.environment().put("CUDA_VISIBLE_DEVICES", selector);
        System.out.println("[Runtime] Device isolation: " + gpu.name() + " | CUDA_VISIBLE_DEVICES=" + selector);
      }
    }
    pb.directory(workingDirectory.toFile());
    pb.redirectErrorStream(true);
    System.out.println("[Runtime] Starting process...");
    process = pb.start();
    active = cfg;
    System.out.println("[Runtime] PID: " + process.pid());

    Process current = process;
    logDrainer =
        new Thread(
            () -> {
              try (BufferedReader reader =
                  new BufferedReader(
                      new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                  System.out.println("[llama] " + line);
                }
              } catch (IOException ignored) {
              }
            },
            "llama-runtime-output");
    logDrainer.setDaemon(true);
    logDrainer.start();

    System.out.println("[Runtime] Waiting for API http://127.0.0.1:" + PORT + "/v1/models ...");
    waitUntilReady(current);
    System.out.println("[Runtime] API is ready");
    System.out.println("[Runtime] ===== MODEL READY =====");
  }

  private void waitUntilReady(Process current) throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    URI uri = URI.create("http://127.0.0.1:" + PORT + "/v1/models");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
    Exception last = null;

    while (System.nanoTime() < deadline) {
      if (!current.isAlive()) {
        int code;
        try { code = current.exitValue(); } catch (IllegalThreadStateException ignored) { code = -1; }
        System.out.println("[Runtime] ERROR: llama.exe exited. Exit code = " + code);
        throw new IllegalStateException(
            "llama.exe завершился при загрузке модели (exit=" + code
                + "). Вывод llama находится выше в консоли.");
      }
      try {
        HttpRequest request =
            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() >= 200 && response.statusCode() < 500) {
          System.out.println("[Runtime] /v1/models -> HTTP " + response.statusCode());
          return;
        }
      } catch (Exception e) {
        last = e;
      }
      Thread.sleep(350);
    }
    throw new TimeoutException(
        "llama.exe не открыл OpenAI API на 127.0.0.1:" + PORT
            + (last == null ? "" : " · " + last.getClass().getSimpleName()));
  }

  private boolean isolateGpu(ModelLaunchConfig cfg) {
    return cfg.automatic() || "По устройству".equals(cfg.isolation());
  }

  private String chooseAutomaticDevice(AppState state) {
    // Intel iGPU is deliberately not auto-selected for the bundled CUDA llama runtime.
    for (Device d : state.devices) {
      if (d.enabled.get()
          && d.id.startsWith("GPU")
          && !d.name.toLowerCase(Locale.ROOT).contains("intel")) return d.id;
    }
    return state.devices.stream()
        .filter(d -> d.enabled.get() && "CPU".equals(d.id))
        .map(d -> d.id)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Нет разрешённого устройства для модели"));
  }

  private int guiGpuIndex(String id) {
    try {
      return Integer.parseInt(id.replace("GPU", "").trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private GpuInfo selectedGpu(String deviceId) {
    int guiIndex = guiGpuIndex(deviceId);
    if (guiIndex < 0) return null;
    HardwareInfo info = HardwareLocator.getInstance().info();
    if (info == null) return null;
    return info.gpus().stream().filter(g -> g.index() == guiIndex).findFirst().orElse(null);
  }

  /**
   * CUDA_DEVICE_ORDER=PCI_BUS_ID makes CUDA enumerate NVIDIA adapters by PCI bus.
   * HardwareLocator keeps the stable UUID/name/bus identity, so GUI numbering no longer
   * gets passed blindly to llama.cpp.
   */
  private int cudaIndex(String deviceId) {
    GpuInfo selected = selectedGpu(deviceId);
    if (selected == null) return guiGpuIndex(deviceId);

    HardwareInfo info = HardwareLocator.getInstance().info();
    if (info == null) return guiGpuIndex(deviceId);

    List<GpuInfo> nvidia = info.gpus().stream()
        .filter(g -> "NVIDIA".equalsIgnoreCase(g.vendor()))
        .sorted(Comparator.comparing(g -> normalizeBusId(g.pciBusId())))
        .toList();
    for (int i = 0; i < nvidia.size(); i++) {
      if (Objects.equals(nvidia.get(i).id(), selected.id())) return i;
    }
    return guiGpuIndex(deviceId);
  }

  private String normalizeBusId(String busId) {
    return busId == null ? "" : busId.trim().toLowerCase(Locale.ROOT);
  }

  public synchronized void stop() {
    System.out.println("[Runtime] Stop requested");
    Process p = process;
    process = null;
    active = null;
    if (p == null) {
      System.out.println("[Runtime] No llama process to stop");
      return;
    }
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
    System.out.println("[Runtime] llama.exe stopped");
  }

  @Override
  public void close() {
    stop();
  }
}
