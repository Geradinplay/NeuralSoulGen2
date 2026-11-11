package com.neuralsoul.ui.pages;

import com.neuralsoul.hardware.*;
import com.neuralsoul.model.*;
import com.neuralsoul.ui.components.*;
import java.util.*;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.util.Duration;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

public final class DashboardPage extends VBox {
  private final HardwareLocator hardware = HardwareLocator.getInstance();

  private final Label cpuValue = Ui.label("—", "tiny");
  private final ProgressBar cpuBar = Ui.progress(0, "");

  private final Label ramValue = Ui.label("—", "tiny");
  private final ProgressBar ramBar = Ui.progress(0, "");

  private final Map<Integer, Label> gpuUsageValues = new HashMap<>();
  private final Map<Integer, ProgressBar> gpuUsageBars = new HashMap<>();
  private final Map<Integer, Label> gpuVramValues = new HashMap<>();
  private final Map<Integer, ProgressBar> gpuVramBars = new HashMap<>();
  private final Map<Integer, Label> gpuDetails = new HashMap<>();

  private AutoCloseable telemetrySubscription;
  private final Map<ProgressBar, Timeline> progressAnimations = new HashMap<>();
  private static final Duration PROGRESS_ANIMATION_DURATION = Duration.millis(750);

  public DashboardPage(
      AppState state,
      Consumer<String> navigate,
      Runnable settings,
      Runnable task,
      Runnable project) {
    super(12);
    setPadding(new Insets(4, 16, 12, 4));

    AvatarPane avatar = new AvatarPane();
    HBox.setHgrow(avatar, Priority.ALWAYS);

    VBox system = buildHardwareCard(settings);

    Label llm = Ui.label("", "tiny");
    Label stt = Ui.label("", "tiny");
    Label tts = Ui.label("", "tiny");

    llm.textProperty().bind(state.llmStatus);
    stt.textProperty().bind(state.sttStatus);
    tts.textProperty().bind(state.ttsStatus);

    VBox models =
        Ui.card(
            "Сервисы",
            Ui.row(Icons.of("spark"), Ui.label("LLM", "body"), Ui.spacer(), llm),
            Ui.row(Icons.of("mic"), Ui.label("STT", "body"), Ui.spacer(), stt),
            Ui.row(Icons.of("voice"), Ui.label("TTS", "body"), Ui.spacer(), tts),
            Ui.link("Список моделей →", () -> navigate.accept("Модели")));

    GridPane quick = new GridPane();
    quick.setHgap(8);
    quick.setVgap(8);

    String[] labels = {
      "Новый чат",
      "Голосовой режим",
      "Агент-задание",
      "Открыть проект",
      "Захват экрана",
      "Продолжить задачу"
    };

    String[] icons = {"chat", "voice", "tasks", "folder", "monitor", "tasks"};

    Runnable[] actions = {
      () -> navigate.accept("Чат"),
      () -> navigate.accept("Голос"),
      task,
      project,
      () -> navigate.accept("Компьютер"),
      () -> navigate.accept("Текущий промт")
    };

    for (int i = 0; i < labels.length; i++) {
      Button b = Ui.button(labels[i], icons[i], actions[i]);
      b.setMaxWidth(Double.MAX_VALUE);
      b.getStyleClass().add("quick-button");
      quick.add(b, i % 2, i / 2);
    }

    for (int i = 0; i < 2; i++) {
      ColumnConstraints col = new ColumnConstraints();
      col.setPercentWidth(50);
      quick.getColumnConstraints().add(col);
    }

    VBox right = new VBox(8, system, models, Ui.card("Быстрые действия", quick));
    right.setPrefWidth(370);
    right.setMinWidth(320);

    ResponsiveColumns top = new ResponsiveColumns(2, 740, avatar, right);
    getChildren().add(top);

    // Dashboard may be created repeatedly when navigating. Subscribe only while
    // it belongs to a Scene so old pages cannot leak listeners.
    sceneProperty()
        .addListener(
            (obs, oldScene, newScene) -> {
              if (newScene != null) startHardwareUpdates();
              else stopHardwareUpdates();
            });
  }

  private VBox buildHardwareCard(Runnable settings) {
    List<Node> rows = new ArrayList<>();
    rows.add(
        Ui.row(
            Ui.label("Система", "card-title"),
            Ui.spacer(),
            Ui.link("Подробно →", settings)));

    if (!hardware.isInitialized()) {
      rows.add(Ui.label("Мониторинг оборудования не инициализирован", "tiny"));
      return Ui.card("", rows.toArray(Node[]::new));
    }

    HardwareInfo info = hardware.info();

    rows.add(Ui.label(info.cpu().name(), "tiny"));
    rows.add(meter("CPU", cpuValue, cpuBar));

    rows.add(
        meter(
            "RAM",
            ramValue,
            ramBar));

    for (GpuInfo gpu : info.gpus()) {
      rows.add(Ui.label("GPU " + gpu.index() + " · " + gpu.name(), "tiny"));

      Label usageValue = Ui.label("—", "tiny");
      ProgressBar usageBar = Ui.progress(0, "");
      gpuUsageValues.put(gpu.index(), usageValue);
      gpuUsageBars.put(gpu.index(), usageBar);
      rows.add(meter("GPU", usageValue, usageBar));

      Label vramValue = Ui.label("—", "tiny");
      ProgressBar vramBar = Ui.progress(0, "");
      gpuVramValues.put(gpu.index(), vramValue);
      gpuVramBars.put(gpu.index(), vramBar);
      rows.add(meter("VRAM", vramValue, vramBar));

      Label details = Ui.label("—", "tiny");
      gpuDetails.put(gpu.index(), details);
      rows.add(details);
    }

    rows.add(Ui.label("Обновление: 1 сек.", "tiny"));
    return Ui.card("", rows.toArray(Node[]::new));
  }

