package com.neuralsoul.ui.pages;

import com.neuralsoul.config.ConfigLocator;
import com.neuralsoul.config.SpeechGenerationSettingsStore;
import com.neuralsoul.config.SpeechGenerationSettingsStore.Common;
import com.neuralsoul.config.SpeechGenerationSettingsStore.Cosy;
import com.neuralsoul.config.SpeechGenerationSettingsStore.Settings;
import com.neuralsoul.model.AppState;
import com.neuralsoul.model.Device;
import com.neuralsoul.ui.components.Icons;
import com.neuralsoul.ui.components.Ui;
import com.neuralsoul.ui.styles.UiStyles;
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
import javafx.scene.Node;
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
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.util.StringConverter;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/**
 * CosyVoice streaming TTS configuration. Runtime integration lives in ServiceLocator;
 * keep this JavaFX page free of audio codec and networking logic.
 */
public final class SpeechGenerationPage extends VBox {
  private record Option(String id, String label) {
    @Override public String toString() { return label; }
  }
  private record VoicePromptPackage(String gguf, String name, Path wav, String text) {}

  private static final Option CHUNKS = new Option(SpeechGenerationSettingsStore.CHUNKS,
      "Чанки текста · предложения/паузы");
  private static final Option INCREMENTAL = new Option(SpeechGenerationSettingsStore.INCREMENTAL,
      "Непрерывный поток LLM → очередь фраз");

  private final SpeechGenerationSettingsStore store;
  private final AppState state;
  /** When enabled, pipeline routes TTS through the persistent CosyVoice service. */
  private final BooleanProperty ttsEnabled = new SimpleBooleanProperty(false);
  private final ComboBox<String> deviceChoice = new ComboBox<>();
  private final ComboBox<String> discoveredModels = new ComboBox<>();
  private final ComboBox<String> discoveredVoicePrompts = new ComboBox<>();
  private final FlowPane voicePromptTiles = new FlowPane(10, 10);
  private final Label modelTitle = Ui.label("CosyVoice", "model-name");
  private final Label modelLocation = wrap("");
  private final Label deviceHint = wrap("");
  // Keep a device ID, not a hard-coded GPU model or vendor-specific ordinal.
  private String manualDeviceId = "";
  // Runtime preference remains independent of automatic device placement or CPU fallback.
  private String runtimePreference = "CUDA-13";
  private final ToggleButton cuda13Runtime = new ToggleButton("CUDA 13.5");
  private final ToggleButton cuda12Runtime = new ToggleButton("CUDA 12.4");
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
  private final ComboBox<Option> textMode = new ComboBox<>();
  private final TextField minChunk = field(), maxChunk = field(), flushDelay = field(), queueLimit = field();
  private final TextField startBuffer = field();

  private final TextField executable = field(), model = field();
  private final TextField voice = field(), voicePrompt = field();
  private final TextField refWav = field(), refText = field();
  private final TextField cpuThreads = field(), host = field(), httpPort = field();
  private final TextField temperature = field(), topP = field(), topK = field(), speed = field(), chunkTokens = field();
  private final Label textHint = wrap("");
  private final Label feedback = wrap("");
  private final Label promptFeedback = wrap("");
  private final java.util.concurrent.atomic.AtomicBoolean encodeInProgress = new java.util.concurrent.atomic.AtomicBoolean();
  private final Button encodeButton = Ui.button("Создать prompt speech GGUF", "voice", this::encodePromptSpeech);
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

    textMode.getItems().setAll(CHUNKS, INCREMENTAL);
    textMode.setMaxWidth(Double.MAX_VALUE);
    textMode.valueProperty().addListener((obs, before, value) -> updateTextHint());

    VBox input = Ui.card("Входящий текст · LLM → TTS",
        grid("Режим подачи текста", textMode,
            "Минимум символов в чанке", minChunk,
            "Максимум символов в чанке", maxChunk,
            "Ожидание следующего фрагмента, мс", flushDelay,
            "Лимит очереди чанков", queueLimit),
        textHint,
        wrap("LLM выдаёт токены → Java собирает фразы → очередь → /v1/audio/speech. "
            + "Один HTTP-запрос содержит завершённый чанк текста, а аудио возвращается потоком; "
            + "при отмене ответа поток закрывается, но сервер остаётся запущенным."));

