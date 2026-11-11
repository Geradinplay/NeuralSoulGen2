package com.neuralsoul.service;

import com.neuralsoul.pipeline.*;
import com.neuralsoul.model.Profile;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

public final class PipelineBackend implements AssistantBackend {
  private final GenerationPipeline pipeline;
  private final BooleanSupplier speech;

  public PipelineBackend(GenerationPipeline pipeline, BooleanSupplier speech) {
    this.pipeline = pipeline;
    this.speech = speech;
  }

  @Override
  public CompletionStage<String> send(String message, String model, String mode) {
    return send(message, model, mode, Profile.NORMAL);
  }

  public CompletionStage<String> send(String message, String model, String mode, Profile profile) {
    return pipeline.generate(message, model, mode, profile, speech.getAsBoolean())
        .thenApply(GenerationResult::answer);
  }

  public GenerationPipeline pipeline() {
    return pipeline;
  }

  @Override
  public void close() {
    pipeline.close();
  }
}
