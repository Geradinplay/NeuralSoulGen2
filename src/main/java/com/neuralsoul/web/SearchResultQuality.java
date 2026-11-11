package com.neuralsoul.web;

import java.net.URI;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cheap lexical gate that rejects obviously unrelated search-engine result pages. */
final class SearchResultQuality {
  private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]{2,}");
  private static final Set<String> STOP = Set.of(
      "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "how", "in", "is",
      "it", "of", "on", "or", "the", "to", "what", "when", "where", "which", "who", "with",
      "find", "search", "web", "online", "current", "latest", "author", "writer", "time",
      "official", "source", "sources", "real", "realtime", "clock", "localized", "alternative",
      "encyclopedic", "translation", "work", "creator", "manga", "anime", "novel",
      "автор", "время", "текущее", "текущий", "сейчас", "сегодня", "найди", "найти", "кто",
      "официальный", "официальная", "источник", "источники", "перевод", "произведение",
      "что", "где", "когда", "какой", "какая", "какие", "это", "для", "или", "про", "при",
      "как", "его", "ее", "её", "из", "на", "по", "во", "со");

  private SearchResultQuality() {}

  static List<SearchHit> filterRelevant(String query, List<SearchHit> hits, int limit) {
    if (hits == null || hits.isEmpty()) return List.of();
    List<String> anchors = anchors(query);
    if (anchors.isEmpty()) return dedupe(hits, limit);
    List<Scored> scored = new ArrayList<>();
    for (SearchHit hit : hits) {
      if (hit == null || hit.url().isBlank()) continue;
      String haystack = normalize(hit.title() + " " + hit.snippet() + " " + urlText(hit.url()));
      int matched = 0, strong = 0;
      for (String anchor : anchors) {
        if (containsTokenish(haystack, anchor)) {
          matched++;
          if (anchor.length() >= 5) strong++;
        }
      }
      double coverage = matched / (double) anchors.size();
      boolean relevant = (strong >= 1 && (anchors.size() <= 3 || coverage >= 0.20)) || coverage >= 0.45;
      if (relevant) scored.add(new Scored(hit, coverage + strong * 0.08));
    }
    scored.sort(Comparator.comparingDouble(Scored::score).reversed());
    List<SearchHit> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Scored item : scored) {
      if (seen.add(item.hit().url())) out.add(item.hit());
      if (out.size() >= Math.max(1, limit)) break;
    }
    return out;
  }

  static List<String> anchors(String query) {
    String normalized = normalize(query);
    LinkedHashSet<String> result = new LinkedHashSet<>();
    Matcher m = TOKEN.matcher(normalized);
    while (m.find()) {
      String token = m.group();
      if (token.length() < 3 || STOP.contains(token)) continue;
      result.add(token);
    }
    return List.copyOf(result);
  }

  private static List<SearchHit> dedupe(List<SearchHit> hits, int limit) {
    List<SearchHit> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (SearchHit hit : hits) {
      if (hit != null && !hit.url().isBlank() && seen.add(hit.url())) out.add(hit);
      if (out.size() >= Math.max(1, limit)) break;
    }
    return out;
  }

  private static String urlText(String url) {
    try {
      URI uri = URI.create(url);
      return (uri.getHost() == null ? "" : uri.getHost()) + " "
          + (uri.getPath() == null ? "" : uri.getPath().replace('/', ' '));
    } catch (Exception ignored) { return url; }
  }

  private static boolean containsTokenish(String haystack, String token) {
    if (haystack.contains(token)) return true;
    if (token.length() >= 6) {
      String stem = token.substring(0, Math.max(5, token.length() - 2));
      return haystack.contains(stem);
    }
    return false;
  }

  private static String normalize(String value) {
    String s = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT);
    return s.replace('ё', 'е');
  }

  private record Scored(SearchHit hit, double score) {}
}
