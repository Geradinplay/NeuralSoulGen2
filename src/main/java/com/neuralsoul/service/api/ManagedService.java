package com.neuralsoul.service.api;

/**
 * Blocking methods: call from pipeline workers, never from the JavaFX thread. HTTP clients
 * start/stop their connection state; they do not own external server processes.
 */
public interface ManagedService extends AutoCloseable {
  void start() throws Exception;

  void stop();

  boolean isRunning();

  @Override
  default void close() {
    stop();
  }
}
