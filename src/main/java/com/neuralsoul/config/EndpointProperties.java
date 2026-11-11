package com.neuralsoul.config;

import java.util.Properties;

final class EndpointProperties {
  private EndpointProperties() {}

  static EndpointConfig read(Properties p, String base, String path, String health) {
    return new EndpointConfig(
        p.getProperty("baseUrl", base),
        p.getProperty("path", path),
        p.getProperty("healthPath", health),
        p.getProperty("apiKeyEnv", ""),
        Integer.parseInt(p.getProperty("timeoutSeconds", "120")));
  }

  static void write(Properties p, EndpointConfig e) {
    p.setProperty("baseUrl", e.baseUrl());
    p.setProperty("path", e.path());
    p.setProperty("healthPath", e.healthPath());
    p.setProperty("apiKeyEnv", e.apiKeyEnv());
    p.setProperty("timeoutSeconds", "" + e.timeoutSeconds());
  }
}
