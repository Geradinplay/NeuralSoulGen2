package com.neuralsoul.model;

/** Controls whether filesystem content access and mutations require a GUI decision. */
public enum FileToolApprovalMode {
  AUTO_CONFIRM("Автоподтверждение"),
  MANUAL("Ручное подтверждение");

  private final String label;

  FileToolApprovalMode(String label) { this.label = label; }

  public String label() { return label; }

  @Override public String toString() { return label; }
}
