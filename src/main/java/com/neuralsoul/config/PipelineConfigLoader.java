package com.neuralsoul.config;

import java.nio.file.Path;
import java.util.Properties;

public final class PipelineConfigLoader extends PropertiesConfigLoader<PipelineConfig> {
  public PipelineConfigLoader(Path path) {
    super(path);
  }

  protected PipelineConfig decode(Properties p) {
    return new PipelineConfig(
        integer(p, "historyPairs", 10),
        integer(p, "maxContextCharacters", 24000),
        integer(p, "microphoneSeconds", 60),
        bool(p, "autoConnect", true));
  }

  protected Properties encode(PipelineConfig c) {
    return props(
        "historyPairs",
        c.historyPairs(),
        "maxContextCharacters",
        c.maxContextCharacters(),
        "microphoneSeconds",
        c.microphoneSeconds(),
        "autoConnect",
        c.autoConnect());
  }
}
