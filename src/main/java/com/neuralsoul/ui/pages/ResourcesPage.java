package com.neuralsoul.ui.pages;

import com.neuralsoul.config.*;
import com.neuralsoul.model.*;
import com.neuralsoul.model.Profile;
import com.neuralsoul.ui.components.*;
import java.nio.file.Path;
import java.util.*;
import javafx.beans.property.*;
import javafx.collections.ListChangeListener;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;

public final class ResourcesPage extends VBox {
  /** General model placement vs. search/embedding configuration under Memory. */
  public enum View { GENERAL, MEMORY_SEARCH, WEB_SEARCH }

  private final boolean memorySearch;
  private final boolean webSearch;
  private boolean searchPage() { return memorySearch || webSearch; }
  private final AppState state;
  private final Runnable persist;
  private final VBox devicesHost = new VBox();
  private final VBox assignments;
  /** Live refresh hooks for every Web Search reasoning selector currently rendered. */
  private final List<Runnable> reasoningSelectorRefreshers = new ArrayList<>();

  public ResourcesPage(AppState state) {
    this(state, () -> {});
  }

  public ResourcesPage(AppState state, Runnable persist) {
    this(state, persist, View.GENERAL);
  }

  public ResourcesPage(AppState state, Runnable persist, View view) {
    super(10);
    this.state = state;
    this.persist = persist == null ? () -> {} : persist;
    this.memorySearch = view == View.MEMORY_SEARCH;
    this.webSearch = view == View.WEB_SEARCH;
    setPadding(new Insets(18));

    if (searchPage()) {
      getChildren().addAll(
          Ui.label(memorySearch ? "Поиск в памяти" : "Модели Web Search", "section-title"),
          Ui.label(memorySearch ? "Модели Memory Search и File Search"
              : "Модели для поиска информации в интернете", "muted"),
          profileSelector());
      String directory = memorySearch ? "model/memory" : "model/web_search";
      Label hint = Ui.label(
          "Источник GGUF: " + directory + " (относительно каталога NeuralSoul). "
          + "Выбрать модель из другой папки нельзя. Параметры сохраняются для каждого профиля.", "tiny");
      hint.setWrapText(true);
      getChildren().add(hint);
      if (memorySearch) getChildren().add(memorySearchExamples());
      getChildren().add(Ui.button("Обновить модели из " + directory, "refresh", this::refreshAll));
      assignments = Ui.card(memorySearch ? "Модели поиска в памяти" : "Модели интернет-поиска",
          Ui.label("Положи GGUF в " + directory + ". Новые файлы не подключаются автоматически. "
              + "Выбери роли и включи нужную модель в текущем профиле.", "tiny"));
      getChildren().add(assignments);
      disableProperty().bind(state.systemStarting.or(state.systemRunning));
      opacityProperty().bind(
          javafx.beans.binding.Bindings.when(state.systemStarting.or(state.systemRunning))
              .then(0.62).otherwise(1.0));
      state.devices.addListener((ListChangeListener<Device>) change -> rebuild());
      state.models.addListener((ListChangeListener<ModelAssignment>) change -> rebuild());
      rebuild();
      return;
    }

    getChildren().addAll(
        Ui.label("Основные модели", "section-title"),
        Ui.label("Основные LLM, устройства и распределение вычислительных ресурсов", "muted"),
        profileSelector());

    VBox modelContent = new VBox(10);
    modelContent.getChildren().add(
        Ui.row(
            Ui.label("Доступные устройства", "card-title"),
            Ui.spacer(),
            Ui.button("Обновить", "refresh", this::refreshAll)));
    modelContent.getChildren().add(devicesHost);

    Label description =
        Ui.label(
            "В автоматическом режиме NeuralSoul сам выбирает разрешённые CPU/GPU и распределение памяти.",
            "tiny");
    description.setWrapText(true);

    CheckBox allowSwap = Ui.check("Разрешить swap моделей", state.allowSwap);
    allowSwap.setTooltip(new Tooltip(
        "Автоматическое размещение может убрать лёгкую вспомогательную модель из VRAM в RAM/CPU, "
            + "если это освобождает место для более тяжёлых моделей. Main LLM в swap не отправляется."));
    CheckBox allowRamOffload =
        Ui.check("Разрешить совместное использование VRAM + RAM", state.allowRamOffload);
    allowRamOffload.setTooltip(new Tooltip(
        "Если целая модель не помещается в VRAM выбранной GPU, разрешить llama.cpp автоматически "
            + "оставить часть слоёв в RAM. Если выключено — автоматическое размещение использует "
            + "только полную загрузку в VRAM либо CPU/RAM."));
    allowSwap.setOnAction(e -> commitAndPersist());
    allowRamOffload.setOnAction(e -> commitAndPersist());
    VBox permissions = new VBox(6, allowSwap, allowRamOffload);

    ToggleButton automatic = Ui.toggle(state.automatic);
    automatic.setOnAction(e -> commitAndPersist());
    modelContent.getChildren().add(
        Ui.card(
            "Управление моделями",
            Ui.row(
                automatic,
                Ui.label("Автоматическое размещение моделей", "body")),
            description,
            permissions));

    assignments =
        Ui.card(
            "Размещение моделей",
            Ui.label(
                "В автоматическом режиме остаются доступны параметры и роли моделей; "
                    + "NeuralSoul блокирует только ручной выбор устройства и изоляцию.",
                "tiny"));
    modelContent.getChildren().add(assignments);


    Label warning =
        Ui.label(
            "Рычажок устройства запрещает NeuralSoul использовать его для моделей. "
                + "Отключённое устройство автоматически становится недоступным в размещении.",
            "tiny");
    warning.setWrapText(true);
    modelContent.getChildren().add(warning);

    getChildren().add(modelContent);

    disableProperty().bind(state.systemStarting.or(state.systemRunning));
    opacityProperty().bind(
        javafx.beans.binding.Bindings.when(state.systemStarting.or(state.systemRunning))
            .then(0.62).otherwise(1.0));

    state.devices.addListener((ListChangeListener<Device>) c -> rebuild());
    state.models.addListener((ListChangeListener<ModelAssignment>) c -> rebuild());
    rebuild();
  }

