package com.neuralsoul.ui.pages;

import com.neuralsoul.config.*;
import com.neuralsoul.model.AppState;
import com.neuralsoul.model.VoiceInputMode;
import com.neuralsoul.service.audio.AudioDeviceManager;
import com.neuralsoul.ui.components.*;
import com.neuralsoul.ui.styles.UiStyles;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;

/** Voice control center split into basic, advanced, testing and information tabs. */
public final class VoicePage extends VBox {
  private final AppState state;
  private final ConfigLocator configs;
  private final Consumer<SttConfig> saveStt;
  private final Consumer<DoubleConsumer> toggleMonitor;
  private final WhisperModelPane whisperPane;

  private final ComboBox<AudioDeviceManager.Choice> microphoneDevice = new ComboBox<>();
  private final ComboBox<AudioDeviceManager.Choice> outputDevice = new ComboBox<>();
  private final Label audioFeedback = Ui.label("", "muted");
  private final AudioWaveformView waveform = new AudioWaveformView();
  private final Label micLevel = Ui.label("Уровень: —", "muted");

  private final TextField listenThreshold = field(), noiseMultiplier = field(), speechStart = field(), silence = field(),
      preRoll = field(), minSendDelay = field(), minUtterance = field(), maxUtterance = field();
  private final TextArea wavResult = new TextArea();
  private final Label listenerFeedback = Ui.label("", "muted");
  private final Label infoInput = wrap(""), infoOutput = wrap(""), infoWhisper = wrap("");

  public VoicePage(
      AppState state,
      ConfigLocator configs,
      Consumer<DoubleConsumer> toggleMonitor,
      Consumer<Consumer<String>> wavTest,
      Consumer<String> speak,
      Consumer<SttConfig> saveStt) {
    super(14);
    this.state = state;
    this.configs = configs;
    this.saveStt = saveStt;
    this.toggleMonitor = toggleMonitor;
    this.whisperPane = new WhisperModelPane(state, configs, cfg -> {
      saveStt.accept(cfg);
      refreshInformation();
    });
    setPadding(new Insets(12, 4, 20, 4));

    SttConfig c = configs.stt();
    loadListener(c);
    configureAudioSelectors();

    Node tabs = voiceTabs(
        new VoiceTab("Основное", mainTab()),
        new VoiceTab("Продвинутые настройки", advancedTab()),
        new VoiceTab("Тестирование", testingTab(wavTest, speak)),
        new VoiceTab("Информация", informationTab()));

    VBox.setVgrow(tabs, Priority.ALWAYS);
    getChildren().addAll(Ui.label("Распознавание речи · Whisper", "section-title"), tabs);
    refreshInformation();
  }

  private Node mainTab() {
    VBox startup = Ui.card("Whisper STT · запуск", whisperPane.startupPermissionContent());
    VBox voiceMode = voiceModeCard();

    VBox audio = Ui.card("Устройства аудио",
        audioDeviceGrid(),
        Ui.row(
            Ui.button("Обновить устройства", "refresh", this::refreshAudioDevices),
            Ui.button("Сохранить устройства", "tasks", this::saveAudioDevices)),
        audioFeedback,
        wrap("Выбранный микрофон используется записью и тестированием. "
            + "Устройство вывода используется TTS и прямым мониторингом микрофона."));

    return page(voiceMode, startup, audio, Ui.card("Модель и устройство Whisper", whisperPane));
  }

