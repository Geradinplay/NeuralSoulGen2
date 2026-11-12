package com.neuralsoul.model;

/** Stable identity of a complete NeuralSoul user profile. */
public enum Profile {
  NORMAL("Main", "main"),
  ROLEPLAY("Roleplay", "roleplay"),
  PROFILE_1("Profile 1", "profile1"),
  PROFILE_2("Profile 2", "profile2"),
  PROFILE_3("Profile 3", "profile3"),
  PROFILE_4("Profile 4", "profile4"),
  PROFILE_5("Profile 5", "profile5");

  private final String label;
  private final String key;

  Profile(String label, String key) {
    this.label = label;
    this.key = key;
  }

  public String label() { return label; }
  public String key() { return key; }
  @Override public String toString() { return label; }
}
