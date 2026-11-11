package com.neuralsoul.bootstrap;

import com.neuralsoul.config.ConfigLocator;
import com.neuralsoul.hardware.HardwareLocator;
import com.neuralsoul.hardware.WindowsHardwareProbe;
import com.neuralsoul.model.AppState;
import com.neuralsoul.pipeline.GenerationPipeline;
import com.neuralsoul.service.PipelineBackend;
import com.neuralsoul.service.ServiceLocator;
import com.neuralsoul.service.SettingsStore;

public final class ApplicationBootstrap {
  private ApplicationBootstrap() {}

  public static AppContext initialize() {
    // 1. Typed configuration registry.
    ConfigLocator configs = ConfigLocator.getInstance();
    configs.initialize();

    // 2. Hardware is detected once; telemetry starts polling every second.
    HardwareLocator hardware = HardwareLocator.getInstance();
    hardware.initialize(new WindowsHardwareProbe());

    // 3. GUI/runtime state.
    AppState state = new AppState();
    SettingsStore settings = new SettingsStore(configs);
    settings.load(state);

    // 4. Runtime services.
    ServiceLocator services = ServiceLocator.getInstance();
    services.setTtsDevices(() -> {
      java.util.List<com.neuralsoul.service.tts.FishSpeechTtsService.AllowedDevice> choices = new java.util.ArrayList<>();
      var info = com.neuralsoul.hardware.HardwareLocator.getInstance().info();
      boolean cpuEnabled = state.devices.stream().anyMatch(d -> "CPU".equals(d.id) && d.enabled.get());
      choices.add(new com.neuralsoul.service.tts.FishSpeechTtsService.AllowedDevice(
          -1, "CPU", "CPU", 0L, cpuEnabled));
      if (info != null) for (var gpu : info.gpus()) {
        boolean enabled = state.devices.stream().anyMatch(d ->
            ("GPU " + gpu.index()).equals(d.id) && d.enabled.get());
        long freeGpuVram = com.neuralsoul.hardware.HardwareLocator.getInstance().telemetry().gpus()
            .stream().filter(g -> g.index() == gpu.index()).mapToLong(g -> g.freeVramBytes())
            .findFirst().orElse(0L);
        choices.add(new com.neuralsoul.service.tts.FishSpeechTtsService.AllowedDevice(
            gpu.index(), gpu.name(), gpu.vendor(),
            freeGpuVram > 0 ? freeGpuVram : gpu.totalVramBytes(), enabled, gpu.id()));
      }
      return java.util.List.copyOf(choices);
    });

    // 5. Generation pipeline. It is created here, but starts only when the user
    // presses "Запустить систему" in the sidebar.
    GenerationPipeline pipeline = new GenerationPipeline(configs, services);

    PipelineBackend backend = new PipelineBackend(pipeline, state.voice::get);

    return new AppContext(state, backend, settings, services);
  }
}
