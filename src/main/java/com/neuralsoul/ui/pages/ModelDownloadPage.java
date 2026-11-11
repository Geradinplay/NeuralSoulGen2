package com.neuralsoul.ui.pages;

import com.neuralsoul.model.AppState;
import com.neuralsoul.model.ModelAssignment;
import com.neuralsoul.model.Profile;
import com.neuralsoul.model.download.ModelDownloadService;
import com.neuralsoul.model.download.ModelDownloadService.RuntimeKind;
import com.neuralsoul.ui.components.Ui;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/** Download / import portable model weights; never downloads or changes runtime executables. */
public final class ModelDownloadPage extends VBox {
  private static final String WHISPER_REPO = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";
  private final AppState state;
  private final Runnable persist;
  private final ComboBox<RuntimeKind> runtime = new ComboBox<>();
  private final ComboBox<String> whisperPresets = new ComboBox<>();
  private final TextField url = new TextField();
  private final TextField fileName = new TextField();
  private final Label folderHint = Ui.label("", "tiny");
  private final Label stateLabel = Ui.label("Выбери рантайм и укажи прямую ссылку на файл модели.", "muted");
  private final Label available = Ui.label("", "tiny");
  private final ProgressBar progressBar = new ProgressBar(0);
  private final Button download = Ui.button("Скачать по ссылке", "download", this::startDownload);
  private final Button importLocal = Ui.button("Импортировать файл", "attach", this::importFromDisk);
  private final Button cancel = Ui.button("Отменить", "stop", this::cancelDownload);
  private final AtomicBoolean cancellation = new AtomicBoolean(false);
  private Task<Path> currentTask;

  public ModelDownloadPage(AppState state, Runnable persist) {
    super(12);
    this.state = state;
    this.persist = persist == null ? () -> {} : persist;
    setPadding(new Insets(18));
    setMinWidth(0);
    setMaxWidth(Double.MAX_VALUE);

    runtime.getItems().setAll(RuntimeKind.values());
    runtime.getSelectionModel().select(RuntimeKind.LLAMA);
    runtime.setMaxWidth(Double.MAX_VALUE);
    whisperPresets.getItems().setAll(
        "ggml-tiny.bin", "ggml-base.bin", "ggml-small.bin", "ggml-medium.bin",
        "ggml-large-v3-turbo.bin", "ggml-large-v3-turbo-q5_0.bin", "ggml-large-v3.bin");
    whisperPresets.setPromptText("Выбери готовую модель Whisper");
    whisperPresets.setMaxWidth(Double.MAX_VALUE);
    whisperPresets.setOnAction(e -> {
      String selected = whisperPresets.getValue();
      if (selected != null && runtime.getValue() == RuntimeKind.WHISPER) {
        url.setText(WHISPER_REPO + selected);
        fileName.clear();
      }
    });

    url.setPromptText("https://huggingface.co/автор/репозиторий/resolve/main/имя.gguf");
    url.setMaxWidth(Double.MAX_VALUE);
    url.setTooltip(new Tooltip("Нужна прямая HTTPS-ссылка на файл, не ссылка /blob/ или /tree/"));
    fileName.setPromptText("Необязательно — имя берётся из ссылки или исходного файла");
    fileName.setMaxWidth(Double.MAX_VALUE);

    runtime.setOnAction(e -> {
      url.clear();
      fileName.clear();
      whisperPresets.getSelectionModel().clearSelection();
      updateRuntimeHints();
    });
    updateRuntimeHints();

    HBox buttons = new HBox(10, download, importLocal, cancel);
    buttons.setAlignment(Pos.CENTER_LEFT);
    cancel.setDisable(true);
    progressBar.setMaxWidth(Double.MAX_VALUE);
    progressBar.setPrefHeight(16);
    stateLabel.setWrapText(true);
    folderHint.setWrapText(true);
    available.setWrapText(true);

    Label instructions = Ui.label(
        "Скачивается только файл весов, не сам рантайм. Для llama.cpp нужен GGUF, "
        + "для whisper.cpp — GGML .bin. Существующие модели не перезаписываются. "
        + "После скачивания модель не запускается автоматически.", "tiny");
    instructions.setWrapText(true);

    VBox presetsBlock = new VBox(6,
        Ui.label("Быстрый выбор Whisper", "body"), whisperPresets);
    presetsBlock.visibleProperty().bind(runtime.valueProperty().isEqualTo(RuntimeKind.WHISPER));
    presetsBlock.managedProperty().bind(presetsBlock.visibleProperty());

    getChildren().addAll(
        Ui.label("Загрузка моделей", "section-title"),
        Ui.label("Локальные модели для llama.cpp и Whisper", "muted"),
        Ui.card("Новая модель",
            Ui.label("Рантайм", "body"), runtime,
            folderHint,
            presetsBlock,
            Ui.label("Прямая HTTPS-ссылка", "body"), url,
            Ui.label("Имя файла (необязательно)", "body"), fileName,
            buttons,
            progressBar,
            stateLabel,
            instructions),
        Ui.card("Установленные модели",
            available,
            Ui.button("Обновить список", "refresh", this::refreshInstalled)));
    refreshInstalled();
  }

