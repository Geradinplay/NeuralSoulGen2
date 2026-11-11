package com.neuralsoul.ui.styles;

import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;

/** Central home for programmatic JavaFX styles that cannot live in theme.css. */
public final class UiStyles {
  private UiStyles() {}

  public static void microphone(Button button, boolean recording) {
    button.setStyle(recording ? "-fx-background-color:#922b53;" : "");
  }

  public static void splashPanel(Region node) { node.setStyle("-fx-background-color:rgba(10,11,22,.97);-fx-background-radius:18;-fx-border-color:rgba(139,122,220,.48);-fx-border-radius:18;-fx-border-width:1;"); }
  public static void splashTitle(Label node) { node.setStyle("-fx-text-fill:linear-gradient(to right,#f4f1ff,#a66cff);-fx-font-size:42px;-fx-font-weight:300;"); }
  public static void splashSubtitle(Label node) { node.setStyle("-fx-text-fill:#aaa6ba;-fx-font-size:16px;-fx-letter-spacing:2px;"); }
  public static void accentProgress(ProgressBar node) { node.setStyle("-fx-accent:#9d5cff;"); }
  public static void splashStatus(Label node) { node.setStyle("-fx-text-fill:#a9a5b8;-fx-font-size:13px;"); }
  public static void splashPercent(Label node) { node.setStyle("-fx-text-fill:#bcb6d2;-fx-font-size:13px;"); }

  public static void startupLog(TextArea node) { node.setStyle("-fx-control-inner-background:#0b0c14;-fx-text-fill:#bdb8cf;-fx-font-family:'Consolas';-fx-font-size:12px;-fx-background-color:#0b0c14;"); }
  public static void dangerButton(Button node) { node.setStyle("-fx-background-color:#3a171d;-fx-text-fill:#fff0f0;-fx-border-color:#9a4551;-fx-border-radius:10;-fx-background-radius:10;-fx-padding:9 16;"); }
  public static void startupPanel(Region node, boolean error) { node.setStyle("-fx-background-color:rgba(10,11,20,.98);-fx-background-radius:18;-fx-border-radius:18;-fx-border-width:1;-fx-border-color:"+(error?"rgba(205,76,91,.70)":"rgba(128,111,185,.55)")+";"); }
  public static void label(Label node, int size, String color) { node.setStyle("-fx-text-fill:"+color+";-fx-font-size:"+size+"px;"); }
  public static void ready(Label node) { label(node,14,"#82e5b0"); }
  public static void errorDetails(TextArea node) { node.setStyle("-fx-control-inner-background:#160d12;-fx-text-fill:#ff9b9b;-fx-font-family:'Consolas';-fx-font-size:12px;"); }
  public static void secondaryDialogButton(Button node, String border) { node.setStyle("-fx-background-color:#14121a;-fx-text-fill:#eee8f2;-fx-border-color:"+border+";-fx-border-radius:10;-fx-background-radius:10;-fx-padding:10 18;"); }
  public static void retryButton(Button node) { node.setStyle("-fx-background-color:#3a171d;-fx-text-fill:#fff0f0;-fx-border-color:#9a4551;-fx-border-radius:10;-fx-background-radius:10;-fx-padding:10 22;"); }

  public static void thinkingBubble(Region node) { node.setStyle("-fx-border-color:rgba(130,130,150,0.35);-fx-border-radius:10;-fx-background-radius:10;"); }
  public static void userBubble(Region node) { node.setStyle("-fx-background-color:rgba(74,57,132,0.72);-fx-background-radius:16;-fx-border-color:rgba(139,112,235,0.45);-fx-border-radius:16;"); }
  public static void heading(Label node, boolean thinking) { node.setStyle("-fx-text-fill:#f4f4f4;-fx-font-size:15px;-fx-font-weight:bold;-fx-padding:3 0 1 0;"+(thinking?"-fx-opacity:0.78;-fx-font-style:italic;":"")); }
  public static void chatText(TextArea node, boolean thinking) { node.setStyle("-fx-background-color:transparent;-fx-background-insets:0;-fx-padding:0;-fx-border-color:transparent;-fx-control-inner-background:transparent;-fx-text-fill:#f4f4f4;-fx-prompt-text-fill:#f4f4f4;-fx-highlight-fill:#6650a4;-fx-highlight-text-fill:#ffffff;"+(thinking?"-fx-opacity:0.78;-fx-font-style:italic;":"")); }
  public static void codeFlow(Node node) { node.setStyle("-fx-padding:10 12 10 12;-fx-background-color:rgba(10,12,18,0.96);"); }
  public static void codeScroll(ScrollPane node) { node.setStyle("-fx-background:rgba(10,12,18,0.96);-fx-background-color:rgba(10,12,18,0.96);-fx-border-color:rgba(130,140,170,0.35);-fx-border-radius:8;-fx-background-radius:8;"); }
  public static void codeBox(Region node) { node.setStyle("-fx-background-color:rgba(12,14,20,0.72);-fx-background-radius:10;-fx-border-color:rgba(130,140,170,0.22);-fx-border-radius:10;"); }
  public static void codeToken(Text node, String color) { node.setStyle("-fx-font-family:'Consolas';-fx-font-size:12px;-fx-fill:"+color+";"); }
  public static void monospace(TextArea node) { node.setStyle("-fx-font-family:'Consolas';-fx-font-size:12px;"); }

