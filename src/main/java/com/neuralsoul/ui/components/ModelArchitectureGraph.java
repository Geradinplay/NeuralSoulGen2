package com.neuralsoul.ui.components;

import com.neuralsoul.model.*;
import com.neuralsoul.ui.styles.UiStyles;
import java.util.*;
import java.util.function.Consumer;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/** Read-only visualization of the active profile's model/agent topology. */
public final class ModelArchitectureGraph extends BorderPane {
  private final AppState state;
  private final GraphPane graph = new GraphPane();
  private final Label profileLabel = Ui.label("", "muted");
  private final ChangeListener<Profile> profileListener = (obs, oldValue, newValue) -> rebuild();

  public ModelArchitectureGraph(AppState state) {
    this.state = Objects.requireNonNull(state);
    setMinHeight(560);
    setPrefHeight(650);
    setMaxWidth(Double.MAX_VALUE);

    VBox heading = new VBox(4,
            Ui.label("График моделей и агентов", "card-title"),
            profileLabel);
    setTop(heading);
    BorderPane.setMargin(heading, new Insets(0, 0, 12, 0));

    setCenter(graph);
    UiStyles.architectureContainer(this);

    state.activeProfile.addListener(profileListener);
    for (ModelAssignment model : state.models) watchModel(model);
    state.models.addListener((javafx.collections.ListChangeListener<ModelAssignment>) change -> {
      while (change.next()) {
        if (change.wasAdded()) for (ModelAssignment model : change.getAddedSubList()) watchModel(model);
      }
      rebuild();
    });
    rebuild();
  }

  private void watchModel(ModelAssignment model) {
    model.enabled.addListener((o, a, b) -> rebuild());
    model.device.addListener((o, a, b) -> rebuild());
    model.ctxSize.addListener((o, a, b) -> rebuild());
    model.parallel.addListener((o, a, b) -> rebuild());
    model.roles.addListener((javafx.collections.SetChangeListener<ModelRole>) change -> rebuild());
    for (LoraSetting lora : model.loras) watchLora(lora);
    model.loras.addListener((javafx.collections.ListChangeListener<LoraSetting>) change -> {
      while (change.next()) {
        if (change.wasAdded()) for (LoraSetting lora : change.getAddedSubList()) watchLora(lora);
      }
      rebuild();
    });
  }

  private void watchLora(LoraSetting lora) {
    lora.enabled.addListener((o, a, b) -> rebuild());
    lora.scale.addListener((o, a, b) -> rebuild());
  }

  private void rebuild() {
    Profile profile = state.activeProfile.get();
    if (profile == null) profile = Profile.NORMAL;
    profileLabel.setText("Профиль: " + profile.label()
            + " · схема читается напрямую из объекта ProfileState");

    List<GraphNode> nodes = new ArrayList<>();
    ModelAssignment main = null;

    for (ModelAssignment model : state.models) {
      ModelAssignment.ProfileConfig cfg = state.modelProfileConfig(profile, model);
      if (!cfg.enabled()) continue;

      if (model.generalModel() && cfg.roles().contains(ModelRole.MAIN_LLM) && main == null) {
        main = model;
        nodes.add(GraphNode.main(model, cfg));
      }

      for (ModelRole role : cfg.roles()) {
        if (role == ModelRole.MAIN_LLM) continue;
        if (role == ModelRole.MEMORY) {
          nodes.add(GraphNode.service(model, role, cfg));
        } else {
          nodes.add(GraphNode.agent(model, role, cfg));
        }
      }
    }

    graph.setTopology(main, nodes);
  }

  private enum NodeKind { MAIN, AGENT, SERVICE }

  private record GraphNode(NodeKind kind, String title, ModelAssignment model, ModelRole role,
                           ModelAssignment.ProfileConfig config) {
    static GraphNode main(ModelAssignment model, ModelAssignment.ProfileConfig config) {
      return new GraphNode(NodeKind.MAIN, "Главная модель", model, ModelRole.MAIN_LLM, config);
    }
    static GraphNode agent(ModelAssignment model, ModelRole role, ModelAssignment.ProfileConfig config) {
      return new GraphNode(NodeKind.AGENT, role.title() + " Agent", model, role, config);
    }
    static GraphNode service(ModelAssignment model, ModelRole role, ModelAssignment.ProfileConfig config) {
      return new GraphNode(NodeKind.SERVICE, role.title(), model, role, config);
    }
    String kindLabel() {
      return switch (kind) {
        case MAIN -> "Главный узел";
        case AGENT -> "Агент / роль модели";
        case SERVICE -> "Сервисный узел";
      };
    }
  }

