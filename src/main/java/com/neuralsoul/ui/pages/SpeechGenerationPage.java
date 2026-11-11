package com.neuralsoul.ui.pages;

import com.neuralsoul.config.ConfigLocator;
import com.neuralsoul.config.SpeechGenerationSettingsStore;
import com.neuralsoul.config.SpeechGenerationSettingsStore.Common;
import com.neuralsoul.config.SpeechGenerationSettingsStore.Fish;
import com.neuralsoul.config.SpeechGenerationSettingsStore.Settings;
import com.neuralsoul.model.AppState;
import com.neuralsoul.model.Device;
import com.neuralsoul.ui.components.Icons;
import com.neuralsoul.ui.components.Ui;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.collections.ListChangeListener;
import javafx.collections.WeakListChangeListener;
import java.util.Locale;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.util.StringConverter;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/**
 * Fish Speech streaming TTS configuration. Runtime integration lives in ServiceLocator;
 * keep this JavaFX page free of audio codec and networking logic.
 */
public final class SpeechGenerationPage extends VBox {
  private record EngineOption(String id, String label) {
    @Override public String toString() { return label; }
  }
  private record Option(String id, String label) {
    @Override public String toString() { return label; }
  }

  // Extensibility boundary: add another engine + its own engine-specific controls;
  // common input, output and transport settings do not depend on Fish Speech.
  private static final EngineOption FISH_ENGINE = new EngineOption(
      SpeechGenerationSettingsStore.FISH_S2, "Fish Speech S2-Pro · GGUF / s2.cpp");
  private static final Option HTTP = new Option(SpeechGenerationSettingsStore.HTTP, "HTTP · PCM16 streaming");
  private static final Option GRPC = new Option(SpeechGenerationSettingsStore.GRPC, "gRPC · требуется отдельный сервер");
  private static final Option CHUNKS = new Option(SpeechGenerationSettingsStore.CHUNKS,
      "Чанки текста · предложения/паузы");
  private static final Option INCREMENTAL = new Option(SpeechGenerationSettingsStore.INCREMENTAL,
      "Непрерывный поток LLM → очередь фраз");

  private final SpeechGenerationSettingsStore store;
  private final AppState state;
  /** When enabled, pipeline routes TTS through the persistent Fish Speech service. */
  private final BooleanProperty ttsEnabled = new SimpleBooleanProperty(false);
  private final ComboBox<String> deviceChoice = new ComboBox<>();
  private final ComboBox<String> discoveredModels = new ComboBox<>();
  private final Label modelTitle = Ui.label("Fish Speech S2-Pro", "model-name");
  private final Label modelLocation = wrap("");
  private final Label deviceHint = wrap("");
  // Keep a device ID, not a hard-coded GPU model or vendor-specific ordinal.
  private String manualDeviceId = "";
  // Runtime preference remains independent of automatic device placement or CPU fallback.
  private String runtimePreference = "VULKAN";
  private final ToggleButton cudaRuntime = new ToggleButton("CUDA");
  private final ToggleButton vulkanRuntime = new ToggleButton("Vulkan");
  private final Label runtimeHint = wrap("");
  private boolean synchronizingDevices;
  /** Prevent partial model settings being written while controls are being populated. */
  private boolean loadingControls;
  /** UI changes are persisted without relying on the explicit Save button. */
  private boolean autoSaveReady;
  private final PauseTransition textSaveDelay = new PauseTransition(Duration.millis(550));
  // Observe enabled flags as well as the list itself: the Resources page changes
  // Device.enabled without adding/removing a Device from state.devices.
  private final Map<Device, ChangeListener<Boolean>> deviceEnabledListeners = new IdentityHashMap<>();
  // Weak listeners prevent discarded Voice pages from being retained by AppState.
  private final ChangeListener<Boolean> automaticListener = (obs, before, after) -> {
    refreshDeviceChoices();
    autoSave();
  };
  private final ListChangeListener<Device> devicesListener = change -> {
    watchDeviceEnabledFlags();
    refreshDeviceChoices();
    autoSave();
  };
  private final ComboBox<EngineOption> engine = new ComboBox<>();
  private final ComboBox<Option> transport = new ComboBox<>();
  private final ComboBox<Option> textMode = new ComboBox<>();
  private final TextField minChunk = field(), maxChunk = field(), flushDelay = field(), queueLimit = field();
  private final TextField startBuffer = field(), stride = field(), holdback = field(), sentencePause = field();
  private final CheckBox lowLatency = new CheckBox("Минимальная задержка (low_latency)");
  private final CheckBox segmentSentences = new CheckBox("Разделять генерацию по предложениям");

