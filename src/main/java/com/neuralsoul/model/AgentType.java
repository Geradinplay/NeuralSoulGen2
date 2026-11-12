package com.neuralsoul.model;

/**
 * Logical agent currently doing work. displayName is what the UI shows
 * in place of the generic "Generating" label.
 */
public enum AgentType {
  IDLE(""),
  MAIN_LLM("Generating"),
  MEMORY_SEARCH("Memory Search"),
  FILE_SEARCH("File Search"),
  WEB_SEARCH("Web Search"),
  REASONING("Reasoning"),
  CODING("Coding"),
  COMPUTER_USE("Computer Use"),
  GENERAL("General");

  private final String displayName;

  AgentType(String displayName) {
    this.displayName = displayName;
  }

  public String displayName() {
    return displayName;
  }
}