  private static final class GraphPane extends Pane {
    private static final double MIN_SCALE = 0.40;
    private static final double MAX_SCALE = 2.00;
    private static final double WORLD_MIN_WIDTH = 1320;
    private static final double WORLD_HEIGHT = 620;

    private final Pane world = new Pane();
    private final Canvas gridCanvas = new Canvas();
    private final Canvas connectionCanvas = new Canvas();
    private final List<NodeCard> cards = new ArrayList<>();
    private final VBox popup = new VBox(8);
    private final Label zoomLabel = Ui.label("100%", "tiny");
    private final HBox toolbar = new HBox(6);

    private double scale = 1.0;
    private double panX = 0;
    private double panY = 0;
    private double dragStartX;
    private double dragStartY;
    private double panStartX;
    private double panStartY;
    private boolean panning;

    GraphPane() {
      setMinHeight(500);
      setPrefHeight(560);
      setClipViewport();

      world.setManaged(false);
      world.getChildren().addAll(gridCanvas, connectionCanvas);
      getChildren().add(world);

      popup.setManaged(false);
      popup.setVisible(false);
      popup.setMaxWidth(360);
      popup.setPrefWidth(340);
      UiStyles.architecturePopup(popup);
      getChildren().add(popup);

      Button minus = miniButton("−", () -> setScaleAroundCenter(scale - 0.10));
      Button reset = miniButton("100%", () -> {
        scale = 1.0;
        panX = 0;
        panY = 0;
        applyTransform();
        hidePopup();
      });
      Button plus = miniButton("+", () -> setScaleAroundCenter(scale + 0.10));
      Button fit = miniButton("Вписать", this::fitToViewport);
      toolbar.getChildren().addAll(minus, zoomLabel, plus, reset, fit);
      toolbar.setAlignment(Pos.CENTER_LEFT);
      toolbar.setPadding(new Insets(6, 8, 6, 8));
      UiStyles.architectureToolbar(toolbar);
      toolbar.setManaged(false);
      getChildren().add(toolbar);

      addEventFilter(ScrollEvent.SCROLL, this::handleZoom);
      addEventFilter(MouseEvent.MOUSE_PRESSED, this::handlePanStart);
      addEventFilter(MouseEvent.MOUSE_DRAGGED, this::handlePanDrag);
      addEventFilter(MouseEvent.MOUSE_RELEASED, this::handlePanEnd);

      setOnMouseClicked(e -> {
        if (e.getTarget() == this || e.getTarget() == world || e.getTarget() == gridCanvas
                || e.getTarget() == connectionCanvas) {
          hidePopup();
          select(null);
        }
      });

      widthProperty().addListener((o, a, b) -> requestLayout());
      heightProperty().addListener((o, a, b) -> requestLayout());
    }

    void setTopology(ModelAssignment ignoredMainModel, List<GraphNode> nodes) {
      hidePopup();
      world.getChildren().setAll(gridCanvas, connectionCanvas);
      cards.clear();

      GraphNode main = nodes.stream().filter(n -> n.kind() == NodeKind.MAIN).findFirst().orElse(null);
      if (main == null) {
        Label empty = Ui.label("Main LLM не выбрана", "muted");
        StackPane holder = new StackPane(empty);
        holder.setManaged(false);
        holder.resizeRelocate(40, 70, 280, 90);
        world.getChildren().add(holder);
        requestLayout();
        return;
      }

      for (GraphNode node : nodes) {
        NodeCard card = new NodeCard(node);
        card.setManaged(false);
        card.setOnMouseClicked(e -> {
          select(card);
          showPopup(card);
          e.consume();
        });
        cards.add(card);
        world.getChildren().add(card);
      }
      requestLayout();
    }

