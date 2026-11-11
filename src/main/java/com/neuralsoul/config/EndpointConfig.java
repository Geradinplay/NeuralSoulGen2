package com.neuralsoul.config;

import java.net.URI;

public record EndpointConfig(
    String baseUrl, String path, String healthPath, String apiKeyEnv, int timeoutSeconds) {
  public EndpointConfig {
    baseUrl = baseUrl.strip().replaceAll("/+$", "");
    URI uri = URI.create(baseUrl);
    if (!java.util.Set.of("http", "https").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null)
      throw new IllegalArgumentException(
          "Нужен HTTP(S) адрес сервера без логина, query и fragment");
    if (!path.startsWith("/") || (!healthPath.isBlank() && !healthPath.startsWith("/")))
      throw new IllegalArgumentException("API paths должны начинаться с /");
    if (timeoutSeconds < 1 || timeoutSeconds > 1800)
      throw new IllegalArgumentException("Таймаут: 1–1800 секунд");
  }

  public URI uri(String route) {
    return URI.create(baseUrl + route);
  }
}
