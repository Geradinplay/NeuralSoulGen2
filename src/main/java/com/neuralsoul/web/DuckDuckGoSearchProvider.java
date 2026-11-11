package com.neuralsoul.web;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.*;

/** Best-effort no-key fallback. DDG may challenge automated clients; this provider reports that explicitly. */
public final class DuckDuckGoSearchProvider implements SearchProvider {
  private final HttpClient http;
  private final Supplier<Integer> timeoutSeconds;

  public DuckDuckGoSearchProvider(HttpClient http, Supplier<Integer> timeoutSeconds) {
    this.http = Objects.requireNonNull(http);
    this.timeoutSeconds = Objects.requireNonNull(timeoutSeconds);
  }

  @Override public String id() { return "duckduckgo"; }
  @Override public boolean configured() { return true; }

  @Override public List<SearchHit> search(String query, int limit) throws Exception {
    String form = "q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&b=&kl=wt-wt";
    URI uri = URI.create("https://html.duckduckgo.com/html/");
    HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds.get()))
        .header("User-Agent", WebToolService.BROWSER_UA)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "en-US,en;q=0.9,ru;q=0.8")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Origin", "https://html.duckduckgo.com")
        .header("Referer", "https://html.duckduckgo.com/")
        .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build();
    HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    String html = res.body() == null ? "" : res.body();
    System.out.println("[WebSearch/HTTP] provider=duckduckgo status=" + res.statusCode()
        + " bodyChars=" + html.length());
    if (res.statusCode() < 200 || res.statusCode() >= 300)
      throw new IllegalStateException("DuckDuckGo Search HTTP " + res.statusCode());
    if (isChallenge(html)) {
      System.out.println("[WebSearch/HTTP] DuckDuckGo anti-bot/challenge page detected");
      throw new IllegalStateException("DuckDuckGo anti-bot challenge returned instead of search results");
    }

    Pattern anchor = Pattern.compile("<a\\b([^>]*)>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    Pattern href = Pattern.compile("\\bhref\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    Matcher anchors = anchor.matcher(html);
    Set<String> seen = new LinkedHashSet<>();
    List<SearchHit> hits = new ArrayList<>();
    int max = Math.max(1, limit);
    while (anchors.find() && hits.size() < max) {
      String attrs = anchors.group(1);
      if (!hasCssClass(attrs, "result__a")) continue;
      Matcher hm = href.matcher(attrs);
      if (!hm.find()) continue;
      String url = decodeDuckUrl(unescape(hm.group(1)));
      String title = cleanHtml(anchors.group(2));
      if (url.isBlank() || title.isBlank() || !seen.add(url)) continue;
      hits.add(new SearchHit(title, url, ""));
    }
    if (hits.isEmpty()) {
      System.out.println("[WebSearch/HTTP] provider=duckduckgo zero parsed results; resultMarker="
          + html.contains("result__a"));
    }
    return hits;
  }

  private static boolean hasCssClass(String attrs, String wanted) {
    Matcher cm = Pattern.compile("\\bclass\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
        .matcher(attrs == null ? "" : attrs);
    if (!cm.find()) return false;
    for (String css : cm.group(1).split("\\s+")) if (css.equalsIgnoreCase(wanted)) return true;
    return false;
  }

  private static boolean isChallenge(String html) {
    if (html == null || html.isBlank()) return false;
    String lower = html.toLowerCase(Locale.ROOT);
    return lower.contains("challenge-form") || lower.contains("anomaly-modal")
        || lower.contains("unusual traffic") || lower.contains("captcha");
  }

  private static String decodeDuckUrl(String href) {
    try {
      URI u = URI.create(href.startsWith("//") ? "https:" + href : href);
      String query = u.getRawQuery();
      if (query != null) for (String part : query.split("&")) {
        int eq = part.indexOf('=');
        if (eq > 0 && part.substring(0, eq).equals("uddg"))
          return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
      }
      return u.toString();
    } catch (Exception e) { return href; }
  }

  private static String cleanHtml(String s) {
    return unescape(s.replaceAll("(?is)<script.*?</script>", " ")
        .replaceAll("(?is)<style.*?</style>", " ")
        .replaceAll("(?is)<[^>]+>", " ")
        .replaceAll("\\s+", " ").trim());
  }

  private static String unescape(String s) {
    return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
  }
}
