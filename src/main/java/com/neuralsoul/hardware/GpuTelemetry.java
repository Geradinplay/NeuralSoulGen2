package com.neuralsoul.hardware;

public record GpuTelemetry(
    int index,
    double utilizationPercent,
    long usedVramBytes,
    long freeVramBytes,
    double temperatureCelsius,
    double powerWatts,
    double fanPercent
) {
  public double usedVramGiB() {
    return usedVramBytes / 1024.0 / 1024.0 / 1024.0;
  }

  public double freeVramGiB() {
    return freeVramBytes / 1024.0 / 1024.0 / 1024.0;
  }
}
