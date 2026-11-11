package com.neuralsoul.ui.components;

import com.neuralsoul.model.Profile;
import com.neuralsoul.model.AppState;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

/** Top application header. Keeps header composition out of AppController. */
public final class AppHeader extends HBox {
  private final Label serviceLabel;

  public AppHeader(boolean testMode, AppState state, Button systemButton) {
    Label brand = Ui.label("NeuralSoul", "brand");
    serviceLabel = Ui.label(testMode ? "LOCAL AI / TEST" : "LOCAL AI / SERVICES", "eyebrow");

    ComboBox<Profile> profile = new ComboBox<>();
    profile.getItems().setAll(Profile.values());
    profile.setValue(state.activeProfile.get());
    profile.setPromptText("Профиль");
    profile.setTooltip(new Tooltip("Активный профиль NeuralSoul"));
    profile.setPrefWidth(125);
    profile.valueProperty().addListener((o, a, b) -> {
      if (b != null && state.activeProfile.get() != b) state.activeProfile.set(b);
    });
    state.activeProfile.addListener((o, a, b) -> {
      if (b != null && profile.getValue() != b) profile.setValue(b);
    });

    getChildren().addAll(Icons.of("spark"), brand, Ui.spacer(), serviceLabel, profile, systemButton);
    setSpacing(8);
    setAlignment(javafx.geometry.Pos.CENTER_LEFT);
    setPadding(new Insets(15, 22, 15, 20));
    getStyleClass().add("app-header");
  }

  public void setCompact(boolean compact) {
    serviceLabel.setVisible(!compact);
    serviceLabel.setManaged(!compact);
  }
}
