package com.neuralsoul.service.http;

import com.neuralsoul.config.*;
import com.neuralsoul.service.api.*;
import java.util.function.Supplier;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class MultipartSttService extends AbstractHttpService implements SttService {
  private final Supplier<SttConfig> config;

  public MultipartSttService(ConfigLoader<SttConfig> config, HttpTransport http) {
    this(config::get, http);
  }

  public MultipartSttService(Supplier<SttConfig> config, HttpTransport http) {
    super(http, () -> config.get().endpoint());
    this.config = config;
  }

  @Override
  public String convertSpeechToText(byte[] wav) throws Exception {
    if (wav.length < 44 || wav.length > 32 * 1024 * 1024)
      throw new IllegalArgumentException("Ожидается WAV от 44 байт до 32 MB");
    SttConfig c = config.get();
    String boundary = "NeuralSoul" + UUID.randomUUID();
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    // whisper.cpp server keeps the model resident. Decode controls are request-scoped,
    // so changing them does not require reloading the GGUF model.
    if (!c.language().isBlank()) field(data, boundary, "language", c.language());
    field(data, boundary, "response_format", "json");
    field(data, boundary, "temperature", c.temperature());
    field(data, boundary, "temperature_inc", c.temperatureIncrement());
    field(data, boundary, "no_speech_thold", c.noSpeechThreshold());
    field(data, boundary, "max_context", c.maxContext());
    field(data, boundary, "max_len", c.maxLen());
    field(data, boundary, "split_on_word", c.splitOnWord());
    field(data, boundary, "best_of", c.bestOf());
    field(data, boundary, "beam_size", c.beamSize());
    field(data, boundary, "audio_ctx", c.audioContext());
    field(data, boundary, "word_thold", c.wordThreshold());
    field(data, boundary, "entropy_thold", c.entropyThreshold());
    field(data, boundary, "logprob_thold", c.logProbThreshold());
    field(data, boundary, "translate", c.translate());
    field(data, boundary, "no_timestamps", c.noTimestamps());
    field(data, boundary, "suppress_nst", c.suppressNonSpeech());
    field(data, boundary, "vad", c.vad());
    if (c.vad()) {
      field(data, boundary, "vad_threshold", c.vadThreshold());
      field(data, boundary, "vad_min_speech_duration_ms", c.vadMinSpeechMs());
      field(data, boundary, "vad_min_silence_duration_ms", c.vadMinSilenceMs());
      field(data, boundary, "vad_max_speech_duration_s", c.vadMaxSpeechSeconds());
      field(data, boundary, "vad_speech_pad_ms", c.vadSpeechPadMs());
      field(data, boundary, "vad_samples_overlap", c.vadOverlapSeconds());
    }
    data.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\""
                + c.fileField()
                + "\"; filename=\"recording.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    data.write(wav);
    data.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    String text =
        new String(
                request(
                    c.endpoint(),
                    c.endpoint().path(),
                    "POST",
                    "multipart/form-data; boundary=" + boundary,
                    data.toByteArray()),
                StandardCharsets.UTF_8)
            .strip();
    if (text.startsWith("{")) text = Json.text(Json.object(Json.read(text)).get("text"));
    if (text.isBlank()) throw new IllegalStateException("Речь не распознана");
    return text;
  }

  private void field(ByteArrayOutputStream out, String boundary, String key, Object value)
      throws Exception {
    field(out, boundary, key, String.valueOf(value));
  }

  private void field(ByteArrayOutputStream out, String boundary, String key, String value)
      throws Exception {
    out.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\""
                + key
                + "\"\r\n\r\n"
                + value
                + "\r\n")
            .getBytes(StandardCharsets.UTF_8));
  }
}
