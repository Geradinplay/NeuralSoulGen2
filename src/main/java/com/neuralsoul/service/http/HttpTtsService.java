package com.neuralsoul.service.http;

import com.neuralsoul.config.*;
import com.neuralsoul.service.api.*;
import java.util.function.Supplier;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class HttpTtsService extends AbstractHttpService implements TtsService {
  private final Supplier<TtsConfig> config;

  public HttpTtsService(ConfigLoader<TtsConfig> config, HttpTransport http) {
    this(config::get, http);
  }

  public HttpTtsService(Supplier<TtsConfig> config, HttpTransport http) {
    super(http, () -> config.get().endpoint());
    this.config = config;
  }

  @Override
  public byte[] convertTextToSpeech(String text) throws Exception {
    TtsConfig c = config.get();
    byte[] result;
    if (c.protocol() == TtsConfig.Protocol.COQUI) {
      String route =
          c.endpoint().path()
              + "?text="
              + encode(text)
              + "&speaker_id="
              + encode(c.voice())
              + "&language_id="
              + encode(c.language());
      result = request(c.endpoint(), route, "GET", null, null);
    } else {
      Map<String, Object> payload =
          Map.of(
              "model",
              c.model(),
              "input",
              text,
              "voice",
              c.voice(),
              "speed",
              c.speed(),
              "response_format",
              "wav");
      result =
          request(
              c.endpoint(),
              c.endpoint().path(),
              "POST",
              "application/json",
              Json.write(payload).getBytes(StandardCharsets.UTF_8));
    }
    if (result.length < 12
        || !new String(result, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
        || !new String(result, 8, 4, StandardCharsets.US_ASCII).equals("WAVE"))
      throw new IllegalStateException(
          "TTS должен возвращать WAV-байты, а не JSON/MP3. Проверь протокол и endpoint.");
    return result;
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
