package com.neuralsoul.config;

import java.nio.file.Path;
import java.util.Properties;

public final class TtsConfigLoader extends PropertiesConfigLoader<TtsConfig> {
  public TtsConfigLoader(Path path) {
    super(path);
  }

  protected TtsConfig decode(Properties p) {
    return new TtsConfig(
        EndpointProperties.read(p, "http://127.0.0.1:5002", "/api/tts", ""),
        TtsConfig.Protocol.valueOf(str(p, "protocol", "COQUI")),
        str(p, "model", "tts-1"),
        str(p, "voice", ""),
        str(p, "language", "ru"),
        number(p, "speed", 1));
  }

  protected Properties encode(TtsConfig c) {
    Properties p =
        props(
            "protocol",
            c.protocol(),
            "model",
            c.model(),
            "voice",
            c.voice(),
            "language",
            c.language(),
            "speed",
            c.speed());
    EndpointProperties.write(p, c.endpoint());
    return p;
  }
}
