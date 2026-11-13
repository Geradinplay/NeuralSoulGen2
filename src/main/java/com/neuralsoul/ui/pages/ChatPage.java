package com.neuralsoul.ui.pages;

import com.neuralsoul.model.*;
import com.neuralsoul.ui.components.*;
import com.neuralsoul.ui.styles.UiStyles;
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

  public ChatPage(AppState state) {
    VBox header = Ui.page("Чат с Ниной", "Локальный AI-компаньон · " + state.mode.get());
    setTop(header);
    conversation.setPadding(new Insets(12, 24, 8, 24));
    scroll = Ui.scroll(conversation);
    setCenter(scroll);
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