  /** Shows real invocation templates of the selected profile, not an outdated hard-coded command. */
  private Node memorySearchExamples() {
    Label example = Ui.label("", "tiny");
    example.setWrapText(true);
    Runnable update = () -> {
      Profile profile = state.activeProfile.get() == null ? Profile.NORMAL : state.activeProfile.get();
      String memory = state.agentCallTemplate(profile, ModelRole.MEMORY).get();
      String files = state.agentCallTemplate(profile, ModelRole.FILE_SEARCH).get();
      if (memory == null || !memory.contains("{task}"))
        memory = ProfileState.defaultAgentCallTemplate(ModelRole.MEMORY);
      if (files == null || !files.contains("{task}"))
        files = ProfileState.defaultAgentCallTemplate(ModelRole.FILE_SEARCH);
      example.setText("Примеры служебных вызовов для Main LLM:\n"
          + "Memory Search: " + memory.replace("{task}", "Что мы обсуждали про Qdrant?") + "\n"
          + "File Search: " + files.replace("{task}", "Найди Java-класс распределения моделей") + "\n"
          + "Команды выполняет пайплайн, пользователю они не показываются. "
          + "Шаблоны можно настроить в профиле.");
    };
    update.run();
    state.activeProfile.addListener((obs, oldProfile, newProfile) -> update.run());
    return Ui.card("Как вызвать Memory Search / File Search", example);
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
      if (state.activeProfile.get() != selected) {
        state.activeProfile.set(selected);
        persist.run();
      }
      refreshStyles.run();
      rebuild();
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
      rebuild();
    });

    refreshStyles.run();
    return buttons;
  }

  private void refreshAll() {
    if (!searchPage()) state.refreshHardwareDevices();
    state.refreshModelFiles();
    state.status.set(memorySearch ? "Список GGUF из model/memory обновлён"
        : webSearch ? "Список GGUF из model/web_search обновлён"
        : "Список устройств и моделей обновлён");
    rebuild();
  }

  private void rebuild() {
    if (!searchPage()) rebuildDeviceCards();
    rebuildAssignments();
  }

  private void rebuildDeviceCards() {
    List<Node> cards = new ArrayList<>();

    for (Device d : state.devices) {
      Label name = Ui.label(d.name, "tiny");
      name.setWrapText(true);
      Label details = Ui.label(d.details, "tiny");
      details.setWrapText(true);

      ToggleButton enabled = Ui.toggle(d.enabled);
      enabled.setTooltip(new Tooltip("Разрешить NeuralSoul использовать " + d.id));
      enabled.setOnAction(e -> {
        if (!d.enabled.get()) disableDeviceInAssignments(d.id);
        rebuildAssignments();
        commitAndPersist();
      });

      VBox card =
          Ui.card(
              "",
              Ui.row(Icons.of("chip"), Ui.label(d.id, "card-title")),
              name,
              details,
              Ui.row(Ui.label("Использовать", "tiny"), Ui.spacer(), enabled));

      card.setMinWidth(0);
      card.setPrefWidth(180);
      HBox.setHgrow(card, Priority.ALWAYS);
      cards.add(card);
    }

    devicesHost.getChildren().setAll(
        new ResponsiveColumns(Math.max(1, Math.min(3, cards.size())), 600, cards.toArray(Node[]::new)));
  }

  private void rebuildAssignments() {
    if (assignments == null) return;
    Node header = assignments.getChildren().isEmpty() ? null : assignments.getChildren().get(0);
    Node subtitle = assignments.getChildren().size() > 1 ? assignments.getChildren().get(1) : null;

    reasoningSelectorRefreshers.clear();
    assignments.getChildren().clear();
    if (header != null) assignments.getChildren().add(header);
    if (subtitle != null) assignments.getChildren().add(subtitle);

    int shown = 0;
    List<ModelAssignment> inactive = new ArrayList<>();
    for (ModelAssignment model : state.models) {
      if (searchPage()) {
        // Only dedicated GGUFs in the page's relative folder are available.
        if (memorySearch && !model.memoryOnly) continue;
        if (webSearch && !model.webSearchOnly) continue;
        if (model.roles.isEmpty()) {
          inactive.add(model);
          continue;
        }
      } else if (!model.generalModel()) {
        continue;
      }
      assignments.getChildren().add(assignment(model));
      shown++;
    }
    if (shown == 0) assignments.getChildren().add(Ui.label(
        memorySearch ? "Нет назначенных моделей. Положи GGUF в model/memory "
            + "и нажми «Подключить» ниже."
            : webSearch ? "Нет назначенной Web Search-модели. Положи GGUF в model/web_search "
                + "и нажми «Подключить» ниже."
            : "Нет обычных GGUF-моделей.", "muted"));

    if (searchPage() && !inactive.isEmpty()) {
      VBox available = new VBox(8);
      for (ModelAssignment model : inactive) {
        Label file = Ui.label(model.fileName, "tiny");
        file.setWrapText(true);
        HBox.setHgrow(file, Priority.ALWAYS);
        Button add = Ui.button("Подключить", "file", () -> {
          if (memorySearch) {
            model.setRole(ModelRole.MEMORY, true);
            model.setRole(ModelRole.FILE_SEARCH, true);
          } else {
            // Only one Web Search GGUF may be enabled per profile/runtime.
            for (ModelAssignment other : state.models) {
              if (other.webSearchOnly && other != model) {
                other.setRole(ModelRole.WEB_SEARCH, false);
                other.enabled.set(false);
              }
            }
            model.setRole(ModelRole.WEB_SEARCH, true);
          }
          model.enabled.set(true);
          commitAndPersist();
          state.status.set("Подключена модель: " + model.name);
          rebuildAssignments();
        });
        HBox row = new HBox(10, file, add);
        row.setAlignment(Pos.CENTER_LEFT);
        available.getChildren().add(row);
      }
      assignments.getChildren().add(Ui.card("Доступные GGUF без назначения", available));
    }
  }

  private void disableDeviceInAssignments(String id) {
    for (ModelAssignment model : state.models) {
      model.placement(id).set(false);
      if (id.equals(model.device.get())) {
        String fallback = "";
        for (Device device : state.devices) {
          if (!id.equals(device.id) && device.enabled.get()) {
            fallback = device.id;
            break;
          }
        }
        model.device.set(fallback);
      }
    }
  }

  private Device findDevice(String id) {
    if (id == null) return null;
    for (Device device : state.devices) {
      if (id.equals(device.id)) return device;
    }
    return null;
  }

  private Node assignment(ModelAssignment model) {
    Label name = Ui.label(model.name, "model-name");
    Label details = Ui.label(model.details, "tiny");
    ToggleButton useModel = Ui.toggle(model.enabled);
    useModel.setTooltip(new Tooltip("Загружать и использовать эту модель при запуске системы"));
    useModel.setOnAction(e -> {
      commitAndPersist();
      refreshReasoningSelectors();
    });

    ComboBox<Device> placement = new ComboBox<>();
    for (Device d : state.devices) if (d.enabled.get()) placement.getItems().add(d);
    placement.setConverter(new StringConverter<>() {
      @Override
      public String toString(Device device) {
        if (device == null) return "";
        return "CPU".equals(device.id) ? device.name : device.name;
      }

      @Override
      public Device fromString(String value) {
        return null;
      }
    });

    Device selectedDevice = findDevice(model.device.get());
    if (selectedDevice == null || !selectedDevice.enabled.get()) {
      selectedDevice = placement.getItems().isEmpty() ? null : placement.getItems().get(0);
      model.device.set(selectedDevice == null ? "" : selectedDevice.id);
    }
    placement.setValue(selectedDevice);
    placement.setOnAction(e -> {
      Device newDevice = placement.getValue();
      if (newDevice != null && !Objects.equals(model.device.get(), newDevice.id)) {
        model.device.set(newDevice.id);
      }
      commitAndPersist();
    });
    placement.setPrefWidth(280);
    placement.disableProperty().bind(state.automatic);
    placement.setTooltip(new Tooltip(
        "В автоматическом режиме устройство выбирает алгоритм размещения NeuralSoul."));

    HBox title = new HBox(8, Icons.of(model.icon), name);
    title.setAlignment(Pos.CENTER_LEFT);
    HBox.setHgrow(title, Priority.ALWAYS);

    HBox top = new HBox(12, title, Ui.label("Использовать", "tiny"), useModel, placement);
    top.setAlignment(Pos.CENTER_LEFT);

    GridPane llamaParams = new GridPane();
    llamaParams.setHgap(8);
    llamaParams.setVgap(6);
    llamaParams.add(Ui.label("llama.cpp параметры модели", "tiny"), 0, 0, 4, 1);

    Spinner<Integer> ctxSize = intSpinner(model.ctxSize, 256, 262144, 256, this::commitAndPersist);
    Spinner<Integer> maxOutputTokens = intSpinner(model.maxOutputTokens, 64, 131072, 64, this::commitAndPersist);
    maxOutputTokens.setTooltip(new Tooltip("Максимум токенов, которые эта модель может сгенерировать за один запрос. Это не --ctx-size."));
    Spinner<Integer> parallel = intSpinner(model.parallel, 1, 16, 1, this::commitAndPersist);
    Spinner<Integer> batchSize = intSpinner(model.batchSize, 32, 8192, 32, this::commitAndPersist);
    Spinner<Integer> ubatchSize = intSpinner(model.ubatchSize, 32, 8192, 32, this::commitAndPersist);
    ComboBox<String> cacheK = cacheTypeBox(model.cacheTypeK, this::commitAndPersist);
    ComboBox<String> cacheV = cacheTypeBox(model.cacheTypeV, this::commitAndPersist);
    ComboBox<String> isolation = new ComboBox<>();
    isolation.getItems().addAll("Нет", "По устройству");
    if (!isolation.getItems().contains(model.isolation.get())) model.isolation.set("Нет");
    isolation.setValue(model.isolation.get());
    isolation.valueProperty().addListener((obs, oldValue, newValue) -> {
      if (newValue != null && !Objects.equals(model.isolation.get(), newValue)) {
        model.isolation.set(newValue);
        commitAndPersist();
      }
    });
    isolation.setPrefWidth(150);
    isolation.disableProperty().bind(state.automatic);
    isolation.setTooltip(new Tooltip(
        "Нет — llama.cpp видит все устройства. По устройству — процесс видит только выбранную GPU. "
            + "В автоматическом режиме изоляцией управляет NeuralSoul."));

    llamaParams.add(Ui.label("--ctx-size", "tiny"), 0, 1); llamaParams.add(ctxSize, 1, 1);
    llamaParams.add(Ui.label("--parallel", "tiny"), 2, 1); llamaParams.add(parallel, 3, 1);
    llamaParams.add(Ui.label("--batch-size", "tiny"), 0, 2); llamaParams.add(batchSize, 1, 2);
    llamaParams.add(Ui.label("--ubatch-size", "tiny"), 2, 2); llamaParams.add(ubatchSize, 3, 2);
    llamaParams.add(Ui.label("--cache-type-k", "tiny"), 0, 3); llamaParams.add(cacheK, 1, 3);
    llamaParams.add(Ui.label("--cache-type-v", "tiny"), 2, 3); llamaParams.add(cacheV, 3, 3);
    if (model.generalModel()) {
      Label maxTokensLabel = Ui.label("Max output tokens", "tiny");
      maxTokensLabel.setTooltip(new Tooltip("Параметр max_tokens OpenAI-compatible API для этой модели."));
      llamaParams.add(maxTokensLabel, 0, 4);
      llamaParams.add(maxOutputTokens, 1, 4);
    }

    HBox isolationRow = new HBox(10, Ui.label("Изоляция модели:", "tiny"), isolation);
    isolationRow.setAlignment(Pos.CENTER_LEFT);

    VBox roleBox = new VBox(4);
    roleBox.getChildren().add(Ui.label(memorySearch ? "Функции поисковой модели" : webSearch ? "Роль интернет-поиска" : "Роли модели", "tiny"));
    FlowPane roles = new FlowPane(10, 4);
    VBox webReasoningBox = webSearch ? webReasoningSelector(model) : null;
    if (webReasoningBox != null) {
      boolean webRoleInitiallyEnabled = model.roles.contains(ModelRole.WEB_SEARCH);
      webReasoningBox.setVisible(webRoleInitiallyEnabled);
      webReasoningBox.setManaged(webRoleInitiallyEnabled);
    }

    VBox fileSearchLimitBox = memorySearch ? fileSearchLimit(model) : null;

    for (ModelRole role : ModelRole.values()) {
      boolean memoryRole = role == ModelRole.MEMORY || role == ModelRole.FILE_SEARCH;
      if (memorySearch && !memoryRole) continue;
      if (webSearch && role != ModelRole.WEB_SEARCH) continue;
      if (!searchPage() && (memoryRole || role == ModelRole.WEB_SEARCH)) continue;
      CheckBox check = new CheckBox(role.title());
      check.setSelected(model.roles.contains(role));
      check.setTooltip(new Tooltip(role.description()));
      check.selectedProperty().addListener((obs, old, enabled) -> {
        if (role == ModelRole.MAIN_LLM) {
          // MAIN_LLM is exclusive and profile-owned. Move it atomically in ProfileState instead
          // of mutating only the live model sets (which are merely the active-profile projection).
          if (enabled) {
            state.assignMainLlm(state.activeProfile.get(), model);
            commitAndPersist();
            rebuildAssignments();
          } else if (model.roles.contains(ModelRole.MAIN_LLM)) {
            // A running profile must always have one Main LLM. To move it, select Main LLM on
            // another model; assignMainLlm() will clear the previous one.
            check.setSelected(true);
          }
          return;
        }

        if (webSearch && enabled) {
          for (ModelAssignment other : state.models) {
            if (other.webSearchOnly && other != model) {
              other.setRole(ModelRole.WEB_SEARCH, false);
              other.enabled.set(false);
            }
          }
          model.enabled.set(true);
        }
        model.setRole(role, enabled);
        if (searchPage() && model.roles.isEmpty()) {
          // An unassigned file must not be loaded in the background.
          model.enabled.set(false);
          commitAndPersist();
          rebuildAssignments();
          return;
        }
        if (role == ModelRole.WEB_SEARCH && webReasoningBox != null) {
          webReasoningBox.setVisible(enabled);
          webReasoningBox.setManaged(enabled);
        }
        if (role == ModelRole.FILE_SEARCH && fileSearchLimitBox != null) {
          fileSearchLimitBox.setVisible(enabled);
          fileSearchLimitBox.setManaged(enabled);
        }
        commitAndPersist();
        if (role == ModelRole.REASONING || role == ModelRole.WEB_SEARCH) {
          refreshReasoningSelectors();
        }
        if (webSearch && enabled) rebuildAssignments();
      });
      roles.getChildren().add(check);
    }
    roleBox.getChildren().add(roles);
    if (webReasoningBox != null) roleBox.getChildren().add(webReasoningBox);
    if (memorySearch) roleBox.getChildren().add(fileSearchLimitBox);

    // Advanced llama.cpp settings and model roles take a lot of vertical space.
    // Keep them collapsed by default and reveal them only when the user asks for them.
    VBox advancedContent = new VBox(6, llamaParams, isolationRow, roleBox);
    advancedContent.setVisible(false);
    advancedContent.setManaged(false);

    ToggleButton advancedToggle = new ToggleButton("Параметры модели  ▸");
    advancedToggle.setSelected(false);
    advancedToggle.setFocusTraversable(false);
    advancedToggle.setTooltip(new Tooltip("Показать параметры llama.cpp и роли модели"));
    advancedToggle.setStyle(
        "-fx-background-color: transparent;"
            + "-fx-text-fill: #aebbd0;"
            + "-fx-padding: 4 12 4 12;"
            + "-fx-cursor: hand;"
            + "-fx-font-size: 11px;");
    advancedToggle.selectedProperty().addListener((obs, wasOpen, isOpen) -> {
      advancedContent.setVisible(isOpen);
      advancedContent.setManaged(isOpen);
      advancedToggle.setText(isOpen ? "Параметры модели  ▾" : "Параметры модели  ▸");
    });

    VBox card = new VBox(6, top, details, advancedToggle, advancedContent);
    card.getStyleClass().add("model-row");
    return card;
  }

  private VBox fileSearchLimit(ModelAssignment model) {
    Spinner<Integer> limit = intSpinner(
        model.fileSearchMaxVisited, 1_000, 5_000_000, 10_000, this::commitAndPersist);
    limit.setPrefWidth(150);
    limit.setTooltip(new Tooltip(
        "Максимальное число файлов и папок, проверяемых за один локальный поиск. "
            + "Большой лимит замедляет обход крупных деревьев."));
    VBox container = new VBox(4,
        Ui.label("Лимит обхода File Search", "tiny"),
        new HBox(10, Ui.label("Макс. объектов за поиск:", "tiny"), limit));
    boolean enabled = model.roles.contains(ModelRole.FILE_SEARCH);
    container.setVisible(enabled);
    container.setManaged(enabled);
    return container;
  }

  private VBox webReasoningSelector(ModelAssignment webModel) {
    Profile profile = state.activeProfile.get() == null ? Profile.NORMAL : state.activeProfile.get();
    ComboBox<ReasoningChoice> box = new ComboBox<>();
    box.setPrefWidth(360);

    // The list is intentionally live. A user can tick/untick the Reasoning role on another
    // model while this Web Search card is already on screen; rebuilding the whole Models page
    // would collapse every advanced section and used to leave this ComboBox stale.
    Runnable refreshChoices = () -> refreshReasoningChoices(box, webModel, profile);
    reasoningSelectorRefreshers.add(refreshChoices);

    box.setConverter(new StringConverter<>() {
      @Override public String toString(ReasoningChoice choice) { return choice == null ? "" : choice.label(); }
      @Override public ReasoningChoice fromString(String value) { return null; }
    });

    refreshChoices.run();

    box.valueProperty().addListener((obs, oldValue, newValue) -> {
      if (newValue == null) return;
      String current = state.webReasoningModelValue(profile, webModel.fileName);
      if (!Objects.equals(current, newValue.key())) {
        state.webReasoningModel(profile, webModel.fileName).set(newValue.key());
        commitAndPersist();
      }
    });

    Label hint = Ui.label(
        "В списке доступны Main LLM и включённые модели с ролью Reasoning. "
            + "Main LLM не загружается второй раз; отдельная Reasoning-модель запускается как зависимость Web Search.",
        "tiny");
    hint.setWrapText(true);
    HBox row = new HBox(10, Ui.label("Reasoning для Web Search:", "tiny"), box);
    row.setAlignment(Pos.CENTER_LEFT);
    VBox container = new VBox(4, row, hint);
    container.setPadding(new Insets(4, 0, 0, 14));
    return container;
  }

  private void refreshReasoningChoices(
      ComboBox<ReasoningChoice> box, ModelAssignment webModel, Profile profile) {
    if (box == null || webModel == null) return;

    String selectedKey = state.webReasoningModelValue(profile, webModel.fileName).replace('\\', '/');
    List<ReasoningChoice> choices = new ArrayList<>();

    String mainName = state.models.stream()
        .filter(ModelAssignment::generalModel)
        .filter(m -> state.modelProfileConfig(profile, m).enabled())
        .filter(m -> state.modelProfileConfig(profile, m).roles().contains(ModelRole.MAIN_LLM))
        .map(m -> m.name)
        .findFirst().orElse("текущая");
    choices.add(new ReasoningChoice(ProfileState.MAIN_LLM_REASONING, "Main LLM — " + mainName));

    for (ModelAssignment candidate : state.models) {
      if (!candidate.generalModel() || candidate == webModel) continue;
      ModelAssignment.ProfileConfig cfg = state.modelProfileConfig(profile, candidate);
      if (!cfg.enabled()) continue;
      if (cfg.roles().contains(ModelRole.MAIN_LLM)) continue;
      if (!cfg.roles().contains(ModelRole.REASONING)) continue;
      choices.add(new ReasoningChoice(candidate.fileName.replace('\\', '/'), candidate.name));
    }

    ReasoningChoice selected = choices.stream()
        .filter(choice -> choice.key().equals(selectedKey))
        .findFirst().orElse(choices.get(0));

    box.getItems().setAll(choices);
    box.setValue(selected);

    // If the selected model was disabled or lost the Reasoning role, immediately fall back to
    // Main LLM so the persisted configuration can never point at an invalid hidden candidate.
    if (!selected.key().equals(selectedKey)) {
      state.webReasoningModel(profile, webModel.fileName).set(selected.key());
      persist.run();
    }
  }

  private void refreshReasoningSelectors() {
    // Copy the list because a future UI refresh may replace selector nodes while an event is firing.
    for (Runnable refresher : List.copyOf(reasoningSelectorRefreshers)) refresher.run();
  }

  private record ReasoningChoice(String key, String label) {}

  /**
   * Commits the live Models editor into the active profile immediately.
   * This is intentionally user-action driven: rebuilding/applying another profile must not
   * overwrite the profile that is being loaded.
   */
  private void commitAndPersist() {
    Profile profile = state.activeProfile.get();
    if (profile != null) state.snapshotActiveProfile(profile);
    persist.run();
  }

  private Spinner<Integer> intSpinner(
      IntegerProperty property, int min, int max, int step, Runnable onChange) {
    Spinner<Integer> spinner = new Spinner<>(min, max, Math.max(min, Math.min(max, property.get())), step);
    spinner.setEditable(true);
    spinner.setPrefWidth(110);
    spinner.getValueFactory().valueProperty().addListener((obs, oldValue, newValue) -> {
      if (newValue != null && property.get() != newValue) {
        property.set(newValue);
        onChange.run();
      }
    });
    return spinner;
  }

  private ComboBox<String> cacheTypeBox(StringProperty property, Runnable onChange) {
    ComboBox<String> box = new ComboBox<>();
    box.getItems().addAll("f32", "f16", "q8_0", "q4_0", "q4_1", "iq4_nl");
    if (!box.getItems().contains(property.get())) box.getItems().add(property.get());
    box.setValue(property.get());
    box.valueProperty().addListener((obs, oldValue, newValue) -> {
      if (newValue != null && !Objects.equals(property.get(), newValue)) {
        property.set(newValue);
        onChange.run();
      }
    });
    box.setPrefWidth(110);
    return box;
  }

  private Node limit(String title, DoubleProperty property, int min, int max, String unit) {
    Label l = Ui.label(title, "tiny");
    l.setPrefWidth(200);
    l.setMinWidth(110);
    l.setWrapText(true);

    Slider slider = new Slider(min, max, property.get());
    slider.setBlockIncrement(1);
    slider.setMajorTickUnit(1);
    slider.setMinorTickCount(0);
    slider.setSnapToTicks(true);
    slider.valueProperty().bindBidirectional(property);
    slider.setMinWidth(80);
    HBox.setHgrow(slider, Priority.ALWAYS);

    Label value = Ui.label("", "tiny");
    value.textProperty().bind(property.asString("%.0f / " + max + " " + unit));
    value.setPrefWidth(85);
    return Ui.row(l, slider, value);
  }
}
