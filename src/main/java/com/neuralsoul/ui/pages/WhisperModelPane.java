package com.neuralsoul.ui.pages;

import com.neuralsoul.config.*;
import com.neuralsoul.model.*;
import com.neuralsoul.hardware.*;
import com.neuralsoul.ui.components.*;
import com.neuralsoul.ui.styles.UiStyles;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;

/** Dedicated Whisper STT model block shown on the Voice page. */
final class WhisperModelPane extends VBox {
  private final AppState state;
  private final ConfigLocator configs;
  private final Consumer<SttConfig> saveStt;

  private final ToggleButton enabled = new ToggleButton();
  private final ComboBox<ModelChoice> model = new ComboBox<>();
  private final ComboBox<Device> device = new ComboBox<>();
  private final ComboBox<String> isolation = new ComboBox<>();

  private final TextField executable = field(), baseUrl = field(), inferencePath = field(), timeout = field(),
      apiModel = field(), language = field(), fileField = field();
  private final Spinner<Integer> threads = new Spinner<>();
  private final Spinner<Integer> processors = new Spinner<>();
  private final CheckBox flashAttention = new CheckBox("Flash Attention");
  private final TextField maxContext = field(), maxLen = field(), bestOf = field(), beamSize = field(), audioContext = field();
  private final TextField wordThreshold = field(), entropyThreshold = field(), logProbThreshold = field(), noSpeechThreshold = field();
  private final TextField temperature = field(), temperatureIncrement = field();
  private final CheckBox splitOnWord = new CheckBox("Split on word"), noFallback = new CheckBox("No fallback"),
      suppressNonSpeech = new CheckBox("Suppress non-speech tokens"), noTimestamps = new CheckBox("No timestamps"),
      translate = new CheckBox("Translate → English");
  private final CheckBox vad = new CheckBox("Whisper VAD");
  private final TextField vadModel = field(), vadThreshold = field(), vadMinSpeech = field(), vadMinSilence = field(),
      vadMaxSpeech = field(), vadPad = field(), vadOverlap = field();
  private final Label feedback = Ui.label("", "tiny");
  private final Label advancedFeedback = Ui.label("", "tiny");
  private VBox advancedContent;
  private boolean loading;

  WhisperModelPane(AppState state, ConfigLocator configs, Consumer<SttConfig> saveStt) {
    super(8);
    this.state = Objects.requireNonNull(state);
    this.configs = Objects.requireNonNull(configs);
    this.saveStt = Objects.requireNonNull(saveStt);
    getStyleClass().add("model-row");

    model.setMaxWidth(Double.MAX_VALUE);
    model.setPrefWidth(430);
    model.setConverter(new StringConverter<>() {
      @Override public String toString(ModelChoice choice) { return choice == null ? "" : choice.label(); }
      @Override public ModelChoice fromString(String value) { return null; }
    });

    device.setPrefWidth(280);
    device.setConverter(new StringConverter<>() {
      @Override public String toString(Device d) { return d == null ? "" : d.id + " · " + d.name; }
      @Override public Device fromString(String value) { return null; }
    });

    isolation.getItems().addAll("Нет", "По устройству");
    isolation.setPrefWidth(160);
    isolation.setTooltip(new Tooltip(
        "Нет — whisper.cpp видит все GPU. По устройству — процесс видит только выбранную GPU через CUDA_VISIBLE_DEVICES."));

    configureCpuSpinners();
    refreshChoices();
    load(configs.stt());

    Label name = Ui.label("Whisper STT", "model-name");
    Label subtitle = Ui.label("Отдельная модель распознавания речи · whisper.cpp", "tiny");
    VBox title = new VBox(1, Ui.row(Icons.of("voice"), name), subtitle);
    HBox.setHgrow(title, Priority.ALWAYS);

    configureRuntimeEnabledToggle();

    HBox top = new HBox(12, title);
    top.setAlignment(Pos.CENTER_LEFT);

    GridPane selector = grid();
    int sr = 0;
    add(selector, sr++, "Модель Whisper", model);
    add(selector, sr++, "Устройство запуска", device);
    add(selector, sr++, "Изоляция", isolation);
    Button refresh = Ui.button("Обновить список моделей", "refresh", this::refreshChoices);
    span(selector, refresh, sr);

    Button save = Ui.button("Сохранить Whisper", "tasks", () -> save(true));
    Button reset = Ui.button("Сбросить на быстрые стандартные", "refresh", this::resetWithConfirmation);
    UiStyles.dangerButton(reset);

    Label hint = Ui.label(
        "Whisper не является LLM и не получает роли агентов. Он запускается отдельным процессом, "
            + "но использует тот же список доступных CPU/GPU и режим изоляции, что остальные локальные модели.",
        "tiny");
    hint.setWrapText(true);

    getChildren().addAll(top, selector, Ui.row(save, reset), feedback, hint);
  }

