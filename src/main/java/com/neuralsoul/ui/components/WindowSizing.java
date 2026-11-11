package com.neuralsoul.ui.components;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

/** All values are JavaFX logical pixels: do not multiply by the Windows DPI scale. */
public final class WindowSizing {
  private static final double MARGIN = 12;

  private WindowSizing() {}

  public static void install(
      Stage stage, Window owner, double preferredWidth, double preferredHeight) {
    Rectangle2D area = screenFor(owner).getVisualBounds();
    applyLimits(stage, area);
    place(stage, fit(area, preferredWidth, preferredHeight, Double.NaN, Double.NaN));
    stage.addEventHandler(
        WindowEvent.WINDOW_SHOWN, e -> Platform.runLater(() -> fitToScreen(stage)));
    ListChangeListener<Screen> screensChanged = c -> Platform.runLater(() -> fitToScreen(stage));
    stage.addEventHandler(
        WindowEvent.WINDOW_SHOWING, e -> Screen.getScreens().addListener(screensChanged));
    stage.addEventHandler(
        WindowEvent.WINDOW_HIDDEN, e -> Screen.getScreens().removeListener(screensChanged));
    // Update limits when moving between monitors without fighting normal window dragging.
    stage
        .xProperty()
        .addListener((o, old, value) -> applyLimits(stage, screenFor(stage).getVisualBounds()));
    stage
        .yProperty()
        .addListener((o, old, value) -> applyLimits(stage, screenFor(stage).getVisualBounds()));
  }

  public static void fitToScreen(Stage stage) {
    if (stage.isFullScreen() || stage.isMaximized()) return;
    Rectangle2D area = screenFor(stage).getVisualBounds();
    applyLimits(stage, area);
    place(stage, fit(area, stage.getWidth(), stage.getHeight(), stage.getX(), stage.getY()));
  }

  /** Includes native window borders/title bar, unlike Scene width/height. */
  public static Rectangle2D fit(Rectangle2D area, double width, double height, double x, double y) {
    double margin = Math.min(MARGIN, Math.min(area.getWidth(), area.getHeight()) / 10);
    double w = Math.min(Math.max(1, width), area.getWidth() - margin * 2);
    double h = Math.min(Math.max(1, height), area.getHeight() - margin * 2);
    double left = area.getMinX() + margin, top = area.getMinY() + margin;
    double px =
        Double.isFinite(x)
            ? Math.max(left, Math.min(x, area.getMaxX() - margin - w))
            : area.getMinX() + (area.getWidth() - w) / 2;
    double py =
        Double.isFinite(y)
            ? Math.max(top, Math.min(y, area.getMaxY() - margin - h))
            : area.getMinY() + (area.getHeight() - h) / 2;
    return new Rectangle2D(px, py, w, h);
  }

  private static void applyLimits(Stage stage, Rectangle2D area) {
    // Allow maximizing to the full work area, but start with an inset for the window shadow.
    stage.setMinWidth(Math.min(640, Math.max(1, area.getWidth() - MARGIN * 2)));
    stage.setMinHeight(Math.min(440, Math.max(1, area.getHeight() - MARGIN * 2)));
    stage.setMaxWidth(area.getWidth());
    stage.setMaxHeight(area.getHeight());
  }

  private static void place(Stage stage, Rectangle2D bounds) {
    stage.setWidth(bounds.getWidth());
    stage.setHeight(bounds.getHeight());
    stage.setX(bounds.getMinX());
    stage.setY(bounds.getMinY());
  }

  private static Screen screenFor(Window window) {
    if (window == null || !Double.isFinite(window.getX()) || !Double.isFinite(window.getY()))
      return Screen.getPrimary();
    Screen best = Screen.getPrimary();
    double largest = 0;
    for (Screen screen : Screen.getScreens()) {
      Rectangle2D b = screen.getBounds();
      double overlap =
          Math.max(
                  0,
                  Math.min(window.getX() + window.getWidth(), b.getMaxX())
                      - Math.max(window.getX(), b.getMinX()))
              * Math.max(
                  0,
                  Math.min(window.getY() + window.getHeight(), b.getMaxY())
                      - Math.max(window.getY(), b.getMinY()));
      if (overlap > largest) {
        largest = overlap;
        best = screen;
      }
    }
    return best;
  }
}
