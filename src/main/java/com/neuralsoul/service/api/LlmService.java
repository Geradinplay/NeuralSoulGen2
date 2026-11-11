package com.neuralsoul.service.api;

import java.util.List;
import java.util.function.Consumer;

public interface LlmService extends ManagedService {
  String generate(List<ConversationTurn> messages, String model) throws Exception;

  default String generateStreaming(
      List<ConversationTurn> messages, String model, Consumer<String> chunkConsumer)
      throws Exception {
    String answer = generate(messages, model);
    chunkConsumer.accept(answer);
    return answer;
  }

  /** Cancel only the currently active inference request without disconnecting the LLM service. */
  default void cancelGeneration() {}

  List<String> listModels() throws Exception;
}
