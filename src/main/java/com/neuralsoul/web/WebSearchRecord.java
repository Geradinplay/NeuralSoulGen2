package com.neuralsoul.web;

import java.time.Instant;
import java.util.List;

/** One real WEB_SEARCH execution exposed to the Internet workspace in the GUI. */
public record WebSearchRecord(Instant timestamp, String query, String provider, List<SearchHit> hits) {
  public WebSearchRecord {
    timestamp = timestamp == null ? Instant.now() : timestamp;
    query = query == null ? "" : query.strip();
    provider = provider == null ? "" : provider.strip();
    hits = hits == null ? List.of() : List.copyOf(hits);
  }
}
