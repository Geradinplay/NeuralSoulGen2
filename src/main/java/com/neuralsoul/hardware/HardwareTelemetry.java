package com.neuralsoul.hardware;

import java.time.Instant;
import java.util.List;

public record HardwareTelemetry(
    Instant timestamp,
    double cpuUsagePercent,
    long usedRamBytes,
    long freeRamBytes,
    List<GpuTelemetry> gpus
) {
  public HardwareTelemetry {
    gpus = List.copyOf(gpus);
  }

  public static HardwareTelemetry empty() {
    return new HardwareTelemetry(Instant.EPOCH, 0.0, 0L, 0L, List.of());
  }

  public double usedRamGiB() {
    return usedRamBytes / 1024.0 / 1024.0 / 1024.0;
  }

  public double freeRamGiB() {
    return freeRamBytes / 1024.0 / 1024.0 / 1024.0;
  }
}
