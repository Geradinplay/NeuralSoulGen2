package com.neuralsoul.hardware;

import com.neuralsoul.hardware.nativeapi.WindowsPdhGpuTelemetry;
import java.util.Optional;

public final class IntelGpuProbe implements AutoCloseable {
  private final WindowsPdhGpuTelemetry pdh = new WindowsPdhGpuTelemetry();
  private GpuTelemetry lastGood;

  public synchronized Optional<GpuTelemetry> telemetry(GpuInfo gpu) {
    if (!"Intel".equalsIgnoreCase(gpu.vendor())) return Optional.empty();

    WindowsPdhGpuTelemetry.Sample sample = pdh.sample();
    long used = sample.totalUsedBytes();

    if (sample.utilizationPercent() < 0 && used < 0) return Optional.ofNullable(lastGood);

    long total = gpu.totalVramBytes();
    long free = total > 0 && used >= 0 ? Math.max(0, total - used) : -1;

    GpuTelemetry next = new GpuTelemetry(gpu.index(), sample.utilizationPercent(), used, free, -1, -1, -1);
    lastGood = merge(next, lastGood);
    return Optional.of(lastGood);
  }

  private static GpuTelemetry merge(GpuTelemetry next, GpuTelemetry old) {
    if (old == null) return next;
    return new GpuTelemetry(
        next.index(),
        d(next.utilizationPercent(), old.utilizationPercent()),
        l(next.usedVramBytes(), old.usedVramBytes()),
        l(next.freeVramBytes(), old.freeVramBytes()),
        d(next.temperatureCelsius(), old.temperatureCelsius()),
        d(next.powerWatts(), old.powerWatts()),
        d(next.fanPercent(), old.fanPercent()));
  }

  private static double d(double v, double fallback) { return Double.isFinite(v) && v >= 0 ? v : fallback; }
  private static long l(long v, long fallback) { return v >= 0 ? v : fallback; }

  @Override public void close() { pdh.close(); }
}
