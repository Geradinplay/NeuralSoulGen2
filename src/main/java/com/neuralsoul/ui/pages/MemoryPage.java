package com.neuralsoul.ui.pages;

import com.neuralsoul.memory.*;
import com.neuralsoul.model.AppState;
import com.neuralsoul.model.Profile;
import com.neuralsoul.model.ModelAssignment;
import com.neuralsoul.model.ModelRole;
import com.neuralsoul.ui.components.*;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.*;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

public final class MemoryPage extends VBox {

  private record SummaryModelChoice(String key, String label) {
    @Override public String toString() { return label; }
  }

  private final MemoryService memory;
  private MemoryService selectedMemory;

  private final MemorySettings settings;
  private final AppState state;

  private final ObservableList<MemoryRecord> rows =
          FXCollections.observableArrayList();

  private final TableView<MemoryRecord> table =
          new TableView<>(rows);

  private final Label status =
          Ui.label("Готово", "tiny");

  public MemoryPage(
          MemoryService memory,
          MemorySettings settings,
          AppState state) {
    this(memory, settings, state, false);
  }

  /** Render inside the Memory hub without a duplicate page heading. */
  public MemoryPage(
          MemoryService memory,
          MemorySettings settings,
          AppState state,
          boolean embedded) {

    super(16);

    this.memory = memory;
    this.selectedMemory = memory;
    this.settings = settings;
    this.state = state;

    setPadding(new Insets(24));

    if (embedded) {
      getChildren().add(Ui.label("Основная память", "section-title"));
    } else {
      getChildren().add(Ui.page("Память",
          "Отдельная долговременная память для каждого профиля"));
    }

    /*
     * Выбор памяти профиля.
     *
     * Здесь специально показываем все семь профилей одновременно,
     * а не ComboBox.
     */
    if (memory instanceof ProfileMemoryService profiles) {

      selectedMemory =
              profiles.service(state.activeProfile.get());

      HBox profileButtons = new HBox(6);
      profileButtons.setFillHeight(true);
      profileButtons.setMaxWidth(Double.MAX_VALUE);

      ToggleGroup profileGroup = new ToggleGroup();

      for (Profile profile :
              Profile.values()) {

        ToggleButton button =
                new ToggleButton(profile.label());

        button.setToggleGroup(profileGroup);
        button.setUserData(profile);

        button.setMinWidth(0);
        button.setPrefWidth(1);
        button.setMaxWidth(Double.MAX_VALUE);

        HBox.setHgrow(button, Priority.ALWAYS);

        if (profile == state.activeProfile.get()) {
          button.setSelected(true);
        }

        profileButtons.getChildren().add(button);
      }

      Runnable refreshProfileStyles =
              () -> {
                for (javafx.scene.Node node :
                        profileButtons.getChildren()) {

                  ToggleButton button =
                          (ToggleButton) node;

                  boolean active =
                          button.isSelected();

                  button.setStyle(
                          "-fx-background-color: "
                                  + (active
                                  ? "#6d5dfc"
                                  : "rgba(18,25,37,.82)")
                                  + ";"
                                  + "-fx-text-fill: "
                                  + (active
                                  ? "#ffffff"
                                  : "#aebbd0")
                                  + ";"
                                  + "-fx-background-radius: 9;"
                                  + "-fx-border-radius: 9;"
                                  + "-fx-border-color: "
                                  + (active
                                  ? "#8578ff"
                                  : "#2b3950")
                                  + ";"
                                  + "-fx-border-width: 1;"
                                  + "-fx-font-weight: 600;"
                                  + "-fx-padding: 8 7;"
                                  + "-fx-cursor: hand;");
                }
              };

      profileGroup
              .selectedToggleProperty()
              .addListener(
                      (o, oldToggle, newToggle) -> {

                        if (newToggle == null) {
                          if (oldToggle != null) {
                            oldToggle.setSelected(true);
                          }
                          return;
                        }

                        Profile selected =
                                (Profile)
                                        newToggle.getUserData();

                        selectedMemory =
                                profiles.service(selected);

                        /*
                         * Профиль глобальный:
                         * чат, промт и память используют одно значение.
                         */
                        state.activeProfile.set(selected);

                        refreshProfileStyles.run();
                        refresh();
                      });

      /*
       * Если профиль поменяли в чате или на странице промта,
       * страница памяти автоматически переключается на него.
       */
      state.activeProfile.addListener(
              (o, oldProfile, newProfile) -> {

                if (newProfile == null) {
                  return;
                }

                selectedMemory =
                        profiles.service(newProfile);

                for (Toggle toggle :
                        profileGroup.getToggles()) {

                  if (toggle.getUserData() == newProfile) {

                    if (!toggle.isSelected()) {
                      toggle.setSelected(true);
                    }

                    break;
                  }
                }

                refresh();
              });

      refreshProfileStyles.run();

      getChildren()
              .add(
                      Ui.card(
                              "Профиль памяти",
                              Ui.label(
                                      "Память профилей физически разделена: записи одного профиля не участвуют в поиске другого.",
                                      "tiny"),
                              profileButtons));
    }

    Slider score =
            new Slider(
                    0,
                    1,
                    settings.minScore.get());

    score.setShowTickLabels(true);
    score.setMajorTickUnit(.2);

    Spinner<Integer> max =
            new Spinner<>(
                    1,
                    100,
                    settings.maxResults.get());

    Spinner<Integer> context =
            new Spinner<>(
                    1,
                    200,
                    settings.contextMessages.get());

    /*
     * Не используем bindBidirectional(...asObject()).
     * Временный ObjectProperty может быть weakly referenced,
     * после чего Spinner визуально возвращается к старому значению.
     */
    score
            .valueProperty()
            .addListener(
                    (o, oldValue, newValue) ->
                            settings.minScore.set(
                                    newValue.doubleValue()));

    max
            .valueProperty()
            .addListener(
                    (o, oldValue, newValue) ->
                            settings.maxResults.set(newValue));

    context
            .valueProperty()
            .addListener(
                    (o, oldValue, newValue) ->
                            settings.contextMessages.set(newValue));

    ComboBox<SummaryModelChoice> summarizationModel = new ComboBox<>();
    summarizationModel.setMaxWidth(Double.MAX_VALUE);
    Profile modelProfile = state.activeProfile.get() == null ? Profile.NORMAL : state.activeProfile.get();
    ModelAssignment mainModel = null;
    for (ModelAssignment model : state.models) {
      if (!model.generalModel()) continue;
      ModelAssignment.ProfileConfig cfg = state.modelProfileConfig(modelProfile, model);
      if (cfg.enabled() && cfg.roles().contains(ModelRole.MAIN_LLM)) {
        mainModel = model;
        break;
      }
    }
    String mainLabel = mainModel == null ? "Main LLM" : "Main LLM — " + mainModel.name;
    summarizationModel.getItems().add(
            new SummaryModelChoice(MemorySettings.MAIN_LLM_SUMMARIZER, mainLabel));
    for (ModelAssignment model : state.models) {
      if (!model.generalModel() || model == mainModel) continue;
      ModelAssignment.ProfileConfig cfg = state.modelProfileConfig(modelProfile, model);
      if (!cfg.enabled()) continue;
      summarizationModel.getItems().add(
              new SummaryModelChoice(model.fileName.replace('\\', '/'), model.name));
    }
    String savedSummarizer = settings.summarizationModel.get();
    SummaryModelChoice selectedSummarizer = summarizationModel.getItems().stream()
            .filter(choice -> choice.key().equals(savedSummarizer))
            .findFirst()
            .orElse(summarizationModel.getItems().get(0));
    summarizationModel.getSelectionModel().select(selectedSummarizer);
    settings.summarizationModel.set(selectedSummarizer.key());
    summarizationModel.valueProperty().addListener((o, oldValue, newValue) -> {
      if (newValue != null) settings.summarizationModel.set(newValue.key());
    });

    VBox searchSettings =
            Ui.card(
                    "Параметры поиска и контекста",
                    Ui.label(
                            "Эти параметры изменяются только при остановленной системе.",
                            "tiny"),
                    Ui.label(
                            "Чувствительность памяти / минимальный score Qdrant",
                            "body"),
                    score,
                    Ui.row(
                            Ui.label(
                                    "Максимум воспоминаний из Qdrant",
                                    "body"),
                            max),
                    Ui.row(
                            Ui.label(
                                    "Последних сообщений обычного контекста",
                                    "body"),
                            context),
                    Ui.label(
                            "Модель суммаризации контекста",
                            "body"),
                    summarizationModel,
                    Ui.label(
                            "Когда сырой контекст перестаёт помещаться, старые пары диалога сжимаются выбранной моделью в rolling summary. Main LLM использует уже запущенную основную модель; другая модель запускается отдельно вместе с системой.",
                            "tiny"));

    searchSettings
            .disableProperty()
            .bind(
                    state.systemRunning.or(
                            state.systemStarting));

    getChildren().add(searchSettings);

    TextField add = new TextField();
    add.setPromptText("Новое воспоминание…");

    HBox.setHgrow(add, Priority.ALWAYS);

    Button save =
            Ui.button(
                    "Добавить",
                    "memory",
                    () -> {
                      selectedMemory.add(add.getText());
                      add.clear();
                      refresh();
                    });

    Button summarize =
            Ui.button(
                    "Суммаризировать",
                    "spark",
                    this::summarize);

    Button refresh =
            Ui.button(
                    "Обновить",
                    "memory",
                    this::refreshFromStorage);

    Button imp =
            Ui.button(
                    "Импорт",
                    "attach",
                    this::importFile);

    Button json =
            Ui.button(
                    "Экспорт JSON",
                    "file",
                    () -> export(false));

    Button xlsx =
            Ui.button(
                    "Экспорт Excel",
                    "file",
                    () -> export(true));

    Button clear =
            Ui.button(
                    "Очистить всю память",
                    "stop",
                    this::clearAll);

    VBox onlineActions =
            new VBox(
                    10,
                    Ui.label(
                            "Управление памятью доступно после запуска системы.",
                            "tiny"),
                    Ui.row(add, save),
                    Ui.row(
                            refresh,
                            summarize,
                            imp,
                            json,
                            xlsx,
                            clear));

    onlineActions
            .disableProperty()
            .bind(state.systemRunning.not());

    getChildren()
            .addAll(
                    onlineActions,
                    status);

    buildTable();

    table
            .disableProperty()
            .bind(state.systemRunning.not());

    refresh();

    VBox.setVgrow(
            table,
            Priority.ALWAYS);

    getChildren().add(table);
  }

