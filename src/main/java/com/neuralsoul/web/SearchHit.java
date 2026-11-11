package com.neuralsoul.web;

/** Provider-neutral web search result. */
public record SearchHit(String title, String url, String snippet) {
  public SearchHit {
    title = title == null ? "" : title.strip();
    url = url == null ? "" : url.strip();
    snippet = snippet == null ? "" : snippet.strip();
  }
}
