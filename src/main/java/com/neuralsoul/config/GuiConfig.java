package com.neuralsoul.config;

import java.util.Map;

public record GuiConfig(Map<String, String> values) {
  public GuiConfig {
    values = Map.copyOf(values);
  }
}
