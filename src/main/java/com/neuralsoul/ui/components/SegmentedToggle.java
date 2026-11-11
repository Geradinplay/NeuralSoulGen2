package com.neuralsoul.ui.components;

import com.neuralsoul.ui.styles.UiStyles;
import java.util.function.Consumer;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;

/** Two-option segmented control used instead of JavaFX's default TabPane headers. */
public final class SegmentedToggle extends HBox {
  private final ToggleButton left;
  private final ToggleButton right;

  public SegmentedToggle(String leftText, String rightText, Consumer<Boolean> onRightSelected) {
    setSpacing(0);
    left = new ToggleButton(leftText);
    right = new ToggleButton(rightText);
    ToggleGroup group = new ToggleGroup();
    left.setToggleGroup(group); right.setToggleGroup(group); left.setSelected(true);
    Runnable refresh = () -> { UiStyles.segmented(left, left.isSelected(), true); UiStyles.segmented(right, right.isSelected(), false); };
    left.setOnAction(e -> { if (!left.isSelected()) left.setSelected(true); refresh.run(); onRightSelected.accept(false); });
    right.setOnAction(e -> { if (!right.isSelected()) right.setSelected(true); refresh.run(); onRightSelected.accept(true); });
    refresh.run();
    getChildren().addAll(left, right);
  }
}
