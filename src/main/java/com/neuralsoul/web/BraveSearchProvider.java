package com.neuralsoul.web;

import com.neuralsoul.service.http.Json;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

/** Official Brave Search API provider. The API key itself stays in an environment variable. */
public final class BraveSearchProvider implements SearchProvider {
  private final HttpClient http;
  private final Supplier<String> apiKeyEnv;
  private final Supplier<Integer> timeoutSeconds;

  public BraveSearchProvider(HttpClient http, Supplier<String> apiKeyEnv, Supplier<Integer> timeoutSeconds) {
    this.http = Objects.requireNonNull(http);
    this.apiKeyEnv = Objects.requireNonNull(apiKeyEnv);
    this.timeoutSeconds = Objects.requireNonNull(timeoutSeconds);
  }

  @Override public String id() { return "brave"; }

  @Override public boolean configured() {
    String env = apiKeyEnv.get();
    return env != null && !env.isBlank() && System.getenv(env.strip()) != null
        && !System.getenv(env.strip()).isBlank();
  }

  @Override public List<SearchHit> search(String query, int limit) throws Exception {
    String env = apiKeyEnv.get();
    String key = env == null ? null : System.getenv(env.strip());
    if (key == null || key.isBlank()) throw new IllegalStateException("Brave API key env is not configured: " + env);
    int count = Math.max(1, Math.min(20, limit));
    URI uri = URI.create("https://api.search.brave.com/res/v1/web/search?q="
        + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&count=" + count);
    HttpRequest req = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(timeoutSeconds.get()))
        .header("Accept", "application/json")
        .header("X-Subscription-Token", key)
        .GET().build();
    HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    System.out.println("[WebSearch/HTTP] provider=brave status=" + res.statusCode()
        + " bodyChars=" + (res.body() == null ? 0 : res.body().length()));
    if (res.statusCode() < 200 || res.statusCode() >= 300)
      throw new IllegalStateException("Brave Search HTTP " + res.statusCode());

    Map<String, Object> root = Json.object(Json.read(res.body()));
    Object web = root.get("web");
    if (!(web instanceof Map<?, ?> webMap) || !(webMap.get("results") instanceof List<?> results))
      return List.of();
    List<SearchHit> hits = new ArrayList<>();
    for (Object item : results) {
      if (!(item instanceof Map<?, ?> map)) continue;
      String title = string(map.get("title"));
      String url = string(map.get("url"));
      String description = string(map.get("description"));
      if (!url.isBlank() && !title.isBlank()) hits.add(new SearchHit(title, url, description));
      if (hits.size() >= count) break;
    }
    return hits;
  }

  private static String string(Object value) { return value instanceof String s ? s : ""; }
}