  private Node meter(String name, Label value, ProgressBar bar) {
    bar.setMaxWidth(Double.MAX_VALUE);
    VBox.setVgrow(bar, Priority.NEVER);
    return new VBox(3, Ui.row(Ui.label(name, "tiny"), Ui.spacer(), value), bar);
  }

  private void startHardwareUpdates() {
    if (!hardware.isInitialized() || telemetrySubscription != null) return;

    telemetrySubscription =
        hardware.subscribe(
            telemetry -> Platform.runLater(() -> updateHardwareUi(telemetry)));
  }

  private void stopHardwareUpdates() {
    if (telemetrySubscription == null) return;
    try {
      telemetrySubscription.close();
    } catch (Exception ignored) {
    }
    telemetrySubscription = null;

    for (Timeline animation : progressAnimations.values()) animation.stop();
    progressAnimations.clear();
  }

  private void updateHardwareUi(HardwareTelemetry telemetry) {
    HardwareInfo info = hardware.info();

    setPercent(cpuBar, cpuValue, telemetry.cpuUsagePercent());

    long totalRam = info.memory().totalBytes();
    long usedRam = telemetry.usedRamBytes();
    double ramAmount = totalRam > 0 ? (double) usedRam / totalRam : 0;
    animateProgress(ramBar, clamp01(ramAmount));
    ramValue.setText(
        String.format(Locale.US, "%.1f / %.1f GB", gib(usedRam), gib(totalRam)));

    Map<Integer, GpuTelemetry> current = new HashMap<>();
    for (GpuTelemetry gpu : telemetry.gpus()) current.put(gpu.index(), gpu);

    for (GpuInfo infoGpu : info.gpus()) {
      GpuTelemetry gpu = current.get(infoGpu.index());
      if (gpu == null) continue;

      Label usageValue = gpuUsageValues.get(infoGpu.index());
      ProgressBar usageBar = gpuUsageBars.get(infoGpu.index());
      setPercent(usageBar, usageValue, gpu.utilizationPercent());

      Label vramValue = gpuVramValues.get(infoGpu.index());
      ProgressBar vramBar = gpuVramBars.get(infoGpu.index());

      if (gpu.usedVramBytes() >= 0 && infoGpu.totalVramBytes() > 0) {
        animateProgress(
            vramBar,
            clamp01((double) gpu.usedVramBytes() / infoGpu.totalVramBytes()));
        vramValue.setText(
            String.format(
                Locale.US,
                "%.1f / %.1f GB",
                gib(gpu.usedVramBytes()),
                gib(infoGpu.totalVramBytes())));
      } else {
        animateProgress(vramBar, 0);
        vramValue.setText("N/A");
      }

      Label details = gpuDetails.get(infoGpu.index());
      if (details != null) {
        List<String> parts = new ArrayList<>();
        if (gpu.temperatureCelsius() >= 0)
          parts.add(String.format(Locale.US, "%.0f°C", gpu.temperatureCelsius()));
        if (gpu.powerWatts() >= 0)
          parts.add(String.format(Locale.US, "%.0f W", gpu.powerWatts()));
        if (gpu.fanPercent() >= 0)
          parts.add(String.format(Locale.US, "Fan %.0f%%", gpu.fanPercent()));
        details.setText(parts.isEmpty() ? "Телеметрия недоступна" : String.join(" · ", parts));
      }
    }
  }

  private void setPercent(ProgressBar bar, Label value, double percent) {
    if (bar == null || value == null) return;
    if (!Double.isFinite(percent) || percent < 0) {
      animateProgress(bar, 0);
      value.setText("N/A");
      return;
    }
    animateProgress(bar, clamp01(percent / 100.0));
    value.setText(String.format(Locale.US, "%.0f%%", percent));
  }

  private void animateProgress(ProgressBar bar, double target) {
    Timeline previous = progressAnimations.remove(bar);
    if (previous != null) previous.stop();

    Timeline animation =
        new Timeline(
            new KeyFrame(
                PROGRESS_ANIMATION_DURATION,
                new KeyValue(
                    bar.progressProperty(),
                    target,
                    Interpolator.EASE_BOTH)));

    animation.setOnFinished(event -> progressAnimations.remove(bar, animation));
    progressAnimations.put(bar, animation);
    animation.play();
  }

  private static double clamp01(double value) {
    return Math.max(0, Math.min(1, value));
  }

  private static double gib(long bytes) {
    return bytes / 1024.0 / 1024.0 / 1024.0;
  }
}
