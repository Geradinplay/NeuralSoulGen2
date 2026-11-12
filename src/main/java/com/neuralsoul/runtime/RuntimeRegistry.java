package com.neuralsoul.runtime;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Central map from a logical runtime key to its long-lived runtime instance.
 * Launch descriptors store only the key; orchestration resolves the instance here.
 */
public final class RuntimeRegistry {
  private final Map<RuntimeKey, Object> runtimes = new EnumMap<>(RuntimeKey.class);

  public RuntimeRegistry register(RuntimeKey key, Object runtime) {
    runtimes.put(Objects.requireNonNull(key), Objects.requireNonNull(runtime));
    return this;
  }

  public <R> R get(RuntimeKey key, Class<R> type) {
    Object runtime = runtimes.get(key);
    if (runtime == null) throw new IllegalStateException("Runtime не зарегистрирован: " + key);
    if (!type.isInstance(runtime)) {
      throw new IllegalStateException("Runtime " + key + " имеет тип "
          + runtime.getClass().getName() + ", ожидался " + type.getName());
    }
    return type.cast(runtime);
  }

  public Object get(RuntimeKey key) {
    Object runtime = runtimes.get(key);
    if (runtime == null) throw new IllegalStateException("Runtime не зарегистрирован: " + key);
    return runtime;
  }
}
