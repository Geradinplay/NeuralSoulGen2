package com.neuralsoul;

import com.neuralsoul.bootstrap.AppContext;
import com.neuralsoul.bootstrap.ApplicationBootstrap;
import javafx.application.Application;
import javafx.application.Platform;
import com.neuralsoul.ui.components.AppSplashScreen;
import java.util.concurrent.CompletableFuture;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

public final class NeuralSoulApp extends Application {
  private AppController controller;

  @Override
  public void start(Stage stage) {
    AppSplashScreen splash = new AppSplashScreen();
    splash.show();
    CompletableFuture.supplyAsync(ApplicationBootstrap::initialize)
        .whenComplete((context, error) -> Platform.runLater(() -> {
          if (error != null) {
            splash.close();
            Throwable cause = error.getCause() == null ? error : error.getCause();
            showStartupError(cause instanceof Exception ex ? ex : new RuntimeException(cause));
            return;
          }
          try {
            controller = new AppController(stage, context.state(), context.backend(), context.settings(), context.services());
            splash.finish(() -> controller.show());
          } catch (Exception e) {
            splash.close();
            showStartupError(e);
          }
        }));
  }

  private void showStartupError(Exception e) {
    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    new Alert(Alert.AlertType.ERROR, "Ошибка запуска NeuralSoul: " + message).showAndWait();
    Platform.exit();
  }

  @Override
  public void stop() {
    if (controller != null) controller.close();
  }
}
