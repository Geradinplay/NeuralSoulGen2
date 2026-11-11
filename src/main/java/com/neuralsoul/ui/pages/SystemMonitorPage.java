package com.neuralsoul.ui.pages;

import com.neuralsoul.hardware.GpuInfo;
import com.neuralsoul.hardware.GpuTelemetry;
import com.neuralsoul.hardware.HardwareInfo;
import com.neuralsoul.hardware.HardwareLocator;
import com.neuralsoul.hardware.HardwareTelemetry;
import com.neuralsoul.ui.components.Icons;
import com.neuralsoul.ui.components.ResponsiveColumns;
import com.neuralsoul.ui.components.Ui;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.util.Duration;

/** Dedicated detailed live hardware monitor, separate from the compact home dashboard. */
public final class SystemMonitorPage extends VBox {
  private static final String COLOR_TEXT = "#f1efff";
  private static final String COLOR_MUTED = "#aeb3cd";
  private static final String COLOR_ACCENT = "#aa78ff";
  private static final String PANEL_STYLE =
      "-fx-background-color:linear-gradient(to bottom right,#18172f,#101624);"
          + "-fx-background-radius:18;-fx-border-color:#665293;"
          + "-fx-border-radius:18;-fx-border-width:1;-fx-padding:20;";
  private static final String SUBCARD_STYLE =
      "-fx-background-color:linear-gradient(to bottom right,#1c2038,#15192d);"
          + "-fx-background-radius:14;-fx-border-color:#49466d;"
          + "-fx-border-radius:14;-fx-border-width:1;-fx-padding:16;";
  private static final String GPUCARD_STYLE =
      "-fx-background-color:linear-gradient(to right,#1b1a35,#12192a);"
          + "-fx-background-radius:16;-fx-border-color:#72529e;"
          + "-fx-border-radius:16;-fx-border-width:1;-fx-padding:18;";
  private static final Duration PROGRESS_ANIMATION_DURATION = Duration.millis(650);

  private final HardwareLocator hardware = HardwareLocator.getInstance();
  private final Label cpuValue = metricValue("—");
  private final Label ramValue = metricValue("—");
  private final ProgressBar cpuBar = progress();
  private final ProgressBar ramBar = progress();
  private final Map<Integer, GpuMeter> gpuMeters = new HashMap<>();
  private final Map<ProgressBar, Timeline> progressAnimations = new HashMap<>();
  private AutoCloseable telemetrySubscription;

  public SystemMonitorPage(Runnable settings) {
    super(16);
    setPadding(new Insets(18, 22, 22, 18));
    setFillWidth(true);
    setMinWidth(0);
    getChildren().add(buildHardwareCard(settings));

    // Only subscribe while the page is visible; release listeners on navigation.
    sceneProperty().addListener((obs, previous, current) -> {
      if (current == null) stopHardwareUpdates();
      else startHardwareUpdates();
    });
  }

  private VBox buildHardwareCard(Runnable settings) {
    VBox panel = new VBox(17);
    panel.setMinWidth(0);
    panel.setStyle(PANEL_STYLE);

    VBox heading = new VBox(5);
    HBox title = new HBox(9, Icons.of("chip"), headingLabel("Система", 23));
    title.setAlignment(Pos.CENTER_LEFT);
    Label cpuName = smallLabel("Мониторинг оборудования", 12);
    if (hardware.isInitialized()) cpuName.setText(hardware.info().cpu().name());
    cpuName.setWrapText(true);
    heading.getChildren().addAll(title, cpuName);
    heading.setMinWidth(0);
    HBox.setHgrow(heading, Priority.ALWAYS);

    Button settingsButton = Ui.link("Схема →", settings);
    settingsButton.setStyle(
        "-fx-background-color:#30254b;-fx-text-fill:#d7b6ff;"
            + "-fx-border-color:#9263ca;-fx-border-radius:12;"
            + "-fx-background-radius:12;-fx-border-width:1;"
            + "-fx-font-size:12px;-fx-padding:9 13;-fx-cursor:hand;");
    HBox top = new HBox(10, heading, settingsButton);
    top.setAlignment(Pos.CENTER_LEFT);
    panel.getChildren().add(top);

    if (!hardware.isInitialized()) {
      panel.getChildren().add(smallLabel("Мониторинг оборудования не инициализирован", 13));
      return panel;
    }

    VBox cpu = summaryCard("chip", "Загрузка ЦП", cpuValue, cpuBar);
    VBox ram = summaryCard("layers", "Оперативная память (ОЗУ)", ramValue, ramBar);
    panel.getChildren().add(new ResponsiveColumns(2, 810, cpu, ram));

    for (GpuInfo gpu : hardware.info().gpus()) {
      GpuMeter meter = new GpuMeter(gpu);
      gpuMeters.put(gpu.index(), meter);
      panel.getChildren().add(meter.card);
    }
    if (gpuMeters.isEmpty()) panel.getChildren().add(smallLabel("Видеокарты не обнаружены", 13));

    HBox footer = new HBox(8, Icons.of("refresh"),
        smallLabel("Обновление: 1 сек. · график нагрузки за 60 последних измерений", 11));
    footer.setAlignment(Pos.CENTER_LEFT);
    panel.getChildren().add(footer);
    return panel;
  }

