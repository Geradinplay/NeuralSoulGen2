package com.neuralsoul.web;

import com.neuralsoul.service.http.Json;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

/** SearXNG JSON API provider. Works with a user-owned/self-hosted SearXNG endpoint. */
public final class SearxngSearchProvider implements SearchProvider {
  private final HttpClient http;
  private final Supplier<String> baseUrl;
  private final Supplier<Integer> timeoutSeconds;

  public SearxngSearchProvider(HttpClient http, Supplier<String> baseUrl, Supplier<Integer> timeoutSeconds) {
    this.http = Objects.requireNonNull(http);
    this.baseUrl = Objects.requireNonNull(baseUrl);
    this.timeoutSeconds = Objects.requireNonNull(timeoutSeconds);
  }

  @Override public String id() { return "searxng"; }
  @Override public boolean configured() {
    String base = baseUrl.get();
    return base != null && !base.isBlank();
  }

  @Override public List<SearchHit> search(String query, int limit) throws Exception {
    String base = baseUrl.get();
    if (base == null || base.isBlank()) throw new IllegalStateException("SearXNG Base URL is not configured");
    base = base.strip();
    while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
    URI uri = URI.create(base + "/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
        + "&format=json&language=all&safesearch=0");
    HttpRequest req = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(timeoutSeconds.get()))
        .header("Accept", "application/json")
        .header("User-Agent", WebToolService.BROWSER_UA)
        .GET().build();
    HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    System.out.println("[WebSearch/HTTP] provider=searxng status=" + res.statusCode()
        + " bodyChars=" + (res.body() == null ? 0 : res.body().length()));
    if (res.statusCode() < 200 || res.statusCode() >= 300)
      throw new IllegalStateException("SearXNG HTTP " + res.statusCode());

    Map<String, Object> root = Json.object(Json.read(res.body()));
    Object raw = root.get("results");
    if (!(raw instanceof List<?> results)) return List.of();
    List<SearchHit> hits = new ArrayList<>();
    int max = Math.max(1, limit);
    for (Object item : results) {
      if (!(item instanceof Map<?, ?> map)) continue;
      String title = string(map.get("title"));
      String url = string(map.get("url"));
      String content = string(map.get("content"));
      if (!url.isBlank() && !title.isBlank()) hits.add(new SearchHit(title, url, content));
      if (hits.size() >= max) break;
    }
    return hits;
  }

  private static String string(Object value) { return value instanceof String s ? s : ""; }
}
