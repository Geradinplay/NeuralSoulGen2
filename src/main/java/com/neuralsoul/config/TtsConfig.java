package com.neuralsoul.config;

public record TtsConfig(
    EndpointConfig endpoint,
    Protocol protocol,
    String model,
    String voice,
    String language,
    double speed) {
  public enum Protocol {
    OPENAI,
    COQUI
  }

  public TtsConfig {
    if (!Double.isFinite(speed) || speed < 0.25 || speed > 4)
      throw new IllegalArgumentException("Скорость TTS: 0.25–4");
  }
}