    CheckBox pcm = new CheckBox("Выводить аудио потоком · WAV (PCM S16LE)");
    pcm.setSelected(true);
    pcm.setDisable(true); // streaming output is a design requirement, not a toggle
    VBox output = Ui.card("Исходящее аудио · TTS → плеер",
        pcm,
        grid("Буфер перед воспроизведением, мс", startBuffer),
        wrap("CosyVoice стримит WAV: 44-байтовый заголовок в первом чанке, дальше — сырой PCM16. "
            + "Частота дискретизации читается из заголовка (CosyVoice3 ≈ 24 кГц); "
            + "плеер сам регулирует буфер, если генерация идёт медленнее воспроизведения."));

    VBox cosyRuntime = buildCosyModelCard();
    VBox voicePrompts = buildVoicePromptCard();

    VBox modelAdvanced = Ui.card("CosyVoice · параметры модели и рантайма",
        grid("EXE рантайма", withFilePicker(executable, "cosyvoice-server.exe", "*.exe"),
            "CPU threads", cpuThreads,
            "Temperature", temperature,
            "Top-p", topP,
            "Top-k (0 = выкл)", topK,
            "Speed", speed,
            "Chunk tokens (0 = авто)", chunkTokens),
        wrap("{backend} в пути рантайма заменяется на cpu, vulkan, cuda-12.4 или cuda-13.5. "
            + "Пути хранятся относительно каталога проекта."));

    VBox cosyServer = Ui.card("CosyVoice · сервер",
        grid("Адрес", host,
            "HTTP порт", httpPort),
        wrap("OpenAI-совместимый API: POST /v1/audio/speech. Порт по умолчанию 5020 — "
            + "не 8080, чтобы не конфликтовать с Main LLM."));

    testText.setWrapText(true);
    testText.setPrefRowCount(3);
    testText.setMaxWidth(Double.MAX_VALUE);
    Button test = Ui.button("Запустить тест TTS", "voice", () -> testGeneration());
    VBox testing = Ui.card("Проверка потоковой генерации",
        wrap("Тест использует текущие настройки. Все переключатели сохраняются автоматически."),
        testText, test, wrap("Тест запускает и держит сервер cosyvoice-server.exe в памяти. "
            + "Логи процесса: logs/tts-cosyvoice.log"));

    Button save = Ui.button("Сохранить настройки TTS", "tasks", this::save);
    Button restore = Ui.button("Вернуть стандартные значения в полях", "refresh", () -> {
      loadToControls(Settings.defaults());
      if (save()) feedback.setText("Настройки TTS сброшены к значениям по умолчанию и сохранены.");
    });
    HBox buttons = new HBox(10, save, restore);
    buttons.setAlignment(Pos.CENTER_LEFT);
    buttons.setMinWidth(0);