  private final TextField executable = field(), model = field(), tokenizer = field();
  private final TextField cpuThreads = field(), host = field(), httpPort = field(), grpcPort = field();
  private final TextField voice = field(), refAudio = field(), refText = field();
  private final TextField temperature = field(), topP = field(), topK = field(), maxTokens = field();
  private final Label transportHint = wrap("");
  private final Label textHint = wrap("");
  private final Label feedback = wrap("");
  private final TextArea testText = new TextArea("Привет! Я NeuralSoul. Проверяем потоковую генерацию речи.");
  private final java.util.concurrent.atomic.AtomicBoolean testInProgress = new java.util.concurrent.atomic.AtomicBoolean();
  private final Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

  public SpeechGenerationPage(AppState state, ConfigLocator configLocator) {
    super(12);
    setPadding(new Insets(12, 4, 24, 4));
    setMaxWidth(Double.MAX_VALUE);
    setMinWidth(0);
    this.state = state;
    store = new SpeechGenerationSettingsStore(configLocator.directory());

    engine.getItems().add(FISH_ENGINE);
    transport.getItems().setAll(HTTP, GRPC);
    textMode.getItems().setAll(CHUNKS, INCREMENTAL);
    engine.setMaxWidth(Double.MAX_VALUE);
    transport.setMaxWidth(Double.MAX_VALUE);
    textMode.setMaxWidth(Double.MAX_VALUE);
    engine.setValue(FISH_ENGINE);

    transport.valueProperty().addListener((obs, before, value) -> updateTransportHint());
    textMode.valueProperty().addListener((obs, before, value) -> updateTextHint());


    VBox basic = Ui.card("Общие настройки",
        grid(
            "TTS-движок", engine,
            "Транспорт", transport),
        transportHint,
        wrap("При включении Fish Speech NeuralSoul переключается со старого Coqui/OpenAI на "
            + "постоянный s2.cpp сервер. Если Fish Speech отключён — используется старый TTS."));

    VBox input = Ui.card("Входящий текст · LLM → TTS",
        grid("Режим подачи текста", textMode,
            "Минимум символов в чанке", minChunk,
            "Максимум символов в чанке", maxChunk,
            "Ожидание следующего фрагмента, мс", flushDelay,
            "Лимит очереди чанков", queueLimit),
        textHint,
        wrap("LLM выдаёт токены → Java собирает фразы → очередь → /generate. "
            + "Один HTTP-запрос содержит завершённый чанк текста, а аудио возвращается потоком; "
            + "при отмене ответа поток закрывается, но s2.exe остаётся запущенным."));

    CheckBox pcm = new CheckBox("Выводить аудио потоком · PCM S16LE (mono)");
    pcm.setSelected(true);
    pcm.setDisable(true); // streaming output is a design requirement, not a toggle
    VBox output = Ui.card("Исходящее аудио · TTS → плеер",
        pcm,
        grid("Буфер перед воспроизведением, мс", startBuffer,
            "Stride декодирования, кадров (0 = auto)", stride,
            "Holdback, кадров (-1 = auto)", holdback,
            "Пауза между предложениями, мс", sentencePause),
        lowLatency,
        segmentSentences,
        wrap("Для HTTP: поток сырых PCM16-байтов, а не готовый WAV. "
            + "Плеер должен читать частоту дискретизации из заголовка сервера и "
            + "регулировать буфер, если генерация идёт медленнее воспроизведения."));

    VBox fishRuntime = buildFishModelCard();

    VBox fishServer = Ui.card("Fish Speech S2-Pro · сервер",
        grid("Адрес", host,
            "HTTP порт", httpPort,
            "gRPC порт (планируется)", grpcPort),
        wrap("Сейчас работает HTTP /generate с потоковым PCM16. У s2.cpp нет собственного "
            + "gRPC-сервера; выбор gRPC приведёт к понятной ошибке до появления отдельного адаптера."));

    VBox fishVoice = Ui.card("Fish Speech S2-Pro · голос и сэмплирование",
        grid("Готовый голос (.s2voice / ID)", voice,
            "Референс WAV/MP3", withFilePicker(refAudio, "Референс голоса", "*.wav", "*.mp3"),
            "Текст референса", refText),
        wrap("Референс и сохранённый профиль голоса — альтернативные источники голоса. "
            + "Для референса нужен соответствующий текст. Возможности зависят от версии s2.cpp."));

    testText.setWrapText(true);
    testText.setPrefRowCount(3);
    testText.setMaxWidth(Double.MAX_VALUE);
    Button test = Ui.button("Запустить тест TTS", "voice", () -> testGeneration());
    VBox testing = Ui.card("Проверка потоковой генерации",
        wrap("Тест использует текущие настройки. Все переключатели сохраняются автоматически."),
        testText, test, wrap("Тест запускает и держит сервер s2.cpp в памяти. "
            + "Логи процесса: logs/tts-fish-s2.log"));

    Button save = Ui.button("Сохранить настройки TTS", "tasks", this::save);
    Button restore = Ui.button("Вернуть стандартные значения в полях", "refresh", () -> {
      loadToControls(Settings.defaults());
      if (save()) feedback.setText("Настройки TTS сброшены к значениям по умолчанию и сохранены.");
    });
    HBox buttons = new HBox(10, save, restore);
    buttons.setAlignment(Pos.CENTER_LEFT);
    buttons.setMinWidth(0);

    getChildren().addAll(
        Ui.label("Генерация речи", "section-title"),
        wrap("Скоростной TTS для NeuralSoul · общий потоковый тракт и сменные движки"),
        fishRuntime, basic, input, output, fishServer, fishVoice, testing,
        buttons, feedback,
        Ui.card("Состояние реализации",
            wrap("РАБОТАЕТ: постоянный Fish Speech runtime, HTTP PCM16 streaming, "
                + "очередь фраз LLM, потоковое воспроизведение, отмена текущей речи."),
            wrap("ОГРАНИЧЕНИЕ: настоящий двусторонний gRPC и непрерывный токенный вход "
                + "внутри одного запроса s2.cpp не поддерживаются.")));
    // The Models page owns this switch. The model selector reacts immediately when it changes.
    state.automatic.addListener(new WeakChangeListener<>(automaticListener));
    state.devices.addListener(new WeakListChangeListener<>(devicesListener));
    watchDeviceEnabledFlags();
    reload();
    installAutoSave();
  }

