package com.neuralsoul.web;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.*;

/** No-key Bing fallback. Prefer the simpler RSS representation, then fall back to HTML. */
public final class BingSearchProvider implements SearchProvider {
  private final HttpClient http;
  private final Supplier<Integer> timeoutSeconds;

  public BingSearchProvider(HttpClient http, Supplier<Integer> timeoutSeconds) {
    this.http = Objects.requireNonNull(http);
    this.timeoutSeconds = Objects.requireNonNull(timeoutSeconds);
  }

  @Override public String id() { return "bing"; }
  @Override public boolean configured() { return true; }

  @Override public List<SearchHit> search(String query, int limit) throws Exception {
    int max = Math.max(1, Math.min(20, limit));
    String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);

    // Bing's normal HTML changes frequently. The RSS representation is much smaller and
    // easier to parse when it is available, so try it first.
    URI rssUri = URI.create("https://www.bing.com/search?q=" + encoded + "&format=rss&setlang=en");
    HttpResponse<String> rss = send(rssUri, "application/rss+xml,application/xml,text/xml;q=0.9,*/*;q=0.7");
    String rssBody = rss.body() == null ? "" : rss.body();
    System.out.println("[WebSearch/HTTP] provider=bing mode=rss status=" + rss.statusCode()
        + " bodyChars=" + rssBody.length());
    if (rss.statusCode() >= 200 && rss.statusCode() < 300 && !isChallenge(rssBody)) {
      List<SearchHit> hits = parseRss(rssBody, max);
      if (!hits.isEmpty()) {
        System.out.println("[WebSearch/HTTP] provider=bing mode=rss parsed=" + hits.size());
        return hits;
      }
      System.out.println("[WebSearch/HTTP] provider=bing mode=rss zero parsed results; falling back to html");
    } else if (isChallenge(rssBody)) {
      System.out.println("[WebSearch/HTTP] provider=bing mode=rss challenge detected; falling back to html");
    }

    URI htmlUri = URI.create("https://www.bing.com/search?q=" + encoded + "&count=" + max + "&setlang=en");
    HttpResponse<String> res = send(htmlUri, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
    String html = res.body() == null ? "" : res.body();
    System.out.println("[WebSearch/HTTP] provider=bing mode=html status=" + res.statusCode()
        + " bodyChars=" + html.length());
    if (res.statusCode() < 200 || res.statusCode() >= 300)
      throw new IllegalStateException("Bing Search HTTP " + res.statusCode());
    if (isChallenge(html))
      throw new IllegalStateException("Bing anti-bot/challenge page returned instead of search results");

    List<SearchHit> hits = parseHtml(html, max);
    if (hits.isEmpty()) {
      String preview = cleanHtml(html.substring(0, Math.min(700, html.length())));
      System.out.println("[WebSearch/HTTP] provider=bing mode=html zero parsed results; b_algo="
          + html.contains("b_algo") + " preview=" + preview);
    }
    return hits;
  }

  private HttpResponse<String> send(URI uri, String accept) throws Exception {
    HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds.get()))
        .header("User-Agent", WebToolService.BROWSER_UA)
        .header("Accept", accept)
        .header("Accept-Language", "en-US,en;q=0.9,ru;q=0.8")
        .header("Cache-Control", "no-cache")
        .GET().build();
    return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  static List<SearchHit> parseRss(String xml, int max) {
    Pattern itemPattern = Pattern.compile("(?is)<item\\b[^>]*>(.*?)</item>");
    Pattern titlePattern = Pattern.compile("(?is)<title\\b[^>]*>(.*?)</title>");
    Pattern linkPattern = Pattern.compile("(?is)<link\\b[^>]*>(.*?)</link>");
    Pattern descriptionPattern = Pattern.compile("(?is)<description\\b[^>]*>(.*?)</description>");
    Matcher items = itemPattern.matcher(xml == null ? "" : xml);
    List<SearchHit> hits = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    while (items.find() && hits.size() < max) {
      String item = items.group(1);
      String title = group(titlePattern, item);
      String url = cleanXmlText(group(linkPattern, item));
      String snippet = cleanHtml(group(descriptionPattern, item));
      title = cleanHtml(title);
      if (!url.startsWith("http") || title.isBlank() || !seen.add(url)) continue;
      hits.add(new SearchHit(title, url, snippet));
    }
    return hits;
  }

  static List<SearchHit> parseHtml(String html, int max) {
    Pattern itemPattern = Pattern.compile("(?is)<li[^>]*class=[\\\"'][^\\\"']*\\bb_algo\\b[^\\\"']*[\\\"'][^>]*>(.*?)</li>");
    Pattern linkPattern = Pattern.compile("(?is)<h2[^>]*>\\s*<a[^>]*href=[\\\"']([^\\\"']+)[\\\"'][^>]*>(.*?)</a>\\s*</h2>");
    Pattern paragraphPattern = Pattern.compile("(?is)<p[^>]*>(.*?)</p>");
    Matcher items = itemPattern.matcher(html == null ? "" : html);
    List<SearchHit> hits = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    while (items.find() && hits.size() < max) {
      String item = items.group(1);
      Matcher link = linkPattern.matcher(item);
      if (!link.find()) continue;
      String url = unescape(link.group(1)).trim();
      String title = cleanHtml(link.group(2));
      if (!url.startsWith("http") || title.isBlank() || !seen.add(url)) continue;
      Matcher paragraph = paragraphPattern.matcher(item);
      String snippet = paragraph.find() ? cleanHtml(paragraph.group(1)) : "";
      hits.add(new SearchHit(title, url, snippet));
    }
    return hits;
  }

  private static String group(Pattern p, String text) {
    Matcher m = p.matcher(text == null ? "" : text);
    return m.find() ? m.group(1) : "";
  }

  private static boolean isChallenge(String body) {
    String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
    return lower.contains("captcha") || lower.contains("unusual traffic")
        || lower.contains("verify you are human") || lower.contains("challenge-form");
  }

  private static String cleanXmlText(String s) {
    String value = s == null ? "" : s.trim();
    if (value.startsWith("<![CDATA[") && value.endsWith("]]>") && value.length() >= 12)
      value = value.substring(9, value.length() - 3);
    return unescape(value).trim();
  }

  private static String cleanHtml(String s) {
    String value = s == null ? "" : s;
    value = value.replace("<![CDATA[", "").replace("]]>", "");
    return unescape(value
        .replaceAll("(?is)<script.*?</script>", " ")
        .replaceAll("(?is)<style.*?</style>", " ")
        .replaceAll("(?is)<[^>]+>", " ")
        .replaceAll("\\s+", " ").trim());
  }

  private static String unescape(String s) {
    return (s == null ? "" : s).replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&nbsp;", " ");
  }
}
