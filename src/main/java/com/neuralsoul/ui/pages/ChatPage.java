package com.neuralsoul.ui.pages;

import com.neuralsoul.files.FilePermissionRequest;
import com.neuralsoul.model.*;
import com.neuralsoul.pipeline.GenerationPipeline;
import com.neuralsoul.ui.components.*;
import com.neuralsoul.ui.styles.UiStyles;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.collections.ListChangeListener;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

public final class ChatPage extends BorderPane {
  private static final Pattern MARKDOWN_H3 = Pattern.compile("(?m)^\\s*###\\s+(.+?)\\s*$");
  private static final Pattern MARKDOWN_BOLD = Pattern.compile("\\*\\*([^\\r\\n*](?:[^\\r\\n]*?[^\\r\\n*])?)\\*\\*");
  private static final Pattern CODE_BLOCK = Pattern.compile(
      "(?s)```\\s*([\\w.+#-]*)\\s*\\n?(.*?)```|<code(?:\\s+(?:lang|language)=\\\"?([\\w.+#-]+)\\\"?)?>(.*?)</code>",
      Pattern.CASE_INSENSITIVE);

  private final VBox conversation = new VBox(10);
  private final ScrollPane scroll;
  private final VBox planPanel = new VBox(8);
  private final TextArea planArea = new TextArea();
  private final TextField planFeedback = new TextField();
  /** File-tool approval card currently waiting in the chat. FX-thread only. */
  private Node pendingPermissionCard;

  public ChatPage(AppState state) {
    VBox header = Ui.page("Чат с Ниной", "Локальный AI-компаньон · " + state.mode.get());
    setTop(header);
    conversation.setPadding(new Insets(12, 24, 8, 24));
    scroll = Ui.scroll(conversation);
    setCenter(scroll);
    buildPlanPanel(state);
    Runnable render =
        () -> {
          conversation.getChildren().clear();
          if (state.messages.isEmpty())
            conversation
                .getChildren()
                .add(
                    Ui.card(
                        "Nina",
                        selectableText("Привет! Напиши сообщение внизу, чтобы начать разговор.", false)));
          for (ChatMessage m : state.messages) {
            boolean thinking = "Think".equals(m.author());
            Node body = renderMessage(m.text(), thinking);
            String author = m.author() == null || m.author().isBlank() ? "Generating" : m.author();
            boolean userMessage = "Вы".equals(m.author());
            // GPT-like layout: user messages are compact bubbles aligned to the right.
            // Nina/agent messages keep the original full-width card layout.
            VBox bubble = Ui.card(userMessage ? "" : (thinking ? "Think" : author), body);
            bubble.getStyleClass().add(
                thinking ? "think-bubble" : (userMessage ? "user-bubble" : "assistant-bubble"));
            if (thinking) {
              bubble.setMaxWidth(680);
              UiStyles.thinkingBubble(bubble);
            }
            if (userMessage) {
              double preferred = userBubbleWidth(m.text());
              bubble.setMinWidth(0);
              bubble.setPrefWidth(preferred);
              bubble.setMaxWidth(720);
              bubble.setPadding(new Insets(8, 12, 8, 12));
              UiStyles.userBubble(bubble);

              HBox row = new HBox(bubble);
              row.setAlignment(Pos.CENTER_RIGHT);
              row.setFillHeight(false);
              conversation.getChildren().add(row);
            } else {
              conversation.getChildren().add(bubble);
            }
          }
          // The approval card lives at the end of the feed; streaming re-renders must keep it.
          if (pendingPermissionCard != null) conversation.getChildren().add(pendingPermissionCard);
        };
    state.messages.addListener(
        (ListChangeListener<ChatMessage>) c -> {
          // Auto-scroll only when a new message/bubble is appended.
          // Streaming chunks replace the existing assistant message, so they must not
          // steal the user's scroll position while Nina is generating.
          boolean appended = false;
          while (c.next()) {
            if (c.wasAdded() && !c.wasRemoved()) {
              appended = true;
            }
          }
          render.run();
          if (appended) {
            javafx.application.Platform.runLater(() -> scroll.setVvalue(1));
          }
        });
    render.run();
  }


