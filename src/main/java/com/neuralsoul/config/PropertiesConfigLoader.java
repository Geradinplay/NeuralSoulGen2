package com.neuralsoul.config;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

/** UTF-8, atomic replacement, validated before changing the in-memory value. */
public abstract class PropertiesConfigLoader<T> implements ConfigLoader<T> {
  private final Path path;
  private volatile T current;

  protected PropertiesConfigLoader(Path path) {
    this.path = path;
  }

  protected abstract T decode(Properties p);

  protected abstract Properties encode(T config);

  @Override
  public synchronized T get() {
    if (current == null) reload();
    return current;
  }

  @Override
  public synchronized T reload() {
    Properties p = new Properties();
    try {
      if (Files.exists(path))
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
          p.load(r);
        }
    } catch (IOException e) {
      throw new IllegalStateException("Не удалось прочитать " + path, e);
    }
    current = decode(p);
    return current;
  }

  @Override
  public synchronized void save(T config) {
    Properties p = encode(config);
    T checked = decode(p);
    Path temp = null;
    try {
      Files.createDirectories(path.getParent());
      temp = Files.createTempFile(path.getParent(), "config-", ".tmp");
      try (Writer w = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
        p.store(w, "NeuralSoul settings");
      }
      try {
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
      }
      current = checked;
    } catch (IOException e) {
      throw new IllegalStateException("Не удалось сохранить " + path, e);
    } finally {
      if (temp != null)
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
        }
    }
  }

  public Path path() {
    return path;
  }

  protected static String str(Properties p, String key, String fallback) {
    return p.getProperty(key, fallback);
  }

  protected static int integer(Properties p, String key, int fallback) {
    return Integer.parseInt(str(p, key, "" + fallback));
  }

  protected static double number(Properties p, String key, double fallback) {
    return Double.parseDouble(str(p, key, "" + fallback));
  }

  protected static boolean bool(Properties p, String key, boolean fallback) {
    String s = str(p, key, "" + fallback);
    if (!s.equals("true") && !s.equals("false"))
      throw new IllegalArgumentException(key + ": ожидается true/false");
    return Boolean.parseBoolean(s);
  }

  protected static Properties props(Object... entries) {
    Properties p = new Properties();
    for (int i = 0; i < entries.length; i += 2)
      p.setProperty(entries[i].toString(), entries[i + 1].toString());
    return p;
  }
}
