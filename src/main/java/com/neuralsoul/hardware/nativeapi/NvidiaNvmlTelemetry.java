package com.neuralsoul.hardware.nativeapi;

import com.neuralsoul.hardware.GpuInfo;
import com.neuralsoul.hardware.GpuTelemetry;
import com.sun.jna.*;
import com.sun.jna.ptr.*;
import java.util.*;

public final class NvidiaNvmlTelemetry implements AutoCloseable {
  private static final int NVML_SUCCESS = 0;
  private static final int NVML_TEMPERATURE_GPU = 0;

  private interface Nvml extends Library {
    int nvmlInit_v2();
    int nvmlShutdown();
    int nvmlDeviceGetHandleByIndex_v2(int index, PointerByReference device);
    int nvmlDeviceGetUtilizationRates(Pointer device, Utilization utilization);
    int nvmlDeviceGetMemoryInfo(Pointer device, Memory memory);
    int nvmlDeviceGetTemperature(Pointer device, int sensorType, IntByReference temp);
    int nvmlDeviceGetPowerUsage(Pointer device, IntByReference milliwatts);
    int nvmlDeviceGetFanSpeed(Pointer device, IntByReference percent);
  }

  public static final class Utilization extends Structure {
    public int gpu;
    public int memory;
    @Override protected List<String> getFieldOrder() { return List.of("gpu", "memory"); }
  }

  public static final class Memory extends Structure {
    public long total;
    public long free;
    public long used;
    @Override protected List<String> getFieldOrder() { return List.of("total", "free", "used"); }
  }

  private Nvml nvml;
  private boolean available;

  public NvidiaNvmlTelemetry() {
    try {
      nvml = Native.load("nvml", Nvml.class);
      available = nvml.nvmlInit_v2() == NVML_SUCCESS;
    } catch (Throwable ignored) {
      nvml = null;
      available = false;
    }
  }

  public Optional<GpuTelemetry> telemetry(GpuInfo gpu) {
    if (!available || !"NVIDIA".equalsIgnoreCase(gpu.vendor())) return Optional.empty();

    PointerByReference deviceRef = new PointerByReference();
    if (nvml.nvmlDeviceGetHandleByIndex_v2(gpu.index(), deviceRef) != NVML_SUCCESS) return Optional.empty();
    Pointer device = deviceRef.getValue();

    double utilization = -1, temperature = -1, power = -1, fan = -1;
    long used = -1, free = -1;

    Utilization util = new Utilization();
    if (nvml.nvmlDeviceGetUtilizationRates(device, util) == NVML_SUCCESS) {
      util.read();
      utilization = Integer.toUnsignedLong(util.gpu);
    }

    Memory memory = new Memory();
    if (nvml.nvmlDeviceGetMemoryInfo(device, memory) == NVML_SUCCESS) {
      memory.read();
      used = memory.used;
      free = memory.free;
    }

    IntByReference value = new IntByReference();
    if (nvml.nvmlDeviceGetTemperature(device, NVML_TEMPERATURE_GPU, value) == NVML_SUCCESS)
      temperature = Integer.toUnsignedLong(value.getValue());

    value.setValue(0);
    if (nvml.nvmlDeviceGetPowerUsage(device, value) == NVML_SUCCESS)
      power = Integer.toUnsignedLong(value.getValue()) / 1000.0;

    value.setValue(0);
    if (nvml.nvmlDeviceGetFanSpeed(device, value) == NVML_SUCCESS)
      fan = Integer.toUnsignedLong(value.getValue());

    return Optional.of(new GpuTelemetry(gpu.index(), utilization, used, free, temperature, power, fan));
  }

  @Override public void close() {
    if (available && nvml != null) {
      try { nvml.nvmlShutdown(); } catch (Throwable ignored) {}
    }
    available = false;
    nvml = null;
  }
}
