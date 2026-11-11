package com.neuralsoul.ui.components;

import java.util.*;
import java.util.function.Consumer;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;

public final class Sidebar extends VBox {
  private final VBox profile =
      new VBox(4, Ui.label("Nina", "card-title"), Ui.label("● Локально", "green"));
  private final Map<String, Button> buttons = new LinkedHashMap<>();

  public Sidebar(Consumer<String> navigate) {
    this(navigate, () -> {});
  }

  public Sidebar(Consumer<String> navigate, Runnable systemToggle) {
    setSpacing(5);
    setPadding(new Insets(10, 10, 16, 10));
    setPrefWidth(170);
    getStyleClass().add("sidebar");
    String[][] items = {
      {"Главная", "home"},
      {"Мониторинг", "monitor"},
      {"Чат", "chat"},
      {"Текущий промт", "tasks"},
      {"Агенты", "layers"},
      {"Память", "memory"},
      {"Интернет", "globe"},
      {"Компьютер", "monitor"},
      {"Голос", "voice"},
      {"Модели", "layers"},
      {"Схема", "layers"}
    };
    for (String[] item : items) {
      Button b = Ui.button(item[0], item[1], () -> navigate.accept(item[0]));
      b.setTooltip(new Tooltip(item[0]));
      b.setAccessibleText(item[0]);
      b.setMaxWidth(Double.MAX_VALUE);
      b.getStyleClass().add("nav-button");
      buttons.put(item[0], b);
      getChildren().add(b);
    }
    Region gap = new Region();
    VBox.setVgrow(gap, Priority.ALWAYS);

    getChildren().addAll(gap, Ui.row(Icons.of("spark"), profile));
    select("Главная");
    setSystemRunning(false);
  }

  public void setCompact(boolean compact) {
    setPrefWidth(compact ? 64 : 170);
    buttons.forEach(
        (name, b) ->
            b.setContentDisplay(compact ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT));
    profile.setVisible(!compact);
    profile.setManaged(!compact);
  }

  public void setSystemRunning(boolean running) {
    profile.getChildren().set(
        1, Ui.label(running ? "● Система запущена" : "○ Система остановлена",
            running ? "green" : "tiny"));
  }

  public void select(String page) {
    buttons.forEach(
        (name, b) -> {
          b.getStyleClass().remove("nav-selected");
          if (name.equals(page)) b.getStyleClass().add("nav-selected");
        });
  }
}