  private static VBox summaryCard(String icon, String title, Label value, ProgressBar bar) {
    VBox content = new VBox(10);
    content.setStyle(SUBCARD_STYLE);
    content.setMinWidth(0);
    Label label = smallLabel(title, 13);
    label.setWrapText(true);
    HBox headline = new HBox(10, label, Ui.spacer(), value);
    headline.setAlignment(Pos.CENTER_LEFT);
    VBox meter = new VBox(9, headline, bar);
    meter.setMinWidth(0);
    HBox.setHgrow(meter, Priority.ALWAYS);
    HBox line = new HBox(13, iconTile(icon), meter);
    line.setAlignment(Pos.CENTER_LEFT);
    content.getChildren().add(line);
    return content;
  }

  private static StackPane iconTile(String icon) {
    StackPane tile = new StackPane(Icons.of(icon));
    tile.setMinSize(43, 43);
    tile.setPrefSize(43, 43);
    tile.setMaxSize(43, 43);
    tile.setStyle(
        "-fx-background-color:#30264e;-fx-background-radius:11;"
            + "-fx-border-color:#795aaf;-fx-border-radius:11;-fx-border-width:1;");
    return tile;
  }

  private static VBox progressMeter(String text, Label value, ProgressBar bar) {
    Label caption = smallLabel(text, 12);
    HBox header = new HBox(10, caption, Ui.spacer(), value);
    header.setAlignment(Pos.CENTER_LEFT);
    VBox box = new VBox(7, header, bar);
    box.setMinWidth(0);
    return box;
  }

  private static ProgressBar progress() {
    ProgressBar bar = Ui.progress(0, "");
    bar.setMinWidth(0);
    bar.setMaxWidth(Double.MAX_VALUE);
    bar.setPrefHeight(12);
    bar.setStyle("-fx-accent:" + COLOR_ACCENT + ";-fx-background-radius:9;");
    return bar;
  }

  private static Label headingLabel(String text, int size) {
    Label label = new Label(text);
    label.setStyle("-fx-font-weight:bold;-fx-text-fill:" + COLOR_TEXT + ";-fx-font-size:" + size + "px;");
    return label;
  }

  private static Label metricValue(String text) {
    Label label = new Label(text);
    label.setStyle(
        "-fx-font-size:17px;-fx-font-weight:bold;-fx-text-fill:" + COLOR_TEXT + ";");
    label.setMinWidth(Region.USE_PREF_SIZE);
    return label;
  }

  private static Label smallLabel(String text, int size) {
    Label label = new Label(text);
    label.setStyle("-fx-text-fill:" + COLOR_MUTED + ";-fx-font-size:" + size + "px;");
    return label;
  }

  private static VBox chip(String glyph, String title, Label value) {
    Label mark = new Label(glyph);
    mark.setStyle("-fx-text-fill:#bf93ff;-fx-font-size:16px;");
    VBox texts = new VBox(2, smallLabel(title, 10), value);
    HBox content = new HBox(8, mark, texts);
    content.setAlignment(Pos.CENTER_LEFT);
    VBox chip = new VBox(content);
    chip.setMinWidth(110);
    chip.setStyle(
        "-fx-background-color:#25243e;-fx-background-radius:10;"
            + "-fx-border-color:#4f4b75;-fx-border-radius:10;"
            + "-fx-border-width:1;-fx-padding:8 10;");
    return chip;
  }