  private static double userBubbleWidth(String value) {
    String text = value == null ? "" : value;
    int longest = 0;
    for (String line : text.split("\\R", -1)) longest = Math.max(longest, line.length());
    // Compact for short prompts, capped so long prompts wrap like modern chat UIs.
    return Math.max(150, Math.min(720, 34 + longest * 7.2));
  }

  /** Scrolls the conversation to the newest message immediately after send. */
  public void scrollToBottom() {
    javafx.application.Platform.runLater(() -> {
      scroll.applyCss();
      scroll.layout();
      scroll.setVvalue(1.0);
    });
  }

  /**
   * Development mode: bottom area = plan approval panel (visible only while a plan is
   * pending) + the regular composer. The composer keeps its own padding.
   */
  public void attachComposer(Node composer) {
    planPanel.setVisible(false);
    planPanel.setManaged(false);
    VBox bottom = new VBox(8, planPanel, composer);
    bottom.setFillWidth(true);
    setBottom(bottom);
  }

  private void buildPlanPanel(AppState state) {
    Label title = Ui.label("План разработки", "muted");
    UiStyles.heading(title, false);
    planArea.setEditable(true);
    planArea.setWrapText(true);
    planArea.getStyleClass().add("body");
    planArea.setMaxWidth(Double.MAX_VALUE);
    planArea.setPrefRowCount(6);
    UiStyles.chatText(planArea, false);
    Label feedbackLabel = Ui.label("Что доработать:", "muted");
    planFeedback.setPromptText("Опишите, что изменить в плане (для кнопки «Доработать»)");
    planFeedback.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(planFeedback, Priority.ALWAYS);

    Button approve = new Button("Одобрить");
    Button revise = new Button("Доработать");
    Button reject = new Button("Отказаться");
    approve.setOnAction(e -> sendDecision(state, GenerationPipeline.PlanDecision.approve(planArea.getText())));
    revise.setOnAction(e -> {
      String feedback = planFeedback.getText().trim();
      if (feedback.isEmpty()) {
        planFeedback.setPromptText("Сначала опишите, что доработать в плане");
        return;
      }
      sendDecision(state, GenerationPipeline.PlanDecision.revise(feedback));
      planFeedback.clear();
    });
    reject.setOnAction(e -> sendDecision(state, GenerationPipeline.PlanDecision.reject()));
    HBox actions = new HBox(8, feedbackLabel, planFeedback, approve, revise, reject);
    actions.setAlignment(Pos.CENTER_LEFT);
    actions.setMaxWidth(Double.MAX_VALUE);

    planPanel.getChildren().addAll(title, planArea, actions);
    planPanel.setPadding(new Insets(10, 12, 0, 12));
    planPanel.setFillWidth(true);
    // The panel exists only while the pipeline waits for a plan decision.
    state.pendingPlan.addListener(
        (o, old, plan) ->
            javafx.application.Platform.runLater(() -> {
              boolean present = plan != null && !plan.isBlank();
              if (present) planArea.setText(plan);
              planPanel.setVisible(present);
              planPanel.setManaged(present);
              if (present) javafx.application.Platform.runLater(() -> scroll.setVvalue(1));
            }));
  }

  private void sendDecision(AppState state, GenerationPipeline.PlanDecision decision) {
    Consumer<GenerationPipeline.PlanDecision> handler = state.planDecisionHandler;
    if (handler == null) {
      System.out.println("[Plan] decision dropped: no handler wired");
      return;
    }
    // Hide immediately; the pipeline will show a new plan on the next PLAN event.
    state.pendingPlan.set(null);
    handler.accept(decision);
  }

