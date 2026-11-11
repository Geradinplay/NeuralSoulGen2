package com.neuralsoul.hardware;

public record GpuInfo(
    int index,
    String id,
    String name,
    String vendor,
    long totalVramBytes,
    String driverVersion,
    String computeCapability,
    String pciBusId
) {
  public double totalVramGiB() {
    return totalVramBytes / 1024.0 / 1024.0 / 1024.0;
  }
}