  /**
   * Fish Speech model card follows the same layout as the Memory/Web Search model cards.
   * No inference runtime is launched here: device selection is a saved preference only.
   * Other TTS engines can supply another card without changing the common stream controls.
   */
  private VBox buildFishModelCard() {
    ToggleButton useModel = Ui.toggle(ttsEnabled);
    useModel.setTooltip(new Tooltip(
        "Включает Fish Speech вместо старого TTS при следующем подключении/запросе. "
            + "Модель остаётся загруженной до отключения службы или системы."));

    modelTitle.setWrapText(true);
    modelTitle.setMinWidth(0);
    HBox title = new HBox(8, Icons.of("voice"), modelTitle);
    title.setAlignment(Pos.CENTER_LEFT);
    title.setMinWidth(0);
    HBox.setHgrow(title, Priority.ALWAYS);

    deviceChoice.setPrefWidth(250);
    deviceChoice.setMaxWidth(310);
    deviceChoice.setMinWidth(0);
    deviceChoice.setConverter(new StringConverter<>() {
      @Override public String toString(String id) {
        if (id == null) return "";
        if ("AUTO".equals(id)) return "Автоматически · Модели";
        for (Device d : state.devices) if (d.id.equals(id)) return d.name;
        return id;
      }
      @Override public String fromString(String label) { return null; }
    });
    deviceChoice.setTooltip(new Tooltip(
        "Обнаруженные CPU/GPU из общего AppState NeuralSoul. "
            + "При автоматическом размещении ручной выбор заблокирован."));
    deviceChoice.disableProperty().bind(state.automatic);
    deviceChoice.valueProperty().addListener((obs, before, selected) -> {
      if (synchronizingDevices || state.automatic.get() || selected == null || "AUTO".equals(selected)) return;
      manualDeviceId = selected;
      refreshRuntimeAvailability();
      autoSave();
    });

    deviceHint.setText("Ручной режим: выбери разрешённое устройство для Fish Speech.");

    HBox top = new HBox(10, title, Ui.label("Использовать", "tiny"), useModel, deviceChoice);
    top.setMinWidth(0);
    top.setAlignment(Pos.CENTER_LEFT);

    model.textProperty().addListener((obs, oldValue, newValue) -> updateModelTitle());
    discoveredModels.setMinWidth(0);
    discoveredModels.setMaxWidth(Double.MAX_VALUE);
    discoveredModels.setPromptText("Модель GGUF из model/tts/fish_speech");
    discoveredModels.valueProperty().addListener((obs, before, selected) -> {
      if (selected != null && !selected.isBlank() && !selected.equals(value(model))) {
        model.setText(selected);
      }
    });
    Button refreshModels = Ui.button("Обновить", "refresh", this::refreshDiscoveredModels);
    HBox availableRow = new HBox(8, discoveredModels, refreshModels);
    HBox.setHgrow(discoveredModels, Priority.ALWAYS);
    availableRow.setMinWidth(0);

    VBox advanced = new VBox(10,
        grid("Найденные GGUF", availableRow,
            "Файл модели", withFilePicker(model, "Модель GGUF", "*.gguf"),
            "EXE рантайма", withFilePicker(executable, "s2.exe", "*.exe"),
            "Tokenizer JSON (auto = рядом с runtime)", withFilePicker(tokenizer, "Tokenizer JSON", "*.json"),
            "CPU threads", cpuThreads),
        wrap("Устройство выбирается из обнаруженного NeuralSoul оборудования. Для CPU "
            + "GPU backend не применяется. {backend} в пути рантайма заменяется на cuda, vulkan "
            + "или cpu. Tokenizer = auto берётся рядом с выбранным s2.exe. Пути относительные к проекту."),
        grid("Temperature", temperature,
            "Top-p", topP,
            "Top-k", topK,
            "Max tokens", maxTokens));
    advanced.setMinWidth(0);
    advanced.setVisible(false);
    advanced.setManaged(false);
    Button expand = Ui.button("Параметры модели ▾", "settings", () -> {
      boolean open = !advanced.isVisible();
      advanced.setVisible(open);
      advanced.setManaged(open);
    });
    VBox card = Ui.card("Fish Speech S2-Pro · модель",
        top, modelLocation, deviceHint, buildRuntimeControls(), expand, advanced);
    card.setMinWidth(0);
    card.setMaxWidth(Double.MAX_VALUE);
    return card;
  }