    private static Button miniButton(String text, Runnable action) {
      Button button = new Button(text);
      button.setOnAction(e -> action.run());
      UiStyles.architectureMiniButton(button);
      return button;
    }

    private void setClipViewport() {
      Rectangle clip = new Rectangle();
      clip.widthProperty().bind(widthProperty());
      clip.heightProperty().bind(heightProperty());
      setClip(clip);
    }

    private void select(NodeCard selected) {
      for (NodeCard card : cards) card.setSelected(card == selected);
    }

    @Override protected void layoutChildren() {
      double viewportW = Math.max(640, getWidth());
      double viewportH = Math.max(500, getHeight());
      toolbar.resizeRelocate(12, 10, Math.min(330, viewportW - 24), 36);

      int agentCount = (int) cards.stream().filter(c -> c.node.kind() == NodeKind.AGENT).count();
      int serviceCount = (int) cards.stream().filter(c -> c.node.kind() == NodeKind.SERVICE).count();
      int maxCount = Math.max(agentCount, serviceCount);
      double worldW = Math.max(WORLD_MIN_WIDTH, 110 + maxCount * 230.0);
      double worldH = WORLD_HEIGHT;
      world.resize(worldW, worldH);
      gridCanvas.setWidth(worldW);
      gridCanvas.setHeight(worldH);
      connectionCanvas.setWidth(worldW);
      connectionCanvas.setHeight(worldH);
      gridCanvas.relocate(0, 0);
      connectionCanvas.relocate(0, 0);

      NodeCard mainCard = cards.stream().filter(c -> c.node.kind() == NodeKind.MAIN).findFirst().orElse(null);
      List<NodeCard> agents = cards.stream().filter(c -> c.node.kind() == NodeKind.AGENT).toList();
      List<NodeCard> services = cards.stream().filter(c -> c.node.kind() == NodeKind.SERVICE).toList();

      if (mainCard != null) mainCard.resizeRelocate((worldW - 310) / 2, 70, 310, 118);
      layoutRow(agents, 245, worldW, 210, 118);
      layoutRow(services, 430, worldW, 220, 104);

      drawGrid(worldW, worldH);
      drawConnections();
      applyTransform();
    }

    private static void layoutRow(List<NodeCard> row, double y, double worldWidth,
                                  double cardWidth, double cardHeight) {
      if (row.isEmpty()) return;
      double gap = 20;
      double total = cardWidth * row.size() + gap * (row.size() - 1);
      double x = Math.max(50, (worldWidth - total) / 2);
      for (NodeCard card : row) {
        card.resizeRelocate(x, y, cardWidth, cardHeight);
        x += cardWidth + gap;
      }
    }

    private void drawGrid(double width, double height) {
      GraphicsContext g = gridCanvas.getGraphicsContext2D();
      g.clearRect(0, 0, width, height);
      g.setFill(Color.web("#0b111b", .84));
      g.fillRect(0, 0, width, height);

      final double step = 24.0;
      for (int i = 0; i <= (int) (width / step); i++) {
        double x = i * step + .5;
        boolean major = i % 5 == 0;
        g.setStroke(Color.web(major ? "#334058" : "#263247", major ? .42 : .22));
        g.setLineWidth(major ? 1.0 : .7);
        g.strokeLine(x, 0, x, height);
      }
      for (int i = 0; i <= (int) (height / step); i++) {
        double y = i * step + .5;
        boolean major = i % 5 == 0;
        g.setStroke(Color.web(major ? "#334058" : "#263247", major ? .42 : .22));
        g.setLineWidth(major ? 1.0 : .7);
        g.strokeLine(0, y, width, y);
      }
    }

    private void drawConnections() {
      GraphicsContext g = connectionCanvas.getGraphicsContext2D();
      g.clearRect(0, 0, connectionCanvas.getWidth(), connectionCanvas.getHeight());
      NodeCard mainCard = cards.stream().filter(c -> c.node.kind() == NodeKind.MAIN).findFirst().orElse(null);
      if (mainCard == null) return;

      double sx = mainCard.getLayoutX() + mainCard.getWidth() / 2;
      double sy = mainCard.getLayoutY() + mainCard.getHeight();
      g.setLineWidth(1.8);

      for (NodeCard card : cards) {
        if (card == mainCard) continue;
        double tx = card.getLayoutX() + card.getWidth() / 2;
        double ty = card.getLayoutY();
        g.setStroke(card.node.kind() == NodeKind.SERVICE
                ? Color.web("#42d3c8", .72) : Color.web("#6f9cff", .78));
        drawCurve(g, sx, sy, tx, ty);
      }
    }

