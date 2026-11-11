package com.neuralsoul.service.audio;

import com.neuralsoul.config.ConfigLocator;
import com.neuralsoul.config.GuiConfig;
import java.util.*;
import javax.sound.sampled.*;

/** Enumerates and opens Java Sound capture/playback devices selected in the Voice page. */
public final class AudioDeviceManager {
  public static final String INPUT_KEY = "voice.inputDevice";
  public static final String OUTPUT_KEY = "voice.outputDevice";

  private AudioDeviceManager() {}

  public record Choice(String id, String label) {
    @Override public String toString() { return label; }
  }

  public static List<Choice> inputDevices() {
    return devices(true);
  }

  public static List<Choice> outputDevices() {
    return devices(false);
  }

  public static String selectedInputId(ConfigLocator configs) {
    return selected(configs, INPUT_KEY);
  }

  public static String selectedOutputId(ConfigLocator configs) {
    return selected(configs, OUTPUT_KEY);
  }

  public static void saveSelection(ConfigLocator configs, String inputId, String outputId) {
    Objects.requireNonNull(configs);
    Map<String, String> values = new LinkedHashMap<>(configs.gui().values());
    values.put(INPUT_KEY, clean(inputId));
    values.put(OUTPUT_KEY, clean(outputId));
    configs.saveGui(new GuiConfig(values));
  }

  public static TargetDataLine targetLine(ConfigLocator configs, AudioFormat format) throws LineUnavailableException {
    String id = selectedInputId(configs);
    DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, format);
    if (id.isBlank()) return AudioSystem.getTargetDataLine(format);
    Mixer mixer = mixer(id);
    if (mixer == null) return AudioSystem.getTargetDataLine(format);
    if (!mixer.isLineSupported(lineInfo))
      throw new LineUnavailableException("Выбранный микрофон не поддерживает формат 16 kHz mono PCM: " + label(mixer.getMixerInfo()));
    return (TargetDataLine) mixer.getLine(lineInfo);
  }

  public static SourceDataLine sourceLine(ConfigLocator configs, AudioFormat format) throws LineUnavailableException {
    String id = selectedOutputId(configs);
    DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, format);
    if (id.isBlank()) return AudioSystem.getSourceDataLine(format);
    Mixer mixer = mixer(id);
    if (mixer == null) return AudioSystem.getSourceDataLine(format);
    if (!mixer.isLineSupported(lineInfo))
      throw new LineUnavailableException("Выбранное устройство вывода не поддерживает формат аудио: " + label(mixer.getMixerInfo()));
    return (SourceDataLine) mixer.getLine(lineInfo);
  }

  private static List<Choice> devices(boolean input) {
    List<Choice> result = new ArrayList<>();
    result.add(new Choice("", "Системное устройство по умолчанию"));
    for (Mixer.Info info : AudioSystem.getMixerInfo()) {
      try {
        Mixer mixer = AudioSystem.getMixer(info);
        if (hasDataLine(mixer, input)) result.add(new Choice(id(info), label(info)));
      } catch (Exception ignored) {}
    }
    return result;
  }

  private static boolean hasDataLine(Mixer mixer, boolean input) {
    Line.Info[] infos = input ? mixer.getTargetLineInfo() : mixer.getSourceLineInfo();
    Class<?> wanted = input ? TargetDataLine.class : SourceDataLine.class;
    for (Line.Info info : infos) {
      if (wanted.isAssignableFrom(info.getLineClass())) return true;
    }
    return false;
  }

  private static Mixer mixer(String id) {
    for (Mixer.Info info : AudioSystem.getMixerInfo()) {
      if (id(info).equals(id)) return AudioSystem.getMixer(info);
    }
    return null;
  }

  private static String selected(ConfigLocator configs, String key) {
    if (configs == null) return "";
    try { return clean(configs.gui().values().get(key)); }
    catch (Exception ignored) { return ""; }
  }

  private static String id(Mixer.Info info) {
    return clean(info.getName()) + "\u001f" + clean(info.getVendor()) + "\u001f" + clean(info.getVersion()) + "\u001f" + clean(info.getDescription());
  }

  private static String label(Mixer.Info info) {
    String name = clean(info.getName());
    String vendor = clean(info.getVendor());
    return vendor.isBlank() || name.toLowerCase(Locale.ROOT).contains(vendor.toLowerCase(Locale.ROOT))
        ? name : name + " · " + vendor;
  }

  private static String clean(String value) { return value == null ? "" : value.strip(); }
}