  private void updateModelTitle() {
    String path = value(model);
    modelLocation.setText(path.isBlank() ? "Файл модели не выбран" : path);
    if (path.isBlank()) {
      modelTitle.setText("Fish Speech S2-Pro · модель не выбрана");
    } else {
      String file = path.replace('\\', '/');
      file = file.substring(file.lastIndexOf('/') + 1);
      modelTitle.setText(file.toLowerCase(Locale.ROOT).endsWith(".gguf")
          ? file.substring(0, file.length() - 5) : file);
    }
  }

  private void refreshDiscoveredModels() {
    Path root = workingDirectory.resolve("model/tts/fish_speech");
    List<String> found = new ArrayList<>();
    if (Files.isDirectory(root)) {
      try (var paths = Files.walk(root)) {
        paths.filter(Files::isRegularFile)
            .filter(f -> !Files.isSymbolicLink(f))
            .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf"))
            .sorted(Comparator.naturalOrder())
            .forEach(f -> found.add(workingDirectory.relativize(f).toString().replace('\\', '/')));
      } catch (Exception ex) {
        feedback.setText("Не удалось прочитать model/tts/fish_speech: " + ex.getMessage());
      }
    }
    String selected = value(model);
    if (!selected.isBlank() && !found.contains(selected)) found.add(selected);
    discoveredModels.getItems().setAll(found);
    if (!selected.isBlank()) discoveredModels.setValue(selected);
    else if (!found.isEmpty()) {
      // The first discovered file is not activated without explicit user selection.
      discoveredModels.getSelectionModel().clearSelection();
    }
    updateModelTitle();
  }

  private void watchDeviceEnabledFlags() {
    // Device instances are recreated by refreshHardwareDevices(). Detach callbacks
    // from old instances and subscribe to the newly detected ones.
    for (var entry : deviceEnabledListeners.entrySet()) {
      entry.getKey().enabled.removeListener(entry.getValue());
    }
    deviceEnabledListeners.clear();
    for (Device device : state.devices) {
      ChangeListener<Boolean> listener = (observable, before, after) -> {
        refreshDeviceChoices();
        autoSave();
      };
      device.enabled.addListener(listener);
      deviceEnabledListeners.put(device, listener);
    }
  }

  private Device findDetectedDevice(String id) {
    for (Device device : state.devices) if (device.id.equals(id)) return device;
    return null;
  }

  private void refreshDeviceChoices() {
    synchronizingDevices = true;
    try {
      List<String> enabledIds = new ArrayList<>();
      for (Device device : state.devices) {
        if (device.enabled.get()) enabledIds.add(device.id);
      }
      if (state.automatic.get()) {
        deviceChoice.getItems().setAll("AUTO");
        deviceChoice.setValue("AUTO");
        deviceHint.setText("Автоматическое размещение включено во вкладке Модели. "
            + "Когда TTS будет интегрирован, выбор устройства передадим общему планировщику. "
            + "Сейчас это только настройка GUI.");
      } else {
        deviceChoice.getItems().setAll(enabledIds);
        if (!enabledIds.contains(manualDeviceId)) {
          manualDeviceId = enabledIds.isEmpty() ? "" : enabledIds.get(0);
        }
        deviceChoice.setValue(manualDeviceId.isBlank() ? null : manualDeviceId);
        deviceHint.setText(enabledIds.isEmpty()
            ? "Нет разрешённых устройств. Разреши CPU или GPU во вкладке Модели."
            : "Доступны только обнаруженные и разрешённые в NeuralSoul устройства. "
                + "Для каждого GPU показывается настоящее название из системы.");
      }
      refreshRuntimeAvailability();
    } finally {
      synchronizingDevices = false;
    }
  }