  /** Startup permission is shown at the very top of the Voice / Basic tab. */
  Node startupPermissionContent() {
    Label stateLabel = Ui.label(enabled.isSelected() ? "Разрешён" : "Отключён", "body");
    enabled.selectedProperty().addListener((obs, oldValue, value) ->
        stateLabel.setText(value ? "Разрешён" : "Отключён"));
    Label hint = Ui.label(
        "Переключатель не запускает и не останавливает Whisper сразу. Он только разрешает или запрещает "
            + "автоматический запуск модели при следующем запуске системы.",
        "tiny");
    hint.setWrapText(true);
    HBox row = new HBox(12, Ui.label("Запускать Whisper вместе с системой", "body"), Ui.spacer(), stateLabel, enabled);
    row.setAlignment(Pos.CENTER_LEFT);
    return new VBox(8, row, hint);
  }

  /** Advanced Whisper runtime/decoding/VAD editor used by the Voice page's second tab. */
  Node advancedContent() {
    if (advancedContent != null) return advancedContent;
    Button save = Ui.button("Сохранить продвинутые настройки Whisper", "tasks", () -> save(true));
    advancedContent = new VBox(10,
        section("Универсальные настройки запуска", runtimeGrid()),
        section("Параметры декодирования Whisper", decodeGrid()),
        section("Whisper VAD", vadGrid()),
        save,
        advancedFeedback);
    advancedContent.setMaxWidth(Double.MAX_VALUE);
    return advancedContent;
  }

  String selectedModelLabel() {
    ModelChoice value = model.getValue();
    return value == null ? "—" : value.label();
  }

  String selectedDeviceLabel() {
    Device value = device.getValue();
    return value == null ? "—" : value.id + " · " + value.name;
  }

  String endpointLabel() {
    return baseUrl.getText().strip() + inferencePath.getText().strip();
  }

  void refreshFromConfig() {
    load(configs.stt());
  }

  private void configureRuntimeEnabledToggle() {
    enabled.getStyleClass().add("switch");
    Region dot = new Region();
    dot.getStyleClass().add("switch-dot");
    enabled.setGraphic(dot);
    enabled.setAccessibleText("Разрешить запуск Whisper вместе с системой");
    enabled.setTooltip(new Tooltip("Разрешает запуск Whisper при запуске NeuralSoul. Не запускает модель немедленно."));
    enabled.disableProperty().bind(state.systemRunning.or(state.systemStarting));
    enabled.selectedProperty().addListener((obs, oldValue, value) -> {
      if (loading) return;
      try {
        SttConfig current = configs.stt();
        SttConfig next = current.withRuntimeModel(
            value, current.executablePath(), current.modelPath(), current.runtimeDevice(), current.isolation(),
            current.threads(), current.processors(), current.flashAttention());
        saveStt.accept(next);
        String message = value
            ? "Whisper будет запускаться вместе с системой"
            : "Автозапуск Whisper отключён";
        feedback.setText(message);
        advancedFeedback.setText(message);
      } catch (Exception e) {
        loading = true;
        enabled.setSelected(oldValue);
        loading = false;
        feedback.setText(e.getMessage() == null ? e.toString() : e.getMessage());
      }
    });
  }

