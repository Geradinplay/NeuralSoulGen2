package com.neuralsoul.hardware.nativeapi;

import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.util.*;

/**
 * Low-overhead Windows GPU telemetry.
 *
 * Important difference from the previous implementation:
 * it DOES NOT add one PDH counter handle for every GPU Engine instance.
 * It keeps only two wildcard counters and asks PDH for their value arrays.
 */
public final class WindowsPdhGpuTelemetry implements AutoCloseable {
  private static final int ERROR_SUCCESS = 0;
  private static final int PDH_MORE_DATA = 0x800007D2;
  private static final int PDH_FMT_DOUBLE = 0x00000200;

  private interface Pdh extends Library {
    Pdh INSTANCE =
        Native.load(
            "pdh",
            Pdh.class,
            Map.of(Library.OPTION_STRING_ENCODING, "UTF-16LE"));

    int PdhOpenQueryW(String dataSource, Pointer userData, PointerByReference query);
    int PdhCloseQuery(Pointer query);
    int PdhCollectQueryData(Pointer query);

    int PdhAddEnglishCounterW(
        Pointer query, String path, Pointer userData, PointerByReference counter);

    int PdhGetFormattedCounterArrayW(
        Pointer counter,
        int format,
        IntByReference bufferSize,
        IntByReference itemCount,
        Pointer itemBuffer);
  }

  public static final class FmtValue extends Structure {
    public int cStatus;
    public Value value;

    public static final class Value extends Union {
      public double doubleValue;
      public long largeValue;
      public int longValue;
    }

    @Override
    protected List<String> getFieldOrder() {
      return List.of("cStatus", "value");
    }
  }

  public static final class FmtItem extends Structure {
    public Pointer szName;
    public FmtValue fmtValue;

    public FmtItem() {
      super();
    }

    public FmtItem(Pointer pointer) {
      super(pointer);
      read();
    }

    @Override
    protected List<String> getFieldOrder() {
      return List.of("szName", "fmtValue");
    }
  }

  private Pointer query;
  private Pointer utilizationCounter;
  private Pointer dedicatedCounter;
  private Pointer sharedCounter;
  private boolean available;

  // Memory counters do not need to be read every second.
  private long lastMemoryReadNanos;
  private long cachedDedicated = -1;
  private long cachedShared = -1;

  public WindowsPdhGpuTelemetry() {
    open();
  }

  public boolean isAvailable() {
    return available;
  }

  public synchronized Sample sample() {
    if (!available || query == null) return Sample.unavailable();

    if (Pdh.INSTANCE.PdhCollectQueryData(query) != ERROR_SUCCESS) {
      return Sample.unavailable();
    }

    List<Item> utilizationItems = readArray(utilizationCounter);
    double utilization = maxValue(utilizationItems);

    long now = System.nanoTime();
    if (lastMemoryReadNanos == 0 || now - lastMemoryReadNanos >= 3_000_000_000L) {
      cachedDedicated = sumValues(readArray(dedicatedCounter));
      cachedShared = sumValues(readArray(sharedCounter));
      lastMemoryReadNanos = now;
    }

    return new Sample(utilization, cachedDedicated, cachedShared);
  }

  private static double maxValue(List<Item> items) {
    double max = -1;
    for (Item item : items) {
      if (item.value >= 0 && Double.isFinite(item.value)) max = Math.max(max, item.value);
    }
    return max < 0 ? -1 : Math.min(100, max);
  }

  private static long sumValues(List<Item> items) {
    double total = 0;
    boolean any = false;
    for (Item item : items) {
      if (item.value >= 0 && Double.isFinite(item.value)) {
        total += item.value;
        any = true;
      }
    }
    return any ? (long) total : -1;
  }


