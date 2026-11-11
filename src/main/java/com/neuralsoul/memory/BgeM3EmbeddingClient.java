package com.neuralsoul.memory;

import com.neuralsoul.service.http.Json;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** OpenAI-compatible embedding client for the dedicated llama.cpp BGE-M3 runtime. */
public final class BgeM3EmbeddingClient {
  public static final int DIMENSIONS = 1024;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final URI endpoint;

  public BgeM3EmbeddingClient(String baseUrl) {
    String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    endpoint = URI.create(base + "v1/embeddings");
  }

  public float[] embed(String text) throws Exception {
    Map<String,Object> body = new LinkedHashMap<>();
    body.put("input", text == null ? "" : text);
    body.put("model", "bge-m3");
    HttpRequest request = HttpRequest.newBuilder(endpoint)
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
        .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() < 200 || response.statusCode() >= 300)
      throw new IllegalStateException("BGE embeddings HTTP " + response.statusCode() + ": " + response.body());

    Map<String,Object> root = Json.object(Json.read(response.body()));
    Object dataObject = root.get("data");
    if (!(dataObject instanceof List<?> data) || data.isEmpty())
      throw new IllegalStateException("BGE embeddings: empty data");
    Map<String,Object> first = Json.object(data.get(0));
    Object embeddingObject = first.get("embedding");
    if (!(embeddingObject instanceof List<?> values))
      throw new IllegalStateException("BGE embeddings: missing embedding array");
    if (values.size() != DIMENSIONS)
      throw new IllegalStateException("BGE embeddings dimension " + values.size() + ", expected " + DIMENSIONS);
    float[] vector = new float[values.size()];
    for (int i=0;i<values.size();i++) {
      Object value=values.get(i);
      if (!(value instanceof Number number)) throw new IllegalStateException("BGE embeddings: non-number at " + i);
      vector[i]=number.floatValue();
    }
    return vector;
  }
}