  void refreshChoices() {
    SttConfig current = configs.stt();
    String selectedPath = selectedModelPath();
    if (selectedPath.isBlank()) selectedPath = current.modelPath();
    final String wanted = selectedPath;

    List<ModelChoice> choices = scanModels();
    if (choices.stream().noneMatch(c -> c.path().equalsIgnoreCase(wanted)) && !wanted.isBlank())
      choices.add(0, choiceForPath(wanted));
    model.getItems().setAll(choices);
    model.setValue(choices.stream().filter(c -> c.path().equalsIgnoreCase(wanted)).findFirst()
        .orElse(choices.isEmpty() ? null : choices.get(0)));

    String selectedDevice = device.getValue() == null ? current.runtimeDevice() : device.getValue().id;
    device.getItems().setAll(state.devices.stream().filter(d -> d.enabled.get()).toList());
    device.setValue(device.getItems().stream().filter(d -> d.id.equals(selectedDevice)).findFirst()
        .orElse(device.getItems().stream().filter(d -> "CPU".equals(d.id)).findFirst()
            .orElse(device.getItems().isEmpty() ? null : device.getItems().get(0))));
  }

  private List<ModelChoice> scanModels() {
    Path root = Path.of(System.getProperty("user.dir"), "model", "stt", "whisper").toAbsolutePath().normalize();
    List<ModelChoice> result = new ArrayList<>();
    if (!Files.isDirectory(root)) return result;
    try (var stream = Files.walk(root)) {
      stream.filter(Files::isRegularFile)
          .filter(this::isWhisperModelFile)
          .sorted()
          .forEach(path -> {
            Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            String relative = project.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
            result.add(choiceForPath(relative));
          });
    } catch (Exception e) {
      feedback.setText("Не удалось просканировать model/stt/whisper: " + e.getMessage());
    }
    return result;
  }

  private boolean isWhisperModelFile(Path path) {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return !name.contains("vad") && (name.endsWith(".bin") || name.endsWith(".gguf"));
  }

  private ModelChoice choiceForPath(String path) {
    try {
      Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
      Path resolved = Path.of(path).isAbsolute() ? Path.of(path) : root.resolve(path);
      long bytes = Files.isRegularFile(resolved) ? Files.size(resolved) : -1;
      String file = resolved.getFileName() == null ? path : resolved.getFileName().toString();
      return new ModelChoice(path.replace('\\', '/'), bytes < 0 ? file : file + " · " + size(bytes));
    } catch (Exception e) {
      return new ModelChoice(path.replace('\\', '/'), path);
    }
  }

  private Node runtimeGrid() {
    GridPane g = grid(); int r = 0;
    add(g, r++, "whisper-server.exe", executable);
    add(g, r++, "Base URL", baseUrl);
    add(g, r++, "Inference path", inferencePath);
    add(g, r++, "HTTP timeout, sec", timeout);
    add(g, r++, "API model id", apiModel);
    add(g, r++, "Language", language);
    add(g, r++, "Multipart field", fileField);
    add(g, r++, "CPU threads", threads);
    add(g, r++, "Processors", processors);
    Label cpuHint = Ui.label(
        "Лимит CPU threads берётся из уже обнаруженного NeuralSoul процессора в «Доступных устройствах». "
            + "Даже при запуске Whisper на GPU CPU threads остаются активны.",
        "tiny");
    cpuHint.setWrapText(true);
    span(g, cpuHint, r++);
    span(g, flashAttention, r);
    return g;
  }

