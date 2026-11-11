package com.neuralsoul.ui.components;

import javafx.animation.*;
import com.neuralsoul.ui.styles.UiStyles;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.effect.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import javafx.scene.text.*;
import javafx.stage.*;
import javafx.util.Duration;

/** Lightweight splash shown while the Java side of NeuralSoul is bootstrapping. */
public final class AppSplashScreen {
  private final Stage stage = new Stage(StageStyle.TRANSPARENT);
  private final ProgressBar progress = new ProgressBar(.08);
  private final Label status = new Label("Initializing...");
  private final Label percent = new Label("8%");
  private final Timeline fakeProgress;
  private double value = .08;

  public AppSplashScreen() {
    StackPane root = new StackPane();
    root.setPrefSize(440, 480);
    UiStyles.splashPanel(root);
    root.setEffect(new DropShadow(35, Color.rgb(77, 48, 150, .34)));

    VBox content = new VBox(12);
    content.setAlignment(Pos.CENTER);
    content.setPadding(new Insets(42, 34, 28, 34));

    StackPane orb = createOrb();
    Label title = new Label("NeuralSoul");
    UiStyles.splashTitle(title);
    Label sub = new Label("Personal AI System");
    UiStyles.splashSubtitle(sub);

    Region spacer = new Region();
    VBox.setVgrow(spacer, Priority.ALWAYS);
    progress.setMaxWidth(Double.MAX_VALUE);
    progress.setPrefHeight(8);
    UiStyles.accentProgress(progress);
    HBox line = new HBox(status, new Region(), percent);
    HBox.setHgrow(line.getChildren().get(1), Priority.ALWAYS);
    UiStyles.splashStatus(status);
    UiStyles.splashPercent(percent);

    content.getChildren().addAll(orb, title, sub, spacer, progress, line);
    root.getChildren().add(content);

    Scene scene = new Scene(root);
    scene.setFill(Color.TRANSPARENT);
    stage.setScene(scene);
    stage.setAlwaysOnTop(true);
    stage.centerOnScreen();

    fakeProgress = new Timeline(new KeyFrame(Duration.millis(180), e -> {
      if (value < .82) {
        value += Math.max(.006, (.84 - value) * .045);
        setProgress(value, value < .28 ? "Чтение конфигурации..." : value < .55 ? "Определение оборудования..." : "Подготовка интерфейса...");
      }
    }));
    fakeProgress.setCycleCount(Animation.INDEFINITE);
  }

  private StackPane createOrb() {
    StackPane p = new StackPane();
    p.setPrefSize(245, 220);
    Circle glow = new Circle(74, Color.rgb(119, 76, 210, .18));
    glow.setEffect(new GaussianBlur(22));
    Circle core = new Circle(66, Color.rgb(88, 74, 150, .34));
    core.setStroke(Color.rgb(203, 183, 255, .85));
    core.setStrokeWidth(1.3);
    Ellipse e1 = orbit(112, 42, 28);
    Ellipse e2 = orbit(105, 38, -31);
    Ellipse e3 = orbit(98, 34, 78);
    Circle star = new Circle(3.5, Color.web("#cba8ff"));
    star.setTranslateX(93); star.setTranslateY(-37);
    p.getChildren().addAll(glow, core, e1, e2, e3, star);
    RotateTransition spin = new RotateTransition(Duration.seconds(8), e1);
    spin.setByAngle(360); spin.setCycleCount(Animation.INDEFINITE); spin.setInterpolator(Interpolator.LINEAR); spin.play();
    RotateTransition spin2 = new RotateTransition(Duration.seconds(11), e2);
    spin2.setByAngle(-360); spin2.setCycleCount(Animation.INDEFINITE); spin2.setInterpolator(Interpolator.LINEAR); spin2.play();
    return p;
  }

  private Ellipse orbit(double rx, double ry, double angle) {
    Ellipse e = new Ellipse(rx, ry);
    e.setFill(Color.TRANSPARENT);
    e.setStroke(Color.rgb(179, 137, 255, .85));
    e.setStrokeWidth(1.1);
    e.setRotate(angle);
    return e;
  }

  public void show() { stage.show(); fakeProgress.play(); }
  public void setProgress(double v, String text) {
    value = Math.max(value, Math.min(.99, v));
    progress.setProgress(value); status.setText(text); percent.setText((int)Math.round(value * 100) + "%");
  }
  public void finish(Runnable after) {
    fakeProgress.stop();
    Timeline t = new Timeline(new KeyFrame(Duration.millis(220), e -> setProgress(1, "Готово")),
        new KeyFrame(Duration.millis(500), e -> { stage.close(); if (after != null) after.run(); }));
    t.play();
  }
  public void close() { fakeProgress.stop(); stage.close(); }
}