  private void open() {
    if (!Platform.isWindows()) return;

    PointerByReference queryRef = new PointerByReference();
    int openStatus = Pdh.INSTANCE.PdhOpenQueryW(null, null, queryRef);
    if (openStatus != ERROR_SUCCESS) return;
    query = queryRef.getValue();

    utilizationCounter =
        add("\\GPU Engine(*)\\Utilization Percentage");
    dedicatedCounter =
        add("\\GPU Adapter Memory(*)\\Dedicated Usage");
    sharedCounter =
        add("\\GPU Adapter Memory(*)\\Shared Usage");

    available =
        utilizationCounter != null
            || dedicatedCounter != null
            || sharedCounter != null;

    // Rate counters need an initial collection.
    if (available) Pdh.INSTANCE.PdhCollectQueryData(query);
  }

  private Pointer add(String path) {
    PointerByReference ref = new PointerByReference();
    int status =
        Pdh.INSTANCE.PdhAddEnglishCounterW(query, path, null, ref);
    return status == ERROR_SUCCESS ? ref.getValue() : null;
  }

  private double readUtilization() {
    List<Item> items = readArray(utilizationCounter);
    if (items.isEmpty()) return -1;

    /*
     * GPU Engine utilization cannot simply be summed: Windows exposes multiple
     * engines and duplicate/process instances. Summing them was the expensive
     * and misleading behaviour of the old implementation.
     *
     * For the UI meter we use the busiest active engine. It is cheap, stable,
     * and never produces artificial values > 100%.
     */
    double max = -1;
    for (Item item : items) {
      if (item.value >= 0 && Double.isFinite(item.value)) {
        max = Math.max(max, item.value);
      }
    }
    return max < 0 ? -1 : Math.min(100, max);
  }

  private long readBytes(Pointer counter) {
    List<Item> items = readArray(counter);
    if (items.isEmpty()) return -1;

    double total = 0;
    boolean any = false;
    for (Item item : items) {
      if (item.value >= 0 && Double.isFinite(item.value)) {
        total += item.value;
        any = true;
      }
    }
    return any ? (long) total : -1;
  }

  private List<Item> readArray(Pointer counter) {
    if (counter == null) return List.of();

    IntByReference size = new IntByReference(0);
    IntByReference count = new IntByReference(0);

    int status = Pdh.INSTANCE.PdhGetFormattedCounterArrayW(
        counter, PDH_FMT_DOUBLE, size, count, null);

    if (status != PDH_MORE_DATA || size.getValue() <= 0 || count.getValue() <= 0) {
      return List.of();
    }

    Memory buffer = new Memory(size.getValue());
    status = Pdh.INSTANCE.PdhGetFormattedCounterArrayW(
        counter, PDH_FMT_DOUBLE, size, count, buffer);
    if (status != ERROR_SUCCESS) return List.of();

    FmtItem first = new FmtItem(buffer);
    FmtItem[] nativeItems = (FmtItem[]) first.toArray(count.getValue());

    List<Item> result = new ArrayList<>(nativeItems.length);
    for (FmtItem nativeItem : nativeItems) {
      nativeItem.read();
      nativeItem.fmtValue.read();
      if (nativeItem.fmtValue.cStatus != ERROR_SUCCESS) continue;

      nativeItem.fmtValue.value.setType(double.class);
      nativeItem.fmtValue.value.read();

      String name = nativeItem.szName == null ? "" : nativeItem.szName.getWideString(0);
      result.add(new Item(name, nativeItem.fmtValue.value.doubleValue));
    }
    return result;
  }


  @Override
  public synchronized void close() {
    if (query != null) {
      Pdh.INSTANCE.PdhCloseQuery(query);
      query = null;
    }

    utilizationCounter = null;
    dedicatedCounter = null;
    sharedCounter = null;
    available = false;
  }

  private record Item(String name, double value) {}

  public record Sample(
      double utilizationPercent,
      long dedicatedBytes,
      long sharedBytes) {

    public static Sample unavailable() {
      return new Sample(-1, -1, -1);
    }

    public long totalUsedBytes() {
      if (dedicatedBytes < 0 && sharedBytes < 0) return -1;
      return Math.max(0, dedicatedBytes) + Math.max(0, sharedBytes);
    }
  }
}