  private Node decodeGrid() {
    GridPane g = grid(); int r = 0;
    add(g,r++,"Max context",maxContext); add(g,r++,"Max segment len",maxLen);
    add(g,r++,"Best of",bestOf); add(g,r++,"Beam size",beamSize); add(g,r++,"Audio ctx",audioContext);
    add(g,r++,"Word threshold",wordThreshold); add(g,r++,"Entropy threshold",entropyThreshold);
    add(g,r++,"LogProb threshold",logProbThreshold); add(g,r++,"No-speech threshold",noSpeechThreshold);
    add(g,r++,"Temperature",temperature); add(g,r++,"Temperature increment",temperatureIncrement);
    span(g, new FlowPane(12, 8, splitOnWord, noFallback, suppressNonSpeech, noTimestamps, translate), r);
    return g;
  }

  private Node vadGrid() {
    GridPane g = grid(); int r = 0;
    span(g, vad, r++); add(g,r++,"VAD model",vadModel); add(g,r++,"Threshold",vadThreshold);
    add(g,r++,"Min speech, ms",vadMinSpeech); add(g,r++,"Min silence, ms",vadMinSilence);
    add(g,r++,"Max speech, sec",vadMaxSpeech); add(g,r++,"Speech pad, ms",vadPad);
    add(g,r++,"Overlap, sec",vadOverlap); return g;
  }

  private void save(boolean showFeedback) {
    try {
      SttConfig old = configs.stt();
      ModelChoice selectedModel = model.getValue();
      Device selectedDevice = device.getValue();
      if (selectedModel == null) throw new IllegalArgumentException("Не выбрана Whisper модель");
      if (selectedDevice == null) throw new IllegalArgumentException("Не выбрано устройство Whisper");
      int selectedThreads = spinnerValue(threads);
      int requestedProcessors = spinnerValue(processors);
      refreshProcessorLimit(selectedThreads, requestedProcessors);
      int selectedProcessors = spinnerValue(processors);
      EndpointConfig endpoint = new EndpointConfig(baseUrl.getText().strip(), inferencePath.getText().strip(), "/", "", i(timeout));
      SttConfig next = new SttConfig(endpoint, apiModel.getText().strip(), language.getText().strip(), fileField.getText().strip(),
          enabled.isSelected(), executable.getText().strip(), selectedModel.path(), selectedDevice.id,
          isolation.getValue(), selectedThreads, selectedProcessors, flashAttention.isSelected(),
          i(maxContext), i(maxLen), splitOnWord.isSelected(), i(bestOf), i(beamSize), i(audioContext),
          d(wordThreshold), d(entropyThreshold), d(logProbThreshold), d(noSpeechThreshold),
          d(temperature), d(temperatureIncrement), noFallback.isSelected(), suppressNonSpeech.isSelected(),
          noTimestamps.isSelected(), translate.isSelected(), vad.isSelected(), vadModel.getText().strip(),
          d(vadThreshold), i(vadMinSpeech), i(vadMinSilence), d(vadMaxSpeech), i(vadPad), d(vadOverlap),
          old.listenThreshold(), old.noiseMultiplier(), old.listenSpeechStartMs(), old.listenSilenceMs(),
          old.listenPreRollMs(), old.listenMinSendDelayMs(), old.listenMinUtteranceMs(),
          old.listenMaxUtteranceSeconds());
      saveStt.accept(next);
      if (showFeedback) {
        String message = "Whisper сохранён · " + selectedModel.label() + " · " + selectedDevice.id;
        feedback.setText(message);
        advancedFeedback.setText(message);
      }
    } catch (Exception e) {
      feedback.setText(e.getMessage() == null ? e.toString() : e.getMessage());
    }
  }

