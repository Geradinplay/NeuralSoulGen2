package com.neuralsoul.service.http;

import com.neuralsoul.config.EndpointConfig;
import java.io.IOException;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class HttpTransport {
  private final java.util.Set<java.io.InputStream> activeStreams =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public byte[] request(
      EndpointConfig c, String path, String method, String contentType, byte[] body)
      throws Exception {
    return perform(c, path, method, contentType, body, false);
  }


  public void stream(
      EndpointConfig c,
      String path,
      String method,
      String contentType,
      byte[] body,
      java.util.function.Consumer<String> lineConsumer)
      throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(c.uri(path)).timeout(Duration.ofSeconds(c.timeoutSeconds()));
    if (contentType != null) b.header("Content-Type", contentType);
    b.header("Accept", "text/event-stream");
    if (!c.apiKeyEnv().isBlank()) {
      String key = System.getenv(c.apiKeyEnv());
      if (key == null || key.isBlank())
        throw new IOException("Не задана переменная окружения " + c.apiKeyEnv());
      b.header("Authorization", "Bearer " + key);
    }
    b.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(body));

    HttpResponse<java.io.InputStream> response;
    try {
      response = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException e) {
      throw new IOException("Нет связи с " + c.baseUrl() + ": " + e.getClass().getSimpleName(), e);
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300)
      throw new IOException("HTTP " + response.statusCode() + " · " + c.baseUrl() + path);

    java.io.InputStream stream = response.body();
    activeStreams.add(stream);
    try (stream;
         java.io.BufferedReader reader =
             new java.io.BufferedReader(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) lineConsumer.accept(line);
    } finally {
      activeStreams.remove(stream);
    }
  }

  /** Closes active SSE bodies so a cancelled generation unblocks readLine immediately. */
  public void cancelStreams() {
    for (java.io.InputStream stream : activeStreams) {
      try { stream.close(); } catch (IOException ignored) {}
    }
  }

  public void probe(EndpointConfig c) throws Exception {
    perform(
        new EndpointConfig(
            c.baseUrl(), c.path(), c.healthPath(), c.apiKeyEnv(), Math.min(8, c.timeoutSeconds())),
        c.healthPath().isBlank() ? "/" : c.healthPath(),
        "GET",
        null,
        null,
        c.healthPath().isBlank());
  }

  private byte[] perform(
      EndpointConfig c,
      String path,
      String method,
      String type,
      byte[] body,
      boolean reachabilityOnly)
      throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(c.uri(path)).timeout(Duration.ofSeconds(c.timeoutSeconds()));
    if (type != null) b.header("Content-Type", type);
    if (!c.apiKeyEnv().isBlank()) {
      String key = System.getenv(c.apiKeyEnv());
      if (key == null || key.isBlank())
        throw new IOException("Не задана переменная окружения " + c.apiKeyEnv());
      b.header("Authorization", "Bearer " + key);
    }
    b.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(body));
    HttpResponse<byte[]> response;
    try {
      response = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new IOException("Нет связи с " + c.baseUrl() + ": " + e.getClass().getSimpleName(), e);
    }
    int status = response.statusCode();
    if ((status < 200 || status >= 300)
        && !(reachabilityOnly && (status == 404 || status == 405))) {
      String detail = new String(response.body(), StandardCharsets.UTF_8);
      if (detail.length() > 500) detail = detail.substring(0, 500);
      throw new IOException("HTTP " + status + " · " + c.baseUrl() + path + " · " + detail);
    }
    return response.body();
  }
}