    Node tabs = voiceTabs(
        new VoiceTab("Основные настройки", page(cosyRuntime, cosyServer, testing, buttons, feedback)),
        new VoiceTab("Продвинутые настройки", page(modelAdvanced, input, output)),
        new VoiceTab("Настройки голосового промта", page(voicePrompts)));
    VBox.setVgrow(tabs, Priority.ALWAYS);
    getChildren().addAll(
        Ui.label("Генерация речи · CosyVoice", "section-title"),
        wrap("CosyVoice (cosyvoice.cpp) · общий потоковый тракт LLM → очередь фраз → плеер"),
        tabs);
    // The Models page owns this switch. The model selector reacts immediately when it changes.
    state.automatic.addListener(new WeakChangeListener<>(automaticListener));
    state.devices.addListener(new WeakListChangeListener<>(devicesListener));
    watchDeviceEnabledFlags();
    reload();
    installAutoSave();
  }

  /**
   * CosyVoice model card follows the same layout as the Memory/Web Search model cards.
   * No inference runtime is launched here: device selection is a saved preference only.
   */
  private VBox buildCosyModelCard() {
    ToggleButton useModel = Ui.toggle(ttsEnabled);
    useModel.setTooltip(new Tooltip(
        "Включает CosyVoice вместо старого TTS при следующем подключении/запросе. "
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

    deviceHint.setText("Ручной режим: выбери разрешённое устройство для CosyVoice.");

    HBox top = new HBox(10, title, Ui.label("Использовать", "tiny"), useModel, deviceChoice);
    top.setMinWidth(0);
    top.setAlignment(Pos.CENTER_LEFT);

    model.textProperty().addListener((obs, oldValue, newValue) -> updateModelTitle());
    discoveredModels.setMinWidth(0);
    discoveredModels.setMaxWidth(Double.MAX_VALUE);
    discoveredModels.setPromptText("Модель GGUF из model/tts/cosyvoice");
    discoveredModels.valueProperty().addListener((obs, before, selected) -> {
      if (selected != null && !selected.isBlank() && !selected.equals(value(model))) {
        model.setText(selected);
      }
    });
    Button refreshModels = Ui.button("Обновить", "refresh", this::refreshDiscoveredModels);
    HBox availableRow = new HBox(8, discoveredModels, refreshModels);
    HBox.setHgrow(discoveredModels, Priority.ALWAYS);
    availableRow.setMinWidth(0);

    VBox card = Ui.card("CosyVoice · модель и устройство",
        top, modelLocation, deviceHint,
        grid("Найденные GGUF", availableRow,
            "Файл модели", withFilePicker(model, "Модель GGUF", "*.gguf")),
        buildRuntimeControls(),
        wrap("Устройство выбирается из обнаруженного и разрешённого оборудования NeuralSoul. "
            + "Голос и его референс настраиваются в отдельной подвкладке."));
    card.setMinWidth(0);
    card.setMaxWidth(Double.MAX_VALUE);
    return card;
  }

  private VBox buildVoicePromptCard() {
    discoveredVoicePrompts.setMinWidth(0);
    discoveredVoicePrompts.setMaxWidth(Double.MAX_VALUE);
    discoveredVoicePrompts.setPromptText("Готовые голоса из model/tts/cosyvoice/prompts");
    discoveredVoicePrompts.valueProperty().addListener((obs, before, selected) -> {
      if (loadingControls || selected == null || selected.isBlank()) return;
      if (!selected.equals(value(voicePrompt))) voicePrompt.setText(selected);
      String file = Path.of(selected).getFileName().toString().replaceFirst("(?i)\\.gguf$", "");
      if (!file.isBlank()) voice.setText(file);
    });
    Button refreshPrompts = Ui.button("Обновить", "refresh", this::refreshDiscoveredVoicePrompts);
    HBox promptsRow = new HBox(8, discoveredVoicePrompts, refreshPrompts);
    HBox.setHgrow(discoveredVoicePrompts, Priority.ALWAYS);
    promptsRow.setMinWidth(0);

    VBox card = Ui.card("Голосовые промты CosyVoice",
        grid("Готовые голоса GGUF", promptsRow,
            "Имя голоса", voice,
            "Prompt speech GGUF", withFilePicker(voicePrompt, "Prompt speech GGUF", "*.gguf"),
            "Референс WAV", withReferenceWavPicker(),
            "Текст референса", refText),
        encodeButton, promptFeedback,
        wrap("Каждый новый голос сохраняется отдельным пакетом: GGUF, reference.wav и prompt.txt. "
            + "Нужен WAV 5–10 секунд чистой речи и его дословная расшифровка."),
        voicePromptTiles);
    card.setMinWidth(0);
    card.setMaxWidth(Double.MAX_VALUE);
    return card;
  }

  private void updateModelTitle() {
    String path = value(model);
    modelLocation.setText(path.isBlank() ? "Файл модели не выбран" : path);
    if (path.isBlank()) {
      modelTitle.setText("CosyVoice · модель не выбрана");
    } else {
      String file = path.replace('\\', '/');
      file = file.substring(file.lastIndexOf('/') + 1);
      modelTitle.setText(file.toLowerCase(Locale.ROOT).endsWith(".gguf")
          ? file.substring(0, file.length() - 5) : file);
    }
  }

  private void refreshDiscoveredModels() {
    Path root = workingDirectory.resolve("model/tts/cosyvoice");
    List<String> found = new ArrayList<>();
    if (Files.isDirectory(root)) {
      try (var paths = Files.walk(root)) {
        paths.filter(Files::isRegularFile)
            .filter(f -> !Files.isSymbolicLink(f))
            .filter(f -> !f.startsWith(root.resolve("prompts")))
            .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf"))
            .sorted(Comparator.naturalOrder())
            .forEach(f -> found.add(workingDirectory.relativize(f).toString().replace('\\', '/')));
      } catch (Exception ex) {
        feedback.setText("Не удалось прочитать model/tts/cosyvoice: " + ex.getMessage());
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

  /** Refresh all generated/imported prompt_speech GGUF files. */
  private void refreshDiscoveredVoicePrompts() {
    Path root = workingDirectory.resolve("model/tts/cosyvoice/prompts");
    List<String> found = new ArrayList<>();
    List<VoicePromptPackage> packages = new ArrayList<>();
    if (Files.isDirectory(root)) {
      try (var paths = Files.walk(root)) {
        paths.filter(Files::isRegularFile)
            .filter(f -> !Files.isSymbolicLink(f))
            .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf"))
            .sorted(Comparator.naturalOrder())
            .forEach(f -> {
              String relative = workingDirectory.relativize(f).toString().replace('\\', '/');
              found.add(relative);
              Path parent = f.getParent();
              Path wav = parent.resolve("reference.wav");
              Path textFile = parent.resolve("prompt.txt");
              String text = "";
              try { if (Files.isRegularFile(textFile)) text = Files.readString(textFile).strip(); }
              catch (Exception ignored) { /* card remains usable without transcript preview */ }
              String name = f.getFileName().toString().replaceFirst("(?i)\\.gguf$", "");
              packages.add(new VoicePromptPackage(relative, name,
                  Files.isRegularFile(wav) ? wav : null, text));
            });
      } catch (Exception ex) {
        promptFeedback.setText("Не удалось прочитать список голосов: " + ex.getMessage());
      }
    }
    String selected = value(voicePrompt);
    if (!selected.isBlank() && !found.contains(selected)) found.add(selected);
    boolean wasLoading = loadingControls;
    loadingControls = true;
    try {
      discoveredVoicePrompts.getItems().setAll(found);
      if (!selected.isBlank()) discoveredVoicePrompts.setValue(selected);
      else discoveredVoicePrompts.getSelectionModel().clearSelection();
      rebuildVoicePromptTiles(packages, selected);
    } finally {
      loadingControls = wasLoading;
    }
  }

  private void rebuildVoicePromptTiles(List<VoicePromptPackage> packages, String selected) {
    voicePromptTiles.getChildren().clear();
    voicePromptTiles.setMinWidth(0);
    voicePromptTiles.setMaxWidth(Double.MAX_VALUE);
    for (VoicePromptPackage item : packages) {
      Label transcript = wrap(item.text().isBlank()
          ? (item.wav() == null ? "Старый GGUF · WAV и текст отсутствуют" : "Текст отсутствует")
          : item.text());
      transcript.setMaxWidth(260);
      Button select = Ui.button(item.gguf().equals(selected) ? "Выбран" : "Выбрать", "tasks", () -> {
        voicePrompt.setText(item.gguf());
        voice.setText(item.name());
        discoveredVoicePrompts.setValue(item.gguf());
        autoSave();
        refreshDiscoveredVoicePrompts();
      });
      Button play = Ui.button("Прослушать", "voice", () -> previewReference(item));
      play.setDisable(item.wav() == null);
      HBox actions = new HBox(8, select, play);
      VBox tile = Ui.card(item.name(), transcript, actions);
      tile.setPrefWidth(300);
      tile.setMaxWidth(300);
      if (item.gguf().equals(selected)) {
        tile.setStyle("-fx-border-color:#7567ff;-fx-border-width:1.5;-fx-border-radius:10;");
      }
      voicePromptTiles.getChildren().add(tile);
    }
    if (packages.isEmpty()) voicePromptTiles.getChildren().add(wrap("Готовых голосов пока нет."));
  }

  private void previewReference(VoicePromptPackage item) {
    if (item.wav() == null || !Files.isRegularFile(item.wav())) {
      promptFeedback.setText("У голоса " + item.name() + " нет reference.wav.");
      return;
    }
    promptFeedback.setText("Воспроизведение референса: " + item.name());
    Thread thread = new Thread(() -> {
      try {
        var player = new com.neuralsoul.service.audio.JavaSoundPlayer();
        player.start();
        try { player.play(Files.readAllBytes(item.wav())); }
        finally { player.stop(); }
        javafx.application.Platform.runLater(() -> promptFeedback.setText("Референс прослушан: " + item.name()));
      } catch (Exception error) {
        javafx.application.Platform.runLater(() -> promptFeedback.setText(
            "Не удалось воспроизвести " + item.name() + ": " + error.getMessage()));
      }
    }, "cosy-voice-preview");
    thread.setDaemon(true);
    thread.start();
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
            + "CosyVoice выберет одну из разрешённых GPU: самая большая видеопамять. "
            + "Без разрешённой GPU — CPU.");
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

  /** CUDA 13.5 / CUDA 12.4 / Vulkan switch belongs to this model card, not a separate TTS-wide card. */
  private VBox buildRuntimeControls() {
    ToggleGroup group = new ToggleGroup();
    cuda13Runtime.setToggleGroup(group);
    cuda12Runtime.setToggleGroup(group);
    vulkanRuntime.setToggleGroup(group);
    cuda13Runtime.setMinWidth(125);
    cuda12Runtime.setMinWidth(125);
    vulkanRuntime.setMinWidth(125);
    cuda13Runtime.setOnAction(event -> chooseRuntime("CUDA-13"));
    cuda12Runtime.setOnAction(event -> chooseRuntime("CUDA-12"));
    vulkanRuntime.setOnAction(event -> chooseRuntime("VULKAN"));
    HBox choices = new HBox(8, Ui.label("GPU backend:", "muted"), cuda13Runtime, cuda12Runtime, vulkanRuntime);
    choices.setAlignment(Pos.CENTER_LEFT);
    choices.setMinWidth(0);
    chooseRuntime("CUDA-13");
    VBox panel = new VBox(6, choices, runtimeHint);
    panel.setMinWidth(0);
    return panel;
  }

  private void chooseRuntime(String choice) {
    runtimePreference = switch (choice) {
      case "CUDA-12" -> "CUDA-12";
      case "VULKAN" -> "VULKAN";
      default -> "CUDA-13";
    };
    cuda13Runtime.setSelected("CUDA-13".equals(runtimePreference));
    cuda12Runtime.setSelected("CUDA-12".equals(runtimePreference));
    vulkanRuntime.setSelected("VULKAN".equals(runtimePreference));
    styleRuntimeButton(cuda13Runtime, cuda13Runtime.isSelected());
    styleRuntimeButton(cuda12Runtime, cuda12Runtime.isSelected());
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
    cuda13Runtime.setDisable(!nvidiaEnabled);
    cuda12Runtime.setDisable(!nvidiaEnabled);
    vulkanRuntime.setDisable(!gpuEnabled);
    if (gpuEnabled && !nvidiaEnabled && runtimePreference.startsWith("CUDA")) {
      chooseRuntime("VULKAN");
    } else {
      updateRuntimeHint();
    }
  }

  private void updateRuntimeHint() {
    if (state == null) return;
    String pack = "CUDA-12".equals(runtimePreference) ? "cuda-12.4" : "cuda-13.5";
    if (state.automatic.get()) {
      boolean supported = !"CUDA-13".equals(runtimePreference) && !"CUDA-12".equals(runtimePreference)
          || state.devices.stream().anyMatch(d -> d.enabled.get() && isNvidia(d));
      runtimeHint.setText(supported
          ? "Автоматическое размещение включено: NeuralSoul выберет устройство из разрешённых. "
              + "Предпочтение backend: " + pack + (runtimePreference.startsWith("CUDA")
                  ? " (при неудаче молча пробуется вторая CUDA-сборка)." : ".")
          : "Внимание: CUDA выбран, но разрешённая NVIDIA GPU не обнаружена. "
              + "Выбери Vulkan или разреши NVIDIA GPU во вкладке Модели.");
      return;
    }
    Device chosen = findDetectedDevice(manualDeviceId);
    if (chosen == null) {
      runtimeHint.setText("Устройство не выбрано. Разреши CPU/GPU во вкладке Модели.");
    } else if ("CPU".equals(chosen.id)) {
      runtimeHint.setText("Выбран CPU: CUDA/Vulkan не используется. "
          + "Предпочтение " + pack + " сохранится для GPU.");
    } else if (runtimePreference.startsWith("CUDA") && !isNvidia(chosen)) {
      runtimeHint.setText("CUDA работает только с NVIDIA GPU. Для текущей видеокарты выбери Vulkan.");
    } else {
      runtimeHint.setText("Устройство: " + chosen.name + ". Backend: " + pack
          + (runtimePreference.startsWith("CUDA")
              ? " (при неудаче молча пробуется вторая CUDA-сборка)"
              : "")
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
    if (runtimePreference.startsWith("CUDA") && !isNvidia(selected))
      throw new IllegalArgumentException("CUDA поддерживается только для NVIDIA GPU. Выбери Vulkan.");
    return runtimePreference.startsWith("CUDA") ? "CUDA" : "VULKAN";
  }

  /** The CUDA pack persisted from the runtime switch: which build to try first. */
  private String runtimePack() {
    return "CUDA-12".equals(runtimePreference) ? "cuda-12.4" : "cuda-13.5";
  }

  private int selectedGpuIndex() {
    // GPU IDs are generated dynamically by AppState.refreshHardwareDevices().
    if (!manualDeviceId.isBlank() && manualDeviceId.startsWith("GPU ")) {
      try { return Integer.parseInt(manualDeviceId.substring(4)); }
      catch (NumberFormatException ignored) { /* keep compatibility with saved legacy IDs */ }
    }
    return 0;
  }

  private void updateTextHint() {
    Option value = textMode.getValue();
    textHint.setText(value != null && SpeechGenerationSettingsStore.INCREMENTAL.equals(value.id())
        ? "Токены LLM поступают немедленно, Java собирает фразы и отправляет их по очереди. "
            + "Один запрос /v1/audio/speech всё ещё содержит только завершённый текстовый чанк."
        : "Режим чанков: LLM посылает частичные ответы, Java собирает фразы и отдаёт "
            + "их TTS отдельными заданиями. Аудио каждого задания выводится потоком.");
  }

  private HBox withFilePicker(TextField field, String title, String... patterns) {
    Button button = Ui.button("...", "file", () -> chooseFile(field, title, patterns));
    button.setMinWidth(42);
    HBox container = new HBox(8, field, button);
    container.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(field, Priority.ALWAYS);
    return container;
  }

  /**
   * Voice references may be selected anywhere on the computer. External files
   * are copied into the project so the persisted path remains portable.
   */
  private HBox withReferenceWavPicker() {
    Button button = Ui.button("...", "file", this::chooseReferenceWav);
    button.setMinWidth(42);
    HBox container = new HBox(8, refWav, button);
    container.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(refWav, Priority.ALWAYS);
    return container;
  }

  private void chooseReferenceWav() {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Референс WAV для CosyVoice");
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("WAV audio", "*.wav"));
    File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
    if (file == null) return;
    Path chosen = file.toPath().toAbsolutePath().normalize();
    try {
      Path target = chosen;
      if (!chosen.startsWith(workingDirectory)) {
        Path refs = workingDirectory.resolve("model/tts/cosyvoice/refs");
        Files.createDirectories(refs);
        String original = chosen.getFileName().toString();
        String base = original.replaceFirst("(?i)\\.wav$", "")
            .replaceAll("[^a-zA-Z0-9._-]", "_");
        if (base.isBlank()) base = "reference";
        target = refs.resolve(base + ".wav");
        int suffix = 2;
        while (Files.exists(target) && !Files.isSameFile(chosen, target)) {
          target = refs.resolve(base + "-" + suffix++ + ".wav");
        }
        if (!Files.exists(target)) Files.copy(chosen, target);
      }
      String relative = workingDirectory.relativize(target).toString().replace('\\', '/');
      refWav.setText(relative);
      autoSave();
      feedback.setText(chosen.equals(target)
          ? "Референс выбран: " + relative
          : "Референс скопирован в проект: " + relative);
    } catch (Exception error) {
      feedback.setText("Не удалось добавить WAV-референс: " + error.getMessage());
    }
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

  /** Map saved settings to the runtime switch. CPU/AUTO keep the current button. */
  private String runtimeFromSaved(String backend, String cudaPack) {
    if ("VULKAN".equals(backend)) return "VULKAN";
    if ("CUDA".equals(backend)) return "cuda-12.4".equals(cudaPack) ? "CUDA-12" : "CUDA-13";
    return runtimePreference;
  }

  /** Restore the common case where a WAV was copied before reference fields became persistent. */
  private String discoverSingleReferenceWav() {
    Path refs = workingDirectory.resolve("model/tts/cosyvoice/refs");
    if (!Files.isDirectory(refs)) return "";
    try (var files = Files.list(refs)) {
      List<Path> wavs = files.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wav"))
          .limit(2).toList();
      return wavs.size() == 1
          ? workingDirectory.relativize(wavs.get(0)).toString().replace('\\', '/')
          : "";
    } catch (Exception ignored) {
      return "";
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
      Cosy f = settings.cosy();
      ttsEnabled.set(c.enabled());
      textMode.setValue(SpeechGenerationSettingsStore.INCREMENTAL.equals(c.textInputMode()) ? INCREMENTAL : CHUNKS);
      minChunk.setText(Integer.toString(c.minChunkCharacters()));
      maxChunk.setText(Integer.toString(c.maxChunkCharacters()));
      flushDelay.setText(Integer.toString(c.flushDelayMs()));
      queueLimit.setText(Integer.toString(c.queueLimit()));
      startBuffer.setText(Integer.toString(c.playbackStartBufferMs()));
      executable.setText(f.executablePath());
      model.setText(f.modelPath());
      voice.setText(f.voice());
      voicePrompt.setText(f.voicePromptPath());
      refWav.setText(f.referenceWavPath().isBlank() ? discoverSingleReferenceWav() : f.referenceWavPath());
      refText.setText(f.referenceText());
      manualDeviceId = "CPU".equals(f.backend()) ? "CPU" : "GPU " + f.deviceIndex();
      chooseRuntime(runtimeFromSaved(f.backend(), f.cudaPack()));
      refreshDeviceChoices();
      refreshDiscoveredModels();
      refreshDiscoveredVoicePrompts();
      cpuThreads.setText(Integer.toString(f.cpuThreads()));
      host.setText(f.host());
      httpPort.setText(Integer.toString(f.httpPort()));
      temperature.setText(Double.toString(f.temperature()));
      topP.setText(Double.toString(f.topP()));
      topK.setText(Integer.toString(f.topK()));
      speed.setText(Double.toString(f.speed()));
      chunkTokens.setText(Integer.toString(f.chunkTokens()));
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
    textMode.valueProperty().addListener((obs, before, after) -> autoSave());

    textSaveDelay.setOnFinished(event -> autoSave());
    for (TextField field : List.of(executable, model, voice, voicePrompt, refWav, refText, cpuThreads, host,
        httpPort, temperature, topP, topK, speed, chunkTokens,
        minChunk, maxChunk, flushDelay, queueLimit, startBuffer)) {
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
      // CosyVoice or changing CUDA/Vulkan. Preserve the last valid field values
      // while saving switches, selections and device preferences immediately.
      try {
        Settings previous = store.load();
        Common old = previous.common();
        Cosy cosy = previous.cosy();
        Common common = new Common(ttsEnabled.get(), old.engineId(),
            old.transport(),
            textMode.getValue() == null ? old.textInputMode() : textMode.getValue().id(),
            old.minChunkCharacters(), old.maxChunkCharacters(), old.flushDelayMs(), old.queueLimit(),
            old.playbackStartBufferMs());
        String backend;
        try { backend = effectiveSavedBackend(); }
        catch (Exception invalidDevice) { backend = cosy.backend(); }
        Cosy updated = new Cosy(cosy.executablePath(), cosy.modelPath(), value(voice),
            cosy.voicePromptPath(), value(refWav), value(refText), backend, runtimePack(),
            "AUTO".equals(backend) ? cosy.deviceIndex() : selectedGpuIndex(),
            cosy.cpuThreads(), cosy.host(), cosy.httpPort(), cosy.temperature(), cosy.topP(),
            cosy.topK(), cosy.speed(), cosy.chunkTokens());
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
        new Common(ttsEnabled.get(), SpeechGenerationSettingsStore.COSYVOICE,
            SpeechGenerationSettingsStore.HTTP,
            textMode.getValue().id(), integer(minChunk), integer(maxChunk), integer(flushDelay),
            integer(queueLimit), integer(startBuffer)),
        new Cosy(value(executable), value(model), value(voice), value(voicePrompt),
            value(refWav), value(refText),
            effectiveSavedBackend(), runtimePack(), selectedGpuIndex(), integer(cpuThreads), value(host),
            integer(httpPort), decimal(temperature), decimal(topP), integer(topK),
            decimal(speed), integer(chunkTokens)));
  }

  /**
   * Encode a reference WAV into a prompt_speech GGUF with the runtime's own
   * cosyvoice-cli. Runs off the FX thread; the result fills the voice-prompt field.
   */
  private void encodePromptSpeech() {
    if (!encodeInProgress.compareAndSet(false, true)) {
      promptFeedback.setText("Кодировка голоса уже выполняется.");
      return;
    }
    final String refWavRel = value(refWav);
    final String promptText = refText.getText() == null ? "" : refText.getText().strip();
    final String voiceName = value(voice);
    final String executableTemplate = value(executable);
    if (refWavRel.isBlank()) {
      encodeInProgress.set(false);
      promptFeedback.setText("Сначала выбери референс WAV.");
      return;
    }
    if (promptText.isBlank()) {
      encodeInProgress.set(false);
      promptFeedback.setText("⚠ Введи в поле «Текст референса» дословно то, что произнесено в WAV.");
      refText.requestFocus();
      return;
    }
    feedback.setText("Кодировка голоса… (CPU, обычно до пары минут)");
    promptFeedback.setText("Подготовка кодировщика голоса…");
    encodeButton.setDisable(true);
    Thread thread = new Thread(() -> {
      String status;
      try {
        var root = workingDirectory;
        com.neuralsoul.service.tts.CosyVoicePromptBuilder.ensureFrontendModels(root,
            message -> javafx.application.Platform.runLater(() -> promptFeedback.setText(
                message + (message.contains("speech_tokenizer") ? " (~925 МБ, только один раз)" : ""))));
        Path cli = com.neuralsoul.service.tts.CosyVoicePromptBuilder.resolveCli(
            root, executableTemplate);
        String tokenizer = com.neuralsoul.service.tts.CosyVoicePromptBuilder.findSpeechTokenizer(root);
        String campplus = com.neuralsoul.service.tts.CosyVoicePromptBuilder.findCampplus(root);
        String created = com.neuralsoul.service.tts.CosyVoicePromptBuilder.encode(
            root, cli, tokenizer, campplus, refWavRel, promptText, voiceName);
        javafx.application.Platform.runLater(() -> {
          loadingControls = true;
          voicePrompt.setText(created);
          loadingControls = false;
          refreshDiscoveredVoicePrompts();
          discoveredVoicePrompts.setValue(created);
          // Persist when the rest of the TTS form is valid; the generated file
          // remains selected even while model/device fields are still unfinished.
          if (!persistSettings(false)) {
            feedback.setText("Голос создан, но остальные TTS-поля пока не готовы: " + created);
          }
        });
        status = "Голос создан: " + created;
      } catch (Exception error) {
        status = "Не удалось создать prompt speech: " + error.getMessage()
            + " (лог: logs/tts-cosyvoice-frontend.log)";
      }
      final String message = status;
      encodeInProgress.set(false);
      javafx.application.Platform.runLater(() -> {
        encodeButton.setDisable(false);
        promptFeedback.setText(message);
        feedback.setText(message);
      });
    }, "cosy-prompt-encoder");
    thread.setDaemon(true);
    thread.start();
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
        status = "Тест TTS завершён. Сервер CosyVoice остаётся подключённым.";
      } catch (Exception error) {
        status = "Ошибка TTS: " + error.getMessage();
      }
      final String message = status;
      testInProgress.set(false);
      javafx.application.Platform.runLater(() -> feedback.setText(message));
    }, "cosy-tts-ui-test");
    thread.setDaemon(true);
    thread.start();
  }

  /** Same inner-tab presentation used by the Whisper speech-recognition page. */
  private Node voiceTabs(VoiceTab... definitions) {
    ToggleGroup group = new ToggleGroup();
    FlowPane header = new FlowPane(8, 8);
    header.setAlignment(Pos.CENTER_LEFT);
    header.setPadding(new Insets(4));
    UiStyles.voiceTabBar(header);

    StackPane content = new StackPane();
    content.setMaxWidth(Double.MAX_VALUE);
    VBox.setVgrow(content, Priority.ALWAYS);

    for (int index = 0; index < definitions.length; index++) {
      VoiceTab definition = definitions[index];
      ToggleButton button = new ToggleButton(definition.title());
      button.setToggleGroup(group);
      button.setUserData(definition.content());
      button.setFocusTraversable(false);
      button.setOnAction(e -> { if (!button.isSelected()) button.setSelected(true); });
      button.selectedProperty().addListener((obs, oldValue, selected) -> {
        UiStyles.voiceTab(button, selected);
        if (selected) content.getChildren().setAll(definition.content());
      });
      UiStyles.voiceTab(button, false);
      header.getChildren().add(button);
      if (index == 0) button.setSelected(true);
    }

    VBox shell = new VBox(10, header, content);
    shell.setMaxWidth(Double.MAX_VALUE);
    VBox.setVgrow(content, Priority.ALWAYS);
    return shell;
  }

  private record VoiceTab(String title, Node content) {}

  private static VBox page(Node... nodes) {
    VBox box = new VBox(12, nodes);
    box.setPadding(new Insets(14, 4, 18, 4));
    box.setMaxWidth(Double.MAX_VALUE);
    return box;
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