  private VBox voiceModeCard() {
    ToggleGroup group = new ToggleGroup();
    ToggleButton interrupt = new ToggleButton("Режим прерывания");
    ToggleButton buffer = new ToggleButton("Режим буфера");
    interrupt.setToggleGroup(group);
    buffer.setToggleGroup(group);
    interrupt.setFocusTraversable(false);
    buffer.setFocusTraversable(false);

    Runnable refresh = () -> {
      UiStyles.voiceMode(interrupt, interrupt.isSelected());
      UiStyles.voiceMode(buffer, buffer.isSelected());
    };
    interrupt.setOnAction(e -> {
      if (!interrupt.isSelected()) interrupt.setSelected(true);
      state.voiceInputMode.set(VoiceInputMode.INTERRUPT);
      refresh.run();
    });
    buffer.setOnAction(e -> {
      if (!buffer.isSelected()) buffer.setSelected(true);
      state.voiceInputMode.set(VoiceInputMode.BUFFER);
      refresh.run();
    });
    group.selectedToggleProperty().addListener((obs, oldValue, newValue) -> refresh.run());

    if (state.voiceInputMode.get() == VoiceInputMode.BUFFER) buffer.setSelected(true);
    else interrupt.setSelected(true);
    refresh.run();

    HBox buttons = new HBox(10, interrupt, buffer);
    buttons.setAlignment(Pos.CENTER_LEFT);
    Label buffered = Ui.label("", "muted");
    buffered.textProperty().bind(state.voiceBufferSize.asString("В буфере: %d фраз"));

    return Ui.card("Поведение голосового ввода",
        buttons,
        wrap("Режим прерывания — новая голосовая фраза останавливает текущую генерацию. "
            + "Предыдущий запрос остаётся в контексте, а новая фраза отправляется сразу после распознавания."),
        wrap("Режим буфера — текущая генерация не прерывается. Распознанные во время ответа фразы "
            + "накапливаются и после завершения ответа автоматически отправляются одним следующим сообщением."),
        buffered);
  }

  private Node advancedTab() {
    Button saveListener = Ui.button("Сохранить детектор речи", "tasks", this::saveListener);
    VBox listener = Ui.card("Детектор речи",
        wrap("Параметры локального определения начала и конца голосовой фразы до отправки WAV в Whisper."),
        listenerGrid(), saveListener, listenerFeedback);
    return page(whisperPane.advancedContent(), listener);
  }

  private Node testingTab(
      Consumer<Consumer<String>> wavTest,
      Consumer<String> speak) {
    Button monitor = Ui.button("Начать проверку микрофона", "mic", () -> {
      if (!state.microphoneMonitor.get()) waveform.clear();
      toggleMonitor.accept(level -> {
        waveform.pushLevel(level);
        double db = 20.0 * Math.log10(Math.max(level, 0.000001));
        micLevel.setText(String.format(java.util.Locale.ROOT, "Уровень: %.1f dBFS", db));
      });
    });
    monitor.disableProperty().bind(state.recording);
    Runnable updateMonitor = () -> monitor.setText(
        state.microphoneMonitor.get() ? "Остановить проверку микрофона" : "Начать проверку микрофона");
    state.microphoneMonitor.addListener((o, a, b) -> updateMonitor.run());
    updateMonitor.run();

    Label monitorHint = wrap("График показывает живой RMS-уровень выбранного микрофона. "
        + "Во время теста звук также выводится в выбранные наушники/устройство вывода; используй наушники, чтобы избежать акустической петли.");

    wavResult.setEditable(false);
    wavResult.setWrapText(true);
    wavResult.setPrefRowCount(5);
    wavResult.setPromptText("Здесь появится распознанный текст. В Main LLM он не отправляется.");
    Button wav = Ui.button("Выбрать WAV и распознать", "file", () -> wavTest.accept(text -> wavResult.setText(text)));
    wav.disableProperty().bind(state.busy.or(state.recording));

    TtsConfig tts = configs.tts();
    Label ttsInfo = wrap("Модель: " + display(tts.model()) + "   ·   Voice: " + display(tts.voice())
        + "   ·   Язык: " + display(tts.language()) + "   ·   Protocol: " + tts.protocol()
        + "   ·   Endpoint: " + tts.endpoint().baseUrl() + tts.endpoint().path());
    TextField ttsText = new TextField("Привет! Я Нина, твой локальный помощник.");
    Button ttsTest = Ui.button("Проверить TTS", "voice", () -> speak.accept(ttsText.getText()));
    ttsTest.disableProperty().bind(state.busy);

    return page(
        Ui.card("Проверка микрофона", monitor, micLevel, waveform, monitorHint),
        Ui.card("Проверка WAV → текст", wav, wavResult),
        Ui.card("Проверка TTS", ttsInfo, ttsText, ttsTest));
  }