  private void buildTable() {

    TableColumn<MemoryRecord, String> date =
            new TableColumn<>("Дата");

    date.setCellValueFactory(
            c ->
                    new SimpleStringProperty(
                            DateTimeFormatter
                                    .ofPattern("dd.MM.yyyy HH:mm")
                                    .withZone(
                                            ZoneId.systemDefault())
                                    .format(
                                            c.getValue()
                                                    .createdAt())));

    date.setPrefWidth(145);

    TableColumn<MemoryRecord, String> type =
            new TableColumn<>("Тип");

    type.setCellValueFactory(
            c ->
                    new SimpleStringProperty(
                            c.getValue().type()));

    type.setPrefWidth(110);

    TableColumn<MemoryRecord, String> text =
            new TableColumn<>("Воспоминание");

    text.setCellValueFactory(
            c ->
                    new SimpleStringProperty(
                            c.getValue().text()));

    text.setPrefWidth(560);

    TableColumn<MemoryRecord, Void> actions =
            new TableColumn<>("Действия");

    actions.setPrefWidth(100);

    actions.setCellFactory(
            c ->
                    new TableCell<>() {

                      final Button del =
                              Ui.button(
                                      "Удалить",
                                      "stop",
                                      () -> {

                                        MemoryRecord record =
                                                getTableView()
                                                        .getItems()
                                                        .get(getIndex());

                                        selectedMemory.delete(
                                                record.id());

                                        refresh();
                                      });

                      @Override
                      protected void updateItem(
                              Void value,
                              boolean empty) {

                        super.updateItem(
                                value,
                                empty);

                        setGraphic(
                                empty
                                        ? null
                                        : del);
                      }
                    });

    table
            .getColumns()
            .addAll(
                    date,
                    type,
                    text,
                    actions);

    table.setColumnResizePolicy(
            TableView.CONSTRAINED_RESIZE_POLICY);
  }

