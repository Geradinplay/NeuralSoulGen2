package com.neuralsoul.ui.pages;

import com.neuralsoul.config.ConfigLocator;
import com.neuralsoul.config.SttConfig;
import com.neuralsoul.model.AppState;
import com.neuralsoul.ui.components.Ui;
import java.util.EnumMap;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Two distinct voice sections; Whisper page remains self-contained. */
public final class VoiceHubPage extends VBox {
  private enum Section { STT, TTS }
  private final EnumMap<Section, Node> pages = new EnumMap<>(Section.class);
  private final EnumMap<Section, ToggleButton> tabs = new EnumMap<>(Section.class);
  private final VBox host = new VBox();

  public VoiceHubPage(AppState state, ConfigLocator configs,
      Consumer<DoubleConsumer> toggleMonitor,
      Consumer<Consumer<String>> wavTest,
      Consumer<String> speak,
      Consumer<SttConfig> saveStt) {
    super(14);
    setPadding(new Insets(18));
    setMinWidth(0);
    setMaxWidth(Double.MAX_VALUE);
    host.setMinWidth(0);
    host.setMaxWidth(Double.MAX_VALUE);

    pages.put(Section.STT, new VoicePage(state, configs, toggleMonitor, wavTest, speak, saveStt));
    pages.put(Section.TTS, new SpeechGenerationPage(state, configs));

    HBox navigation = new HBox(8);
    navigation.setMaxWidth(Double.MAX_VALUE);
    ToggleGroup group = new ToggleGroup();
    addTab(navigation, group, Section.STT, "Распознавание речи");
    addTab(navigation, group, Section.TTS, "Генерация речи");
    getChildren().addAll(Ui.label("Голос", "page-title"), navigation, host);
    show(Section.STT);
  }

  private void addTab(HBox navigation, ToggleGroup group, Section section, String title) {
    ToggleButton button = new ToggleButton(title);
    button.setToggleGroup(group);
    button.setMinWidth(0);
    button.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(button, Priority.ALWAYS);
    button.setOnAction(e -> show(section));
    tabs.put(section, button);
    navigation.getChildren().add(button);
  }

  private void show(Section section) {
    host.getChildren().setAll(pages.get(section));
    tabs.forEach((key, tab) -> {
      boolean selected = key == section;
      if (selected && !tab.isSelected()) tab.setSelected(true);
      tab.setStyle("-fx-background-color:" + (selected ? "#6d5dfc" : "rgba(18,25,37,.82)") + ";"
          + "-fx-text-fill:" + (selected ? "white" : "#aebbd0") + ";"
          + "-fx-border-color:" + (selected ? "#8578ff" : "#2b3950") + ";"
          + "-fx-background-radius:9;-fx-border-radius:9;-fx-border-width:1;"
          + "-fx-font-weight:600;-fx-padding:10 12;-fx-cursor:hand;");
    });
  }
}