  private Node informationTab() {
    Label sttStatus = Ui.label("", "body");
    sttStatus.textProperty().bind(state.sttStatus);
    Label ttsStatus = Ui.label("", "body");
    ttsStatus.textProperty().bind(state.ttsStatus);
    Button refresh = Ui.button("Обновить информацию", "refresh", () -> {
      whisperPane.refreshFromConfig();
      refreshAudioDevices();
      refreshInformation();
    });

    return page(
        Ui.card("Состояние голосовых сервисов",
            infoRow("Whisper / STT", sttStatus),
            infoRow("TTS", ttsStatus)),
        Ui.card("Текущая конфигурация", infoInput, infoOutput, infoWhisper, refresh),
        Ui.card("Где хранятся настройки",
            wrap("Whisper: " + configs.directory().resolve("stt.properties")),
            wrap("Микрофон и устройство вывода: " + configs.directory().resolve("gui.properties"))),
        Ui.card("О голосовом тракте",
            wrap("Микрофон → запись → Whisper STT → Main LLM → TTS → выбранное устройство вывода."),
            wrap("Whisper запускается отдельным процессом. Выбор CPU/GPU, изоляция, threads/processors, runtime, decoding и VAD находятся внутри вкладки «Голос».")));
  }

  private Node audioDeviceGrid() {
    GridPane g = grid();
    add(g, 0, "Микрофон", microphoneDevice);
    add(g, 1, "Наушники / вывод", outputDevice);
    return g;
  }

  private void configureAudioSelectors() {
    StringConverter<AudioDeviceManager.Choice> converter = new StringConverter<>() {
      @Override public String toString(AudioDeviceManager.Choice value) { return value == null ? "" : value.label(); }
      @Override public AudioDeviceManager.Choice fromString(String value) { return null; }
    };
    microphoneDevice.setConverter(converter);
    outputDevice.setConverter(converter);
    microphoneDevice.setMaxWidth(Double.MAX_VALUE);
    outputDevice.setMaxWidth(Double.MAX_VALUE);
    refreshAudioDevices();
  }

  private void refreshAudioDevices() {
    selectAudioChoice(microphoneDevice, AudioDeviceManager.inputDevices(), AudioDeviceManager.selectedInputId(configs));
    selectAudioChoice(outputDevice, AudioDeviceManager.outputDevices(), AudioDeviceManager.selectedOutputId(configs));
    refreshInformation();
  }

  private static void selectAudioChoice(ComboBox<AudioDeviceManager.Choice> box,
                                        List<AudioDeviceManager.Choice> choices,
                                        String selectedId) {
    String current = box.getValue() == null ? selectedId : box.getValue().id();
    box.getItems().setAll(choices);
    box.setValue(choices.stream().filter(c -> c.id().equals(current)).findFirst()
        .orElse(choices.isEmpty() ? null : choices.get(0)));
  }

  private void saveAudioDevices() {
    try {
      String input = microphoneDevice.getValue() == null ? "" : microphoneDevice.getValue().id();
      String output = outputDevice.getValue() == null ? "" : outputDevice.getValue().id();
      AudioDeviceManager.saveSelection(configs, input, output);
      audioFeedback.setText("Аудиоустройства сохранены");
      refreshInformation();
    } catch (Exception e) {
      audioFeedback.setText(e.getMessage() == null ? e.toString() : e.getMessage());
    }
  }

