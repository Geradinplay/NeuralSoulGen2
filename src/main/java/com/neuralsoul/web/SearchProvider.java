package com.neuralsoul.web;

import java.util.List;

/** Pluggable search backend. Providers return normalized hits; orchestration lives in WebToolService. */
public interface SearchProvider {
  String id();
  boolean configured();
  List<SearchHit> search(String query, int limit) throws Exception;
}
