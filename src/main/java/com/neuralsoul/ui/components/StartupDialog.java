package com.neuralsoul.ui.components;

import javafx.geometry.*;
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
  private final Label qdrant = step("○  Qdrant Memory Runtime", false);
  private final Label memory = step("○  Memory Search Model", false);
  private final Label main = step("○  Main LLM", false);
  private final Label tts = step("○  Fish Speech S2-Pro", false);
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
    VBox body = new VBox(12, title, top, progress, qdrant, memory, main, tts, log, stopRow);
    root.getChildren().addAll(header, body);
    root.setPadding(new Insets(20, 24, 22, 24));
    UiStyles.startupPanel(root, false); root.setEffect(new DropShadow(28, Color.rgb(35,20,70,.35)));
    Scene scene = new Scene(root, 500, 545); scene.setFill(Color.TRANSPARENT); dialog.setScene(scene);
    dialog.setOnCloseRequest(e -> { if (!failed) e.consume(); });
  }

  private static Label label(String s, int size, String color) { Label l=new Label(s); UiStyles.label(l,size,color); return l; }
  private static Label step(String s, boolean ok) { Label l=label(s,14,ok?"#e8e3f3":"#aaa5b7"); l.setPadding(new Insets(4,0,4,4)); return l; }
  private void append(String s) { log.appendText(s + "\n"); log.positionCaret(log.getLength()); }
  private void set(double p, String name) { progress.setProgress(p); percent.setText((int)Math.round(p*100)+"%"); if(name!=null) model.setText(name); }

  public void show() { dialog.show(); }
  public void close() { dialog.close(); }
  public void qdrantLoading() { set(.08,"Qdrant Memory Runtime"); qdrant.setText("◉  Qdrant Memory Runtime     Запуск..."); append("[qdrant] starting local memory runtime..."); }
  public void qdrantReady() { set(.18,null); qdrant.setText("✓  Qdrant Memory Runtime     Готово"); UiStyles.ready(qdrant); append("[qdrant] ready"); }
  public void memoryDisabled() { set(.34,"Memory Search отключён"); memory.setText("–  Memory Search Model       Отключён"); append("[memory] semantic model disabled"); }
  public void memoryLoading(String name) { set(.28,"Memory Search Model ("+name+")"); memory.setText("◉  Memory Search Model       Загрузка..."); append("[memory] loading " + name); }
  public void memoryReady() { set(.42,null); memory.setText("✓  Memory Search Model       Готово"); UiStyles.ready(memory); append("[memory] model ready"); }
  public void mainLoading(String name, int usedMb, int ignored) {
    double p = progress.getProgress(); if (p < .48) p=.48; else p=Math.min(.94,p+.015); set(p,name);
    main.setText("◉  Main LLM                  Загрузка...");
    if (usedMb > 0) append("[llama] loading tensors... process memory: " + usedMb + " MB");
    else append("[llama] initializing llama.cpp for " + name);
  }
  public void mainReady(String name) { set(.96,name); main.setText("✓  Main LLM                  Готово"); UiStyles.ready(main); append("[llama] API server ready"); }
  public void ttsLoading() {
    set(.97,"Fish Speech S2-Pro");
    tts.setText("◉  Fish Speech S2-Pro          Загрузка...");
    append("[tts] starting persistent Fish Speech runtime...");
  }
  public void ttsReady() {
    set(.99,null);
    tts.setText("✓  Fish Speech S2-Pro          Готово");
    UiStyles.ready(tts);
    append("[tts] model ready — persistent server");
  }
  public void ttsDisabled() {
    tts.setText("–  Fish Speech S2-Pro          Отключён");
    append("[tts] disabled by configuration");
  }
  public void ttsFailed(String message) {
    tts.setText("⚠  Fish Speech S2-Pro          Ошибка запуска");
    append("[tts] startup failed: " + message);
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