  private static boolean isNvidia(Device device) {
    if (device == null) return false;
    String name = device.name.toLowerCase(Locale.ROOT);
    return name.contains("nvidia") || name.contains("geforce")
        || name.contains("quadro") || name.contains("tesla") || name.contains("rtx");
  }

  /** CUDA / Vulkan switch belongs to this model card, not a separate TTS-wide card. */
  private VBox buildRuntimeControls() {
    ToggleGroup group = new ToggleGroup();
    cudaRuntime.setToggleGroup(group);
    vulkanRuntime.setToggleGroup(group);
    cudaRuntime.setMinWidth(125);
    vulkanRuntime.setMinWidth(125);
    cudaRuntime.setOnAction(event -> chooseRuntime("CUDA"));
    vulkanRuntime.setOnAction(event -> chooseRuntime("VULKAN"));
    HBox choices = new HBox(8, Ui.label("GPU backend:", "muted"), cudaRuntime, vulkanRuntime);
    choices.setAlignment(Pos.CENTER_LEFT);
    choices.setMinWidth(0);
    chooseRuntime("VULKAN");
    VBox panel = new VBox(6, choices, runtimeHint);
    panel.setMinWidth(0);
    return panel;
  }

  private void chooseRuntime(String choice) {
    runtimePreference = "CUDA".equals(choice) ? "CUDA" : "VULKAN";
    cudaRuntime.setSelected("CUDA".equals(runtimePreference));
    vulkanRuntime.setSelected("VULKAN".equals(runtimePreference));
    styleRuntimeButton(cudaRuntime, cudaRuntime.isSelected());
    styleRuntimeButton(vulkanRuntime, vulkanRuntime.isSelected());
    updateRuntimeHint();
    autoSave();
  }

  private static void styleRuntimeButton(ToggleButton button, boolean active) {
    button.setStyle("-fx-background-color:" + (active ? "#6d5dfc" : "rgba(18,25,37,.82)") + ";"
        + "-fx-text-fill:" + (active ? "white" : "#aebbd0") + ";"
        + "-fx-border-color:" + (active ? "#8578ff" : "#2b3950") + ";"
        + "-fx-background-radius:9;-fx-border-radius:9;-fx-border-width:1;"
        + "-fx-font-weight:600;-fx-padding:10 18;-fx-cursor:hand;");
  }

  private void refreshRuntimeAvailability() {
    boolean gpuEnabled = false;
    boolean nvidiaEnabled = false;
    for (Device device : state.devices) {
      if (!device.enabled.get() || "CPU".equals(device.id)) continue;
      if (state.automatic.get() || device.id.equals(manualDeviceId)) {
        gpuEnabled = true;
        if (isNvidia(device)) nvidiaEnabled = true;
      }
    }
    cudaRuntime.setDisable(!nvidiaEnabled);
    vulkanRuntime.setDisable(!gpuEnabled);
    if (gpuEnabled && !nvidiaEnabled && "CUDA".equals(runtimePreference)) {
      chooseRuntime("VULKAN");
    } else {
      updateRuntimeHint();
    }
  }

  private void updateRuntimeHint() {
    if (state == null) return;
    if (state.automatic.get()) {
      boolean supported = !"CUDA".equals(runtimePreference)
          || state.devices.stream().anyMatch(d -> d.enabled.get() && isNvidia(d));
      runtimeHint.setText(supported
          ? "Автоматическое размещение включено: NeuralSoul выберет устройство из разрешённых. "
              + "Предпочтение backend: " + runtimePreference + "."
          : "Внимание: CUDA выбран, но разрешённая NVIDIA GPU не обнаружена. "
              + "Выбери Vulkan или разреши NVIDIA GPU во вкладке Модели.");
      return;
    }
    Device chosen = findDetectedDevice(manualDeviceId);
    if (chosen == null) {
      runtimeHint.setText("Устройство не выбрано. Разреши CPU/GPU во вкладке Модели.");
    } else if ("CPU".equals(chosen.id)) {
      runtimeHint.setText("Выбран CPU: CUDA/Vulkan не используется. "
          + "Предпочтение " + runtimePreference + " сохранится для GPU.");
    } else if ("CUDA".equals(runtimePreference) && !isNvidia(chosen)) {
      runtimeHint.setText("CUDA работает только с NVIDIA GPU. Для текущей видеокарты выбери Vulkan.");
    } else {
      runtimeHint.setText("Устройство: " + chosen.name + ". Backend: " + runtimePreference
          + ". Доступность backend проверится при подключении движка.");
    }
  }

