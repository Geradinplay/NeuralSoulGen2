package com.neuralsoul.ui.components;

import java.util.Arrays;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

/** Lightweight live microphone level graph. Values are normalized RMS levels in the 0..1 range. */
public final class AudioWaveformView extends Region {
  private static final int SAMPLES = 180;
  private final Canvas canvas = new Canvas();
  private final double[] levels = new double[SAMPLES];
  private int cursor;
  private boolean filled;

  public AudioWaveformView() {
    getChildren().add(canvas);
    setMinHeight(110);
    setPrefHeight(150);
    setMaxWidth(Double.MAX_VALUE);
    widthProperty().addListener((o, a, b) -> redraw());
    heightProperty().addListener((o, a, b) -> redraw());
  }

  public void pushLevel(double level) {
    levels[cursor] = Math.max(0.0, Math.min(1.0, level));
    cursor = (cursor + 1) % levels.length;
    if (cursor == 0) filled = true;
    redraw();
  }

  public void clear() {
    Arrays.fill(levels, 0.0);
    cursor = 0;
    filled = false;
    redraw();
  }

  @Override protected void layoutChildren() {
    canvas.setWidth(Math.max(1, getWidth()));
    canvas.setHeight(Math.max(1, getHeight()));
    redraw();
  }

  private void redraw() {
    double w = canvas.getWidth(), h = canvas.getHeight();
    if (w <= 1 || h <= 1) return;
    GraphicsContext g = canvas.getGraphicsContext2D();
    g.setFill(Color.web("#0f1623"));
    g.fillRoundRect(0, 0, w, h, 10, 10);
    g.setStroke(Color.web("#26354b"));
    g.setLineWidth(1);
    for (int i = 1; i < 4; i++) {
      double y = h * i / 4.0;
      g.strokeLine(0, y, w, y);
    }

    int count = filled ? levels.length : cursor;
    if (count < 2) return;
    g.setStroke(Color.web("#7d70ff"));
    g.setLineWidth(2);
    for (int i = 0; i < count; i++) {
      int index = filled ? (cursor + i) % levels.length : i;
      double x = count <= 1 ? 0 : i * (w - 1) / (count - 1.0);
      // sqrt makes quiet speech visible without changing the actual detector threshold.
      double normalized = Math.sqrt(levels[index]);
      double y = h - 8 - normalized * (h - 16);
      if (i == 0) g.beginPath();
      if (i == 0) g.moveTo(x, y); else g.lineTo(x, y);
    }
    g.stroke();
  }
}
