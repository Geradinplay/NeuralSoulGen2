package com.neuralsoul.hardware;

/**
 * Platform-specific hardware reader.
 *
 * Static information is relatively expensive and should normally be read once.
 * Telemetry is intended to be polled periodically.
 */
public interface HardwareProbe extends AutoCloseable {
  HardwareInfo detect();
  HardwareTelemetry telemetry(HardwareInfo hardware);

  @Override
  default void close() {}
}