  private String effectiveSavedBackend() {
    if (state.automatic.get()) {
      // The device is chosen later by NeuralSoul; the CUDA/Vulkan preference is
      // persisted separately and must be validated against the selected device.
      return "AUTO";
    }
    if (manualDeviceId.isBlank())
      throw new IllegalArgumentException("Для TTS не выбрано устройство. Разреши CPU/GPU во вкладке Модели.");
    Device selected = findDetectedDevice(manualDeviceId);
    if (selected == null || !selected.enabled.get())
      throw new IllegalArgumentException("Выбранное TTS-устройство недоступно или отключено.");
    if ("CPU".equals(manualDeviceId)) return "CPU";
    if ("CUDA".equals(runtimePreference) && !isNvidia(selected))
      throw new IllegalArgumentException("CUDA поддерживается только для NVIDIA GPU. Выбери Vulkan.");
    return runtimePreference;
  }

  private int selectedGpuIndex() {
    // GPU IDs are generated dynamically by AppState.refreshHardwareDevices().
    if (!manualDeviceId.isBlank() && manualDeviceId.startsWith("GPU ")) {
      try { return Integer.parseInt(manualDeviceId.substring(4)); }
      catch (NumberFormatException ignored) { /* keep compatibility with saved legacy IDs */ }
    }
    return 0;
  }

  private void updateTransportHint() {
    Option value = transport.getValue();
    transportHint.setText(value != null && SpeechGenerationSettingsStore.GRPC.equals(value.id())
        ? "s2.cpp не имеет gRPC-сервера. Выбор пока заблокирует TTS, "
            + "используй HTTP до подключения настоящего gRPC-адаптера."
        : "HTTP: у s2.cpp поддерживается поток PCM16 на выход. "
            + "Каждый запрос /generate пока получает завершённый текстовый фрагмент.");
  }

  private void updateTextHint() {
    Option value = textMode.getValue();
    textHint.setText(value != null && SpeechGenerationSettingsStore.INCREMENTAL.equals(value.id())
        ? "Токены LLM поступают немедленно, Java собирает фразы и отправляет их по очереди. "
            + "Один /generate всё ещё принимает только завершённый текстовый чанк."
        : "Режим чанков: LLM посылает частичные ответы, Java собирает фразы и отдаёт "
            + "их TTS отдельными заданиями. Аудио каждого задания может выводиться потоком.");
  }

  private HBox withFilePicker(TextField field, String title, String... patterns) {
    Button button = Ui.button("...", "file", () -> chooseFile(field, title, patterns));
    button.setMinWidth(42);
    HBox container = new HBox(8, field, button);
    container.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(field, Priority.ALWAYS);
    return container;
  }

