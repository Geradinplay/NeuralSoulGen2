package com.neuralsoul.ui.components;

import javafx.geometry.*;
import com.neuralsoul.runtime.ModelLaunch;
import com.neuralsoul.ui.styles.UiStyles;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.*;

/** NeuralSoul styled model/runtime startup window. All methods are called on the FX thread. */
public final class StartupDialog {
  private final Stage dialog = new Stage(StageStyle.TRANSPARENT);
  private final VBox root = new VBox(14);
  private final Label title = label("Загрузка модели", 25, "#f6f4fb");
  private final Label model = label("Подготовка компонентов", 14, "#ded9eb");
  private final Label percent = label("0%", 14, "#f4f1ff");
  private final ProgressBar progress = new ProgressBar(0);
  private final TextArea log = new TextArea();
  private final Runnable retryAction;
  private final Runnable stopAction;
  private boolean failed;

  public StartupDialog(Stage owner) { this(owner, null, null); }
  public StartupDialog(Stage owner, Runnable retryAction) { this(owner, retryAction, null); }
  public StartupDialog(Stage owner, Runnable retryAction, Runnable stopAction) {
    this.retryAction = retryAction;
    this.stopAction = stopAction;
    dialog.initOwner(owner); dialog.initModality(Modality.WINDOW_MODAL); dialog.setResizable(false);
    progress.setMaxWidth(Double.MAX_VALUE); progress.setPrefHeight(9); UiStyles.accentProgress(progress);
    log.setEditable(false); log.setWrapText(false); log.setPrefRowCount(6);
    UiStyles.startupLog(log);
    log.setText("[system] waiting for runtime...\n");

    HBox header = new HBox(label("✦  NeuralSoul", 16, "#cfc9dc"), new Region());
    HBox.setHgrow(header.getChildren().get(1), Priority.ALWAYS);
    HBox top = new HBox(model, new Region(), percent); HBox.setHgrow(top.getChildren().get(1), Priority.ALWAYS);
    Button emergencyStop = new Button("■  Остановить запуск");
    UiStyles.dangerButton(emergencyStop);
    emergencyStop.setDisable(stopAction == null);
    emergencyStop.setOnAction(e -> {
      emergencyStop.setDisable(true);
      emergencyStop.setText("Остановка…");
      append("[system] emergency startup stop requested");
      if (stopAction != null) stopAction.run();
    });
    HBox stopRow = new HBox(new Region(), emergencyStop); HBox.setHgrow(stopRow.getChildren().get(0), Priority.ALWAYS);
    // The runtime list is dynamic and belongs in the log. No fixed Qdrant/Main/TTS
    // rows: the startup plan is the single source of truth for what actually starts.
    VBox body = new VBox(12, title, top, progress, log, stopRow);
    root.getChildren().addAll(header, body);
    root.setPadding(new Insets(20, 24, 22, 24));
    UiStyles.startupPanel(root, false); root.setEffect(new DropShadow(28, Color.rgb(35,20,70,.35)));
    Scene scene = new Scene(root, 500, 430); scene.setFill(Color.TRANSPARENT); dialog.setScene(scene);
    dialog.setOnCloseRequest(e -> { if (!failed) e.consume(); });
  }

  private static Label label(String s, int size, String color) { Label l=new Label(s); UiStyles.label(l,size,color); return l; }
  private void append(String s) { log.appendText(s + "\n"); log.positionCaret(log.getLength()); }
  private void set(double p, String name) { progress.setProgress(p); percent.setText((int)Math.round(p*100)+"%"); if(name!=null) model.setText(name); }

  public void show() { dialog.show(); }
  public void close() { dialog.close(); }
  public void planReady(java.util.List<ModelLaunch<?, ?>> launches) {
    append("[startup] plan contains " + launches.size() + " runtime(s)");
    for (ModelLaunch<?, ?> launch : launches) {
      String path = launch.modelPath() == null ? "" : " · " + launch.modelPath();
      append("[plan] " + launch.runtimeKey() + " · " + launch.displayName() + path);
    }
  }
  public void modelStarting(ModelLaunch<?, ?> launch, int index, int total) {
    double p = total == 0 ? 0 : (double) (index - 1) / total;
    set(p, launch.displayName());
    String path = launch.modelPath() == null ? "" : " · " + launch.modelPath();
    append("[" + launch.runtimeKey().name().toLowerCase() + "] starting "
        + launch.displayName() + path);
  }
  public void modelReady(ModelLaunch<?, ?> launch, int index, int total) {
    set(total == 0 ? 1 : (double) index / total, launch.displayName());
    append("[" + launch.runtimeKey().name().toLowerCase() + "] ready · " + launch.displayName());
  }
  public void modelFailed(ModelLaunch<?, ?> launch, int index, int total, Throwable error) {
    double p = total == 0 ? 0 : (double) index / total;
    set(p, launch.displayName());
    append("[" + launch.runtimeKey().name().toLowerCase() + "] "
        + (launch.optional() ? "optional failure" : "FAILED") + " · "
        + (error.getMessage() == null ? error.toString() : error.getMessage()));
  }
  public void successAndClose() { set(1,"Система готова"); append("[system] all components are ready"); dialog.close(); }
  public void fail(String stage, Throwable t) {
    failed=true; UiStyles.startupPanel(root, true); root.getChildren().clear();
    String msg=t==null?"Неизвестная ошибка":(t.getMessage()==null?t.toString():t.getMessage());
    Label brand=label("✦  NeuralSoul",16,"#d9cbd0"); Label errTitle=label("⚠   Ошибка запуска",24,"#fff3f3");
    Label sub=label("Не удалось завершить этап: " + stage,14,"#d8bdc2"); sub.setWrapText(true);
    TextArea details=new TextArea("[error] " + msg); details.setEditable(false); details.setWrapText(true); details.setPrefRowCount(5);
    UiStyles.errorDetails(details);
    Button logs=new Button("▣  Открыть логи"); Button close=new Button("Закрыть"); Button retry=new Button("↻  Повторить");
    logs.setOnAction(e->{ details.setPrefRowCount(details.getPrefRowCount()==5?11:5); dialog.sizeToScene(); });
    close.setOnAction(e -> dialog.close());
    retry.setDisable(retryAction==null); retry.setOnAction(e->{ dialog.close(); if(retryAction!=null) retryAction.run(); });
    UiStyles.secondaryDialogButton(logs, "#3b2932");
    UiStyles.secondaryDialogButton(close, "#55495e");
    UiStyles.retryButton(retry);
    HBox buttons=new HBox(12,logs,new Region(),close,retry); HBox.setHgrow(buttons.getChildren().get(1),Priority.ALWAYS);
    root.getChildren().addAll(brand,new Region(),errTitle,sub,details,buttons); root.setPadding(new Insets(20,26,24,26));
    dialog.getScene().getWindow().sizeToScene();
  }
}
