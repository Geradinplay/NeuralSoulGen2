package com.neuralsoul.pipeline;

public record PipelineEvent(long generation, Type type, String text) {
  public enum Type {
    STATUS,
    TRANSCRIPT,
    RESPONSE,
    MEMORY_SKIP,
    CHUNK,
    WARNING,
    THINK_START,
    THINK_END,
    /** Development mode: a plan proposal that waits for user approval in the chat UI. */
    PLAN
  }
}
