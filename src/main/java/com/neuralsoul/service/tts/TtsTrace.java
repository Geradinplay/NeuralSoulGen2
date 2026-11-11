package com.neuralsoul.service.tts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Correlated, metadata-only LLM -> TTS diagnostics. Never logs user text or HTTP bodies. */
public final class TtsTrace {
  private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();
  private static final Object FILE_LOCK = new Object();
  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
  private static final Path LOG = Path.of(System.getProperty("user.dir"), "logs", "tts-pipeline.log");
  private static boolean fileErrorShown;

  private TtsTrace() {}

  public static String id() { return CONTEXT.get(); }
  public static boolean active() { return CONTEXT.get() != null; }

  /** A scope may cross multiple method calls, but must be reopened on a worker thread. */
  public static Scope with(String id) {
    String previous = CONTEXT.get();
    if (id == null || id.isBlank()) CONTEXT.remove(); else CONTEXT.set(id);
    return () -> { if (previous == null) CONTEXT.remove(); else CONTEXT.set(previous); };
  }

  @FunctionalInterface public interface Scope extends AutoCloseable { @Override void close(); }

  public static void log(String stage, String detail) { log(id(), stage, detail); }

  public static void log(String correlationId, String stage, String detail) {
    String message = detail == null ? "" : detail.replace('\r', ' ').replace('\n', ' ');
    if (message.length() > 500) message = message.substring(0, 500) + "...";
    String line = "[TTS/TRACE] " + LocalTime.now().format(CLOCK)
        + " id=" + (correlationId == null ? "system" : correlationId)
        + " stage=" + stage + " " + message;
    System.out.println(line);
    synchronized (FILE_LOCK) {
      try {
        Files.createDirectories(LOG.getParent());
        Files.writeString(LOG, line + System.lineSeparator(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      } catch (IOException e) {
        if (!fileErrorShown) {
          fileErrorShown = true;
          System.err.println("[TTS/TRACE] Can't write " + LOG + ": " + e.getMessage());
        }
      }
    }
  }
}
