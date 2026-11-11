package com.neuralsoul.web;

import com.neuralsoul.service.http.Json;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

/**
 * Keyless factual discovery fallback backed by Wikimedia's public Action API.
 * This is not a replacement for a general web index; it gives the research agent
 * a stable source of candidate pages when browser-oriented search engines block automation.
 */
public final class WikipediaSearchProvider implements SearchProvider {
  private final HttpClient http;
  private final Supplier<Integer> timeoutSeconds;

  public WikipediaSearchProvider(HttpClient http, Supplier<Integer> timeoutSeconds) {
    this.http = Objects.requireNonNull(http);
    this.timeoutSeconds = Objects.requireNonNull(timeoutSeconds);
  }

  @Override public String id() { return "wikipedia"; }
  @Override public boolean configured() { return true; }

  @Override public List<SearchHit> search(String query, int limit) throws Exception {
    int max = Math.max(1, Math.min(20, limit));
    String language = containsCyrillic(query) ? "ru" : "en";
    List<SearchHit> hits = searchWiki(language, query, max);
    // English is a useful second pass for names/titles even when the original task is Russian.
    if (hits.isEmpty() && !"en".equals(language)) hits = searchWiki("en", query, max);
    return hits;
  }

  private List<SearchHit> searchWiki(String language, String query, int max) throws Exception {
    String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
    URI uri = URI.create("https://" + language + ".wikipedia.org/w/api.php"
        + "?action=query&list=search&srsearch=" + encoded
        + "&srnamespace=0&srlimit=" + max
        + "&srprop=snippet&format=json&formatversion=2&utf8=1");
    HttpRequest req = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(timeoutSeconds.get()))
        .header("Accept", "application/json")
        .header("User-Agent", WebToolService.BROWSER_UA)
        .GET().build();
    HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    System.out.println("[WebSearch/HTTP] provider=wikipedia lang=" + language
        + " status=" + res.statusCode() + " bodyChars=" + (res.body() == null ? 0 : res.body().length()));
    if (res.statusCode() < 200 || res.statusCode() >= 300)
      throw new IllegalStateException("Wikipedia Search HTTP " + res.statusCode());

    Map<String, Object> root = Json.object(Json.read(res.body()));
    Object queryObject = root.get("query");
    if (!(queryObject instanceof Map<?, ?> queryMap)) return List.of();
    Object raw = queryMap.get("search");
    if (!(raw instanceof List<?> results)) return List.of();

    List<SearchHit> hits = new ArrayList<>();
    for (Object item : results) {
      if (!(item instanceof Map<?, ?> map)) continue;
      String title = string(map.get("title"));
      if (title.isBlank()) continue;
      String snippet = cleanHtml(string(map.get("snippet")));
      String url = "https://" + language + ".wikipedia.org/wiki/"
          + URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8).replace("+", "%20");
      hits.add(new SearchHit(title, url, snippet));
      if (hits.size() >= max) break;
    }
    return hits;
  }

  private static boolean containsCyrillic(String value) {
    if (value == null) return false;
    return value.codePoints().anyMatch(cp -> cp >= 0x0400 && cp <= 0x052F);
  }

  private static String string(Object value) { return value instanceof String s ? s : ""; }

  private static String cleanHtml(String value) {
    if (value == null || value.isBlank()) return "";
    return value.replaceAll("(?is)<[^>]+>", " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replaceAll("\\s+", " ").trim();
  }
}
