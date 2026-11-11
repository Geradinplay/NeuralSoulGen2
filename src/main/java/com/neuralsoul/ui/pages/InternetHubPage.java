package com.neuralsoul.ui.pages;

import com.neuralsoul.model.AppState;
import com.neuralsoul.ui.components.Ui;
import java.util.EnumMap;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Internet workspace: existing WEB_SEARCH journal plus a dedicated GGUF model editor. */
public final class InternetHubPage extends VBox {
  private enum Section { HISTORY, MODELS }
  private final AppState state;
  private final Runnable persist;
  private final Runnable clearHistory;
  private final EnumMap<Section, Node> pages = new EnumMap<>(Section.class);
  private final EnumMap<Section, ToggleButton> tabs = new EnumMap<>(Section.class);
  private final VBox host = new VBox();

  public InternetHubPage(AppState state, Runnable persist, Runnable clearHistory) {
    super(14);
    this.state = state;
    this.persist = persist == null ? () -> {} : persist;
    this.clearHistory = clearHistory == null ? () -> {} : clearHistory;
    setMinWidth(0);
    setMaxWidth(Double.MAX_VALUE);
    host.setMinWidth(0);
    host.setMaxWidth(Double.MAX_VALUE);
    setPadding(new Insets(18));
    getChildren().addAll(Ui.label("Интернет", "page-title"),
        Ui.label("История интернет-поиска и модели Web Search", "muted"));
    HBox nav = new HBox(8);
    nav.setMaxWidth(Double.MAX_VALUE);
    ToggleGroup group = new ToggleGroup();
    addTab(nav, group, Section.HISTORY, "История поиска");
    addTab(nav, group, Section.MODELS, "Модели Web Search");
    getChildren().addAll(nav, host);
    show(Section.HISTORY);
  }

  private void addTab(HBox nav, ToggleGroup group, Section section, String label) {
    ToggleButton b = new ToggleButton(label);
    b.setToggleGroup(group);
    b.setMaxWidth(Double.MAX_VALUE);
    b.setMinWidth(0);
    HBox.setHgrow(b, Priority.ALWAYS);
    b.setOnAction(e -> show(section));
    tabs.put(section, b);
    nav.getChildren().add(b);
  }

  private void show(Section section) {
    Node page = pages.computeIfAbsent(section, s -> switch (s) {
      case HISTORY -> new WorkspacePage("Интернет", state,
          () -> {}, () -> {}, () -> {}, persist, clearHistory, true);
      case MODELS -> new ResourcesPage(state, persist, ResourcesPage.View.WEB_SEARCH);
    });
    host.getChildren().setAll(page);
    for (var entry : tabs.entrySet()) {
      boolean selected = entry.getKey() == section;
      ToggleButton b = entry.getValue();
      if (selected && !b.isSelected()) b.setSelected(true);
      b.setStyle("-fx-background-color:" + (selected ? "#6d5dfc" : "rgba(18,25,37,.82)") + ";"
          + "-fx-text-fill:" + (selected ? "white" : "#aebbd0") + ";"
          + "-fx-border-color:" + (selected ? "#8578ff" : "#2b3950") + ";"
          + "-fx-background-radius:9;-fx-border-radius:9;-fx-border-width:1;"
          + "-fx-font-weight:600;-fx-padding:10 12;-fx-cursor:hand;");
    }
  }
}