  private void resetWithConfirmation() {
    Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
    if (getScene() != null && getScene().getWindow() != null) alert.initOwner(getScene().getWindow());
    alert.setTitle("NeuralSoul · Сброс Whisper");
    alert.setHeaderText("Вернуть быстрые стандартные настройки Whisper?");
    alert.setContentText("Будут сброшены модель, устройство, изоляция, runtime, decoding и VAD. "
        + "Настройки автоматического окончания фразы во вкладке «Голос» не изменятся.");
    try { alert.getDialogPane().getStylesheets().add(getClass().getResource("/com/neuralsoul/theme.css").toExternalForm()); }
    catch (Throwable ignored) {}
    ButtonType reset = new ButtonType("Сбросить", ButtonBar.ButtonData.OK_DONE);
    ButtonType cancel = new ButtonType("Отмена", ButtonBar.ButtonData.CANCEL_CLOSE);
    alert.getButtonTypes().setAll(reset, cancel);
    alert.setOnShown(e -> {
      UiStyles.dangerButton((Button) alert.getDialogPane().lookupButton(reset));
      UiStyles.secondaryDialogButton((Button) alert.getDialogPane().lookupButton(cancel), "#4b4660");
    });
    if (alert.showAndWait().orElse(cancel) != reset) return;

    SttConfig old = configs.stt();
    SttConfig d = SttConfig.speedDefaults().withListener(
        old.listenThreshold(), old.noiseMultiplier(), old.listenSpeechStartMs(), old.listenSilenceMs(),
        old.listenPreRollMs(), old.listenMinSendDelayMs(), old.listenMinUtteranceMs(),
        old.listenMaxUtteranceSeconds());
    refreshChoices();
    load(d);
    save(false); // save the hardware-clamped CPU threads/processors shown in the editor
    feedback.setText("Восстановлены быстрые стандартные настройки Whisper");
    advancedFeedback.setText("Восстановлены быстрые стандартные настройки Whisper");
  }

  private void load(SttConfig c) {
    loading = true;
    enabled.setSelected(c.runtimeEnabled());
    loading = false;
    executable.setText(c.executablePath()); baseUrl.setText(c.endpoint().baseUrl());
    inferencePath.setText(c.endpoint().path()); timeout.setText("" + c.endpoint().timeoutSeconds());
    apiModel.setText(c.model()); language.setText(c.language()); fileField.setText(c.fileField());
    refreshCpuLimits(c.threads(), c.processors());
    flashAttention.setSelected(c.flashAttention());
    isolation.setValue("По устройству".equals(c.isolation()) ? "По устройству" : "Нет");
    maxContext.setText("" + c.maxContext()); maxLen.setText("" + c.maxLen()); splitOnWord.setSelected(c.splitOnWord());
    bestOf.setText("" + c.bestOf()); beamSize.setText("" + c.beamSize()); audioContext.setText("" + c.audioContext());
    wordThreshold.setText("" + c.wordThreshold()); entropyThreshold.setText("" + c.entropyThreshold());
    logProbThreshold.setText("" + c.logProbThreshold()); noSpeechThreshold.setText("" + c.noSpeechThreshold());
    temperature.setText("" + c.temperature()); temperatureIncrement.setText("" + c.temperatureIncrement());
    noFallback.setSelected(c.noFallback()); suppressNonSpeech.setSelected(c.suppressNonSpeech());
    noTimestamps.setSelected(c.noTimestamps()); translate.setSelected(c.translate());
    vad.setSelected(c.vad()); vadModel.setText(c.vadModelPath()); vadThreshold.setText("" + c.vadThreshold());
    vadMinSpeech.setText("" + c.vadMinSpeechMs()); vadMinSilence.setText("" + c.vadMinSilenceMs());
    vadMaxSpeech.setText("" + c.vadMaxSpeechSeconds()); vadPad.setText("" + c.vadSpeechPadMs());
    vadOverlap.setText("" + c.vadOverlapSeconds());

    refreshChoices();
    model.getItems().stream().filter(choice -> choice.path().equalsIgnoreCase(c.modelPath())).findFirst().ifPresent(model::setValue);
    device.getItems().stream().filter(d -> d.id.equals(c.runtimeDevice())).findFirst().ifPresent(device::setValue);
  }