    private static void drawCurve(GraphicsContext g, double sx, double sy, double tx, double ty) {
      double mid = (sy + ty) / 2;
      g.beginPath();
      g.moveTo(sx, sy);
      g.bezierCurveTo(sx, mid, tx, mid, tx, ty);
      g.stroke();
    }

    private void handleZoom(ScrollEvent e) {
      if (e.getDeltaY() == 0) return;
      double factor = e.getDeltaY() > 0 ? 1.10 : 0.90;
      zoomAt(e.getX(), e.getY(), scale * factor);
      e.consume();
    }

    private void setScaleAroundCenter(double target) {
      zoomAt(getWidth() / 2.0, getHeight() / 2.0, target);
    }

    private void zoomAt(double pivotX, double pivotY, double targetScale) {
      double newScale = clamp(targetScale, MIN_SCALE, MAX_SCALE);
      if (Math.abs(newScale - scale) < 0.0001) return;
      double ratio = newScale / scale;
      panX = pivotX - (pivotX - panX) * ratio;
      panY = pivotY - (pivotY - panY) * ratio;
      scale = newScale;
      applyTransform();
      hidePopup();
    }

    private void fitToViewport() {
      double ww = Math.max(1, world.getWidth());
      double wh = Math.max(1, world.getHeight());
      double availableW = Math.max(100, getWidth() - 36);
      double availableH = Math.max(100, getHeight() - 70);
      scale = clamp(Math.min(availableW / ww, availableH / wh), MIN_SCALE, 1.0);
      panX = (getWidth() - ww * scale) / 2.0;
      panY = Math.max(52, (getHeight() - wh * scale) / 2.0 + 20);
      applyTransform();
      hidePopup();
    }

    private void applyTransform() {
      world.setScaleX(scale);
      world.setScaleY(scale);
      world.setTranslateX(panX + world.getWidth() * (scale - 1) / 2.0);
      world.setTranslateY(panY + world.getHeight() * (scale - 1) / 2.0);
      zoomLabel.setText(Math.round(scale * 100) + "%");
    }

    private void handlePanStart(MouseEvent e) {
      if (e.getButton() != MouseButton.PRIMARY && e.getButton() != MouseButton.MIDDLE) return;
      Node target = e.getPickResult().getIntersectedNode();
      if (isInsideNodeCard(target) || isInsideToolbar(target) || isInsidePopup(target)) return;
      panning = true;
      dragStartX = e.getX();
      dragStartY = e.getY();
      panStartX = panX;
      panStartY = panY;
      setCursor(Cursor.CLOSED_HAND);
      e.consume();
    }

    private void handlePanDrag(MouseEvent e) {
      if (!panning) return;
      panX = panStartX + e.getX() - dragStartX;
      panY = panStartY + e.getY() - dragStartY;
      applyTransform();
      hidePopup();
      e.consume();
    }

    private void handlePanEnd(MouseEvent e) {
      if (!panning) return;
      panning = false;
      setCursor(Cursor.DEFAULT);
      e.consume();
    }

    private boolean isInsideNodeCard(Node node) {
      while (node != null && node != this) {
        if (node instanceof NodeCard) return true;
        node = node.getParent();
      }
      return false;
    }

    private boolean isInsideToolbar(Node node) {
      while (node != null && node != this) {
        if (node == toolbar) return true;
        node = node.getParent();
      }
      return false;
    }

    private boolean isInsidePopup(Node node) {
      while (node != null && node != this) {
        if (node == popup) return true;
        node = node.getParent();
      }
      return false;
    }