  private final class GpuMeter {
    final VBox card = new VBox(15);
    final ProgressBar usage = progress();
    final Label usageValue = metricValue("—");
    final ProgressBar vram = progress();
    final Label vramValue = metricValue("—");
    final Label temperature = metricValue("—");
    final Label power = metricValue("—");
    final Label fan = metricValue("—");
    final GpuSparkline chart = new GpuSparkline();

    GpuMeter(GpuInfo gpu) {
      card.setMinWidth(0);
      card.setStyle(GPUCARD_STYLE);

      Label badge = smallLabel("GPU " + gpu.index(), 12);
      badge.setStyle(
          "-fx-text-fill:#e6d9ff;-fx-font-weight:bold;-fx-font-size:12px;"
              + "-fx-background-color:#463365;-fx-background-radius:16;"
              + "-fx-padding:4 10;-fx-border-color:#825ab7;"
              + "-fx-border-radius:16;-fx-border-width:1;");
      Label title = headingLabel(gpu.name(), 17);
      title.setWrapText(true);
      title.setMinWidth(0);
      HBox.setHgrow(title, Priority.ALWAYS);
      HBox header = new HBox(11, iconTile("monitor"), badge, title);
      header.setAlignment(Pos.CENTER_LEFT);
      card.getChildren().add(header);

      VBox stats = new VBox(20,
          progressMeter("Загрузка GPU", usageValue, usage),
          progressMeter("Видеопамять (VRAM)", vramValue, vram));
      stats.setMinWidth(0);
      stats.setAlignment(Pos.CENTER_LEFT);

      Label chartTitle = smallLabel("НАГРУЗКА · ПОСЛЕДНИЕ 60 СЕК.", 10);
      HBox.setHgrow(chartTitle, Priority.ALWAYS);
      javafx.scene.layout.FlowPane chips = new javafx.scene.layout.FlowPane(8, 8,
          chip("°", "Температура", temperature),
          chip("ϟ", "Потребление", power),
          chip("◉", "Вентилятор", fan));
      chips.setPrefWrapLength(400);
      chips.setMinWidth(0);
      VBox graph = new VBox(8, chartTitle, chart, chips);
      graph.setMinWidth(0);
      graph.setMaxWidth(Double.MAX_VALUE);

      card.getChildren().add(new ResponsiveColumns(2, 670, stats, graph));
    }

    void update(GpuTelemetry data, GpuInfo info) {
      setPercent(usage, usageValue, data.utilizationPercent());
      if (Double.isFinite(data.utilizationPercent()) && data.utilizationPercent() >= 0)
        chart.add(data.utilizationPercent());

      if (data.usedVramBytes() >= 0 && info.totalVramBytes() > 0) {
        animateProgress(vram, clamp01((double) data.usedVramBytes() / info.totalVramBytes()));
        vramValue.setText(String.format(Locale.US, "%.1f / %.1f GB",
            gib(data.usedVramBytes()), gib(info.totalVramBytes())));
      } else {
        animateProgress(vram, 0);
        vramValue.setText("N/A");
      }
      temperature.setText(formatMetric(data.temperatureCelsius(), "°C"));
      power.setText(formatMetric(data.powerWatts(), " W"));
      fan.setText(formatMetric(data.fanPercent(), "%"));
    }
  }

  private static String formatMetric(double value, String unit) {
    if (!Double.isFinite(value) || value < 0) return "N/A";
    return String.format(Locale.US, "%.0f%s", value, unit);
  }

  /** Draws only sampled GPU utilization, no synthetic or guessed history. */
  private static final class GpuSparkline extends StackPane {
    private final Canvas canvas = new Canvas(300, 71);
    private final ArrayDeque<Double> samples = new ArrayDeque<>(60);

    GpuSparkline() {
      setMinWidth(0);
      setPrefHeight(91);
      setMinHeight(91);
      setPadding(new Insets(10));
      setStyle(
          "-fx-background-color:#171b30;-fx-background-radius:11;"
              + "-fx-border-color:#484265;-fx-border-radius:11;-fx-border-width:1;");
      canvas.widthProperty().bind(Bindings.max(1.0, widthProperty().subtract(20.0)));
      canvas.widthProperty().addListener((obs, before, after) -> draw());
      getChildren().add(canvas);
      draw();
    }

    void add(double percentage) {
      if (!Double.isFinite(percentage)) return;
      if (samples.size() == 60) samples.removeFirst();
      samples.addLast(Math.max(0, Math.min(100, percentage)));
      draw();
    }

