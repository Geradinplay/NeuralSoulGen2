package com.neuralsoul.ui.pages;

import com.neuralsoul.model.AppState;
import com.neuralsoul.ui.components.ModelArchitectureGraph;
import com.neuralsoul.ui.components.Ui;
import javafx.geometry.Insets;
import javafx.scene.layout.VBox;

/** Page showing only the automatically built model architecture graph. */
public final class SchemaPage extends VBox {
  public SchemaPage(AppState state) {
    super(14);
    setPadding(new Insets(18));
    getChildren()
        .addAll(
            Ui.label("Схема", "page-title"),
            Ui.label(
                "Архитектура строится автоматически из настроек активного профиля во вкладке «Модели».",
                "muted"),
            new ModelArchitectureGraph(state));
  }
}
