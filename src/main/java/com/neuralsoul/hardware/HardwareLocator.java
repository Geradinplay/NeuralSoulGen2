package com.neuralsoul.hardware;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Global hardware registry.
 *
 * HardwareInfo is detected once. Dynamic telemetry is refreshed every second
 * on a daemon thread. UI code can either read telemetry() or subscribe().
 */
public final class HardwareLocator implements AutoCloseable {
  private static final HardwareLocator INSTANCE = new HardwareLocator();

  private final AtomicReference<HardwareTelemetry> telemetry =
      new AtomicReference<>(HardwareTelemetry.empty());

  private final CopyOnWriteArrayList<Consumer<HardwareTelemetry>> listeners =
      new CopyOnWriteArrayList<>();

  private ScheduledExecutorService scheduler;
  private HardwareProbe probe;
  private volatile HardwareInfo hardware;
  private volatile boolean initialized;

  private HardwareLocator() {}

  public static HardwareLocator getInstance() {
    return INSTANCE;
  }

  public synchronized void initialize(HardwareProbe probe) {
    if (initialized) return;

    this.probe = Objects.requireNonNull(probe);
    this.hardware = probe.detect();

    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "neuralsoul-hardware-telemetry");
              thread.setDaemon(true);
              return thread;
            });

    initialized = true;

    refreshTelemetry();
    scheduler.scheduleAtFixedRate(this::refreshTelemetrySafely, 1, 1, TimeUnit.SECONDS);
  }

  public boolean isInitialized() {
    return initialized;
  }

  public HardwareInfo info() {
    requireInitialized();
    return hardware;
  }

  public CpuInfo cpu() {
    return info().cpu();
  }

  public MemoryInfo memory() {
    return info().memory();
  }

  public List<GpuInfo> gpus() {
    return info().gpus();
  }

  public HardwareTelemetry telemetry() {
    requireInitialized();
    return telemetry.get();
  }

  public void refreshTelemetry() {
    requireInitialized();
    HardwareTelemetry next = probe.telemetry(hardware);
    telemetry.set(next);

    for (Consumer<HardwareTelemetry> listener : listeners) {
      try {
        listener.accept(next);
      } catch (RuntimeException ignored) {
        // One consumer must not stop hardware polling.
      }
    }
  }

  public AutoCloseable subscribe(Consumer<HardwareTelemetry> listener) {
    Objects.requireNonNull(listener);
    listeners.add(listener);

    if (initialized) listener.accept(telemetry.get());

    return () -> listeners.remove(listener);
  }

  @Override
  public synchronized void close() {
    if (scheduler != null) {
      scheduler.shutdownNow();
      scheduler = null;
    }

    listeners.clear();

    if (probe != null) {
      try { probe.close(); } catch (Exception ignored) {}
    }

    probe = null;
    hardware = null;
    telemetry.set(HardwareTelemetry.empty());
    initialized = false;
  }

  private void refreshTelemetrySafely() {
    try {
      refreshTelemetry();
    } catch (RuntimeException ignored) {
      // Keep the scheduler alive if one hardware read fails.
    }
  }

  private void requireInitialized() {
    if (!initialized) {
      throw new IllegalStateException(
          "HardwareLocator is not initialized. Call initialize(HardwareProbe) first.");
    }
  }
}
