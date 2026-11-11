package com.neuralsoul.service.tts;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Enumerates Vulkan devices using the Vulkan loader rather than assuming that
 * Windows/nvidia-smi GPU indices match vkEnumeratePhysicalDevices indices.
 *
 * No Vulkan device is created; the temporary instance is destroyed immediately.
 */
public final class VulkanDeviceSelector {
  private VulkanDeviceSelector() {}

  public record Device(int index, String name, int vendorId, int deviceId) {}

  private interface Vulkan extends Library {
    int vkCreateInstance(CreateInfo createInfo, Pointer allocator, PointerByReference instance);
    int vkEnumeratePhysicalDevices(Pointer instance, IntByReference count, Pointer[] devices);
    void vkGetPhysicalDeviceProperties(Pointer device, Pointer properties);
    void vkDestroyInstance(Pointer instance, Pointer allocator);
  }

  /** VkInstanceCreateInfo (Vulkan 1.0 core). */
  public static final class CreateInfo extends Structure {
    public int sType = 1; // VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO
    public Pointer pNext;
    public int flags;
    public Pointer pApplicationInfo;
    public int enabledLayerCount;
    public Pointer ppEnabledLayerNames;
    public int enabledExtensionCount;
    public Pointer ppEnabledExtensionNames;
    @Override protected List<String> getFieldOrder() {
      return List.of("sType", "pNext", "flags", "pApplicationInfo", "enabledLayerCount",
          "ppEnabledLayerNames", "enabledExtensionCount", "ppEnabledExtensionNames");
    }
  }

  public static List<Device> enumerate() {
    Vulkan lib;
    try {
      String library = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
          ? "vulkan-1" : "vulkan";
      lib = Native.load(library, Vulkan.class);
    } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
      throw new IllegalStateException("Не найден Vulkan loader (vulkan-1.dll): " + e.getMessage(), e);
    }

    PointerByReference instanceRef = new PointerByReference();
    int result = lib.vkCreateInstance(new CreateInfo(), null, instanceRef);
    if (result != 0 || instanceRef.getValue() == null)
      throw new IllegalStateException("vkCreateInstance завершился с кодом " + result);
    Pointer instance = instanceRef.getValue();
    try {
      IntByReference countRef = new IntByReference();
      result = lib.vkEnumeratePhysicalDevices(instance, countRef, null);
      if (result != 0) throw new IllegalStateException("vkEnumeratePhysicalDevices(count)=" + result);
      if (countRef.getValue() <= 0) throw new IllegalStateException("Vulkan не обнаружил GPU");
      Pointer[] physical = new Pointer[countRef.getValue()];
      result = lib.vkEnumeratePhysicalDevices(instance, countRef, physical);
      if (result != 0 && result != 5) // VK_INCOMPLETE is 5
        throw new IllegalStateException("vkEnumeratePhysicalDevices(list)=" + result);
      List<Device> devices = new ArrayList<>();
      for (int i = 0; i < Math.min(countRef.getValue(), physical.length); i++) {
        // VkPhysicalDeviceProperties starts with five uint32 fields, followed by
        // a null-terminated deviceName[256]. Allocate ample room for VkPhysicalDeviceLimits.
        Memory properties = new Memory(4096);
        properties.clear();
        lib.vkGetPhysicalDeviceProperties(physical[i], properties);
        devices.add(new Device(i, properties.getString(20, "UTF-8"),
            properties.getInt(8), properties.getInt(12)));
      }
      return List.copyOf(devices);
    } finally {
      lib.vkDestroyInstance(instance, null);
    }
  }

  /** Refuses a guessed index if physical-device names are ambiguous or absent. */
  public static int selectByName(String requestedName, List<Device> devices) {
    String target = normalizeName(requestedName);
    List<Device> matches = devices.stream()
        .filter(d -> normalizeName(d.name()).equals(target)).toList();
    if (matches.size() != 1)
      throw new IllegalStateException("Не удалось однозначно сопоставить выбранную GPU '"
          + requestedName + "' с Vulkan устройствами " + devices + ". Найдено совпадений: "
          + matches.size() + ". Выбери CUDA для NVIDIA или проверь Vulkan драйверы.");
    return matches.get(0).index();
  }

  private static String normalizeName(String name) {
    if (name == null) return "";
    return name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{Alnum}]", "")
        .replace("nvidiacorporation", "nvidia");
  }
}
