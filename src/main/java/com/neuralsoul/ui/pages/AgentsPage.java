package com.neuralsoul.ui.pages;
import com.neuralsoul.model.*;
import com.neuralsoul.config.AgentPrompts;
import com.neuralsoul.ui.components.Ui;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

public final class AgentsPage extends VBox {
 private record AgentEntry(String label, javafx.beans.property.StringProperty prompt) {
   @Override public String toString() { return label; }
 }
 public AgentsPage(AppState state, Runnable save) {
  super(14); setPadding(new Insets(24));
  Label info = new Label("Общие настройки агентов для всех профилей. Остановите систему для редактирования. Изменения применятся при следующем запуске.");
  info.setWrapText(true);
  ComboBox<AgentEntry> roles = new ComboBox<>();
  for (ModelRole role : AgentPrompts.roles()) {
    if (role != ModelRole.GENERAL) roles.getItems().add(new AgentEntry(role.title(), state.agentPrompt(role)));
  }
  for (ModelAssignment model : state.models) {
    if (!model.generalModel()) continue;
    boolean general = false;
    for (Profile profile : Profile.values()) {
      if (state.modelProfileConfig(profile, model).roles().contains(ModelRole.GENERAL)) general = true;
    }
    if (general) roles.getItems().add(new AgentEntry("General · " + model.name + " [" + model.fileName + "]", state.generalAgentSystemPrompt(model.fileName)));
  }
  roles.setMaxWidth(Double.MAX_VALUE);
  TextArea editor = new TextArea(); editor.setWrapText(true); editor.setPrefRowCount(20);
  editor.disableProperty().bind(state.systemRunning.or(state.systemStarting));
  roles.valueProperty().addListener((o, old, selected) -> {
   if (old != null) editor.textProperty().unbindBidirectional(old.prompt());
   if (selected != null) editor.textProperty().bindBidirectional(selected.prompt());
  });
  roles.getSelectionModel().selectFirst();
  Label tools = new Label("Web Search LLM формирует интернет-запросы. File Tools — встроенные Java-инструменты glob/grep/read/edit/patch, отдельная модель для них не нужна. Основная или Coding-модель выбирает операцию, а режим подтверждения задаётся на странице файлов. Каждая General-модель имеет собственный промпт.");
  tools.setWrapText(true);
  Button saveButton = new Button("Сохранить"); saveButton.setOnAction(e -> save.run());
  saveButton.disableProperty().bind(state.systemRunning.or(state.systemStarting));
  getChildren().addAll(Ui.label("Агенты", "page-title"), info, roles, editor, tools, saveButton);
 }
}
