package com.neuralsoul.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Ordered, observable startup plan containing only models that will actually start. */
public final class ModelStartupPlan {
  @FunctionalInterface public interface Guard { void check() throws Exception; }
  public interface Listener {
    default void planReady(List<ModelLaunch<?, ?>> launches) {}
    default void starting(ModelLaunch<?, ?> launch, int index, int total) {}
    default void ready(ModelLaunch<?, ?> launch, int index, int total) {}
    default void failed(ModelLaunch<?, ?> launch, int index, int total, Throwable error) {}
  }

  private final RuntimeRegistry registry;
  private final List<ModelLaunch<?, ?>> launches = new ArrayList<>();

  public ModelStartupPlan(RuntimeRegistry registry) {
    this.registry = Objects.requireNonNull(registry);
  }

  public ModelStartupPlan add(ModelLaunch<?, ?> launch) {
    launches.add(Objects.requireNonNull(launch));
    return this;
  }

  public List<ModelLaunch<?, ?>> launches() { return List.copyOf(launches); }

  public void execute(Listener listener) throws Exception {
    execute(listener, () -> {});
  }

  public void execute(Listener listener, Guard guard) throws Exception {
    Listener events = listener == null ? new Listener() {} : listener;
    Guard checks = guard == null ? () -> {} : guard;
    List<ModelLaunch<?, ?>> snapshot = launches();
    events.planReady(snapshot);
    for (int i = 0; i < snapshot.size(); i++) {
      ModelLaunch<?, ?> launch = snapshot.get(i);
      int index = i + 1;
      checks.check();
      events.starting(launch, index, snapshot.size());
      try {
        launch.start(registry);
        checks.check();
        events.ready(launch, index, snapshot.size());
      } catch (Exception error) {
        events.failed(launch, index, snapshot.size(), error);
        if (!launch.optional()) throw error;
        System.out.println("[Startup] Optional model failed: " + launch.displayName() + ": " + error);
      }
    }
  }
}
