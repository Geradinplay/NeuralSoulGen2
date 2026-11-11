package com.neuralsoul.service;

import java.util.concurrent.CompletionStage;

/**
 * Backend boundary. Complete futures off the UI thread; the controller marshals results onto
 * JavaFX.
 */
public interface AssistantBackend extends AutoCloseable {
  CompletionStage<String> send(String message, String model, String mode);

  @Override
  default void close() {}
}
