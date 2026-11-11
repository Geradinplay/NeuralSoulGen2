package com.neuralsoul.web;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.*;

/** Best-effort no-key general-web fallback using Mojeek's public search page. */
public final class MojeekSearchProvider implements SearchProvider {
  private final HttpClient http;
  private final Supplier<Integer> timeoutSeconds;

  public MojeekSearchProvider(HttpClient http, Supplier<Integer> timeoutSeconds) {
    this.http = Objects.requireNonNull(http);
    this.timeoutSeconds = Objects.requireNonNull(timeoutSeconds);
  }
  @Override public String id() { return "mojeek"; }
  @Override public boolean configured() { return true; }

  @Override public List<SearchHit> search(String query, int limit) throws Exception {
    int max = Math.max(1, Math.min(20, limit));
    URI uri = URI.create("https://www.mojeek.com/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
    HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds.get()))
        .header("User-Agent", WebToolService.BROWSER_UA)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "en-US,en;q=0.9,ru;q=0.8").GET().build();
    HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    String html = res.body() == null ? "" : res.body();
    System.out.println("[WebSearch/HTTP] provider=mojeek status=" + res.statusCode() + " bodyChars=" + html.length());
    if (res.statusCode() < 200 || res.statusCode() >= 300)
      throw new IllegalStateException("Mojeek Search HTTP " + res.statusCode());
    if (isChallenge(html)) throw new IllegalStateException("Mojeek challenge page returned instead of search results");
    return parseExternalLinks(html, max);
  }

  static List<SearchHit> parseExternalLinks(String html, int max) {
    Pattern anchor = Pattern.compile("(?is)<a\\b([^>]*)>(.*?)</a>");
    Pattern href = Pattern.compile("(?is)\\bhref\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']");
    Matcher anchors = anchor.matcher(html == null ? "" : html);
    LinkedHashMap<String, SearchHit> hits = new LinkedHashMap<>();
    while (anchors.find() && hits.size() < max) {
      Matcher hm = href.matcher(anchors.group(1));
      if (!hm.find()) continue;
      String url = unescape(hm.group(1)).trim();
      if (url.startsWith("//")) url = "https:" + url;
      if (!url.startsWith("http://") && !url.startsWith("https://")) continue;
      try {
        String host = URI.create(url).getHost();
        if (host == null || host.endsWith("mojeek.com")) continue;
      } catch (Exception ignored) { continue; }
      String title = cleanHtml(anchors.group(2));
      if (title.length() < 2) continue;
      hits.putIfAbsent(url, new SearchHit(title, url, ""));
    }
    return new ArrayList<>(hits.values());
  }

  private static boolean isChallenge(String html) {
    String lower = html == null ? "" : html.toLowerCase(Locale.ROOT);
    return lower.contains("captcha") || lower.contains("verify you are human")
        || lower.contains("unusual traffic") || lower.contains("challenge-form");
  }
  private static String cleanHtml(String s) {
    return unescape((s == null ? "" : s).replaceAll("(?is)<script.*?</script>", " ")
        .replaceAll("(?is)<style.*?</style>", " ").replaceAll("(?is)<[^>]+>", " ")
        .replaceAll("\\s+", " ").trim());
  }
  private static String unescape(String s) {
    return (s == null ? "" : s).replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
  }
}
