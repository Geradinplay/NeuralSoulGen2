package com.neuralsoul.hardware;

import com.sun.jna.*;
import com.sun.jna.ptr.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/**
 * One-shot ACTUAL GPU-memory audit at the end of system startup.
 *
 * Windows WDDM accounts per-process GPU memory in PDH rather than NVML.  We
 * use \\GPU Process Memory(*)\\Dedicated Usage / Shared Usage and resolve its
 * adapter LUID through the display device's DEVPKEY_Gpu_Luid.  NVIDIA totals
 * come directly from nvidia-smi, independently of model placement settings.
 * No reported value is inferred from the GGUF size or requested GPU.
 */
public final class GpuMemoryAudit {
  private static final long MIB = 1024L * 1024;
  private static final Pattern PROCESS_INSTANCE = Pattern.compile(
      "(?i)^pid_(\\d+)_luid_(0x[0-9a-f]+)_(0x[0-9a-f]+)_phys_(\\d+)$");
  private static final Pattern ADAPTER_INSTANCE = Pattern.compile(
      "(?i)^luid_(0x[0-9a-f]+)_(0x[0-9a-f]+)_phys_(\\d+)$");

  private GpuMemoryAudit() {}

  /** Name is a logical model name; PID is the live OS process, not an intended GPU. */
  public record Target(String model, long pid) {
    public Target { Objects.requireNonNull(model); }
  }
  public record Usage(long dedicatedBytes, long sharedBytes) {}
  public record Row(long pid, String luid, long dedicatedBytes, long sharedBytes) {}
  public record Gpu(String index, String uuid, String name, long usedMiB, long totalMiB) {}

