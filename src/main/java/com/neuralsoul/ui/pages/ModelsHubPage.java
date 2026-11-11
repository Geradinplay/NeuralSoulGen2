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

/** General model management and LoRA adapters in one sidebar section. */
public final class ModelsHubPage extends VBox {
  private enum Section { MODELS, LORA, DOWNLOAD }

  private final AppState state;
  private final Runnable persist;
  private final EnumMap<Section, Node> pages = new EnumMap<>(Section.class);
  private final EnumMap<Section, ToggleButton> tabs = new EnumMap<>(Section.class);
  private final VBox pageHost = new VBox();

  public ModelsHubPage(AppState state, Runnable persist) {
    super(14);
    this.state = state;
    this.persist = persist == null ? () -> {} : persist;
    setPadding(new Insets(18));
    setMinWidth(0);
    setMaxWidth(Double.MAX_VALUE);
    pageHost.setMinWidth(0);
    pageHost.setMaxWidth(Double.MAX_VALUE);

    getChildren().addAll(
        Ui.label("Модели", "page-title"),
        Ui.label("Управление моделями, адаптерами LoRA и загрузками", "muted"));

    HBox navigation = new HBox(8);
    navigation.setMaxWidth(Double.MAX_VALUE);
    ToggleGroup group = new ToggleGroup();
    addTab(navigation, group, Section.MODELS, "Основные модели");
    addTab(navigation, group, Section.LORA, "LoRA");
    addTab(navigation, group, Section.DOWNLOAD, "Загрузка моделей");
    getChildren().addAll(navigation, pageHost);
    showModels();
  }

  private void addTab(HBox row, ToggleGroup group, Section section, String label) {
    ToggleButton tab = new ToggleButton(label);
    tab.setToggleGroup(group);
    tab.setMinWidth(0);
    tab.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(tab, Priority.ALWAYS);
    tab.setOnAction(e -> show(section));
    tabs.put(section, tab);
    row.getChildren().add(tab);
  }

  public void showModels() { show(Section.MODELS); }
  public void showLora() { show(Section.LORA); }
  public void showDownload() { show(Section.DOWNLOAD); }

  private void show(Section section) {
    Node page = pages.computeIfAbsent(section, this::createPage);
    if (section == Section.LORA && page instanceof LoraPage loraPage) {
      loraPage.refreshModels();
    }
    pageHost.getChildren().setAll(page);

    for (var entry : tabs.entrySet()) {
      boolean selected = entry.getKey() == section;
      ToggleButton tab = entry.getValue();
      if (selected && !tab.isSelected()) tab.setSelected(true);
      tab.setStyle(
          "-fx-background-color:" + (selected ? "#6d5dfc" : "rgba(18,25,37,.82)") + ";"
          + "-fx-text-fill:" + (selected ? "white" : "#aebbd0") + ";"
          + "-fx-border-color:" + (selected ? "#8578ff" : "#2b3950") + ";"
          + "-fx-background-radius:9;-fx-border-radius:9;-fx-border-width:1;"
          + "-fx-font-weight:600;-fx-padding:10 12;-fx-cursor:hand;");
    }
  }

  private Node createPage(Section section) {
    return switch (section) {
      case MODELS -> new ResourcesPage(state, persist);
      case LORA -> new LoraPage(state, persist);
      case DOWNLOAD -> new ModelDownloadPage(state, persist);
    };
  }
}
