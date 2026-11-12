package com.neuralsoul.model;

/** Behaviour of hands-free voice input while Main LLM is already generating. */
public enum VoiceInputMode {
  INTERRUPT("Режим прерывания"),
  BUFFER("Режим буфера");

  private final String displayName;

  VoiceInputMode(String displayName) {
    this.displayName = displayName;
  }

  public String displayName() {
    return displayName;
  }
}