  public static void printAfterStartup(Collection<Target> targets) {
    System.out.println("[GPU/ACTUAL] ===== ФАКТИЧЕСКАЯ ВИДЕОПАМЯТЬ ПОСЛЕ ЗАПУСКА =====");
    try {
      if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
        System.out.println("[GPU/ACTUAL] Windows WDDM required; platform=" + System.getProperty("os.name"));
        return;
      }
      List<Gpu> nvidia = readNvidiaTotals();
      for (Gpu gpu : nvidia) {
        System.out.printf(Locale.ROOT,
            "[GPU/ACTUAL] GPU %s | %s | used=%s / %s MiB [NVIDIA driver] | UUID=%s%n",
            gpu.index(), gpu.name(), number(gpu.usedMiB()), number(gpu.totalMiB()), gpu.uuid());
      }
      if (nvidia.isEmpty())
        System.out.println("[GPU/ACTUAL] nvidia-smi unavailable; NVIDIA GPU totals cannot be measured");

      Map<String, String> luidNames = readWindowsGpuLuids();
      Snapshot snapshot = readWindowsCounters();
      if (!snapshot.available) {
        System.out.println("[GPU/ACTUAL] WDDM process counters unavailable. "
            + "No per-model VRAM numbers can be verified on this machine.");
        return;
      }
      if (luidNames.isEmpty())
        System.out.println("[GPU/ACTUAL] WARNING: PnP LUID -> GPU name mapping unavailable; "
            + "showing physical LUID instead of guessing a GPU");

      Map<Long, List<Row>> byPid = new HashMap<>();
      for (Row row : snapshot.processes) byPid.computeIfAbsent(row.pid(), ignored -> new ArrayList<>()).add(row);
      Set<Long> seen = new HashSet<>();
      Map<String, Long> ourDedicatedByGpu = new LinkedHashMap<>();
      for (Target target : targets) {
        if (target == null || target.pid() <= 0) continue;
        if (!seen.add(target.pid())) {
          System.out.println("[GPU/ACTUAL] REUSED PID=" + target.pid() + " " + target.model()
              + " (same process; VRAM is not double-counted)");
          continue;
        }
        List<Row> samples = byPid.getOrDefault(target.pid(), List.of());
        boolean processLive = ProcessHandle.of(target.pid()).map(ProcessHandle::isAlive).orElse(false);
        if (samples.isEmpty()) {
          System.out.println("[GPU/ACTUAL] " + target.model() + " | PID=" + target.pid()
              + " | dedicated=N/A | no WDDM per-process sample; alive=" + processLive);
          continue;
        }
        Map<String, Usage> perDevice = aggregate(samples);
        for (var entry : perDevice.entrySet()) {
          Usage usage = entry.getValue();
          String luid = entry.getKey();
          String name = luidNames.get(luid);
          String physical = name == null ? "UNKNOWN GPU (LUID=" + luid + ")" : name;
          ourDedicatedByGpu.merge(physical, usage.dedicatedBytes(), Long::sum);
          System.out.printf(Locale.ROOT,
              "[GPU/ACTUAL] %-31s PID=%-7d GPU=%s | dedicated=%s MiB | shared=%s MiB [Windows WDDM]%n",
              target.model(), target.pid(), physical,
              mib(usage.dedicatedBytes()), mib(usage.sharedBytes()));
        }
      }
      for (var entry : ourDedicatedByGpu.entrySet()) {
        System.out.println("[GPU/ACTUAL] NeuralSoul PROCESSES on " + entry.getKey()
            + " | dedicated sum=" + mib(entry.getValue()) + " MiB (unique PIDs)");
      }
      for (var gpu : snapshot.adapters.entrySet()) {
        String name = luidNames.getOrDefault(gpu.getKey(), "UNKNOWN GPU (LUID=" + gpu.getKey() + ")");
        System.out.println("[GPU/ACTUAL] WDDM adapter total: " + name
            + " | dedicated=" + mib(gpu.getValue()) + " MiB (all processes)");
      }
      System.out.println("[GPU/ACTUAL] Dedicated=VRAM allocated by the process; shared=system RAM mapped for GPU.");
      System.out.println("[GPU/ACTUAL] GPU total includes Windows/other apps/driver; "
          + "WDDM dedicated numbers may not sum to NVIDIA driver memory.used.");
    } catch (Throwable e) {
      System.out.println("[GPU/ACTUAL] Measurement failed (models keep running): " + e);
    } finally {
      System.out.println("[GPU/ACTUAL] ===== КОНЕЦ ФАКТИЧЕСКОГО ЗАМЕРА =====");
    }
  }

  /** Aggregate by Windows physical adapter LUID, not CUDA or GUI index. */
  public static Map<String, Usage> aggregate(Collection<Row> rows) {
    Map<String, Usage> result = new LinkedHashMap<>();
    for (Row row : rows) {
      Usage old = result.getOrDefault(row.luid(), new Usage(0L, 0L));
      result.put(row.luid(), new Usage(old.dedicatedBytes() + Math.max(0L, row.dedicatedBytes()),
          old.sharedBytes() + Math.max(0L, row.sharedBytes())));
    }
    return result;
  }

  /** Pure parser for Windows PDH instance names, testable without a GPU. */
  public static Row parseProcess(String instance, double dedicated, double shared) {
    Matcher matcher = PROCESS_INSTANCE.matcher(instance.trim());
    if (!matcher.matches()) return null;
    return new Row(Long.parseLong(matcher.group(1)),
        luid(matcher.group(2), matcher.group(3)),
        Math.max(0L, (long) dedicated), Math.max(0L, (long) shared));
  }

  public static String luid(String high, String low) {
    return String.format(Locale.ROOT, "0x%08x_0x%08x",
        Long.parseUnsignedLong(high.substring(2), 16), Long.parseUnsignedLong(low.substring(2), 16));
  }

  private static String mib(long bytes) {
    return String.format(Locale.ROOT, "%.1f", bytes / (double) MIB);
  }
  private static String number(long value) { return value < 0 ? "N/A" : Long.toString(value); }
  private static long parseLong(String v) {
    try { return Long.parseLong(v.trim()); } catch (Exception e) { return -1; }
  }

  private static List<Gpu> readNvidiaTotals() {
    List<Gpu> result = new ArrayList<>();
    String output = runCommand(List.of("nvidia-smi",
        "--query-gpu=index,uuid,name,memory.used,memory.total",
        "--format=csv,noheader,nounits"), 8);
    for (String line : output.lines().toList()) {
      // A normal GPU name may contain punctuation but not a comma on the typical NVIDIA cards.
      String[] parts = line.split(",", 5);
      if (parts.length != 5) continue;
      String index = parts[0].trim();
      if (!index.matches("\\d+")) continue;
      result.add(new Gpu(index, parts[1].trim(), parts[2].trim(),
          parseLong(parts[3]), parseLong(parts[4])));
    }
    return result;
  }

  /** Windows DEVPKEY_Gpu_Luid (a UInt64); produce exactly the counter LUID's 2x32 bits. */
  private static Map<String, String> readWindowsGpuLuids() {
    String script = "[Console]::OutputEncoding=[Text.Encoding]::UTF8; "
        + "$ErrorActionPreference='SilentlyContinue'; "
        + "foreach($dev in Get-PnpDevice -Class Display -PresentOnly){ "
        + "$prop=Get-PnpDeviceProperty -InstanceId $dev.InstanceId "
        + "-KeyName '{60B193CB-5276-4D0F-96FC-F173ABAD3EC6} 2'; "
        + "if($null -ne $prop -and $null -ne $prop.Data){ "
        + "$raw=[uint64]$prop.Data; $hi=($raw -shr 32) -band 4294967295; "
        + "$lo=$raw -band 4294967295; "
        + "$key=('0x{0:x8}_0x{1:x8}' -f $hi,$lo); "
        + "Write-Output ($key+'|'+$dev.FriendlyName) } }";
    String output = runCommand(List.of("powershell.exe", "-NoProfile", "-NonInteractive",
        "-Command", script), 10);
    Map<String, String> result = new LinkedHashMap<>();
    for (String line : output.lines().toList()) {
      int split = line.indexOf('|');
      if (split <= 0) continue;
      String key = line.substring(0, split).trim().toLowerCase(Locale.ROOT);
      if (key.matches("0x[0-9a-f]{8}_0x[0-9a-f]{8}"))
        result.put(key, line.substring(split + 1).trim());
    }
    return result;
  }

  private static String runCommand(List<String> args, int timeoutSeconds) {
    Process process = null;
    try {
      process = new ProcessBuilder(args).redirectErrorStream(true).start();
      Process running = process;
      java.util.concurrent.CompletableFuture<byte[]> output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
        try { return running.getInputStream().readAllBytes(); }
        catch (IOException ignored) { return new byte[0]; }
      });
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return "";
      }
      if (process.exitValue() != 0) return "";
      return new String(output.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8).trim();
    } catch (Exception ignored) {
      return "";
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
    }
  }

  private record Sample(String instance, double value) {}
  private record Snapshot(boolean available, List<Row> processes, Map<String, Long> adapters) {}

  private interface Pdh extends Library {
    Pdh INSTANCE = Native.load("pdh", Pdh.class,
        Map.of(Library.OPTION_STRING_ENCODING, "UTF-16LE"));
    int PdhOpenQueryW(String source, Pointer userdata, PointerByReference query);
    int PdhCloseQuery(Pointer query);
    int PdhCollectQueryData(Pointer query);
    int PdhAddEnglishCounterW(Pointer query, String path, Pointer userdata, PointerByReference counter);
    int PdhGetFormattedCounterArrayW(Pointer counter, int format,
                                    IntByReference size, IntByReference count, Pointer buffer);
  }
  public static final class FmtValue extends Structure {
    public int status;
    public Value value;
    public static final class Value extends Union {
      public double d;
      public long q;
      public int i;
    }
    @Override protected List<String> getFieldOrder() { return List.of("status", "value"); }
  }
  public static final class FmtItem extends Structure {
    public Pointer name;
    public FmtValue value;
    public FmtItem() {}
    public FmtItem(Pointer pointer) { super(pointer); read(); }
    @Override protected List<String> getFieldOrder() { return List.of("name", "value"); }
  }

  private static Snapshot readWindowsCounters() {
    PointerByReference queryRef = new PointerByReference();
    if (Pdh.INSTANCE.PdhOpenQueryW(null, null, queryRef) != 0)
      return new Snapshot(false, List.of(), Map.of());
    Pointer query = queryRef.getValue();
    try {
      Pointer dedicated = add(query, "\\GPU Process Memory(*)\\Dedicated Usage");
      Pointer shared = add(query, "\\GPU Process Memory(*)\\Shared Usage");
      Pointer total = add(query, "\\GPU Adapter Memory(*)\\Dedicated Usage");
      if (dedicated == null) return new Snapshot(false, List.of(), Map.of());
      Pdh.INSTANCE.PdhCollectQueryData(query);
      Pdh.INSTANCE.PdhCollectQueryData(query);
      Map<String, Row> merged = new LinkedHashMap<>();
      for (Sample sample : array(dedicated)) merge(merged, sample, true);
      for (Sample sample : array(shared)) merge(merged, sample, false);
      Map<String, Long> totals = new LinkedHashMap<>();
      for (Sample sample : array(total)) {
        Matcher m = ADAPTER_INSTANCE.matcher(sample.instance());
        if (m.matches()) totals.merge(luid(m.group(1), m.group(2)),
            Math.max(0L, (long) sample.value()), Long::sum);
      }
      return new Snapshot(true, List.copyOf(merged.values()), Map.copyOf(totals));
    } finally {
      Pdh.INSTANCE.PdhCloseQuery(query);
    }
  }

  private static void merge(Map<String, Row> merged, Sample sample, boolean dedicated) {
    Row next = parseProcess(sample.instance(), dedicated ? sample.value() : 0,
        dedicated ? 0 : sample.value());
    if (next == null) return;
    String key = next.pid() + "|" + next.luid();
    Row old = merged.get(key);
    if (old == null) merged.put(key, next);
    else merged.put(key, new Row(next.pid(), next.luid(),
        old.dedicatedBytes() + next.dedicatedBytes(), old.sharedBytes() + next.sharedBytes()));
  }

  private static Pointer add(Pointer query, String path) {
    PointerByReference counter = new PointerByReference();
    return Pdh.INSTANCE.PdhAddEnglishCounterW(query, path, null, counter) == 0
        ? counter.getValue() : null;
  }

  private static List<Sample> array(Pointer counter) {
    if (counter == null) return List.of();
    IntByReference size = new IntByReference(0), count = new IntByReference(0);
    final int MORE_DATA = 0x800007D2, FORMAT_DOUBLE = 0x200;
    int status = Pdh.INSTANCE.PdhGetFormattedCounterArrayW(counter, FORMAT_DOUBLE, size, count, null);
    if (status != MORE_DATA || size.getValue() <= 0 || count.getValue() <= 0) return List.of();
    Memory buffer = new Memory(size.getValue());
    status = Pdh.INSTANCE.PdhGetFormattedCounterArrayW(counter, FORMAT_DOUBLE, size, count, buffer);
    if (status != 0) return List.of();
    FmtItem[] items = (FmtItem[]) new FmtItem(buffer).toArray(count.getValue());
    List<Sample> result = new ArrayList<>(items.length);
    for (FmtItem item : items) {
      item.read();
      item.value.read();
      if (item.value.status != 0 || item.name == null) continue;
      item.value.value.setType(double.class);
      item.value.value.read();
      if (Double.isFinite(item.value.value.d) && item.value.value.d >= 0)
        result.add(new Sample(item.name.getWideString(0), item.value.value.d));
    }
    return result;
  }
}
