package com.neuralsoul.ui.components;

import com.neuralsoul.model.AppState;
import com.neuralsoul.ui.styles.UiStyles;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;

public final class Composer extends VBox {
  public final TextField input = new TextField();

  public Composer(AppState state, Consumer<String> send, Runnable attach, Runnable newChat) {
    this(state, send, attach, newChat, () -> {}, () -> {}, () -> {});
  }

  public Composer(
      AppState state,
      Consumer<String> send,
      Runnable attach,
      Runnable newChat,
      Runnable stop,
      Runnable microphone,
      Runnable clearContext) {
    super(9);
    setMinWidth(0);
    getStyleClass().add("composer");
    // Keep the composer compact near the bottom edge.
    // Explicit padding also prevents the theme from leaving an oversized bottom gap.
    setPadding(new Insets(10, 12, 5, 12));
    Button fresh = Ui.link("Новый чат", newChat);
    fresh.setGraphic(Icons.of("chat"));
    Button clip = Ui.button("", "attach", attach);
    clip.setTooltip(new Tooltip("Прикрепить файл"));
    ToggleButton voice = Ui.toggle(state.voice);
    voice.setTooltip(new Tooltip("Озвучивать ответы через TTS"));
    Label voiceText = Ui.label("Голос", "muted");
    ToggleButton listen = Ui.toggle(state.alwaysListen);
    listen.setTooltip(new Tooltip("Непрерывный голосовой ввод. Поведение во время генерации задаётся во вкладке «Голос»."));
    Label listenText = Ui.label("Слушать", "muted");
    Button submit = Ui.button("", "send", () -> submit(send));
    submit.setId("send-message");
    submit.getStyleClass().add("send-button");
    submit
        .disableProperty()
        .bind(
            state.busy
                .or(state.recording)
                .or(state.systemRunning.not())
                .or(input.textProperty().isEmpty()));
    submit.setAccessibleText("Отправить сообщение");
    Button mic = Ui.button("", "mic", microphone);
    mic.setId("microphone");
    mic.setTooltip(new Tooltip("Начать запись / закончить и отправить"));
    // Voice modes decide what a microphone press does while Main LLM is busy:
    // interrupt immediately or record into the voice buffer.
    mic.disableProperty().bind(state.systemRunning.not());
    mic.setAccessibleText("Микрофон");
    state.recording.addListener(
        (o, old, recording) -> {
          UiStyles.microphone(mic, recording);
        });
    Button cancel = Ui.button("Стоп", "tasks", stop);
    cancel.setId("stop-generation");
    getChildren().add(Ui.row(fresh, Ui.spacer(), clip, mic, listenText, listen, voiceText, voice, cancel, submit));
    ToggleGroup modes = new ToggleGroup();
    FlowPane modeRow = new FlowPane(7, 7);
    for (String name : new String[] {"Обычный", "Глубокий анализ", "Разработка"}) {
      ToggleButton b = new ToggleButton(name);
      b.setToggleGroup(modes);
      b.setSelected(name.equals(state.mode.get()));
      b.setOnAction(
          e -> {
            if (b.isSelected()) state.mode.set(name);
            else b.setSelected(true);
          });
      modeRow.getChildren().add(b);
    }
    Button clearContextButton = Ui.button("Clear context", "tasks", clearContext);
    clearContextButton.setId("clear-context");
    clearContextButton.setTooltip(new Tooltip("Сохранить текущий conversation context в память и очистить контекст LLM"));
    clearContextButton.disableProperty().bind(state.busy.or(state.recording));
    modeRow.getChildren().add(clearContextButton);
    modeRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
    getChildren().add(modeRow);
    input.setId("message-input");
    input.setOnAction(e -> submit(send));
    input.disableProperty().bind(state.busy.or(state.recording).or(state.systemRunning.not()));
    input.promptTextProperty().bind(
        javafx.beans.binding.Bindings.when(state.systemRunning)
            .then("Напишите сообщение…")
            .otherwise("Сначала запустите систему"));
    getChildren().add(input);
  }

  private void submit(Consumer<String> send) {
    String message = input.getText().trim();
    if (!message.isEmpty()) {
      input.clear();
      send.accept(message);
    }
  }
}
