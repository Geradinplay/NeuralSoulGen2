package com.neuralsoul.web;

import com.neuralsoul.config.ConfigLocator;
import com.neuralsoul.service.http.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Persistent JSON journal for the Internet workspace search history. */
public final class WebSearchHistoryStore {
  public static final String FILE_NAME = "web-search-history.json";

  private final Path file;
  private final List<WebSearchRecord> records = new ArrayList<>();
  private boolean loaded;

  public WebSearchHistoryStore() {
    this(ConfigLocator.getInstance().directory().resolve(FILE_NAME));
  }

  WebSearchHistoryStore(Path file) {
    this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
  }

  public Path file() {
    return file;
  }

  public synchronized List<WebSearchRecord> load() {
    ensureLoaded();
    return List.copyOf(records);
  }

  public synchronized void append(WebSearchRecord record) {
    if (record == null) return;
    ensureLoaded();
    records.add(record);
    save();
  }

  public synchronized void clear() {
    ensureLoaded();
    records.clear();
    save();
  }

  private void ensureLoaded() {
    if (loaded) return;
    loaded = true;
    records.clear();
    if (!Files.isRegularFile(file)) {
      save();
      return;
    }

    try {
      String source = Files.readString(file, StandardCharsets.UTF_8);
      if (source.isBlank()) return;
      Map<String, Object> root = Json.object(Json.read(source));
      Object rawSearches = root.get("searches");
      if (!(rawSearches instanceof List<?> searches)) return;
      for (Object raw : searches) {
        WebSearchRecord parsed = parseRecord(raw);
        if (parsed != null) records.add(parsed);
      }
      System.out.println("[WebSearch/History] loaded=" + records.size() + " file=" + file);
    } catch (Exception e) {
      System.out.println("[WebSearch/History] load failed file=" + file + " error=" + e);
      // Keep the corrupt file for inspection instead of destroying it automatically.
    }
  }

  private void save() {
    try {
      Files.createDirectories(file.getParent());
      Map<String, Object> root = new LinkedHashMap<>();
      root.put("version", 1);
      List<Object> searches = new ArrayList<>(records.size());
      for (WebSearchRecord record : records) searches.add(toJson(record));
      root.put("searches", searches);

      String json = Json.write(root);
      Path temp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(temp, json, StandardCharsets.UTF_8,
          StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
      try {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Cannot save Web Search history to " + file, e);
    }
  }

  private static Map<String, Object> toJson(WebSearchRecord record) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("timestamp", record.timestamp().toString());
    json.put("query", record.query());
    json.put("provider", record.provider());
    List<Object> hits = new ArrayList<>(record.hits().size());
    for (SearchHit hit : record.hits()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("title", hit.title());
      item.put("url", hit.url());
      item.put("snippet", hit.snippet());
      hits.add(item);
    }
    json.put("hits", hits);
    return json;
  }

  private static WebSearchRecord parseRecord(Object raw) {
    if (!(raw instanceof Map<?, ?> map)) return null;
    String timestamp = string(map.get("timestamp"));
    String query = string(map.get("query"));
    String provider = string(map.get("provider"));
    Instant instant;
    try {
      instant = timestamp.isBlank() ? Instant.now() : Instant.parse(timestamp);
    } catch (Exception ignored) {
      instant = Instant.now();
    }

    List<SearchHit> hits = new ArrayList<>();
    Object rawHits = map.get("hits");
    if (rawHits instanceof List<?> list) {
      for (Object rawHit : list) {
        if (!(rawHit instanceof Map<?, ?> hit)) continue;
        hits.add(new SearchHit(
            string(hit.get("title")),
            string(hit.get("url")),
            string(hit.get("snippet"))));
      }
    }
    return new WebSearchRecord(instant, query, provider, hits);
  }

  private static String string(Object value) {
    return value instanceof String text ? text : "";
  }
}
