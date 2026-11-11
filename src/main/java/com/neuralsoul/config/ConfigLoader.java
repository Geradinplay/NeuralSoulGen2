package com.neuralsoul.config;

public interface ConfigLoader<T> {
  T get();

  T reload();

  void save(T config);
}
