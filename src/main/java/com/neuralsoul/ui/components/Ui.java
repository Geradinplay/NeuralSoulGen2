package com.neuralsoul.ui.components;

import javafx.beans.property.BooleanProperty;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

public final class Ui {
  private Ui() {}

  public static Label label(String text, String style) {
    Label l = new Label(text);
    l.getStyleClass().add(style);
    return l;
  }

  public static Region spacer() {
    Region r = new Region();
    HBox.setHgrow(r, Priority.ALWAYS);
    return r;
  }

  public static HBox row(Node... nodes) {
    HBox r = new HBox(10, nodes);
    r.setAlignment(Pos.CENTER_LEFT);
    return r;
  }

  public static VBox card(String title, Node... children) {
    VBox box = new VBox(8);
    box.getStyleClass().add("card");
    if (!title.isEmpty()) box.getChildren().add(label(title, "card-title"));
    box.getChildren().addAll(children);
    return box;
  }

  public static Button button(String text, String icon, Runnable action) {
    Button b = new Button(text, Icons.of(icon));
    b.setOnAction(e -> action.run());
    return b;
  }

  public static Button link(String text, Runnable action) {
    Button b = new Button(text);
    b.getStyleClass().add("link-button");
    b.setOnAction(e -> action.run());
    return b;
  }

  public static ToggleButton toggle(BooleanProperty prop) {
    ToggleButton b = new ToggleButton();
    b.getStyleClass().add("switch");
    Region dot = new Region();
    dot.getStyleClass().add("switch-dot");
    b.setGraphic(dot);
    b.selectedProperty().bindBidirectional(prop);
    b.setAccessibleText("Разрешить использование");
    return b;
  }

  public static CheckBox check(String text, BooleanProperty prop) {
    CheckBox c = new CheckBox(text);
    c.setWrapText(true);
    c.selectedProperty().bindBidirectional(prop);
    return c;
  }

  public static ScrollPane scroll(Node content) {
    ScrollPane s = new ScrollPane(content);
    s.setFitToWidth(true);
    s.setMinSize(0, 0);
    s.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
    s.getStyleClass().add("page-scroll");
    return s;
  }

  public static VBox page(String title, String subtitle, Node... nodes) {
    VBox p = new VBox(18);
    p.setPadding(new Insets(22));
    p.getChildren().addAll(label(title, "page-title"), label(subtitle, "muted"));
    p.getChildren().addAll(nodes);
    return p;
  }

  public static ProgressBar progress(double value, String style) {
    ProgressBar p = new ProgressBar(value);
    p.setMaxWidth(Double.MAX_VALUE);
    p.getStyleClass().add(style);
    return p;
  }
}