  private void configureCpuSpinners() {
    threads.setEditable(true);
    processors.setEditable(true);
    threads.setPrefWidth(130);
    processors.setPrefWidth(130);
    refreshCpuLimits(configs.stt().threads(), configs.stt().processors());
    threads.valueProperty().addListener((obs, oldValue, newValue) -> {
      if (newValue != null) refreshProcessorLimit(newValue, processors.getValue());
    });
  }

  /** Uses HardwareLocator's already-detected CPU; this does not probe the machine a second time. */
  private int detectedLogicalCpuThreads() {
    try {
      CpuInfo cpu = HardwareLocator.getInstance().cpu();
      return cpu == null ? 1 : Math.max(1, cpu.logicalCores());
    } catch (Exception ignored) {
      return 1;
    }
  }

  private void refreshCpuLimits(int preferredThreads, int preferredProcessors) {
    int logical = detectedLogicalCpuThreads();
    int t = Math.max(1, Math.min(logical, preferredThreads));
    threads.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, logical, t, 1));
    refreshProcessorLimit(t, preferredProcessors);
    threads.setTooltip(new Tooltip("Обнаружено логических потоков CPU: " + logical));
  }

  private void refreshProcessorLimit(int selectedThreads, Integer preferredProcessors) {
    int logical = detectedLogicalCpuThreads();
    int safeThreads = Math.max(1, Math.min(logical, selectedThreads));
    int maxProcessors = Math.max(1, logical / safeThreads);
    int preferred = preferredProcessors == null ? 1 : preferredProcessors;
    int value = Math.max(1, Math.min(maxProcessors, preferred));
    processors.setValueFactory(
        new SpinnerValueFactory.IntegerSpinnerValueFactory(1, maxProcessors, value, 1));
    processors.setTooltip(new Tooltip(
        "Максимум рассчитан из CPU: floor(" + logical + " / " + safeThreads + ") = " + maxProcessors));
  }

  private String selectedModelPath() { return model.getValue() == null ? "" : model.getValue().path(); }
  private static String size(long bytes) {
    if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
    if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    return String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0);
  }
  private static VBox section(String title, Node content) { return Ui.card(title, content); }
  private static GridPane grid() {
    GridPane g = new GridPane(); g.setHgap(12); g.setVgap(8); g.setMaxWidth(Double.MAX_VALUE);
    ColumnConstraints a = new ColumnConstraints(); a.setMinWidth(170); a.setPrefWidth(210);
    ColumnConstraints b = new ColumnConstraints(); b.setHgrow(Priority.ALWAYS); b.setFillWidth(true);
    g.getColumnConstraints().addAll(a, b); return g;
  }
  private static void add(GridPane g, int row, String name, Control value) {
    g.add(Ui.label(name, "muted"), 0, row); g.add(value, 1, row); value.setMaxWidth(Double.MAX_VALUE);
  }
  private static void span(GridPane g, Node n, int row) { g.add(n, 0, row, 2, 1); }
  private static TextField field() { TextField f = new TextField(); f.setMaxWidth(Double.MAX_VALUE); return f; }
  private static int spinnerValue(Spinner<Integer> spinner) {
    SpinnerValueFactory<Integer> factory = spinner.getValueFactory();
    if (factory instanceof SpinnerValueFactory.IntegerSpinnerValueFactory ints) {
      int parsed;
      try { parsed = Integer.parseInt(spinner.getEditor().getText().strip()); }
      catch (Exception ignored) { parsed = spinner.getValue() == null ? ints.getMin() : spinner.getValue(); }
      parsed = Math.max(ints.getMin(), Math.min(ints.getMax(), parsed));
      factory.setValue(parsed);
      spinner.getEditor().setText(Integer.toString(parsed));
      return parsed;
    }
    return spinner.getValue() == null ? 1 : spinner.getValue();
  }
  private static int i(TextField f) { return Integer.parseInt(f.getText().strip()); }
  private static double d(TextField f) { return Double.parseDouble(f.getText().strip().replace(',', '.')); }
  private record ModelChoice(String path, String label) {}
}