    private void draw() {
      double width = canvas.getWidth();
      double height = canvas.getHeight();
      if (width <= 2 || height <= 2) return;
      GraphicsContext g = canvas.getGraphicsContext2D();
      g.clearRect(0, 0, width, height);
      g.setStroke(Color.web("#31324e"));
      g.setLineWidth(1);
      for (int i = 1; i <= 3; i++) {
        double y = i * height / 4.0;
        g.strokeLine(0, y, width, y);
      }
      if (samples.size() < 2) {
        g.setFill(Color.web("#8b91ad"));
        g.fillText("Ожидание данных…", 8, height / 2.0 + 4);
        return;
      }
      double[] numbers = new double[samples.size()];
      int n = 0;
      double max = 0;
      for (double value : samples) {
        numbers[n++] = value;
        max = Math.max(max, value);
      }
      // Dynamic scale preserves detail for low utilization as well.
      double top = Math.max(20, Math.ceil((max + 5) / 10) * 10);
      double[] ys = new double[n];
      for (int i = 0; i < n; i++)
        ys[i] = height - 5 - numbers[i] / top * (height - 12);

      g.beginPath();
      g.moveTo(0, height);
      for (int i = 0; i < n; i++) g.lineTo(i * width / (n - 1.0), ys[i]);
      g.lineTo(width, height);
      g.closePath();
      g.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
          new Stop(0, Color.web("#a875ff", .28)),
          new Stop(1, Color.web("#a875ff", .01))));
      g.fill();

      g.setStroke(Color.web("#c58cff"));
      g.setLineWidth(2);
      g.beginPath();
      for (int i = 0; i < n; i++) {
        double x = i * width / (n - 1.0);
        if (i == 0) g.moveTo(x, ys[i]);
        else g.lineTo(x, ys[i]);
      }
      g.stroke();
    }
  }

  private void startHardwareUpdates() {
    if (!hardware.isInitialized() || telemetrySubscription != null) return;
    telemetrySubscription = hardware.subscribe(
        telemetry -> Platform.runLater(() -> {
          if (getScene() != null) updateHardwareUi(telemetry);
        }));
  }

  private void stopHardwareUpdates() {
    if (telemetrySubscription != null) {
      try { telemetrySubscription.close(); } catch (Exception ignored) { }
      telemetrySubscription = null;
    }
    for (Timeline animation : progressAnimations.values()) animation.stop();
    progressAnimations.clear();
  }

  private void updateHardwareUi(HardwareTelemetry telemetry) {
    HardwareInfo info = hardware.info();
    setPercent(cpuBar, cpuValue, telemetry.cpuUsagePercent());
    long totalRam = info.memory().totalBytes();
    long usedRam = telemetry.usedRamBytes();
    animateProgress(ramBar, totalRam > 0 ? clamp01((double) usedRam / totalRam) : 0);
    ramValue.setText(totalRam > 0
        ? String.format(Locale.US, "%.1f / %.1f GB", gib(usedRam), gib(totalRam)) : "N/A");
    Map<Integer, GpuTelemetry> current = new HashMap<>();
    for (GpuTelemetry gpu : telemetry.gpus()) current.put(gpu.index(), gpu);
    for (GpuInfo gpu : info.gpus()) {
      GpuMeter meter = gpuMeters.get(gpu.index());
      GpuTelemetry sample = current.get(gpu.index());
      if (meter != null && sample != null) meter.update(sample, gpu);
    }
  }

  private void setPercent(ProgressBar bar, Label value, double percent) {
    if (!Double.isFinite(percent) || percent < 0) {
      animateProgress(bar, 0);
      value.setText("N/A");
    } else {
      animateProgress(bar, clamp01(percent / 100));
      value.setText(String.format(Locale.US, "%.0f%%", percent));
    }
  }

  private void animateProgress(ProgressBar bar, double target) {
    Timeline previous = progressAnimations.remove(bar);
    if (previous != null) previous.stop();
    Timeline animation = new Timeline(new KeyFrame(PROGRESS_ANIMATION_DURATION,
        new KeyValue(bar.progressProperty(), target, Interpolator.EASE_BOTH)));
    animation.setOnFinished(e -> progressAnimations.remove(bar, animation));
    progressAnimations.put(bar, animation);
    animation.play();
  }

  private static double clamp01(double number) {
    return Math.max(0, Math.min(1, number));
  }

  private static double gib(long bytes) {
    return bytes / 1024.0 / 1024.0 / 1024.0;
  }
}
