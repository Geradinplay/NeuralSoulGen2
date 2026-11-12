package com.neuralsoul.model;

import javafx.beans.property.*;

public final class Device {
  public final String id, name, details;
  public final BooleanProperty enabled = new SimpleBooleanProperty(true);

  public Device(String id, String name, String details) {
    this(id, name, details, true);
  }

  public Device(String id, String name, String details, boolean enabled) {
    this.id = id;
    this.name = name;
    this.details = details;
    this.enabled.set(enabled);
  }
}