  /**
   * File-tool approval as an inline chat block (not a modal dialog). The calling pipeline
   * thread blocks until the user presses "Подтвердить" or "Отклонить" in the chat.
   */
  public boolean awaitFilePermission(FilePermissionRequest request) {
    CompletableFuture<Boolean> decision = new CompletableFuture<>();
    Runnable show = () -> {
      try {
        Node card = permissionCard(request, decision);
        pendingPermissionCard = card;
        conversation.getChildren().add(card);
        scrollToBottom();
      } catch (Throwable e) {
        pendingPermissionCard = null;
        decision.complete(false);
      }
    };
    if (javafx.application.Platform.isFxApplicationThread()) show.run();
    else javafx.application.Platform.runLater(show);
    try {
      return decision.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (java.util.concurrent.ExecutionException e) {
      return false;
    }
  }

  /** Inline approval card; after the decision it stays in the chat as a record. */
  private Node permissionCard(FilePermissionRequest request, CompletableFuture<Boolean> decision) {
    String action = switch (request.operation()) {
      case "read" -> "Прочитать файл";
      case "write" -> "Записать файл";
      case "edit" -> "Изменить файл";
      case "patch" -> "Применить изменение";
      case "grep" -> "Поиск внутри файлов";
      case "delete" -> "Удалить файл или папку";
      default -> request.operation();
    };
    Label title = Ui.label("Запрос доступа · " + action, "muted");
    UiStyles.heading(title, false);
    Label pathLabel = Ui.label(request.path(), "muted");
    pathLabel.setWrapText(true);
    pathLabel.setStyle("-fx-font-family:'Consolas';-fx-font-size:12px;");
    Node details = permissionDetails(request);

    Button approve = new Button("Подтвердить");
    Button deny = new Button("Отклонить");
    approve.setOnAction(e -> {
      if (decision.isDone()) return;
      approve.setDisable(true);
      deny.setDisable(true);
      title.setText("Подтверждено · " + action);
      pendingPermissionCard = null;
      decision.complete(true);
    });
    deny.setOnAction(e -> {
      if (decision.isDone()) return;
      approve.setDisable(true);
      deny.setDisable(true);
      title.setText("Отклонено · " + action);
      pendingPermissionCard = null;
      decision.complete(false);
    });
    HBox actions = new HBox(8, approve, deny);
    actions.setAlignment(Pos.CENTER_LEFT);

    VBox card = new VBox(8, title, pathLabel, details, actions);
    card.setFillWidth(true);
    card.setPadding(new Insets(10, 12, 10, 12));
    card.getStyleClass().add("assistant-bubble");
    return card;
  }

  /**
   * Permission details rendered with code highlighting. Sections of the details text
   * ("--- CURRENT ---", "--- PROPOSED ---", "--- OLD ---", "--- NEW ---") become labelled
   * code blocks; anything without sections stays a plain selectable text.
   */
  private static Node permissionDetails(FilePermissionRequest request) {
    String details = request.details() == null ? "" : request.details();
    String lang = languageForPath(request.path());
    Pattern section = Pattern.compile("(?m)^---\\s*(.+?)\\s*---\\s*$");
    Matcher first = section.matcher(details);
    if (!first.find()) return selectableText(details, false);

    VBox box = new VBox(6);
    box.setFillWidth(true);
    Matcher matcher = section.matcher(details);
    int from = 0;
    while (matcher.find()) {
      if (matcher.start() > from) {
        String head = details.substring(from, matcher.start()).strip();
        if (!head.isBlank()) box.getChildren().add(selectableText(head, false));
      }
      Label tag = Ui.label(matcher.group(1), "muted");
      int bodyStart = matcher.end();
      int bodyEnd = details.length();
      Matcher next = section.matcher(details);
      if (next.find(bodyStart)) bodyEnd = next.start();
      String body = details.substring(bodyStart, bodyEnd).strip();
      box.getChildren().add(tag);
      box.getChildren().add(codeBlock(lang, body.isEmpty() ? "(пусто)" : body));
      from = bodyEnd;
    }
    if (from < details.length()) {
      String tail = details.substring(from).strip();
      if (!tail.isBlank()) box.getChildren().add(selectableText(tail, false));
    }
    return box;
  }

  /** Picks a syntax-highlighting language from the target file extension. */
  private static String languageForPath(String path) {
    String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
    if (p.endsWith(".html") || p.endsWith(".htm")) return "html";
    if (p.endsWith(".css")) return "css";
    if (p.endsWith(".js")) return "javascript";
    if (p.endsWith(".ts")) return "typescript";
    if (p.endsWith(".py")) return "python";
    if (p.endsWith(".java")) return "java";
    if (p.endsWith(".json")) return "json";
    if (p.endsWith(".xml") || p.endsWith(".yml") || p.endsWith(".yaml")) return "xml";
    return "code";
  }

  /**
   * Renders fenced Markdown code (```java ... ```) and <code>...</code> as a real
   * code panel. Everything else is a read-only TextArea so chat text can be selected
   * with the mouse and copied with Ctrl+C.
   */
  private static Node renderMessage(String value, boolean thinking) {
    String text = value == null ? "" : value;
    Matcher matcher = CODE_BLOCK.matcher(text);
    VBox parts = new VBox(4);
    int from = 0;
    boolean found = false;
    while (matcher.find()) {
      found = true;
      if (matcher.start() > from) {
        String plain = text.substring(from, matcher.start()).replaceFirst("\\s+$", "");
        if (!plain.isEmpty()) parts.getChildren().add(markdownText(plain, thinking));
      }
      String language = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
      String code = matcher.group(2) != null ? matcher.group(2) : matcher.group(4);
      parts.getChildren().add(codeBlock(language, code));
      from = matcher.end();
    }
    if (!found) return markdownText(text, thinking);
    if (from < text.length()) {
      String tail = text.substring(from).replaceFirst("^\\s+", "");
      if (!tail.isEmpty()) parts.getChildren().add(markdownText(tail, thinking));
    }
    parts.setFillWidth(true);
    return parts;
  }


  /** Renders Markdown level-3 headings as a slightly larger bold line. */
  private static Node markdownText(String text, boolean thinking) {
    Matcher matcher = MARKDOWN_H3.matcher(text == null ? "" : text);
    if (!matcher.find()) return inlineMarkdown(text, thinking);
    VBox box = new VBox(3);
    int from = 0;
    do {
      if (matcher.start() > from) {
        String before = text.substring(from, matcher.start()).replaceFirst("\\s+$", "");
        if (!before.isBlank()) box.getChildren().add(inlineMarkdown(before, thinking));
      }
      Label heading = new Label(matcher.group(1).trim());
      heading.setWrapText(true);
      heading.setMaxWidth(Double.MAX_VALUE);
      UiStyles.heading(heading, thinking);
      box.getChildren().add(heading);
      from = matcher.end();
    } while (matcher.find());
    if (from < text.length()) {
      String tail = text.substring(from).replaceFirst("^\\s+", "");
      if (!tail.isBlank()) box.getChildren().add(inlineMarkdown(tail, thinking));
    }
    box.setFillWidth(true);
    return box;
  }

  /** Renders **text** as bold while leaving unmatched asterisks unchanged. */
  private static Node inlineMarkdown(String value, boolean thinking) {
    String text = value == null ? "" : value;
    Matcher matcher = MARKDOWN_BOLD.matcher(text);
    if (!matcher.find()) return selectableText(text, thinking);

    TextFlow flow = new TextFlow();
    flow.setMaxWidth(Double.MAX_VALUE);
    int from = 0;
    do {
      if (matcher.start() > from) addInlineText(flow, text.substring(from, matcher.start()), thinking, false);
      addInlineText(flow, matcher.group(1), thinking, true);
      from = matcher.end();
    } while (matcher.find());
    if (from < text.length()) addInlineText(flow, text.substring(from), thinking, false);

    MenuItem copy = new MenuItem("Копировать текст");
    copy.setOnAction(event -> {
      ClipboardContent content = new ClipboardContent();
      content.putString(text);
      Clipboard.getSystemClipboard().setContent(content);
    });
    ContextMenu menu = new ContextMenu(copy);
    flow.setOnContextMenuRequested(event -> menu.show(flow, event.getScreenX(), event.getScreenY()));
    return flow;
  }

  private static void addInlineText(TextFlow flow, String value, boolean thinking, boolean bold) {
    if (value == null || value.isEmpty()) return;
    Text node = new Text(value);
    UiStyles.chatInline(node, thinking, bold);
    flow.getChildren().add(node);
  }

  private static TextArea selectableText(String text, boolean thinking) {
    TextArea area = new TextArea(text);
    area.setEditable(false);
    area.setWrapText(true);
    area.setFocusTraversable(true);
    area.getStyleClass().add("body");
    area.setMaxWidth(Double.MAX_VALUE);
    VBox.setVgrow(area, Priority.NEVER);
    UiStyles.chatText(area, thinking);

    // The chat ScrollPane owns vertical scrolling. A message TextArea therefore must
    // not reserve a fixed/capped viewport of its own: its height follows the actual
    // wrapped text and the surrounding card follows that height automatically.
    // Recalculate only when the available width really changes, so resizing works
    // without creating a width <-> height feedback loop while tokens are streaming.
    area.widthProperty().addListener((obs, oldWidth, newWidth) -> {
      double width = newWidth == null ? 0 : newWidth.doubleValue();
      if (width > 40 && Math.abs(width - oldWidth.doubleValue()) > 0.5) {
        fitTextAreaToContent(area, text, width);
      }
    });
    javafx.application.Platform.runLater(() -> {
      double width = area.getWidth();
      fitTextAreaToContent(area, text, width > 40 ? width : 820);
    });
    // Compact first layout before the real control width is known.
    fitTextAreaToContent(area, text, 820);
    return area;
  }

  private static void fitTextAreaToContent(TextArea area, String value, double width) {
    String text = value == null ? "" : value;
    double usableWidth = Math.max(80, width - 12);

    Text measure = new Text(text.isEmpty() ? " " : text);
    measure.setFont(area.getFont());
    measure.setWrappingWidth(usableWidth);
    double textHeight = Math.ceil(measure.getLayoutBounds().getHeight());

    // A few pixels are kept for the TextArea skin/caret. There is deliberately no
    // upper cap: long answers grow and are scrolled by the conversation ScrollPane.
    double height = Math.max(30, textHeight + 10);
    area.setPrefHeight(height);
    area.setMinHeight(height);
    area.setMaxHeight(height);
  }

  private static Node codeBlock(String language, String code) {
    String lang = language == null || language.isBlank() ? "code" : language.trim();
    String clean = code == null ? "" : code;
    if (clean.startsWith("\n")) clean = clean.substring(1);
    if (clean.endsWith("\n")) clean = clean.substring(0, clean.length() - 1);

    Label tag = Ui.label(lang, "muted");
    Button copy = new Button("Копировать");
    String finalClean = clean;
    copy.setOnAction(e -> {
      ClipboardContent content = new ClipboardContent();
      content.putString(finalClean);
      Clipboard.getSystemClipboard().setContent(content);
    });
    HBox head = new HBox(8, tag, Ui.spacer(), copy);
    head.setAlignment(Pos.CENTER_LEFT);

    TextFlow codeFlow = highlightedCode(clean, lang);
    codeFlow.setLineSpacing(1.5);
    UiStyles.codeFlow(codeFlow);

    ScrollPane codeScroll = new ScrollPane(codeFlow);
    codeScroll.setFitToHeight(true);
    codeScroll.setFitToWidth(false);
    codeScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
    codeScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
    int rows = Math.max(2, Math.min(20, clean.split("\\R", -1).length));
    codeScroll.setPrefHeight(24 + rows * 19);
    codeScroll.setMaxWidth(Double.MAX_VALUE);
    UiStyles.codeScroll(codeScroll);

    VBox box = new VBox(4, head, codeScroll);
    box.setFillWidth(true);
    box.setPadding(new Insets(6));
    UiStyles.codeBox(box);
    return box;
  }


  private static TextFlow highlightedCode(String code, String language) {
    TextFlow flow = new TextFlow();
    Pattern pattern = syntaxPattern(language);
    Matcher matcher = pattern.matcher(code);
    int last = 0;
    while (matcher.find()) {
      if (matcher.start() > last) addCodeText(flow, code.substring(last, matcher.start()), "#e7eaf0");
      String color = "#e7eaf0";
      if (matcher.group("COMMENT") != null) color = "#6f7d8f";
      else if (matcher.group("STRING") != null) color = "#9ad17b";
      else if (matcher.group("NUMBER") != null) color = "#e5b567";
      else if (matcher.group("KEYWORD") != null) color = "#c792ea";
      else if (matcher.group("TYPE") != null) color = "#82aaff";
      else if (matcher.group("ANNOTATION") != null) color = "#ffcb6b";
      addCodeText(flow, matcher.group(), color);
      last = matcher.end();
    }
    if (last < code.length()) addCodeText(flow, code.substring(last), "#e7eaf0");
    return flow;
  }

  private static void addCodeText(TextFlow flow, String value, String color) {
    Text text = new Text(value);
    UiStyles.codeToken(text, color);
    flow.getChildren().add(text);
  }

  private static Pattern syntaxPattern(String language) {
    String lang = language == null ? "" : language.toLowerCase();
    String keywords;
    String types;
    if (lang.contains("python") || lang.equals("py")) {
      keywords = "and|as|assert|async|await|break|class|continue|def|del|elif|else|except|False|finally|for|from|global|if|import|in|is|lambda|None|nonlocal|not|or|pass|raise|return|True|try|while|with|yield";
      types = "str|int|float|bool|list|dict|set|tuple|bytes|object";
    } else if (lang.contains("javascript") || lang.equals("js") || lang.contains("typescript") || lang.equals("ts")) {
      keywords = "break|case|catch|class|const|continue|debugger|default|delete|do|else|export|extends|finally|for|from|function|if|import|in|instanceof|let|new|return|static|super|switch|this|throw|try|typeof|var|void|while|with|yield|async|await";
      types = "string|number|boolean|any|unknown|never|object|Array|Promise|Map|Set";
    } else {
      keywords = "abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|default|do|double|else|enum|extends|final|finally|float|for|goto|if|implements|import|instanceof|int|interface|long|native|new|package|private|protected|public|return|short|static|strictfp|super|switch|synchronized|this|throw|throws|transient|try|void|volatile|while|var|record|sealed|permits|namespace|using|async|await|override|virtual";
      types = "String|Integer|Long|Double|Float|Boolean|Object|List|Map|Set|Collection|Optional|Stream|Task|DateTime|Exception|RuntimeException";
    }
    String regex =
        "(?<COMMENT>//[^\\n]*|/\\*[\\s\\S]*?\\*/|#[^\\n]*)"
            + "|(?<STRING>\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'(?:\\\\.|[^'\\\\])*')"
            + "|(?<NUMBER>\\b(?:0x[0-9A-Fa-f]+|\\d+(?:\\.\\d+)?)\\b)"
            + "|(?<ANNOTATION>@[A-Za-z_$][\\w$]*)"
            + "|(?<KEYWORD>\\b(?:" + keywords + ")\\b)"
            + "|(?<TYPE>\\b(?:" + types + ")\\b)";
    return Pattern.compile(regex);
  }

}
