package com.neuralsoul.ui.components;

import javafx.geometry.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;

/** Uses a viewport of the supplied reference. Replace with a portrait asset in production. */
public final class AvatarPane extends StackPane {
  public AvatarPane() {
    Image image =
        new Image(AvatarPane.class.getResource("/com/neuralsoul/concept.png").toExternalForm());
    ImageView portrait = new ImageView(image);
    portrait.setViewport(new Rectangle2D(147, 58, 332, 455));
    portrait.setPreserveRatio(true);
    portrait
        .fitWidthProperty()
        .bind(
            javafx.beans.binding.Bindings.max(
                widthProperty(), heightProperty().multiply(332.0 / 455.0)));
    Region shade = new Region();
    shade.getStyleClass().add("portrait-shade");
    VBox text =
        new VBox(
            6,
            Ui.label("Nina", "portrait-name"),
            Ui.label("Твой локальный AI-компаньон", "portrait-subtitle"),
            Ui.label("Готова помочь! Чем займёмся сегодня?", "portrait-subtitle"));
    text.setPadding(new Insets(20));
    text.setMaxHeight(110);
    StackPane.setAlignment(text, Pos.BOTTOM_LEFT);
    getChildren().addAll(portrait, shade, text);
    setMinSize(220, 310);
    setPrefHeight(420);
    setPrefWidth(360);
    getStyleClass().add("portrait");
    Rectangle clip = new Rectangle();
    clip.widthProperty().bind(widthProperty());
    clip.heightProperty().bind(heightProperty());
    clip.setArcWidth(18);
    clip.setArcHeight(18);
    setClip(clip);
  }
}
