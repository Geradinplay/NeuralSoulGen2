package com.neuralsoul.config;

import java.nio.file.Path;
import java.util.Properties;

public final class WebSearchConfigLoader extends PropertiesConfigLoader<WebSearchConfig> {
  public WebSearchConfigLoader(Path path) { super(path); }

  @Override protected WebSearchConfig decode(Properties p) {
    WebSearchConfig d = WebSearchConfig.defaults();
    return new WebSearchConfig(
        WebSearchConfig.Provider.parse(str(p, "primaryProvider", d.primaryProvider().name())),
        bool(p, "fallbackEnabled", d.fallbackEnabled()),
        str(p, "braveApiKeyEnv", d.braveApiKeyEnv()),
        str(p, "searxngBaseUrl", d.searxngBaseUrl()),
        integer(p, "timeoutSeconds", d.timeoutSeconds()));
  }

  @Override protected Properties encode(WebSearchConfig c) {
    return props(
        "primaryProvider", c.primaryProvider().name(),
        "fallbackEnabled", c.fallbackEnabled(),
        "braveApiKeyEnv", c.braveApiKeyEnv(),
        "searxngBaseUrl", c.searxngBaseUrl(),
        "timeoutSeconds", c.timeoutSeconds());
  }
}
