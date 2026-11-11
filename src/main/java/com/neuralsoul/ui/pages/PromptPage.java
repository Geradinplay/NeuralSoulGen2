package com.neuralsoul.ui.pages;

import com.neuralsoul.model.Profile;
import com.neuralsoul.model.AppState;
import com.neuralsoul.model.ModelRole;
import com.neuralsoul.model.ProfileState;
import com.neuralsoul.ui.components.Ui;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

public final class PromptPage extends VBox {

  public PromptPage(AppState state, Runnable save) {
    super(14);
    setPadding(new Insets(24));

    Label status = Ui.label("", "muted");
    status.textProperty().bind(
            Bindings.when(state.systemRunning.or(state.systemStarting))
                    .then("● ONLINE — остановите систему для редактирования")
                    .otherwise("● OFFLINE — промты доступны для редактирования"));

    StackPane content = new StackPane();

    java.util.EnumMap<Profile, VBox> panes =
            new java.util.EnumMap<>(Profile.class);

    for (Profile p : Profile.values()) {
      VBox pane = promptPane(state, p, save);

      boolean selected = p == state.activeProfile.get();
      pane.setVisible(selected);
      pane.setManaged(selected);

      panes.put(p, pane);
      content.getChildren().add(pane);
    }

    /*
     * Все профили показываем сразу.
     * Кнопки равномерно делят всю доступную ширину.
     */
    HBox profileButtons = new HBox(6);
    profileButtons.setFillHeight(true);
    profileButtons.setMaxWidth(Double.MAX_VALUE);

    ToggleGroup profileGroup = new ToggleGroup();

    for (Profile p : Profile.values()) {
      ToggleButton button = new ToggleButton(p.label());

      button.setToggleGroup(profileGroup);
      button.setUserData(p);

      button.setMinWidth(0);
      button.setPrefWidth(1);
      button.setMaxWidth(Double.MAX_VALUE);

      HBox.setHgrow(button, Priority.ALWAYS);

      if (p == state.activeProfile.get()) {
        button.setSelected(true);
      }

      profileButtons.getChildren().add(button);
    }

    Runnable refreshStyles = () -> {
      for (javafx.scene.Node node : profileButtons.getChildren()) {
        ToggleButton button = (ToggleButton) node;
        boolean active = button.isSelected();

        button.setStyle(
                "-fx-background-color: "
                        + (active ? "#6d5dfc" : "rgba(18,25,37,.82)")
                        + ";"
                        + "-fx-text-fill: "
                        + (active ? "#ffffff" : "#aebbd0")
                        + ";"
                        + "-fx-background-radius: 9;"
                        + "-fx-border-radius: 9;"
                        + "-fx-border-color: "
                        + (active ? "#8578ff" : "#2b3950")
                        + ";"
                        + "-fx-border-width: 1;"
                        + "-fx-font-weight: 600;"
                        + "-fx-padding: 8 7;"
                        + "-fx-cursor: hand;");
      }
    };

    profileGroup.selectedToggleProperty().addListener(
            (o, oldToggle, newToggle) -> {
              // Не позволяем оставить интерфейс вообще без выбранного профиля.
              if (newToggle == null) {
                if (oldToggle != null) {
                  oldToggle.setSelected(true);
                }
                return;
              }

              Profile selected = (Profile) newToggle.getUserData();

              panes.forEach(
                      (profile, pane) -> {
                        boolean show = profile == selected;
                        pane.setVisible(show);
                        pane.setManaged(show);
                      });

              state.activeProfile.set(selected);
              refreshStyles.run();
            });

    /*
     * Если профиль был изменён где-то ещё в интерфейсе,
     * например в чате, PromptPage тоже переключится.
     */
    state.activeProfile.addListener(
            (o, oldProfile, newProfile) -> {
              if (newProfile == null) {
                return;
              }

              for (Toggle toggle : profileGroup.getToggles()) {
                if (toggle.getUserData() == newProfile) {
                  if (!toggle.isSelected()) {
                    toggle.setSelected(true);
                  }
                  break;
                }
              }
            });

    refreshStyles.run();

    VBox.setVgrow(content, Priority.ALWAYS);

    getChildren()
            .addAll(
                    Ui.label("Текущий промт", "page-title"),
                    status,
                    profileButtons,
                    content);
  }

