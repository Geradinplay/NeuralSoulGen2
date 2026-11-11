package com.neuralsoul.ui.pages;

import com.neuralsoul.model.*;
import com.neuralsoul.ui.components.*;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;

public final class ModelsPage extends VBox {
  public ModelsPage(AppState state, Runnable settings) {
    this(state, settings, () -> {});
  }

  public ModelsPage(AppState state, Runnable settings, Runnable refresh) {
    super(16);
    setPadding(new Insets(24));
    getChildren()
        .addAll(
            Ui.label("Модели LLM", "page-title"),
            Ui.label("Список с подключённого сервера /v1/models", "muted"),
            Ui.row(
                Ui.button("Обновить с сервера", "refresh", refresh),
                Ui.button("Настройки API", "settings", settings)));
    if (state.availableModels.isEmpty()) {
      Label hint =
          Ui.label(
              "Нажми «Обновить с сервера». Model ID можно также ввести вручную в поле выбора модели"
                  + " чата.",
              "muted");
      hint.setWrapText(true);
      getChildren().add(hint);
    }
    for (String name : state.availableModels) {
      Button select =
          Ui.button(
              "Использовать",
              "chat",
              () -> {
                state.selectedModel.set(name);
                state.status.set("Выбрана модель: " + name);
              });
      Label label = Ui.label(name, "card-title");
      label.setWrapText(true);
      HBox.setHgrow(label, Priority.ALWAYS);
      getChildren().add(Ui.card("", Ui.row(Icons.of("spark"), label, Ui.spacer(), select)));
    }
  }
}
