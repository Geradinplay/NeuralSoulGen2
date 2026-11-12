package com.neuralsoul.runtime;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public final class QdrantRuntimeManager implements AutoCloseable {
  public static final int PORT = 6333;
  private Process process;
  private boolean ownsProcess;

  public synchronized void start() throws Exception {
    if (process != null && process.isAlive()) return;

    // Do not start a second qdrant.exe against the same storage. A second
    // process would lock the WAL and crash. Reuse an already healthy local API.
    if (isApiReady()) {
      ownsProcess = false;
      System.out.println("[Qdrant] Existing API detected on http://127.0.0.1:" + PORT);
      return;
    }

    Path dir = Path.of(System.getProperty("user.dir"), "runtime", "data").toAbsolutePath();
    Path exe = dir.resolve("qdrant.exe");
    if (!Files.isRegularFile(exe))
      throw new FileNotFoundException("Qdrant не найден: " + exe);

    Files.createDirectories(dir.resolve("qdrant_storage"));
    ProcessBuilder pb = new ProcessBuilder(exe.toString());
    pb.directory(dir.toFile());
    pb.environment().put("QDRANT__SERVICE__HTTP_PORT", Integer.toString(PORT));
    pb.environment().put("QDRANT__STORAGE__STORAGE_PATH",
        dir.resolve("qdrant_storage").toString());
    pb.redirectErrorStream(true);

    System.out.println("[Qdrant] Starting: " + exe);
    process = pb.start();
    ownsProcess = true;
    Process current = process;
    Thread outputThread = new Thread(() -> {
      try (BufferedReader r = current.inputReader()) {
        for (String line; (line = r.readLine()) != null; )
          System.out.println("[qdrant] " + line);
      } catch (IOException ignored) {}
    }, "qdrant-output");
    outputThread.setDaemon(true);
    outputThread.start();
    waitUntilReady(current);
    System.out.println("[Qdrant] API ready on http://127.0.0.1:" + PORT);
  }

  private void waitUntilReady(Process current) throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    URI uri = URI.create("http://127.0.0.1:" + PORT + "/readyz");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
    Exception last = null;
    while (System.nanoTime() < deadline) {
      if (!current.isAlive())
        throw new IllegalStateException("Qdrant завершился с кодом " + current.exitValue());
      try {
        HttpResponse<String> r = client.send(
            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 200 && r.statusCode() < 300) return;
      } catch (Exception e) { last = e; }
      Thread.sleep(300);
    }
    throw new IllegalStateException("Qdrant API не запустился за 45 секунд", last);
  }

  private boolean isApiReady() {
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
      HttpResponse<String> r = client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + "/readyz"))
              .timeout(Duration.ofSeconds(1)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      return r.statusCode() >= 200 && r.statusCode() < 300;
    } catch (Exception ignored) {
      return false;
    }
  }

  public synchronized boolean isRunning() {
    return (process != null && process.isAlive()) || isApiReady();
  }

  public synchronized void stop() {
    Process p = process; process = null;
    boolean owned = ownsProcess; ownsProcess = false;
    if (p == null || !owned) return;
    System.out.println("[Qdrant] Stopping...");
    p.destroy();
    try {
      if (!p.waitFor(4, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        p.waitFor(2, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      p.destroyForcibly();
    }
  }

  @Override public void close() { stop(); }
}