  private VBox promptPane(AppState state, Profile profile, Runnable save) {
    TextArea personality =
            editor(state.personalityPrompt(profile).get(), 10);

    TextArea general =
            editor(state.generalPrompt(profile).get(), 18);

    personality
            .disableProperty()
            .bind(state.systemRunning.or(state.systemStarting));

    general
            .disableProperty()
            .bind(state.systemRunning.or(state.systemStarting));

    personality
            .textProperty()
            .addListener(
                    (o, a, b) -> state.personalityPrompt(profile).set(b));

    general
            .textProperty()
            .addListener(
                    (o, a, b) -> state.generalPrompt(profile).set(b));

    Label effective = Ui.label("", "tiny");
    effective.setWrapText(true);

    effective
            .textProperty()
            .bind(
                    Bindings.createStringBinding(
                            () ->
                                    "Итоговый общий промт: "
                                            + state.effectiveGeneralPrompt(profile).length()
                                            + " символов · активных goal-модулей: "
                                            + state.agentPromptModules.size(),
                            state.generalPrompt(profile),
                            state.agentPromptModules));

    Button saveButton =
            Ui.button("Сохранить", "save", save);

    saveButton
            .disableProperty()
            .bind(state.systemRunning.or(state.systemStarting));

    Button resetButton =
            Ui.button(
                    "Сбросить промт",
                    "refresh",
                    () -> {
                      // Restore the canonical editable prompt and persist it. Behavioral
                      // rules live only here — the pipeline injects no hidden prompt text.
                      general.setText(
                              com.neuralsoul.config.AgentPrompts.defaultGeneralPrompt());
                      save.run();
                    });

    resetButton
            .disableProperty()
            .bind(state.systemRunning.or(state.systemStarting));

    VBox box =
            new VBox(
                    14,
                    agentCallTemplatesCard(state, profile),
                    Ui.card(
                            "1. Личность · " + profile.label(),
                            wrapped(
                                    "Личность, характер, стиль общения и поведение для этого режима."),
                            personality),
                    Ui.card(
                            "2. Общий промт · " + profile.label(),
                            wrapped(
                                    "Базовые инструкции для этого режима. Включённые цели агента добавляются автоматически."),
                            general,
                            effective),
                    Ui.row(saveButton, resetButton));

    box.setPadding(new Insets(14, 0, 0, 0));

    return box;
  }

