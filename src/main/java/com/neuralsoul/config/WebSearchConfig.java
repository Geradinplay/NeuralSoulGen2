package com.neuralsoul.config;

import java.util.Locale;

/** Configuration of the web-search backend used by the WEB_SEARCH sub-agent. */
public record WebSearchConfig(
    Provider primaryProvider,
    boolean fallbackEnabled,
    String braveApiKeyEnv,
    String searxngBaseUrl,
    int timeoutSeconds) {

  public enum Provider {
    AUTO("Авто"),
    BRAVE("Brave Search API"),
    SEARXNG("SearXNG"),
    BING("Bing HTML"),
    DUCKDUCKGO("DuckDuckGo HTML");

    private final String title;
    Provider(String title) { this.title = title; }
    public String title() { return title; }
    @Override public String toString() { return title; }

    public static Provider parse(String value) {
      if (value == null || value.isBlank()) return AUTO;
      try { return valueOf(value.strip().toUpperCase(Locale.ROOT)); }
      catch (IllegalArgumentException ignored) { return AUTO; }
    }
  }

  public WebSearchConfig {
    primaryProvider = primaryProvider == null ? Provider.AUTO : primaryProvider;
    braveApiKeyEnv = braveApiKeyEnv == null ? "" : braveApiKeyEnv.strip();
    searxngBaseUrl = searxngBaseUrl == null ? "" : searxngBaseUrl.strip();
    if (!searxngBaseUrl.isBlank()) {
      if (!searxngBaseUrl.startsWith("http://") && !searxngBaseUrl.startsWith("https://"))
        throw new IllegalArgumentException("SearXNG Base URL должен начинаться с http:// или https://");
      while (searxngBaseUrl.endsWith("/")) searxngBaseUrl = searxngBaseUrl.substring(0, searxngBaseUrl.length() - 1);
    }
    if (timeoutSeconds < 3 || timeoutSeconds > 120)
      throw new IllegalArgumentException("Web Search timeout должен быть 3..120 секунд");
  }

  public static WebSearchConfig defaults() {
    return new WebSearchConfig(Provider.AUTO, true, "BRAVE_SEARCH_API_KEY", "", 15);
  }
}