  private Node listenerGrid() {
    GridPane g = grid(); int r = 0;
    add(g,r++,"Min RMS threshold",listenThreshold); add(g,r++,"Noise multiplier",noiseMultiplier);
    add(g,r++,"Speech start, ms",speechStart); add(g,r++,"Silence → send, ms",silence);
    add(g,r++,"Pre-roll, ms",preRoll); add(g,r++,"Не отправлять раньше, ms",minSendDelay);
    add(g,r++,"Min utterance, ms",minUtterance);
    add(g,r++,"Max utterance, sec",maxUtterance); return g;
  }

  private void saveListener() {
    try {
      SttConfig current = configs.stt();
      SttConfig next = current.withListener(
          d(listenThreshold), d(noiseMultiplier), i(speechStart), i(silence), i(preRoll),
          i(minSendDelay), i(minUtterance), i(maxUtterance));
      saveStt.accept(next);
      listenerFeedback.setText("Настройки автоматического окончания фразы сохранены");
      refreshInformation();
    } catch (Exception e) {
      listenerFeedback.setText(e.getMessage() == null ? e.toString() : e.getMessage());
    }
  }

  private void loadListener(SttConfig c) {
    listenThreshold.setText("" + c.listenThreshold()); noiseMultiplier.setText("" + c.noiseMultiplier());
    speechStart.setText("" + c.listenSpeechStartMs()); silence.setText("" + c.listenSilenceMs());
    preRoll.setText("" + c.listenPreRollMs()); minSendDelay.setText("" + c.listenMinSendDelayMs());
    minUtterance.setText("" + c.listenMinUtteranceMs());
    maxUtterance.setText("" + c.listenMaxUtteranceSeconds());
  }

  private void refreshInformation() {
    AudioDeviceManager.Choice input = microphoneDevice.getValue();
    AudioDeviceManager.Choice output = outputDevice.getValue();
    infoInput.setText("Микрофон: " + (input == null ? "—" : input.label()));
    infoOutput.setText("Наушники / вывод: " + (output == null ? "—" : output.label()));
    SttConfig stt = configs.stt();
    infoWhisper.setText("Whisper: " + whisperPane.selectedModelLabel() + " · "
        + whisperPane.selectedDeviceLabel() + " · " + stt.threads() + " CPU threads · "
        + stt.processors() + " processors · " + whisperPane.endpointLabel());
  }

  private static HBox infoRow(String name, Node value) {
    HBox row = new HBox(12, Ui.label(name, "muted"), Ui.spacer(), value);
    row.setAlignment(Pos.CENTER_LEFT);
    return row;
  }

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
      button.setOnAction(e -> {
        if (!button.isSelected()) button.setSelected(true);
      });
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

  private static GridPane grid() {
    GridPane g = new GridPane(); g.setHgap(12); g.setVgap(8); g.setMaxWidth(Double.MAX_VALUE);
    ColumnConstraints a = new ColumnConstraints(); a.setMinWidth(170); a.setPrefWidth(210);
    ColumnConstraints b = new ColumnConstraints(); b.setHgrow(Priority.ALWAYS); b.setFillWidth(true);
    g.getColumnConstraints().addAll(a, b); return g;
  }

  private static void add(GridPane g, int row, String name, Control value) {
    g.add(Ui.label(name, "muted"), 0, row); g.add(value, 1, row); value.setMaxWidth(Double.MAX_VALUE);
  }
  private static TextField field() { TextField f = new TextField(); f.setMaxWidth(Double.MAX_VALUE); return f; }
  private static Label wrap(String text) { Label l = Ui.label(text, "muted"); l.setWrapText(true); l.setMaxWidth(Double.MAX_VALUE); return l; }
  private static int i(TextField f) { return Integer.parseInt(f.getText().strip()); }
  private static double d(TextField f) { return Double.parseDouble(f.getText().strip().replace(',', '.')); }
  private static String display(String v) { return v == null || v.isBlank() ? "—" : v; }
}