  private void refresh() {
    rows.setAll(
            selectedMemory.all());

    status.setText(
            "Воспоминаний: "
                    + rows.size());
  }

  private void refreshFromStorage() {

    status.setText(
            "Обновление памяти…");

    CompletableFuture
            .runAsync(
                    () -> {
                      try {
                        selectedMemory.synchronize();
                      } catch (Exception e) {
                        throw new CompletionException(e);
                      }
                    })
            .whenComplete(
                    (unused, error) ->
                            Platform.runLater(
                                    () -> {

                                      if (error != null) {
                                        status.setText(
                                                "Ошибка обновления: "
                                                        + rootMessage(error));
                                        return;
                                      }

                                      refresh();
                                      table.refresh();

                                      status.setText(
                                              "Память обновлена · воспоминаний: "
                                                      + rows.size());
                                    }));
  }

  private String rootMessage(Throwable error) {

    Throwable current = error;

    while (current.getCause() != null) {
      current = current.getCause();
    }

    return current.getMessage() == null
            ? current.toString()
            : current.getMessage();
  }

  private void clearAll() {

    Alert alert =
            new Alert(
                    Alert.AlertType.CONFIRMATION,
                    "Удалить ВСЮ память?",
                    ButtonType.YES,
                    ButtonType.NO);

    alert.setHeaderText(
            "Очистка памяти");

    if (alert
            .showAndWait()
            .orElse(ButtonType.NO)
            == ButtonType.YES) {

      selectedMemory.clear();
      refresh();
    }
  }

