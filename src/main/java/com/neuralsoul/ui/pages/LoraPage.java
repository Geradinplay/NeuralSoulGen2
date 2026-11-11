package com.neuralsoul.ui.pages;

import com.neuralsoul.lora.LoraArchiveService;
import com.neuralsoul.model.Profile;
import com.neuralsoul.model.*;
import com.neuralsoul.ui.components.*;
import com.neuralsoul.ui.styles.UiStyles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

/** Dedicated LoRA management page. */
public final class LoraPage extends VBox {
  private final AppState state;
  private final Runnable persist;
  private final HBox modelShowcase = new HBox(10);
  private final VBox adapters = new VBox(8);
  private final List<ModelAssignment> availableModels = new ArrayList<>();
  private ModelAssignment selectedModel;
  private int selectedModelIndex;

  public LoraPage(AppState state) {
    this(state, () -> {});
  }

  public LoraPage(AppState state, Runnable persist) {
    super(12);
    this.state = state;
    this.persist = persist == null ? () -> {} : persist;
    setPadding(new Insets(18));

    getChildren().addAll(
        Ui.label("Адаптеры LoRA", "section-title"),
        Ui.label("Адаптеры LoRA для основной модели. Настройки сохраняются отдельно для каждого профиля.", "muted"),
        profileSelector(),
        Ui.card("Базовая модель", modelShowcase),
        buildToolbar(),
        adapters);

    disableProperty().bind(state.systemStarting.or(state.systemRunning));
    opacityProperty().bind(
        javafx.beans.binding.Bindings.when(state.systemStarting.or(state.systemRunning))
            .then(0.62).otherwise(1.0));

    state.activeProfile.addListener((obs, oldProfile, newProfile) -> refresh());
    refresh();
  }

