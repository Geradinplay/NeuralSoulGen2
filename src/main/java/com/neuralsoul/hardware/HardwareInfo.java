package com.neuralsoul.hardware;

import java.util.List;

public record HardwareInfo(
    CpuInfo cpu,
    MemoryInfo memory,
    List<GpuInfo> gpus
) {
  public HardwareInfo {
    gpus = List.copyOf(gpus);
  }
}