    private void showPopup(NodeCard card) {
      GraphNode node = card.node;
      ModelAssignment model = node.model();
      ModelAssignment.ProfileConfig cfg = node.config();

      Label title = Ui.label(node.title(), "card-title");
      title.setWrapText(true);

      HBox header = new HBox(8, title);
      header.setAlignment(Pos.CENTER_LEFT);

      VBox body = new VBox(8);
      body.getChildren().addAll(
              muted(node.kindLabel()),
              detail("Модель", model.name),
              detail("GGUF", model.fileName),
              detail("Устройство", cfg.device()),
              detail("Роль", node.role() == null ? "Main LLM" : node.role().title()),
              detail("CTX", Integer.toString(cfg.ctxSize())),
              detail("Parallel", Integer.toString(cfg.parallel()))
      );

      long enabledLoras = cfg.loras()
              .values()
              .stream()
              .filter(ModelAssignment.LoraConfig::enabled)
              .count();

      body.getChildren().add(detail("LoRA", enabledLoras == 0 ? "Нет" : Long.toString(enabledLoras)));

      final double popupWidth = 360.0;
      final double maxPopupHeight = Math.max(240.0, Math.min(430.0, getHeight() - 72.0));

      popup.setMinWidth(popupWidth);
      popup.setPrefWidth(popupWidth);
      popup.setMaxWidth(420);

      body.setPrefWidth(popupWidth - 24);
      body.setMaxWidth(popupWidth - 24);

      header.setPrefWidth(popupWidth - 24);
      header.setMaxWidth(popupWidth - 24);

      header.applyCss();
      header.autosize();
      header.layout();

      body.applyCss();
      body.autosize();
      body.layout();

      double bodyHeight = body.prefHeight(popupWidth - 24);
      double headerHeight = Math.max(32.0, header.prefHeight(popupWidth - 24));

      double desiredHeight = headerHeight + bodyHeight + 28.0;

      if (desiredHeight > maxPopupHeight) {
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setPannable(true);

        scroll.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-background: transparent;"
        );

        VBox.setVgrow(scroll, Priority.ALWAYS);

        popup.getChildren().setAll(header, scroll);
      } else {
        popup.getChildren().setAll(header, body);
      }
      popup.setVisible(true);
      popup.applyCss();
      popup.autosize();
      popup.layout();

      double pw = popupWidth;

      double ph = Math.min(maxPopupHeight, Math.max(110.0, popup.prefHeight(pw)));

      Bounds sceneBounds = card.localToScene(card.getBoundsInLocal());

      Bounds localBounds = sceneToLocal(sceneBounds);

      double x = localBounds.getMaxX() + 12;
      double y = localBounds.getMinY();

      if (x + pw > getWidth() - 10) {
        x = localBounds.getMinX() - pw - 12;
      }

      if (x < 10) {
        x = Math.max(10, (getWidth() - pw) / 2.0);
      }

      if (y + ph > getHeight() - 10) {
        y = Math.max(52, getHeight() - ph - 10);
      }

      if (y < 52) {
        y = 52;
      }
      popup.resizeRelocate(x, y, pw, ph);
      popup.toFront();
      toolbar.toFront();
    }

    private void hidePopup() {
      popup.setVisible(false);
    }

    private static VBox detail(String title, String value) {
      Label key = Ui.label(title, "tiny");
      Label val = Ui.label(value == null || value.isBlank() ? "—" : value, "muted");
      val.setWrapText(true);
      val.setMaxWidth(310);
      return new VBox(1, key, val);
    }

    private static Label muted(String text) {
      Label label = Ui.label(text, "muted");
      label.setWrapText(true);
      return label;
    }

    private static double clamp(double value, double min, double max) {
      return Math.max(min, Math.min(max, value));
    }
  }

  private static final class NodeCard extends VBox {
    private final GraphNode node;

    NodeCard(GraphNode node) {
      this.node = node;
      setSpacing(5);
      setPadding(new Insets(12));
      setAlignment(Pos.CENTER_LEFT);
      Label title = Ui.label(node.title(), "card-title");
      title.setWrapText(true);
      Label model = Ui.label(node.model().name, "muted");
      model.setWrapText(true);
      Label device = Ui.label(node.config().device(), "tiny");
      getChildren().addAll(title, model, device);
      setSelected(false);
    }

    void setSelected(boolean selected) {
      UiStyles.architectureNode(this, node.kind().name(), selected);
    }
  }
}
