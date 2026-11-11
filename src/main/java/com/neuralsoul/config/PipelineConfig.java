package com.neuralsoul.config;

public record PipelineConfig(
    int historyPairs, int maxContextCharacters, int microphoneSeconds, boolean autoConnect) {
  public PipelineConfig {
    if (historyPairs < 0 || historyPairs > 100)
      throw new IllegalArgumentException("История: 0–100 пар");
    if (maxContextCharacters < 1000 || maxContextCharacters > 500000)
      throw new IllegalArgumentException("Лимит контекста: 1000–500000 символов");
    if (microphoneSeconds < 1 || microphoneSeconds > 300)
      throw new IllegalArgumentException("Запись: 1–300 секунд");
  }
}
