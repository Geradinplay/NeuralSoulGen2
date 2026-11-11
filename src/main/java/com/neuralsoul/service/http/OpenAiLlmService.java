package com.neuralsoul.service.http;

import com.neuralsoul.config.*;
import com.neuralsoul.service.api.*;
import com.neuralsoul.service.tts.TtsTrace;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.Consumer;

public final class OpenAiLlmService extends AbstractHttpService implements LlmService {
  private final Supplier<LlmConfig> config;
  private final Map<String, Object> requestOptions;

  public OpenAiLlmService(ConfigLoader<LlmConfig> config, HttpTransport http) {
    this(config::get, http);
  }

  public OpenAiLlmService(Supplier<LlmConfig> config, HttpTransport http) {
    this(config, http, Map.of());
  }

  public OpenAiLlmService(Supplier<LlmConfig> config, HttpTransport http, Map<String, Object> requestOptions) {
    super(http, () -> config.get().endpoint());
    this.config = config;
    this.requestOptions = requestOptions == null ? Map.of() : Map.copyOf(requestOptions);
  }

  @Override
  public String generate(List<ConversationTurn> messages, String model) throws Exception {
    LlmConfig c = config.get();
    Map<String, Object> payload = new LinkedHashMap<>();
    String selected = model == null || model.isBlank() ? c.model() : model;
    if (!selected.isBlank()) payload.put("model", selected);
    payload.put(
        "messages",
        messages.stream().map(m -> Map.of("role", m.role(), "content", m.content())).toList());
    payload.put("temperature", c.temperature());
    payload.put("max_tokens", c.maxTokens());
    payload.putAll(requestOptions);
    payload.put("stream", false);
    byte[] body =
        request(
            c.endpoint(),
            c.endpoint().path(),
            "POST",
            "application/json",
            Json.write(payload).getBytes(StandardCharsets.UTF_8));
    Map<String, Object> result = Json.object(Json.read(new String(body, StandardCharsets.UTF_8)));
    if (!(result.get("choices") instanceof List<?> list) || list.isEmpty())
      throw new IllegalStateException("LLM ответ не содержит choices");
    String text = Json.text(Json.object(Json.object(list.get(0)).get("message")).get("content"));
    if (text.isBlank()) throw new IllegalStateException("LLM вернул пустой ответ");
    return text;
  }

  @Override
  public String generateStreaming(
      List<ConversationTurn> messages, String model, Consumer<String> chunkConsumer)
      throws Exception {
    requireRunning();
    LlmConfig c = config.get();
    Map<String, Object> payload = new LinkedHashMap<>();
    String selected = model == null || model.isBlank() ? c.model() : model;
    if (!selected.isBlank()) payload.put("model", selected);
    payload.put(
        "messages",
        messages.stream().map(m -> Map.of("role", m.role(), "content", m.content())).toList());
    payload.put("temperature", c.temperature());
    payload.put("max_tokens", c.maxTokens());
    payload.putAll(requestOptions);
    payload.put("stream", true);
    // llama.cpp: explicitly allow reuse of the previous prompt/KV cache.
    payload.put("cache_prompt", true);

    StringBuilder answer = new StringBuilder();
    long started = System.nanoTime();
    int[] chunkCount = {0};
    if (TtsTrace.active()) TtsTrace.log("LLM_HTTP_START", "model=" + selected
        + " messages=" + messages.size() + " maxTokens=" + c.maxTokens() + " stream=true");
    http.stream(
        c.endpoint(),
        c.endpoint().path(),
        "POST",
        "application/json",
        Json.write(payload).getBytes(StandardCharsets.UTF_8),
        line -> {
          if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
          if (!line.startsWith("data:")) return;
          String data = line.substring(5).trim();
          if (data.isEmpty() || "[DONE]".equals(data)) return;
          Object parsed = Json.read(data);
          Map<String, Object> root = Json.object(parsed);
          Object choicesObject = root.get("choices");
          if (!(choicesObject instanceof List<?> choices) || choices.isEmpty()) return;
          Map<String, Object> choice = Json.object(choices.get(0));
          Object deltaObject = choice.get("delta");
          if (!(deltaObject instanceof Map<?, ?>)) return;
          Object content = Json.object(deltaObject).get("content");
          if (content == null) return;
          String chunk = Json.text(content);
          if (chunk.isEmpty()) return;
          answer.append(chunk);
          chunkCount[0]++;
          if (TtsTrace.active() && (chunkCount[0] == 1 || chunkCount[0] % 40 == 0)) {
            TtsTrace.log("LLM_HTTP_CHUNKS", "count=" + chunkCount[0] + " chars=" + answer.length()
                + " elapsedMs=" + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
          }
          chunkConsumer.accept(chunk);
        });

    if (TtsTrace.active()) TtsTrace.log("LLM_HTTP_DONE", "chunks=" + chunkCount[0]
        + " chars=" + answer.length() + " elapsedMs="
        + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    if (answer.length() == 0) throw new IllegalStateException("LLM вернул пустой streaming-ответ");
    return answer.toString();
  }

  @Override
  public void cancelGeneration() {
    http.cancelStreams();
  }

  @Override
  public List<String> listModels() throws Exception {
    EndpointConfig c = config.get().endpoint();
    byte[] body = request(c, "/v1/models", "GET", null, null);
    Object data = Json.object(Json.read(new String(body, StandardCharsets.UTF_8))).get("data");
    if (!(data instanceof List<?> list))
      throw new IllegalStateException("Models API: отсутствует data");
    List<String> names = new ArrayList<>();
    for (Object item : list) names.add(Json.text(Json.object(item).get("id")));
    return names;
  }
}
