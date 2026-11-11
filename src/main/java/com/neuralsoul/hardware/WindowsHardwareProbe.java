package com.neuralsoul.hardware;

import com.sun.management.OperatingSystemMXBean;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.neuralsoul.hardware.nativeapi.NvidiaNvmlTelemetry;

/**
 * Windows hardware probe.
 *
 * Static CPU/general GPU data comes from PowerShell/CIM.
 * NVIDIA cards get richer information and live telemetry from nvidia-smi.
 */
public final class WindowsHardwareProbe implements HardwareProbe {
  private static final long MIB = 1024L * 1024L;
  private final IntelGpuProbe intelGpuProbe = new IntelGpuProbe();
  private final NvidiaNvmlTelemetry nvidiaNvml = new NvidiaNvmlTelemetry();
  private final OperatingSystemMXBean os =
      (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

  @Override
  public HardwareInfo detect() {
    CpuInfo cpu = detectCpu();
    MemoryInfo memory = new MemoryInfo(os.getTotalMemorySize());

    List<GpuInfo> nvidia = detectNvidiaGpus();
    List<GpuInfo> gpus = nvidia.isEmpty() ? detectWindowsGpus() : nvidia;

    return new HardwareInfo(cpu, memory, gpus);
  }

  @Override
  public HardwareTelemetry telemetry(HardwareInfo hardware) {
    double cpu = percent(os.getCpuLoad());
    long total = os.getTotalMemorySize();
    long free = os.getFreeMemorySize();
    long used = Math.max(0L, total - free);

    List<GpuTelemetry> result = new ArrayList<>();
    for (GpuInfo gpu : hardware.gpus()) {
      GpuTelemetry value = null;

      if ("NVIDIA".equalsIgnoreCase(gpu.vendor())) {
        value = nvidiaNvml.telemetry(gpu).orElse(null);
      } else if ("Intel".equalsIgnoreCase(gpu.vendor())) {
        value = intelGpuProbe.telemetry(gpu).orElse(null);
      }

      if (value == null) value = new GpuTelemetry(gpu.index(), -1, -1, -1, -1, -1, -1);
      result.add(value);
    }

    return new HardwareTelemetry(Instant.now(), cpu, used, free, result);
  }

  private CpuInfo detectCpu() {
    String output =
        powershell(
            "$c=Get-CimInstance Win32_Processor | Select-Object -First 1;"
                + "Write-Output ($c.Name+'|'+$c.NumberOfCores+'|'+$c.NumberOfLogicalProcessors)");

    String[] p = output.strip().split("\\|", -1);
    String name = p.length > 0 && !p[0].isBlank() ? p[0].trim() : System.getenv("PROCESSOR_IDENTIFIER");
    int physical = p.length > 1 ? integer(p[1], Runtime.getRuntime().availableProcessors()) : Runtime.getRuntime().availableProcessors();
    int logical = p.length > 2 ? integer(p[2], Runtime.getRuntime().availableProcessors()) : Runtime.getRuntime().availableProcessors();

    return new CpuInfo(
        name == null ? "Unknown CPU" : name,
        System.getProperty("os.arch", "unknown"),
        physical,
        logical);
  }

  private List<GpuInfo> detectNvidiaGpus() {
    String output =
        command(
            List.of(
                "nvidia-smi",
                "--query-gpu=index,uuid,name,memory.total,driver_version,compute_cap,pci.bus_id",
                "--format=csv,noheader,nounits"));

    if (output.isBlank()) return List.of();

    List<GpuInfo> result = new ArrayList<>();
    for (String line : output.lines().toList()) {
      List<String> c = csv(line);
      if (c.size() < 7) continue;

      int index = integer(c.get(0), result.size());
      long vram = mebibytes(c.get(3));

      result.add(
          new GpuInfo(
              index,
              c.get(1).trim(),
              c.get(2).trim(),
              "NVIDIA",
              vram,
              c.get(4).trim(),
              c.get(5).trim(),
              c.get(6).trim()));
    }
    return List.copyOf(result);
  }

  private List<GpuInfo> detectWindowsGpus() {
    String output =
        powershell(
            "$i=0; Get-CimInstance Win32_VideoController | ForEach-Object {"
                + "Write-Output ($i.ToString()+'|'+$_.PNPDeviceID+'|'+$_.Name+'|'+$_.AdapterRAM+'|'+$_.DriverVersion);"
                + "$i++ }");

    List<GpuInfo> result = new ArrayList<>();
    for (String line : output.lines().toList()) {
      String[] p = line.split("\\|", -1);
      if (p.length < 5) continue;

      int index = integer(p[0], result.size());
      String name = p[2].trim();
      result.add(
          new GpuInfo(
              index,
              p[1].trim(),
              name,
              vendor(name),
              longValue(p[3], 0),
              p[4].trim(),
              "",
              ""));
    }
    return List.copyOf(result);
  }


  private String powershell(String script) {
    return command(
        List.of(
            "powershell.exe",
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            script));
  }

  private String command(List<String> command) {
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      boolean finished = process.waitFor(4, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        return "";
      }
      if (process.exitValue() != 0) return "";
      return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (Exception ignored) {
      return "";
    }
  }

  private static String vendor(String name) {
    String n = name.toLowerCase(Locale.ROOT);
    if (n.contains("nvidia")) return "NVIDIA";
    if (n.contains("amd") || n.contains("radeon")) return "AMD";
    if (n.contains("intel")) return "Intel";
    return "Unknown";
  }

  private static double percent(double fraction) {
    if (!Double.isFinite(fraction) || fraction < 0) return -1;
    return Math.max(0, Math.min(100, fraction * 100.0));
  }

  private static int integer(String value, int fallback) {
    try {
      return Integer.parseInt(value.trim());
    } catch (Exception e) {
      return fallback;
    }
  }

  private static long longValue(String value, long fallback) {
    try {
      return Long.parseLong(value.trim());
    } catch (Exception e) {
      return fallback;
    }
  }

  private static long mebibytes(String value) {
    double mib = number(value);
    return mib < 0 ? -1 : (long) (mib * MIB);
  }

  private static double number(String value) {
    try {
      String s = value.trim();
      if (s.isEmpty() || s.equalsIgnoreCase("N/A") || s.equals("[Not Supported]")) return -1;
      return Double.parseDouble(s);
    } catch (Exception e) {
      return -1;
    }
  }

  private static List<String> csv(String line) {
    List<String> result = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;

    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (ch == '"') {
        if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          current.append('"');
          i++;
        } else {
          quoted = !quoted;
        }
      } else if (ch == ',' && !quoted) {
        result.add(current.toString().trim());
        current.setLength(0);
      } else {
        current.append(ch);
      }
    }
    result.add(current.toString().trim());
    return result;
  }
  @Override
  public void close() {
    intelGpuProbe.close();
    nvidiaNvml.close();
  }

}
