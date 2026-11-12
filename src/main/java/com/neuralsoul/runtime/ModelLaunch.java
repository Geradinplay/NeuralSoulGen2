package com.neuralsoul.runtime;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One typed model launch descriptor: immutable settings, model path and runtime key.
 * The launcher is the only adapter needed for runtimes with different start signatures.
 */
public final class ModelLaunch<C, R> {
  @FunctionalInterface public interface Launcher<C, R> {
    void start(R runtime, C settings) throws Exception;
  }

  public enum State { PENDING, STARTING, READY, FAILED }

  private final RuntimeKey runtimeKey;
  private final Class<R> runtimeType;
  private final String displayName;
  private final Path modelPath;
  private final C settings;
  private final boolean optional;
  private final Launcher<C, R> launcher;
  private volatile State state = State.PENDING;
  private volatile Throwable failure;

  public ModelLaunch(RuntimeKey runtimeKey, Class<R> runtimeType, String displayName,
                     Path modelPath, C settings, boolean optional, Launcher<C, R> launcher) {
    this.runtimeKey = Objects.requireNonNull(runtimeKey);
    this.runtimeType = Objects.requireNonNull(runtimeType);
    this.displayName = Objects.requireNonNull(displayName);
    this.modelPath = modelPath;
    this.settings = settings;
    this.optional = optional;
    this.launcher = Objects.requireNonNull(launcher);
  }

  public RuntimeKey runtimeKey() { return runtimeKey; }
  public String displayName() { return displayName; }
  public Path modelPath() { return modelPath; }
  public C settings() { return settings; }
  public boolean optional() { return optional; }
  public State state() { return state; }
  public Throwable failure() { return failure; }

  public void start(RuntimeRegistry registry) throws Exception {
    state = State.STARTING;
    failure = null;
    try {
      R runtime = registry.get(runtimeKey, runtimeType);
      launcher.start(runtime, settings);
      state = State.READY;
    } catch (Exception e) {
      failure = e;
      state = State.FAILED;
      throw e;
    }
  }
}