  private void chooseFile(TextField target, String title, String... patterns) {
    FileChooser chooser = new FileChooser();
    chooser.setTitle(title);
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(title, patterns));
    File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
    if (file == null) return;
    Path chosen = file.toPath().toAbsolutePath().normalize();
    if (!chosen.startsWith(workingDirectory)) {
      feedback.setText("Файл находится вне папки NeuralSoul. Перемести его внутрь проекта, "
          + "чтобы использовать относительный путь.");
      return;
    }
    target.setText(workingDirectory.relativize(chosen).toString().replace('\\', '/'));
  }

  private void reload() {
    try {
      loadToControls(store.load());
      feedback.setText("Настройки хранятся в " + store.path().getFileName() + ".");
    } catch (Exception e) {
      loadToControls(Settings.defaults());
      feedback.setText("Не удалось прочитать TTS-настройки: " + e.getMessage());
    }
  }

  private void loadToControls(Settings settings) {
    // This method also runs when the user presses Reset. Saving a half-populated
    // collection of controls would otherwise overwrite valid TTS settings.
    boolean wasLoading = loadingControls;
    loadingControls = true;
    textSaveDelay.stop();
    try {
      Common c = settings.common();
      Fish f = settings.fish();
      ttsEnabled.set(c.enabled());
      engine.setValue(FISH_ENGINE);
      transport.setValue(SpeechGenerationSettingsStore.GRPC.equals(c.transport()) ? GRPC : HTTP);
      textMode.setValue(SpeechGenerationSettingsStore.INCREMENTAL.equals(c.textInputMode()) ? INCREMENTAL : CHUNKS);
      minChunk.setText(Integer.toString(c.minChunkCharacters()));
      maxChunk.setText(Integer.toString(c.maxChunkCharacters()));
      flushDelay.setText(Integer.toString(c.flushDelayMs()));
      queueLimit.setText(Integer.toString(c.queueLimit()));
      startBuffer.setText(Integer.toString(c.playbackStartBufferMs()));
      stride.setText(Integer.toString(c.decodeStrideFrames()));
      holdback.setText(Integer.toString(c.holdbackFrames()));
      lowLatency.setSelected(c.lowLatency());
      segmentSentences.setSelected(c.segmentSentences());
      sentencePause.setText(Integer.toString(c.sentencePauseMs()));
      executable.setText(f.executablePath());
      model.setText(f.modelPath());
      tokenizer.setText(f.tokenizerPath());
      manualDeviceId = "CPU".equals(f.backend()) ? "CPU" : "GPU " + f.deviceIndex();
      chooseRuntime(f.runtimePreference());
      refreshDeviceChoices();
      refreshDiscoveredModels();
      cpuThreads.setText(Integer.toString(f.cpuThreads()));
      host.setText(f.host());
      httpPort.setText(Integer.toString(f.httpPort()));
      grpcPort.setText(Integer.toString(f.grpcPort()));
      voice.setText(f.voice());
      refAudio.setText(f.referenceAudio());
      refText.setText(f.referenceText());
      temperature.setText(Double.toString(f.temperature()));
      topP.setText(Double.toString(f.topP()));
      topK.setText(Integer.toString(f.topK()));
      maxTokens.setText(Integer.toString(f.maxTokens()));
    } finally {
      loadingControls = wasLoading;
    }
  }

  /**
   * The sidebar recreates VoiceHubPage on every visit. All switches and choices
   * therefore have to be saved immediately; text edits are debounced to avoid
   * writing a properties file for each keystroke.
   */
  private void installAutoSave() {
    ttsEnabled.addListener((obs, before, after) -> autoSave());
    engine.valueProperty().addListener((obs, before, after) -> autoSave());
    transport.valueProperty().addListener((obs, before, after) -> autoSave());
    textMode.valueProperty().addListener((obs, before, after) -> autoSave());
    lowLatency.selectedProperty().addListener((obs, before, after) -> autoSave());
    segmentSentences.selectedProperty().addListener((obs, before, after) -> autoSave());

    textSaveDelay.setOnFinished(event -> autoSave());
    for (TextField field : List.of(executable, model, tokenizer, cpuThreads, host, httpPort,
        grpcPort, voice, refAudio, refText, temperature, topP, topK, maxTokens,
        minChunk, maxChunk, flushDelay, queueLimit, startBuffer, stride, holdback, sentencePause)) {
      field.textProperty().addListener((obs, before, after) -> {
        if (autoSaveReady && !loadingControls) textSaveDelay.playFromStart();
      });
      field.focusedProperty().addListener((obs, before, focused) -> {
        if (!focused && autoSaveReady && !loadingControls) {
          textSaveDelay.stop();
          autoSave();
        }
      });
      field.setOnAction(event -> {
        textSaveDelay.stop();
        autoSave();
      });
    }
    autoSaveReady = true;
  }

  private void autoSave() {
    if (!autoSaveReady || loadingControls || synchronizingDevices) return;
    persistSettings(false);
  }

  /** Called by the visible Save button, and before the TTS test. */
  private boolean save() {
    textSaveDelay.stop();
    return persistSettings(true);
  }

  private boolean persistSettings(boolean explicit) {
    if (loadingControls) return false;
    try {
      Settings settings = controlsToSettings();
      store.save(settings);
      feedback.setText(explicit
          ? "TTS-настройки сохранены в " + store.path().getFileName() + "."
          : "TTS-настройки сохранены автоматически.");
      return true;
    } catch (Exception problem) {
      if (explicit) {
        feedback.setText("Ошибка сохранения TTS: " + problem.getMessage());
        return false;
      }
      // One unfinished numeric/path field must not prevent the user from enabling
      // Fish Speech or changing CUDA/Vulkan. Preserve the last valid field values
      // while saving switches, selections and device preferences immediately.
      try {
        Settings previous = store.load();
        Common old = previous.common();
        Fish fish = previous.fish();
        Common common = new Common(ttsEnabled.get(), old.engineId(),
            transport.getValue() == null ? old.transport() : transport.getValue().id(),
            textMode.getValue() == null ? old.textInputMode() : textMode.getValue().id(),
            old.minChunkCharacters(), old.maxChunkCharacters(), old.flushDelayMs(), old.queueLimit(),
            old.playbackStartBufferMs(), lowLatency.isSelected(), old.decodeStrideFrames(),
            old.holdbackFrames(), segmentSentences.isSelected(), old.sentencePauseMs());
        String backend;
        try { backend = effectiveSavedBackend(); }
        catch (Exception invalidDevice) { backend = fish.backend(); }
        Fish updated = new Fish(fish.executablePath(), fish.modelPath(), fish.tokenizerPath(),
            backend, "AUTO".equals(backend) ? fish.deviceIndex() : selectedGpuIndex(),
            fish.cpuThreads(), fish.host(), fish.httpPort(), fish.grpcPort(), fish.voice(),
            fish.referenceAudio(), fish.referenceText(), fish.temperature(), fish.topP(),
            fish.topK(), fish.maxTokens(), runtimePreference);
        store.save(new Settings(common, updated));
        feedback.setText("Переключатели сохранены. Проверь незаполненные или неверные поля: "
            + problem.getMessage());
        return true;
      } catch (Exception backupError) {
        feedback.setText("Не удалось сохранить TTS-настройки: " + backupError.getMessage());
        return false;
      }
    }
  }

  private Settings controlsToSettings() {
    return new Settings(
        new Common(ttsEnabled.get(), engine.getValue().id(), transport.getValue().id(),
            textMode.getValue().id(), integer(minChunk), integer(maxChunk), integer(flushDelay),
            integer(queueLimit), integer(startBuffer), lowLatency.isSelected(), integer(stride),
            integer(holdback), segmentSentences.isSelected(), integer(sentencePause)),
        new Fish(value(executable), value(model), value(tokenizer),
            effectiveSavedBackend(), selectedGpuIndex(), integer(cpuThreads), value(host), integer(httpPort),
            integer(grpcPort), value(voice), value(refAudio), value(refText),
            decimal(temperature), decimal(topP), integer(topK), integer(maxTokens), runtimePreference));
  }

  private void testGeneration() {
    if (state.busy.get()) {
      feedback.setText("Дождись завершения текущей генерации NeuralSoul.");
      return;
    }
    if (!testInProgress.compareAndSet(false, true)) {
      feedback.setText("Тест TTS уже выполняется.");
      return;
    }
    if (testText.getText() == null || testText.getText().isBlank()) {
      testInProgress.set(false);
      feedback.setText("Введи текст для тестирования TTS.");
      return;
    }
    if (!save()) {
      testInProgress.set(false);
      return;
    }
    if (!com.neuralsoul.service.ServiceLocator.getInstance().isInitialized()) {
      testInProgress.set(false);
      feedback.setText("Сначала запусти систему NeuralSoul.");
      return;
    }
    String words = testText.getText();
    feedback.setText("Генерация и потоковое воспроизведение…");
    Thread thread = new Thread(() -> {
      String status;
      try {
        var services = com.neuralsoul.service.ServiceLocator.getInstance();
        if (!services.tts().isRunning()) services.tts().start();
        services.tts().speak(words, services.player());
        services.player().finishSpeech();
        status = "Тест TTS завершён. Процесс Fish Speech остаётся подключённым.";
      } catch (Exception error) {
        status = "Ошибка TTS: " + error.getMessage();
      }
      final String message = status;
      testInProgress.set(false);
      javafx.application.Platform.runLater(() -> feedback.setText(message));
    }, "fish-tts-ui-test");
    thread.setDaemon(true);
    thread.start();
  }

  private static TextField field() {
    TextField field = new TextField();
    field.setMaxWidth(Double.MAX_VALUE);
    field.setMinWidth(0);
    return field;
  }
  private static String value(TextField f) { return f.getText() == null ? "" : f.getText().strip(); }
  private static int integer(TextField f) { return Integer.parseInt(value(f)); }
  private static double decimal(TextField f) {
    return Double.parseDouble(value(f).replace(',', '.'));
  }
  private static Label wrap(String text) {
    Label label = Ui.label(text, "muted");
    label.setWrapText(true);
    label.setMinWidth(0);
    label.setMaxWidth(Double.MAX_VALUE);
    return label;
  }
  private static GridPane grid(Object... entries) {
    GridPane g = new GridPane();
    g.setHgap(12);
    g.setVgap(9);
    g.setMaxWidth(Double.MAX_VALUE);
    g.setMinWidth(0);
    ColumnConstraints left = new ColumnConstraints();
    left.setMinWidth(170);
    left.setPrefWidth(245);
    ColumnConstraints right = new ColumnConstraints();
    right.setMinWidth(0);
    right.setHgrow(Priority.ALWAYS);
    right.setFillWidth(true);
    g.getColumnConstraints().addAll(left, right);
    for (int i = 0; i < entries.length; i += 2) {
      Label label = Ui.label((String) entries[i], "muted");
      label.setWrapText(true);
      javafx.scene.Node control = (javafx.scene.Node) entries[i + 1];
      g.add(label, 0, i / 2);
      g.add(control, 1, i / 2);
      if (control instanceof javafx.scene.layout.Region region) {
        region.setMaxWidth(Double.MAX_VALUE);
        region.setMinWidth(0);
      }
    }
    return g;
  }
}