  private void summarize() {

    ChoiceDialog<String> dialog =
            new ChoiceDialog<>(
                    "Вся память",
                    "Вся память",
                    "Сегодня",
                    "7 дней",
                    "30 дней");

    dialog.setTitle(
            "Суммаризация");

    dialog.setHeaderText(
            "Выбери период");

    dialog
            .showAndWait()
            .ifPresent(
                    choice -> {

                      Instant from =
                              switch (choice) {

                                case "Сегодня" ->
                                        LocalDate
                                                .now()
                                                .atStartOfDay(
                                                        ZoneId.systemDefault())
                                                .toInstant();

                                case "7 дней" ->
                                        Instant
                                                .now()
                                                .minus(
                                                        Duration.ofDays(7));

                                case "30 дней" ->
                                        Instant
                                                .now()
                                                .minus(
                                                        Duration.ofDays(30));

                                default -> null;
                              };

                      status.setText(
                              "Суммаризация…");

                      selectedMemory
                              .summarize(
                                      from,
                                      Instant.now())
                              .whenComplete(
                                      (summary, error) ->
                                              Platform.runLater(
                                                      () -> {

                                                        if (error != null) {
                                                          status.setText(
                                                                  "Ошибка: "
                                                                          + error.getMessage());
                                                          return;
                                                        }

                                                        TextArea area =
                                                                new TextArea(summary);

                                                        area.setWrapText(true);
                                                        area.setEditable(false);

                                                        Dialog<ButtonType>
                                                                resultDialog =
                                                                new Dialog<>();

                                                        resultDialog.setTitle(
                                                                "Summary");

                                                        resultDialog
                                                                .getDialogPane()
                                                                .setContent(area);

                                                        ButtonType keep =
                                                                new ButtonType(
                                                                        "Сохранить в память",
                                                                        ButtonBar.ButtonData.OK_DONE);

                                                        resultDialog
                                                                .getDialogPane()
                                                                .getButtonTypes()
                                                                .addAll(
                                                                        keep,
                                                                        ButtonType.CLOSE);

                                                        if (resultDialog
                                                                .showAndWait()
                                                                .orElse(
                                                                        ButtonType.CLOSE)
                                                                == keep) {

                                                          selectedMemory.add(
                                                                  summary);

                                                          refresh();
                                                        }

                                                        status.setText(
                                                                "Готово");
                                                      }));
                    });
  }

  private void export(boolean excel) {

    FileChooser chooser =
            new FileChooser();

    chooser.setInitialFileName(
            excel
                    ? "neuralsoul-selectedMemory.xlsx"
                    : "neuralsoul-selectedMemory.json");

    Path path =
            file(
                    chooser,
                    true);

    if (path == null) {
      return;
    }

    try {

      if (excel) {
        selectedMemory.exportXlsx(path);
      } else {
        selectedMemory.exportJson(path);
      }

      status.setText(
              "Экспортировано: "
                      + path);

    } catch (Exception e) {

      status.setText(
              "Ошибка экспорта: "
                      + e.getMessage());
    }
  }

  private void importFile() {

    FileChooser chooser =
            new FileChooser();

    chooser
            .getExtensionFilters()
            .addAll(
                    new FileChooser.ExtensionFilter(
                            "Memory JSON/Excel",
                            "*.json",
                            "*.xlsx"));

    Path path =
            file(
                    chooser,
                    false);

    if (path == null) {
      return;
    }

    try {

      if (path
              .toString()
              .toLowerCase()
              .endsWith(".xlsx")) {

        selectedMemory.importXlsx(path);

      } else {

        selectedMemory.importJson(path);
      }

      refresh();

    } catch (Exception e) {

      status.setText(
              "Ошибка импорта: "
                      + e.getMessage());
    }
  }

  private Path file(
          FileChooser chooser,
          boolean save) {

    java.io.File file =
            save
                    ? chooser.showSaveDialog(
                    getScene().getWindow())
                    : chooser.showOpenDialog(
                    getScene().getWindow());

    return file == null
            ? null
            : file.toPath();
  }
}