package com.neuralsoul.model;

import javafx.beans.property.*;

/** One LoRA adapter discovered below a model's sibling lora/ directory. */
public final class LoraSetting {
  public final String name;
  /** Path relative to the model's lora directory, always using '/'. */
  public final String relativePath;
  public final BooleanProperty enabled = new SimpleBooleanProperty(false);
  public final DoubleProperty scale = new SimpleDoubleProperty(1.0);

  public LoraSetting(String name, String relativePath) {
    this.name = name;
    this.relativePath = relativePath;
  }

  @Override public String toString() { return name; }
}