  private VBox agentCallTemplatesCard(AppState state, Profile profile) {
    VBox rows = new VBox(9);

    // Built-in agents/tools use an internal protocol. It is intentionally read-only:
    // changing parser syntax from the GUI can desynchronise Main LLM instructions and runtime parsing.
    // Only concrete GENERAL models keep a user-editable call example below.
    for (ModelRole role : ProfileState.callTemplateRoles()) {
      Label name = Ui.label(role.title(), "muted");
      name.setMinWidth(105);

      String fixedTemplate = ProfileState.defaultAgentCallTemplate(role);
      TextField template = new TextField(fixedTemplate);
      template.setEditable(false);
      template.setFocusTraversable(true); // keep it selectable/copyable
      HBox.setHgrow(template, Priority.ALWAYS);

      Label fixed = Ui.label("системный", "tiny");
      fixed.setMinWidth(72);

      Button help = new Button("?");
      help.setOnAction(e -> showTemplateHelp(role));
      help.setMinSize(28, 28);
      help.setPrefSize(28, 28);
      help.setMaxSize(28, 28);
      help.setStyle("-fx-background-radius: 14; -fx-border-radius: 14; -fx-padding: 0; -fx-font-weight: 700;");

      HBox line = new HBox(8, name, template, fixed, help);
      line.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
      rows.getChildren().add(line);
    }

    // GENERAL is not one global role. Every model with the General checkbox is a distinct agent.
    for (com.neuralsoul.model.ModelAssignment model : state.models) {
      if (!model.generalModel()) continue;
      com.neuralsoul.model.ModelAssignment.ProfileConfig cfg = state.modelProfileConfig(profile, model);
      if (!cfg.enabled() || !cfg.roles().contains(ModelRole.GENERAL)) continue;

      Label name = Ui.label("General · " + model.name, "muted");
      name.setMinWidth(180);

      TextField template = new TextField(state.generalAgentCallTemplate(profile, model).get());
      String defaultTemplate = ProfileState.defaultGeneralAgentCallTemplate(model.fileName, model.name);
      template.setPromptText(defaultTemplate);
      HBox.setHgrow(template, Priority.ALWAYS);
      template.disableProperty().bind(state.systemRunning.or(state.systemStarting));

      Label warning = Ui.label("", "tiny");
      warning.setWrapText(true);
      warning.setManaged(false);
      warning.setVisible(false);

      Runnable validate = () -> {
        String value = template.getText() == null ? "" : template.getText();
        boolean ok = validTemplate(value);
        warning.setText(ok ? "" : "Шаблон должен содержать ровно один {task} и текст до и после него.");
        warning.setManaged(!ok);
        warning.setVisible(!ok);
        if (ok) state.generalAgentCallTemplate(profile, model).set(value);
      };
      template.textProperty().addListener((o, a, b) -> validate.run());

      Button help = new Button("?");
      help.setOnAction(e -> showGeneralTemplateHelp(model.name, defaultTemplate));
      help.setMinSize(28, 28);
      help.setPrefSize(28, 28);
      help.setMaxSize(28, 28);
      help.setStyle("-fx-background-radius: 14; -fx-border-radius: 14; -fx-padding: 0; -fx-font-weight: 700;");

      Button reset = new Button("Сбросить");
      reset.setOnAction(e -> {
        template.setText(defaultTemplate);
        state.generalAgentCallTemplate(profile, model).set(defaultTemplate);
      });
      reset.disableProperty().bind(state.systemRunning.or(state.systemStarting));

      HBox line = new HBox(8, name, template, help, reset);
      line.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
      rows.getChildren().addAll(line, warning);
    }

    return Ui.card(
        "Форматы вызова агентов и инструментов · " + profile.label(),
        wrapped("NeuralSoul перехватывает эти конструкции в ответе Main LLM. {task} — место для задачи. "
            + "Форматы встроенных агентов и инструментов являются системными и доступны только для просмотра. "
            + "Изменять пример вызова можно только у конкретных General-моделей; для них настройка отдельная для каждого профиля."),
        rows);
  }

  private boolean validTemplate(String value) {
    if (value == null) return false;
    int first = value.indexOf("{task}");
    if (first <= 0) return false;
    if (first != value.lastIndexOf("{task}")) return false;
    return first + 6 < value.length();
  }

  private void showTemplateHelp(ModelRole role) {
    Alert alert = new Alert(Alert.AlertType.INFORMATION);
    alert.setTitle("Формат вызова агента");
    alert.setHeaderText(role.title());
    alert.setContentText(
        "Это системный формат вызова встроенного агента/инструмента. Он показывается в «Текущем промте» для понимания того, какой синтаксис получает Main LLM, но изменять его нельзя.\n\n"
            + "Формат:\n" + ProfileState.defaultAgentCallTemplate(role) + "\n\n"
            + "{task} — место, куда Main LLM подставляет конкретную задачу. Пользовательский формат вызова доступен только для General-моделей.");
    alert.showAndWait();
  }

  private void showGeneralTemplateHelp(String modelName, String defaultTemplate) {
    Alert alert = new Alert(Alert.AlertType.INFORMATION);
    alert.setTitle("Формат вызова General-агента");
    alert.setHeaderText("General · " + modelName);
    alert.setContentText(
        "Этот General-вызов привязан именно к модели «" + modelName + "» и к текущему профилю.\n\n"
            + "Используйте {task} ровно один раз.\n\n"
            + "Стандартный формат:\n" + defaultTemplate + "\n\n"
            + "Можно задать собственный синтаксис, например:\n<general_model>{task}</general_model>");
    alert.showAndWait();
  }

  private TextArea editor(String text, int rows) {
    TextArea area =
            new TextArea(text == null ? "" : text);

    area.setWrapText(true);
    area.setPrefRowCount(rows);
    area.setMaxWidth(Double.MAX_VALUE);

    VBox.setVgrow(area, Priority.ALWAYS);

    return area;
  }

  private Label wrapped(String text) {
    Label label = Ui.label(text, "muted");
    label.setWrapText(true);
    return label;
  }
}