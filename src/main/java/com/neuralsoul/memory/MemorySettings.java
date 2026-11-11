package com.neuralsoul.memory;

import javafx.beans.property.*;

public final class MemorySettings {
  public static final String MAIN_LLM_SUMMARIZER = "__MAIN_LLM__";
  public final DoubleProperty minScore = new SimpleDoubleProperty(0.60);
  public final IntegerProperty maxResults = new SimpleIntegerProperty(10);
  public final IntegerProperty contextMessages = new SimpleIntegerProperty(20);
  /** Physical model key used for rolling conversation-context summarization. */
  public final StringProperty summarizationModel = new SimpleStringProperty(MAIN_LLM_SUMMARIZER);
}
