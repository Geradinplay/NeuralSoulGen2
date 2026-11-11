package com.neuralsoul.ui.pages;

import com.neuralsoul.memory.MemoryService;
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

/** Three views of one memory system, sharing the active profile and persisted model settings. */
public final class MemoryHubPage extends VBox {
  private enum Section { MAIN, FILES, SEARCH }

  private final AppState state;
  private final MemoryService memory;
  private final Runnable attachFile;
  private final Runnable openProject;
  private final Runnable persist;
  private final EnumMap<Section, Node> pages = new EnumMap<>(Section.class);
  private final EnumMap<Section, ToggleButton> tabs = new EnumMap<>(Section.class);
  private final VBox pageHost = new VBox();
  private Section current = Section.MAIN;

  public MemoryHubPage(AppState state, MemoryService memory,
                       Runnable attachFile, Runnable openProject, Runnable persist) {
    super(14);
    this.state = state;
    this.memory = memory;
    this.attachFile = attachFile;
    this.openProject = openProject;
    this.persist = persist == null ? () -> {} : persist;
    setPadding(new Insets(18));

    getChildren().addAll(
        Ui.label("Память", "page-title"),
        Ui.label("Основная память, сохранённые файлы и модели поиска", "muted"));

    HBox navigation = new HBox(8);
    navigation.setMaxWidth(Double.MAX_VALUE);
    ToggleGroup group = new ToggleGroup();
    addTab(navigation, group, Section.MAIN, "Основная память");
    addTab(navigation, group, Section.FILES, "Файловая память");
    addTab(navigation, group, Section.SEARCH, "Поиск в памяти");
    getChildren().addAll(navigation, pageHost);
    showMainMemory();
  }

  private void addTab(HBox row, ToggleGroup group, Section section, String label) {
    ToggleButton tab = new ToggleButton(label);
    tab.setToggleGroup(group);
    tab.setMaxWidth(Double.MAX_VALUE);
    tab.setMinWidth(0);
    HBox.setHgrow(tab, Priority.ALWAYS);
    tab.setOnAction(e -> show(section));
    tabs.put(section, tab);
    row.getChildren().add(tab);
  }

  public void showMainMemory() { show(Section.MAIN); }
  public void showFileMemory() { show(Section.FILES); }
  public void showMemorySearch() { show(Section.SEARCH); }

  private void show(Section section) {
    current = section;
    Node page = pages.computeIfAbsent(section, this::createPage);
    if (section == Section.FILES && page instanceof WorkspacePage filePage) {
      filePage.refreshFileModels();
    }
    pageHost.getChildren().setAll(page);
    ToggleButton active = tabs.get(section);
    if (!active.isSelected()) active.setSelected(true);
    for (var entry : tabs.entrySet()) {
      boolean selected = entry.getKey() == current;
      entry.getValue().setStyle(
          "-fx-background-color:" + (selected ? "#6d5dfc" : "rgba(18,25,37,.82)") + ";"
          + "-fx-text-fill:" + (selected ? "white" : "#aebbd0") + ";"
          + "-fx-border-color:" + (selected ? "#8578ff" : "#2b3950") + ";"
          + "-fx-background-radius:9;-fx-border-radius:9;-fx-border-width:1;"
          + "-fx-font-weight:600;-fx-padding:10 12;-fx-cursor:hand;");
    }
  }

  private Node createPage(Section section) {
    return switch (section) {
      case MAIN -> new MemoryPage(memory, state.memorySettings, state, true);
      case FILES -> new WorkspacePage(
          "Файлы", state, attachFile, () -> {}, openProject, persist);
      case SEARCH -> new ResourcesPage(state, persist, ResourcesPage.View.MEMORY_SEARCH);
    };
  }
}
