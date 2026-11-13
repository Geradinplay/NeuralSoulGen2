package com.neuralsoul.model;

public enum ModelRole {
  MAIN_LLM("Main LLM", "Основная Nina: диалог, решения и маршрутизация задач"),
  CODING("Coding", "Написание, анализ и изменение кода"),
  WEB_SEARCH("Web Search", "Поиск и анализ информации в интернете"),
  REASONING("Reasoning", "Планирование и сложные рассуждения"),
  COMPUTER_USE("Computer Use", "Мышь, клавиатура и управление приложениями"),
  MEMORY("Memory Search", "Семантический поиск по долговременной памяти"),
  FILE_SEARCH("Файловые инструменты", "Встроенные glob/grep/read/edit/patch без отдельной поисковой модели"),
  GENERAL("General", "Универсальный вспомогательный субагент");

  private final String title;
  private final String description;

  ModelRole(String title, String description) {
    this.title = title;
    this.description = description;
  }

  public String title() { return title; }
  public String description() { return description; }
}
