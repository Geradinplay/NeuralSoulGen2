package com.neuralsoul.runtime;

import com.neuralsoul.hardware.*;
import com.neuralsoul.memory.MemorySettings;
import com.neuralsoul.model.*;
import java.nio.file.*;
import java.util.*;

/**
 * Builds one deterministic automatic-placement plan for the model processes that can be started
 * for the active profile. The planner only uses devices explicitly enabled on the Models page.
 *
 * <p>Whole-model GPU placement is preferred. When VRAM+RAM is allowed and no GPU has enough
 * estimated free VRAM for the complete model, the model is assigned to the GPU with the largest
 * remaining budget and llama.cpp auto-fit is allowed to keep the rest in RAM. Swap is deliberately
 * conservative: only small auxiliary models may be moved to CPU/RAM to preserve VRAM for larger
 * models. Main LLM and the memory embedding model are never selected as swap candidates.</p>
 */
public final class AutomaticModelPlacement {
  public enum Purpose { MAIN, MEMORY, WEB_SEARCH, WEB_REASONING, SUMMARY, GENERAL }

  public record Placement(String deviceId, boolean partialOffload, boolean swapMode,
                          long modelBytes, long estimatedGpuBytes) {
    public boolean gpu() { return deviceId != null && deviceId.startsWith("GPU"); }
  }

  public static final class Plan {
    private final Map<String, Placement> placements;
    private final List<String> logLines;

    private Plan(Map<String, Placement> placements, List<String> logLines) {
      this.placements = Map.copyOf(placements);
      this.logLines = List.copyOf(logLines);
    }

    public Placement placement(Purpose purpose, String modelKey) {
      return placements.get(key(purpose, modelKey));
    }

    public String device(Purpose purpose, String modelKey, String fallback) {
      Placement p = placement(purpose, modelKey);
      return p == null || p.deviceId() == null || p.deviceId().isBlank() ? fallback : p.deviceId();
    }

    public void printToLog() {
      if (logLines.isEmpty()) return;
      System.out.println("[AutoPlacement] ===== PLAN =====");
      for (String line : logLines) System.out.println("[AutoPlacement] " + line);
      System.out.println("[AutoPlacement] ===== /PLAN =====");
    }
  }

  private record Request(Purpose purpose, ModelAssignment model, int ctxSize, int parallel,
                         int priority, boolean swapEligible) {}

  private static final long GIB = 1024L * 1024L * 1024L;
  private static final long MIB = 1024L * 1024L;
  private static final long GPU_RESERVE = 1024L * MIB;
  private static final long MAX_LIGHT_SWAP_BYTES = 4L * GIB;

  private AutomaticModelPlacement() {}