  private Node profileSelector() {
    HBox buttons = new HBox(6);
    buttons.setFillHeight(true);
    buttons.setMaxWidth(Double.MAX_VALUE);
    ToggleGroup group = new ToggleGroup();

    for (Profile profile : Profile.values()) {
      ToggleButton button = new ToggleButton(profile.label());
      button.setToggleGroup(group);
      button.setUserData(profile);
      button.setMinWidth(0);
      button.setPrefWidth(1);
      button.setMaxWidth(Double.MAX_VALUE);
      HBox.setHgrow(button, Priority.ALWAYS);
      if (profile == state.activeProfile.get()) button.setSelected(true);
      buttons.getChildren().add(button);
    }

    Runnable refreshStyles = () -> {
      for (Node node : buttons.getChildren()) {
        ToggleButton button = (ToggleButton) node;
        boolean active = button.isSelected();
        button.setStyle(
            "-fx-background-color:" + (active ? "#6d5dfc" : "rgba(18,25,37,.82)") + ";"
                + "-fx-text-fill:" + (active ? "#ffffff" : "#aebbd0") + ";"
                + "-fx-background-radius:9;-fx-border-radius:9;"
                + "-fx-border-color:" + (active ? "#8578ff" : "#2b3950") + ";"
                + "-fx-border-width:1;-fx-font-weight:600;-fx-padding:8 7;-fx-cursor:hand;");
      }
    };

    group.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
      if (newToggle == null) {
        if (oldToggle != null) oldToggle.setSelected(true);
        return;
      }
      Profile selected = (Profile) newToggle.getUserData();
      if (state.activeProfile.get() != selected) state.activeProfile.set(selected);
      refreshStyles.run();
    });

    state.activeProfile.addListener((obs, oldProfile, newProfile) -> {
      if (newProfile == null) return;
      for (Toggle toggle : group.getToggles()) {
        if (toggle.getUserData() == newProfile) {
          if (!toggle.isSelected()) toggle.setSelected(true);
          break;
        }
      }
      refreshStyles.run();
    });

    refreshStyles.run();
    return buttons;
  }

  private Node buildToolbar() {
    Button importButton = Ui.button("Добавить LoRA", "attach", this::importLora);
    Button rescan = Ui.button("Пересканировать", "refresh", this::rescan);
    return Ui.row(Ui.label("GGUF LoRA выбранной модели", "card-title"), Ui.spacer(), importButton, rescan);
  }

  /** Re-read available base models when the LoRA tab is reopened. */
  public void refreshModels() {
    refresh();
  }

  private void saveLoraSettings() {
    Profile active = state.activeProfile.get();
    if (active != null) state.snapshotActiveProfile(active);
    persist.run();
  }

  private void refresh() {
    Profile profile = state.activeProfile.get();
    String previousPath = selectedModel == null ? null : selectedModel.fileName;

    availableModels.clear();
    for (ModelAssignment model : state.models) {
      if (model.generalModel()) availableModels.add(model);
    }

    selectedModelIndex = 0;
    if (previousPath != null) {
      for (int i = 0; i < availableModels.size(); i++) {
        if (previousPath.equals(availableModels.get(i).fileName)) {
          selectedModelIndex = i;
          break;
        }
      }
    }
    selectedModel = availableModels.isEmpty() ? null : availableModels.get(selectedModelIndex);
    rebuildModelShowcase();
    rebuildAdapters();
  }

  /** One-model-at-a-time carousel/showcase instead of giant model cards. */
  private void rebuildModelShowcase() {
    modelShowcase.getChildren().clear();
    modelShowcase.setAlignment(Pos.CENTER_LEFT);
    modelShowcase.setMaxWidth(Double.MAX_VALUE);

    if (selectedModel == null) {
      modelShowcase.getChildren().add(Ui.label("Основные LLM не обнаружены.", "muted"));
      return;
    }

    Button previous = new Button("‹");
    Button next = new Button("›");
    styleArrow(previous);
    styleArrow(next);
    previous.setDisable(availableModels.size() <= 1);
    next.setDisable(availableModels.size() <= 1);
    previous.setOnAction(e -> selectRelativeModel(-1));
    next.setOnAction(e -> selectRelativeModel(1));

    Label name = Ui.label(selectedModel.name, "card-title");
    Label path = Ui.label(selectedModel.fileName, "tiny");
    path.setWrapText(true);
    Label counter = Ui.label((selectedModelIndex + 1) + " / " + availableModels.size(), "muted");

    VBox text = new VBox(4, name, path);
    BorderPane selected = new BorderPane();
    selected.setCenter(text);
    selected.setRight(counter);
    BorderPane.setAlignment(counter, Pos.CENTER_RIGHT);
    selected.setPadding(new Insets(12, 16, 12, 16));
    selected.setMinHeight(66);
    selected.setMaxWidth(Double.MAX_VALUE);
    selected.setStyle(
        "-fx-background-color:#151c28;"
            + "-fx-background-radius:10;"
            + "-fx-border-radius:10;"
            + "-fx-border-color:#465875;"
            + "-fx-border-width:1;"
            + "-fx-cursor:hand;");
    HBox.setHgrow(selected, Priority.ALWAYS);

    // Clicking the showcase itself advances to the next model as well.
    selected.setOnMouseClicked(e -> {
      if (availableModels.size() > 1) selectRelativeModel(1);
    });

    modelShowcase.getChildren().addAll(previous, selected, next);
  }

  private void styleArrow(Button button) {
    button.setMinSize(42, 66);
    button.setPrefSize(42, 66);
    button.setMaxSize(42, 66);
    button.setStyle(
        "-fx-background-color:#151c28;"
            + "-fx-text-fill:#c7d2e5;"
            + "-fx-font-size:26px;"
            + "-fx-background-radius:10;"
            + "-fx-border-radius:10;"
            + "-fx-border-color:#2d3b51;"
            + "-fx-border-width:1;"
            + "-fx-cursor:hand;");
  }

  private void selectRelativeModel(int delta) {
    if (availableModels.isEmpty()) return;
    selectedModelIndex = Math.floorMod(selectedModelIndex + delta, availableModels.size());
    selectedModel = availableModels.get(selectedModelIndex);
    rebuildModelShowcase();
    rebuildAdapters();
  }

  private void rebuildAdapters() {
    adapters.getChildren().clear();
    ModelAssignment model = selectedModel;
    if (model == null) {
      adapters.getChildren().add(Ui.label("Выбери базовую модель.", "muted"));
      return;
    }
    if (model.loras.isEmpty()) {
      adapters.getChildren().add(Ui.label("Для выбранной модели LoRA не найдены.", "muted"));
      return;
    }

    for (LoraSetting lora : model.loras) {
      CheckBox enabled = new CheckBox();
      UiStyles.loraEnabledCheck(enabled);
      enabled.setMinSize(26, 26);
      enabled.setPrefSize(26, 26);
      enabled.selectedProperty().bindBidirectional(lora.enabled);
      enabled.setOnAction(e -> saveLoraSettings());

      Label name = Ui.label(lora.name, "card-title");
      Label path = Ui.label(lora.relativePath, "tiny");
      path.setWrapText(true);
      VBox text = new VBox(2, name, path);
      HBox.setHgrow(text, Priority.ALWAYS);

      Spinner<Double> scale = new Spinner<>(-16.0, 16.0, lora.scale.get(), 0.05);
      scale.setEditable(true);
      scale.setPrefWidth(105);
      scale.getValueFactory().valueProperty().addListener((o, a, b) -> {
        if (b != null && Math.abs(lora.scale.get() - b) > 0.000001) {
          lora.scale.set(b);
          saveLoraSettings();
        }
      });
      lora.scale.addListener((o, a, b) -> {
        if (Math.abs(scale.getValue() - b.doubleValue()) > 0.000001)
          scale.getValueFactory().setValue(b.doubleValue());
      });

      HBox row = new HBox(12, enabled, text, Ui.label("Scale", "tiny"), scale);
      row.setAlignment(Pos.CENTER_LEFT);
      row.setPadding(new Insets(9, 12, 9, 12));
      row.setMinHeight(54);
      row.setMaxWidth(Double.MAX_VALUE);
      row.setStyle(
          "-fx-background-color:#151c28;"
              + "-fx-background-radius:9;"
              + "-fx-border-radius:9;"
              + "-fx-border-color:#2d3b51;"
              + "-fx-border-width:1;");
      adapters.getChildren().add(row);
    }
  }

  private void importLora() {
    ModelAssignment model = selectedModel;
    if (model == null) {
      state.status.set("Сначала выбери модель для LoRA");
      return;
    }
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Выбрать архив LoRA");
    chooser.getExtensionFilters().add(
        new FileChooser.ExtensionFilter("LoRA archive (*.zip, *.rar)", "*.zip", "*.rar"));
    java.io.File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
    if (file == null) return;
    try {
      Path imported = new LoraArchiveService().importArchive(model, file.toPath());
      model.refreshLoras(Path.of(System.getProperty("user.dir"), "model"));
      model.applyProfile(state.modelProfileConfig(state.activeProfile.get(), model));
      state.status.set("LoRA импортирована: " + imported.getFileName());
      saveLoraSettings();
      rebuildAdapters();
    } catch (Exception e) {
      state.status.set("Ошибка импорта LoRA: " + e.getMessage());
      Alert alert = new Alert(Alert.AlertType.ERROR, e.getMessage(), ButtonType.OK);
      alert.setHeaderText("Не удалось импортировать LoRA");
      alert.showAndWait();
    }
  }

  private void rescan() {
    if (selectedModel != null) {
      selectedModel.refreshLoras(Path.of(System.getProperty("user.dir"), "model"));
      selectedModel.applyProfile(state.modelProfileConfig(state.activeProfile.get(), selectedModel));
    }
    rebuildAdapters();
  }
}