  public static void segmented(ToggleButton button, boolean active, boolean left) {
    String radius = left ? "10 0 0 10" : "0 10 10 0";
    button.setStyle("-fx-background-color:"+(active?"#6d5dfc":"rgba(18,25,37,.82)")+";"
        +"-fx-text-fill:"+(active?"#ffffff":"#aebbd0")+";"
        +"-fx-background-radius:"+radius+";-fx-border-radius:"+radius+";"
        +"-fx-border-color:"+(active?"#8578ff":"#2b3950")+";-fx-border-width:1;"
        +"-fx-font-weight:600;-fx-padding:8 18;-fx-cursor:hand;");
  }
  public static void loraModelSelector(ToggleButton button, boolean active) {
    button.setStyle(
        "-fx-background-color:" + (active ? "rgba(78,63,155,.72)" : "rgba(15,20,31,.88)") + ";"
            + "-fx-text-fill:" + (active ? "#ffffff" : "#d7ddea") + ";"
            + "-fx-background-radius:12;-fx-border-radius:12;"
            + "-fx-border-color:" + (active ? "#8b7cff" : "#2b3950") + ";"
            + "-fx-border-width:" + (active ? "1.5" : "1") + ";"
            + "-fx-padding:12 14;-fx-cursor:hand;-fx-alignment:center-left;");
  }

  public static void loraEnabledCheck(CheckBox checkBox) {
    checkBox.setStyle(
        "-fx-font-size:14px;-fx-font-weight:600;-fx-text-fill:#dfe5f2;"
            + "-fx-padding:6 8;-fx-cursor:hand;");
    checkBox.setScaleX(1.08);
    checkBox.setScaleY(1.08);
  }

  public static void architectureContainer(Region node) {
    node.setStyle("-fx-background-color:rgba(9,14,24,.58);-fx-background-radius:16;"
        + "-fx-border-color:rgba(90,115,160,.34);-fx-border-radius:16;-fx-border-width:1;-fx-padding:16;");
  }

  public static void architectureDetails(Region node) {
    node.setStyle("-fx-background-color:rgba(15,21,33,.88);-fx-background-radius:14;"
        + "-fx-border-color:rgba(77,98,132,.42);-fx-border-radius:14;-fx-border-width:1;-fx-padding:16;");
  }


  public static void architecturePopup(Region node) {
    node.setStyle("-fx-background-color:rgba(15,21,33,.97);-fx-background-radius:12;"
        + "-fx-border-color:rgba(105,129,173,.70);-fx-border-radius:12;-fx-border-width:1;-fx-padding:12;"
        + "-fx-effect:dropshadow(gaussian,rgba(0,0,0,.45),18,0.22,0,6);");
  }

  public static void architectureToolbar(Region node) {
    node.setStyle("-fx-background-color:rgba(12,17,27,.92);-fx-background-radius:10;"
        + "-fx-border-color:rgba(77,98,132,.45);-fx-border-radius:10;-fx-border-width:1;");
  }

  public static void architectureMiniButton(Button node) {
    node.setStyle("-fx-background-color:rgba(24,31,46,.96);-fx-text-fill:#dce6f5;"
        + "-fx-border-color:#33445f;-fx-border-radius:7;-fx-background-radius:7;"
        + "-fx-padding:5 9;-fx-font-size:11px;-fx-cursor:hand;");
  }

  public static void architecturePopupClose(Button node) {
    node.setStyle("-fx-background-color:transparent;-fx-text-fill:#9fb0ca;-fx-font-size:18px;"
        + "-fx-padding:0 2 0 6;-fx-cursor:hand;");
  }

  public static void architectureNode(Region node, String kind, boolean selected) {
    String border = switch (kind) {
      case "MAIN" -> selected ? "#8b7cff" : "#6f63d9";
      case "SERVICE" -> selected ? "#55e5d6" : "#287f83";
      default -> selected ? "#68a2ff" : "#355b9c";
    };
    String background = switch (kind) {
      case "MAIN" -> "rgba(46,38,105,.88)";
      case "SERVICE" -> "rgba(16,55,61,.82)";
      default -> "rgba(20,39,70,.84)";
    };
    node.setStyle("-fx-background-color:" + background + ";-fx-background-radius:14;"
        + "-fx-border-color:" + border + ";-fx-border-radius:14;-fx-border-width:"
        + (selected ? "2" : "1.2") + ";-fx-cursor:hand;");
  }

  public static void voiceTabBar(Region node) {
    node.setStyle("-fx-background-color:rgba(10,15,25,.72);-fx-background-radius:13;"
        + "-fx-border-color:rgba(72,91,126,.48);-fx-border-radius:13;-fx-border-width:1;");
  }

  public static void voiceTab(ToggleButton button, boolean active) {
    button.setStyle(
        "-fx-background-color:" + (active ? "rgba(91,75,190,.88)" : "transparent") + ";"
            + "-fx-text-fill:" + (active ? "#ffffff" : "#aebbd0") + ";"
            + "-fx-background-radius:10;-fx-border-radius:10;"
            + "-fx-border-color:" + (active ? "#8b7cff" : "transparent") + ";"
            + "-fx-border-width:1;-fx-font-weight:" + (active ? "700" : "600") + ";"
            + "-fx-padding:9 16;-fx-cursor:hand;");
  }

  public static void voiceMode(ToggleButton button, boolean active) {
    button.setStyle(
        "-fx-background-color:" + (active ? "rgba(91,75,190,.82)" : "rgba(15,21,33,.88)") + ";"
            + "-fx-text-fill:" + (active ? "#ffffff" : "#c2ccdc") + ";"
            + "-fx-background-radius:11;-fx-border-radius:11;"
            + "-fx-border-color:" + (active ? "#8b7cff" : "#33445f") + ";"
            + "-fx-border-width:" + (active ? "1.5" : "1") + ";"
            + "-fx-font-weight:600;-fx-padding:10 16;-fx-cursor:hand;");
  }


}