  public static Plan plan(AppState state) {
    if (state == null || !state.automatic.get()) return new Plan(Map.of(), List.of());
    Profile profile = state.activeProfile.get() == null ? Profile.NORMAL : state.activeProfile.get();
    List<Request> requests = collectRequests(state, profile);

    HardwareLocator hardware = HardwareLocator.getInstance();
    HardwareInfo hw = hardware.info();
    HardwareTelemetry telemetry = hardware.telemetry();
    Map<Integer, Long> liveFree = new HashMap<>();
    if (telemetry != null) for (GpuTelemetry gpu : telemetry.gpus())
      if (gpu.freeVramBytes() > 0) liveFree.put(gpu.index(), gpu.freeVramBytes());
    Map<String, Long> remaining = new LinkedHashMap<>();
    Map<String, Long> totals = new LinkedHashMap<>();
    if (hw != null) {
      for (GpuInfo gpu : hw.gpus()) {
        String id = "GPU " + gpu.index();
        if (!deviceEnabled(state, id)) continue;
        // The bundled runtime is CUDA-oriented. Do not auto-place on Intel/unsupported adapters.
        if (!"NVIDIA".equalsIgnoreCase(gpu.vendor())
            && (gpu.name() == null || !gpu.name().toLowerCase(Locale.ROOT).contains("nvidia"))) continue;
        long total = Math.max(0, gpu.totalVramBytes());
        long totalBudget = Math.max(0, total - Math.min(GPU_RESERVE, total / 8));
        long freeNow = liveFree.getOrDefault(gpu.index(), total);
        long liveBudget = Math.max(0, freeNow - 512L * MIB);
        long usable = Math.min(totalBudget, liveBudget);
        totals.put(id, total);
        remaining.put(id, usable);
      }
    }
    boolean cpuEnabled = deviceEnabled(state, "CPU");
    long largestGpu = totals.values().stream().mapToLong(Long::longValue).max().orElse(0L);
    long lightSwapLimit = largestGpu > 0 ? Math.min(MAX_LIGHT_SWAP_BYTES, largestGpu / 3) : MAX_LIGHT_SWAP_BYTES;
    long freeRam = telemetry == null ? 0L : Math.max(0L, telemetry.freeRamBytes());
    long ramBudget = freeRam > 0 ? Math.max(0L, freeRam - Math.min(2L * GIB, freeRam / 6)) : Long.MAX_VALUE / 8;

    // Main first, then large models first inside the same priority. This prevents small helpers
    // from fragmenting VRAM before the important model is placed.
    requests.sort(Comparator.comparingInt(Request::priority)
        .thenComparingLong((Request r) -> -modelBytes(r.model()))
        .thenComparing(r -> r.model().fileName));

    LinkedHashMap<String, Placement> out = new LinkedHashMap<>();
    List<String> logs = new ArrayList<>();
    for (Request request : requests) {
      long bytes = modelBytes(request.model());
      long need = estimateGpuBytes(bytes, request.ctxSize(), request.parallel());
      String fullGpu = chooseFullFitGpu(remaining, need, request.purpose() == Purpose.MAIN);

      Placement placement;
      if (fullGpu != null) {
        remaining.put(fullGpu, Math.max(0, remaining.get(fullGpu) - need));
        placement = new Placement(fullGpu, false, false, bytes, need);
      } else if (state.allowSwap.get() && request.swapEligible() && bytes > 0 && bytes <= lightSwapLimit
          && cpuEnabled) {
        // Swap candidates are intentionally light auxiliary models only. In this mode the model
        // stays in system RAM/OS page cache and consumes no dedicated VRAM.
        if (bytes > ramBudget)
          throw new IllegalStateException("Недостаточно свободной RAM для swap-модели " + request.model().name);
        ramBudget -= bytes;
        placement = new Placement("CPU", false, true, bytes, need);
      } else if (state.allowRamOffload.get() && !remaining.isEmpty()) {
        String gpu = remaining.entrySet().stream().max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey).orElse(null);
        long available = gpu == null ? 0 : remaining.getOrDefault(gpu, 0L);
        if (gpu != null && available > 256L * MIB) {
          long estimatedRamPart = Math.max(0L, bytes - available);
          if (estimatedRamPart > ramBudget)
            throw new IllegalStateException("Недостаточно свободной RAM для частичной загрузки модели "
                + request.model().name);
          ramBudget -= estimatedRamPart;
          remaining.put(gpu, 0L);
          placement = new Placement(gpu, true, false, bytes, need);
        } else if (cpuEnabled) {
          if (bytes > ramBudget)
            throw new IllegalStateException("Недостаточно свободной RAM для модели " + request.model().name);
          ramBudget -= bytes;
          placement = new Placement("CPU", false, false, bytes, need);
        } else {
          throw new IllegalStateException("Недостаточно VRAM и CPU запрещён для модели " + request.model().name);
        }
      } else if (cpuEnabled) {
        if (bytes > ramBudget)
          throw new IllegalStateException("Недостаточно свободной RAM для модели " + request.model().name);
        ramBudget -= bytes;
        placement = new Placement("CPU", false, false, bytes, need);
      } else {
        throw new IllegalStateException("Нет разрешённого устройства для модели " + request.model().name);
      }

      out.put(key(request.purpose(), request.model().fileName), placement);
      String mode = placement.swapMode() ? "SWAP/RAM"
          : placement.partialOffload() ? "VRAM+RAM partial" : placement.gpu() ? "VRAM" : "CPU/RAM";
      logs.add(request.purpose() + " · " + request.model().name + " -> " + placement.deviceId()
          + " · " + mode + " · model=" + gib(bytes) + " GiB · est=" + gib(need) + " GiB");
    }
    if (freeRam > 0) logs.add("RAM budget: free=" + gib(freeRam) + " GiB, remaining~=" + gib(ramBudget) + " GiB");
    if (!totals.isEmpty()) {
      for (Map.Entry<String, Long> e : totals.entrySet()) {
        logs.add(e.getKey() + " budget: total=" + gib(e.getValue()) + " GiB, remaining~="
            + gib(remaining.getOrDefault(e.getKey(), 0L)) + " GiB");
      }
    }
    return new Plan(out, logs);
  }

  private static List<Request> collectRequests(AppState state, Profile profile) {
    List<Request> out = new ArrayList<>();
    ModelAssignment main = null;
    ModelAssignment web = null;
    boolean memoryAdded = false;
    for (ModelAssignment model : state.models) {
      ModelAssignment.ProfileConfig cfg = state.modelProfileConfig(profile, model);
      if (!cfg.enabled()) continue;
      if (model.memoryOnly) {
        if (!memoryAdded) {
          out.add(new Request(Purpose.MEMORY, model, 2048, 1, 1, false));
          memoryAdded = true;
        }
        continue;
      }
      if (model.webSearchOnly) {
        if (cfg.roles().contains(ModelRole.WEB_SEARCH)) web = model;
        continue;
      }
      if (cfg.roles().contains(ModelRole.MAIN_LLM)) main = model;
    }
    if (main != null) {
      ModelAssignment.ProfileConfig cfg = state.modelProfileConfig(profile, main);
      out.add(new Request(Purpose.MAIN, main, cfg.ctxSize(), cfg.parallel(), 0, false));
    }
    if (web != null) {
      ModelAssignment.ProfileConfig cfg = state.modelProfileConfig(profile, web);
      out.add(new Request(Purpose.WEB_SEARCH, web, cfg.ctxSize(), cfg.parallel(), 2, true));

      String selected = normalize(state.webReasoningModelValue(profile, web.fileName));
      if (!selected.isBlank() && !ProfileState.MAIN_LLM_REASONING.equals(selected)) {
        ModelAssignment reasoning = findEnabled(state, profile, selected);
        if (reasoning != null && reasoning != main && reasoning != web
            && state.modelProfileConfig(profile, reasoning).roles().contains(ModelRole.REASONING)) {
          ModelAssignment.ProfileConfig rc = state.modelProfileConfig(profile, reasoning);
          out.add(new Request(Purpose.WEB_REASONING, reasoning, rc.ctxSize(), rc.parallel(), 3, true));
        }
      }
    }

    String summaryKey = normalize(state.memorySettings.summarizationModel.get());
    if (!summaryKey.isBlank() && !MemorySettings.MAIN_LLM_SUMMARIZER.equals(summaryKey)) {
      ModelAssignment summary = findEnabled(state, profile, summaryKey);
      if (summary != null && summary != main) {
        ModelAssignment.ProfileConfig cfg = state.modelProfileConfig(profile, summary);
        out.add(new Request(Purpose.SUMMARY, summary, cfg.ctxSize(), 1, 4, true));
      }
    }

    for (ModelAssignment model : state.models) {
      if (!model.generalModel() || model == main) continue;
      ModelAssignment.ProfileConfig cfg = state.modelProfileConfig(profile, model);
      if (cfg.enabled() && cfg.roles().contains(ModelRole.GENERAL))
        out.add(new Request(Purpose.GENERAL, model, cfg.ctxSize(), cfg.parallel(), 5, true));
    }
    return out;
  }

  private static ModelAssignment findEnabled(AppState state, Profile profile, String key) {
    for (ModelAssignment model : state.models) {
      if (!model.generalModel() || !normalize(model.fileName).equals(key)) continue;
      if (state.modelProfileConfig(profile, model).enabled()) return model;
    }
    return null;
  }

  private static String chooseFullFitGpu(Map<String, Long> remaining, long need, boolean preferLargest) {
    if (remaining.isEmpty()) return null;
    if (preferLargest) {
      return remaining.entrySet().stream().filter(e -> e.getValue() >= need)
          .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }
    return remaining.entrySet().stream().filter(e -> e.getValue() >= need)
        .min(Comparator.comparingLong(e -> e.getValue() - need)).map(Map.Entry::getKey).orElse(null);
  }

  private static boolean deviceEnabled(AppState state, String id) {
    for (Device d : state.devices) if (id.equals(d.id)) return d.enabled.get();
    return false;
  }

  private static long modelBytes(ModelAssignment model) {
    try {
      Path path = Path.of(System.getProperty("user.dir"), "model").resolve(model.fileName).normalize();
      return Files.isRegularFile(path) ? Files.size(path) : 0L;
    } catch (Exception ignored) {
      return 0L;
    }
  }

  private static long estimateGpuBytes(long modelBytes, int ctxSize, int parallel) {
    if (modelBytes <= 0) return Long.MAX_VALUE / 8;
    long weights = (long) Math.ceil(modelBytes * 1.08);
    long ctx = Math.max(256L * MIB,
        (long) Math.ceil((Math.max(256, ctxSize) / 4096.0) * Math.max(1, parallel) * 256L * MIB));
    return weights + ctx;
  }

  private static String key(Purpose purpose, String modelKey) {
    return purpose.name() + "|" + normalize(modelKey);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.replace('\\', '/');
  }

  private static String gib(long bytes) {
    if (bytes <= 0 || bytes >= Long.MAX_VALUE / 16) return "?";
    return String.format(Locale.ROOT, "%.2f", bytes / (double) GIB);
  }
}
