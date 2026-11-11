package com.neuralsoul.ui.components;

import javafx.scene.Node;
import javafx.scene.layout.*;

/** Reflows existing nodes without rebuilding controls or losing their state. */
public final class ResponsiveColumns extends GridPane {
  private final int columns;
  private final double breakpoint;
  private int currentColumns = -1;

  public ResponsiveColumns(int columns, double breakpoint, Node... children) {
    this.columns = columns;
    this.breakpoint = breakpoint;
    setHgap(12);
    setVgap(12);
    setMinWidth(0);
    getChildren().addAll(children);
    for (Node child : children) {
      setHgrow(child, Priority.ALWAYS);
      if (child instanceof Region r) r.setMinWidth(0);
    }
    widthProperty().addListener((o, old, value) -> reflow(value.doubleValue()));
    reflow(0);
  }

  private void reflow(double width) {
    int count = width < breakpoint ? 1 : columns;
    if (count == currentColumns) return;
    currentColumns = count;
    getColumnConstraints().clear();
    for (int i = 0; i < count; i++) {
      ColumnConstraints c = new ColumnConstraints();
      c.setPercentWidth(100.0 / count);
      c.setMinWidth(0);
      getColumnConstraints().add(c);
    }
    for (int i = 0; i < getChildren().size(); i++) {
      Node n = getChildren().get(i);
      setColumnIndex(n, i % count);
      setRowIndex(n, i / count);
    }
  }
}
