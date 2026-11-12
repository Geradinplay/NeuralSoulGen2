package com.neuralsoul.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns NeuralSoul external runtimes for the lifetime of the application.
 *
 * start/stop of individual runtimes is still controlled by the System button,
 * but application/JVM shutdown always tears down every owned process.
 */
public final class RuntimeLifecycleManager implements AutoCloseable {
  private final ModelRuntimeManager modelRuntime = new ModelRuntimeManager();
  private final QdrantRuntimeManager qdrantRuntime = new QdrantRuntimeManager();
  private final MemoryEmbeddingRuntimeManager memoryEmbeddingRuntime =
      new MemoryEmbeddingRuntimeManager();
  private final WebSearchRuntimeManager webSearchRuntime = new WebSearchRuntimeManager();
  private final WebReasoningRuntimeManager webReasoningRuntime = new WebReasoningRuntimeManager();
  private final GeneralAgentRuntimeManager generalAgentRuntime = new GeneralAgentRuntimeManager();
  private final SummarizationRuntimeManager summarizationRuntime = new SummarizationRuntimeManager();
  private final WhisperRuntimeManager whisperRuntime = new WhisperRuntimeManager();
  private final RuntimeRegistry registry = new RuntimeRegistry()
      .register(RuntimeKey.QDRANT, qdrantRuntime)
      .register(RuntimeKey.MEMORY_SEARCH, memoryEmbeddingRuntime)
      .register(RuntimeKey.MAIN_LLM, modelRuntime)
      .register(RuntimeKey.SUMMARIZATION, summarizationRuntime)
      .register(RuntimeKey.WHISPER_STT, whisperRuntime)
      .register(RuntimeKey.WEB_REASONING, webReasoningRuntime)
      .register(RuntimeKey.WEB_SEARCH, webSearchRuntime)
      .register(RuntimeKey.GENERAL_AGENTS, generalAgentRuntime);

  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Thread shutdownHook;

  public RuntimeLifecycleManager() {
    shutdownHook = new Thread(this::shutdownFromHook, "neuralsoul-runtime-shutdown");
    Runtime.getRuntime().addShutdownHook(shutdownHook);
    System.out.println("[Lifecycle] JVM shutdown hook registered");
  }

  public ModelRuntimeManager model() {
    return modelRuntime;
  }

  public QdrantRuntimeManager qdrant() {
    return qdrantRuntime;
  }

  public MemoryEmbeddingRuntimeManager memoryEmbedding() {
    return memoryEmbeddingRuntime;
  }

  public WebSearchRuntimeManager webSearch() {
    return webSearchRuntime;
  }

  public WebReasoningRuntimeManager webReasoning() {
    return webReasoningRuntime;
  }

  public GeneralAgentRuntimeManager generalAgents() {
    return generalAgentRuntime;
  }

  public SummarizationRuntimeManager summarization() {
    return summarizationRuntime;
  }

  public WhisperRuntimeManager whisper() {
    return whisperRuntime;
  }

  public RuntimeRegistry registry() {
    return registry;
  }

  /**
   * Stops all external runtimes. Safe to call repeatedly.
   * Managers themselves perform graceful destroy -> destroyForcibly fallback.
   */
  public void stopAll() {
    System.out.println("[Lifecycle] Stopping all runtimes...");
    stop("Main LLM", modelRuntime);
    stop("Web Search", webSearchRuntime);
    stop("Web Reasoning", webReasoningRuntime);
    stop("General Agents", generalAgentRuntime);
    stop("Context Summarization", summarizationRuntime);
    stop("Whisper STT", whisperRuntime);
    stop("Memory/BGE", memoryEmbeddingRuntime);
    stop("Qdrant", qdrantRuntime);
    System.out.println("[Lifecycle] All runtimes stopped");
  }

  private static void stop(String name, AutoCloseable runtime) {
    try {
      runtime.close();
    } catch (Throwable e) {
      System.out.println("[Lifecycle] Failed to stop " + name + ": " + e);
    }
  }

  private void shutdownFromHook() {
    if (!closed.compareAndSet(false, true)) return;
    System.out.println("[Lifecycle] JVM shutdown detected");
    stopAll();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;

    stopAll();

    // During normal JavaFX shutdown the hook is no longer needed.
    // During JVM shutdown removing the currently-running hook is illegal,
    // therefore guard it.
    if (Thread.currentThread() != shutdownHook) {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
        System.out.println("[Lifecycle] JVM shutdown hook removed");
      } catch (IllegalStateException ignored) {
        // JVM shutdown has already started; the hook will finish naturally.
      }
    }
  }
}
