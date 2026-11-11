package com.neuralsoul.config;

import java.nio.file.*;
import java.util.*;
import java.util.prefs.Preferences;

public final class GuiConfigLoader extends PropertiesConfigLoader<GuiConfig> {
  public GuiConfigLoader(Path path) {
    super(path);
  }

  protected GuiConfig decode(Properties p) {
    Map<String, String> values = new LinkedHashMap<>();
    if (!Files.exists(path())) {
      try {
        Preferences old = Preferences.userRoot().node("com/neuralsoul/desktop-concept");
        for (String key : old.keys()) values.put(key, old.get(key, ""));
      } catch (java.util.prefs.BackingStoreException ignored) {
      }
    }
    p.forEach((k, v) -> values.put(k.toString(), v.toString()));
    return new GuiConfig(values);
  }

  protected Properties encode(GuiConfig c) {
    Properties p = new Properties();
    p.putAll(c.values());
    return p;
  }
}
