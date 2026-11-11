package com.neuralsoul.ui.components;

import java.util.Map;
import javafx.scene.Group;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

public final class Icons {
  private Icons() {}

  private static final Map<String, String> PATHS =
      Map.ofEntries(
          Map.entry("home", "M3 10 L12 3 L21 10 M5 9 V21 H10 V15 H14 V21 H19 V9"),
          Map.entry(
              "chat",
              "M5 4 H19 Q21 4 21 6 V16 Q21 18 19 18 H10 L5 22 V18 H4 Q2 18 2 16 V6 Q2 4 5 4"),
          Map.entry(
              "users",
              "M9 3 A3 3 0 1 1 8.9 3 M2 21 V17 Q2 12 8 12 Q14 12 14 17 V21 Z M16 4 Q22 4 21 9 Q20"
                  + " 12 17 12 M17 14 Q23 14 23 20"),
          Map.entry(
              "tasks", "M7 5 H4 V22 H20 V5 H17 M8 2 H16 V7 H8 Z M8 12 L10 14 L16 10 M8 18 H16"),
          Map.entry("memory", "M4 3 H20 V21 H4 Z M8 7 H16 M8 11 H16 M8 15 H12"),
          Map.entry("folder", "M2 5 H9 L12 8 H22 V21 H2 Z"),
          Map.entry("globe", "M12 2 A10 10 0 1 1 11.9 2 M2 12 H22 M12 2 Q4 12 12 22 Q20 12 12 2"),
          Map.entry("monitor", "M2 3 H22 V17 H2 Z M12 17 V22 M7 22 H17"),
          Map.entry("voice", "M3 9 H7 L12 4 V20 L7 15 H3 Z M16 8 Q20 12 16 16 M19 4 Q27 12 19 20"),
          Map.entry(
              "settings",
              "M12 2 L15 6 L20 5 L22 10 L18 13 L19 18 L14 21 L10 18 L5 20 L2 15 L5 11 L4 6 L9 5 Z"
                  + " M12 8 A4 4 0 1 1 11.9 8"),
          Map.entry("spark", "M12 2 L15 9 L22 12 L15 15 L12 22 L9 15 L2 12 L9 9 Z"),
          Map.entry(
              "mic",
              "M8 5 A4 4 0 0 1 16 5 V12 A4 4 0 0 1 8 12 Z M4 10 V12 A8 8 0 0 0 20 12 V10 M12 20"
                  + " V24"),
          Map.entry("send", "M3 3 L22 12 L3 21 L8 12 Z M8 12 H21"),
          Map.entry("file", "M5 2 H14 L20 8 V22 H5 Z M14 2 V8 H20 M8 13 H17 M8 17 H15"),
          Map.entry(
              "chip",
              "M5 5 H19 V19 H5 Z M9 9 H15 V15 H9 Z M8 1 V5 M16 1 V5 M8 19 V23 M16 19 V23 M1 8 H5 M1"
                  + " 16 H5 M19 8 H23 M19 16 H23"),
          Map.entry("layers", "M12 2 L23 8 L12 14 L1 8 Z M1 13 L12 19 L23 13 M1 18 L12 24 L23 18"),
          Map.entry("code", "M8 5 L1 12 L8 19 M16 5 L23 12 L16 19 M14 2 L10 22"),
          Map.entry(
              "brain",
              "M12 3 Q5 0 5 6 Q0 6 3 12 Q0 18 6 18 Q6 24 12 21 Q18 24 18 18 Q24 18 21 12 Q24 6 19 6"
                  + " Q19 0 12 3 V21"),
          Map.entry("attach", "M8 15 L17 6 Q23 2 23 8 L10 21 Q3 26 1 19 Q0 16 4 12 L14 2"),
          Map.entry(
              "refresh",
              "M21 8 A9 9 0 0 0 4 6 L1 9 M1 3 V9 H7 M3 16 A9 9 0 0 0 20 18 L23 15 M17 15 H23 V21"),
          Map.entry("trash", "M4 6 H20 M9 6 V3 H15 V6 M6 6 L7 22 H17 L18 6 M10 10 V18 M14 10 V18"));

  public static StackPane of(String name) {
    SVGPath p = new SVGPath();
    p.setContent(PATHS.getOrDefault(name, PATHS.get("spark")));
    p.setFill(Color.TRANSPARENT);
    p.setStroke(Color.web("#bec9e0"));
    p.setStrokeWidth(1.6);
    p.setScaleX(.72);
    p.setScaleY(.72);
    StackPane pane = new StackPane(new Group(p));
    pane.setMinSize(22, 22);
    pane.setPrefSize(22, 22);
    pane.setMaxSize(22, 22);
    pane.setMouseTransparent(true);
    return pane;
  }
}
