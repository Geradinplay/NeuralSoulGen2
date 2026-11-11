package com.neuralsoul.config;

import java.nio.file.Path;
import java.util.Properties;

public final class LlmConfigLoader extends PropertiesConfigLoader<LlmConfig> {
  public LlmConfigLoader(Path path) {
    super(path);
  }

  protected LlmConfig decode(Properties p) {
    return new LlmConfig(
        EndpointProperties.read(p, "http://127.0.0.1:8080", "/v1/chat/completions", "/v1/models"),
        str(p, "model", ""),
        str(
            p,
            "systemPrompt",
            "Ты Нина, дружелюбный локальный AI-помощник. Отвечай по-русски, ясно и по делу."),
        number(p, "temperature", 0.7),
        integer(p, "maxTokens", 1024));
  }

  protected Properties encode(LlmConfig c) {
    Properties p =
        props(
            "model",
            c.model(),
            "systemPrompt",
            c.systemPrompt(),
            "temperature",
            c.temperature(),
            "maxTokens",
            c.maxTokens());
    EndpointProperties.write(p, c.endpoint());
    return p;
  }
}