  private void updateRuntimeHints() {
    RuntimeKind kind = runtime.getValue();
    if (kind == null) kind = RuntimeKind.LLAMA;
    folderHint.setText("Каталог установки: " + kind.directory()
        + "/<имя модели>/<имя модели>" + kind.extension()
        + " (относительно рабочей папки NeuralSoul)");
    url.setPromptText(kind == RuntimeKind.LLAMA
        ? "https://huggingface.co/автор/GGUF/resolve/main/model.gguf"
        : WHISPER_REPO + "ggml-base.bin");
    refreshInstalled();
  }

  private void startDownload() {
    if (currentTask != null) return;
    RuntimeKind kind = runtime.getValue();
    String address = url.getText().strip();
    if (address.isEmpty()) {
      stateLabel.setText("Введи прямую HTTPS-ссылку на файл модели.");
      return;
    }
    launchTask(kind, null, address, fileName.getText().strip());
  }

  private void importFromDisk() {
    if (currentTask != null) return;
    RuntimeKind kind = runtime.getValue();
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Выбери модель для " + kind.label());
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
        kind == RuntimeKind.LLAMA ? "GGUF модели" : "Whisper GGML модели",
        "*" + kind.extension()));
    java.io.File source = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
    if (source == null) return;
    launchTask(kind, source.toPath(), null, fileName.getText().strip());
  }

  private void launchTask(RuntimeKind kind, Path source, String address, String requestedName) {
    cancellation.set(false);
    Task<Path> task = new Task<>() {
      @Override protected Path call() throws Exception {
        updateMessage("Подготовка загрузки…");
        ModelDownloadService.Progress indicator = (done, total) -> {
          if (total > 0) updateProgress(done, total);
          else updateProgress(-1, 1);
          updateMessage("Получено " + formatBytes(done)
              + (total > 0 ? " / " + formatBytes(total) : " (размер неизвестен)"));
        };
        if (source != null)
          return ModelDownloadService.importFile(kind, source, requestedName, indicator,
              () -> cancellation.get() || isCancelled());
        return ModelDownloadService.download(kind, address, requestedName, indicator,
            () -> cancellation.get() || isCancelled());
      }
    };
    currentTask = task;
    progressBar.progressProperty().bind(task.progressProperty());
    stateLabel.textProperty().bind(task.messageProperty());
    setBusy(true);
    task.setOnSucceeded(e -> {
      Path installed = task.getValue();
      finishTask();
      try {
        if (kind == RuntimeKind.LLAMA) {
          state.refreshModelFiles();
          disableNewLlama(installed);
          persist.run();
          stateLabel.setText("Готово: " + installed.getFileName()
              + ". Модель добавлена в «Основные модели» (не активирована).");
        } else {
          stateLabel.setText("Готово: " + installed.getFileName()
              + ". Выбери модель во вкладке «Голос → Основное» и обнови список.");
        }
        refreshInstalled();
      } catch (Exception ex) {
        stateLabel.setText("Файл установлен, но не удалось обновить список: " + errorText(ex));
      }
    });
    task.setOnFailed(e -> {
      Throwable failure = task.getException();
      finishTask();
      stateLabel.setText("Ошибка: " + errorText(failure));
    });
    task.setOnCancelled(e -> {
      finishTask();
      stateLabel.setText("Загрузка отменена. Незавершённый файл будет очищен.");
    });
    Thread worker = new Thread(task, "neuralsoul-model-download");
    worker.setDaemon(true);
    worker.start();
  }

  private void cancelDownload() {
    if (currentTask == null) return;
    cancellation.set(true);
    currentTask.cancel(true);
  }

  private void finishTask() {
    progressBar.progressProperty().unbind();
    stateLabel.textProperty().unbind();
    currentTask = null;
    setBusy(false);
  }

  private void setBusy(boolean busy) {
    download.setDisable(busy);
    importLocal.setDisable(busy);
    runtime.setDisable(busy);
    whisperPresets.setDisable(busy);
    url.setDisable(busy);
    fileName.setDisable(busy);
    cancel.setDisable(!busy);
  }

  /** A downloaded LLM must not become the active Main LLM merely because a file was added. */
  private void disableNewLlama(Path installed) {
    // AppState stores paths relative to model/, not bare filenames.
    String modelKey = Path.of("model").toAbsolutePath().normalize()
        .relativize(installed.toAbsolutePath().normalize())
        .toString().replace('\\', '/');
    for (ModelAssignment model : state.models) {
      if (!model.generalModel() || !modelKey.equals(model.fileName.replace('\\', '/'))) continue;
      for (Profile profile : Profile.values()) {
        ModelAssignment.ProfileConfig cfg = state.profile(profile).model(model.fileName);
        if (cfg == null) continue;
        state.profile(profile).setModel(model.fileName, new ModelAssignment.ProfileConfig(
            false, cfg.device(), cfg.ctxSize(), cfg.maxOutputTokens(), cfg.fileSearchMaxVisited(),
            cfg.parallel(), cfg.batchSize(), cfg.ubatchSize(), cfg.cacheTypeK(), cfg.cacheTypeV(),
            cfg.isolation(), cfg.roles(), cfg.placements(), cfg.loras()));
      }
      model.enabled.set(false);
      state.applyProfile(state.activeProfile.get() == null ? Profile.NORMAL : state.activeProfile.get());
      break;
    }
  }

  private void refreshInstalled() {
    RuntimeKind kind = runtime.getValue();
    if (kind == null) return;
    Path dir = Path.of(kind.directory());
    if (!Files.isDirectory(dir)) {
      available.setText("Папка " + kind.directory() + " пока пустая.");
      return;
    }
    try (var entries = Files.walk(dir)) {
      var files = entries.filter(Files::isRegularFile)
          .filter(p -> !Files.isSymbolicLink(p))
          .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(kind.extension()))
          .sorted(Comparator.comparing(p -> p.toString().toLowerCase(Locale.ROOT)))
          .toList();
      if (files.isEmpty()) {
        available.setText("Файлы моделей не обнаружены в " + kind.directory());
        return;
      }
      StringBuilder list = new StringBuilder();
      for (Path path : files) {
        if (list.length() > 0) list.append('\n');
        list.append("• ").append(dir.relativize(path).toString().replace('\\', '/'))
            .append(" · ").append(formatBytes(Files.size(path)));
      }
      available.setText(list.toString());
    } catch (IOException e) {
      available.setText("Не удалось прочитать каталог: " + errorText(e));
    }
  }

  private static String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " Б";
    if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f КБ", bytes / 1024d);
    if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f МБ", bytes / 1048576d);
    return String.format(Locale.ROOT, "%.2f ГБ", bytes / 1073741824d);
  }

  private static String errorText(Throwable error) {
    if (error == null) return "неизвестная ошибка";
    return error.getMessage() == null || error.getMessage().isBlank()
        ? error.getClass().getSimpleName() : error.getMessage();
  }
}
