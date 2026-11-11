package com.neuralsoul.hardware;

public record MemoryInfo(
    long totalBytes
) {
  public double totalGiB() {
    return totalBytes / 1024.0 / 1024.0 / 1024.0;
  }
}
