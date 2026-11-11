package com.neuralsoul.config;

public record LlmConfig(
    EndpointConfig endpoint, String model, String systemPrompt, double temperature, int maxTokens) {
  public LlmConfig {
    if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2)
      throw new IllegalArgumentException("Temperature: 0–2");
    if (maxTokens < 1 || maxTokens > 131072)
      throw new IllegalArgumentException("Max tokens: 1–131072");
  }
}
