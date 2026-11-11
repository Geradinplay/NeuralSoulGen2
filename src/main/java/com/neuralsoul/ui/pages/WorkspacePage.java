package com.neuralsoul.ui.pages;

import com.neuralsoul.model.*;
import com.neuralsoul.ui.components.*;
import com.neuralsoul.web.*;
import java.awt.Desktop;
import java.net.URI;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;

public final class WorkspacePage extends VBox {
  private static final int INTERNET_PAGE_SIZE = 10;
  private Runnable refreshFileModels = () -> {};

  /** Refresh role-dependent file search cards when returning from Memory Search. */
  public void refreshFileModels() { refreshFileModels.run(); }

  public WorkspacePage(
      String section, AppState state, Runnable attach, Runnable task, Runnable project, Runnable persist) {
    this(section, state, attach, task, project, persist, state.webSearches::clear);
  }

  public WorkspacePage(
      String section, AppState state, Runnable attach, Runnable task, Runnable project, Runnable persist,
      Runnable clearWebSearchHistory) {
    this(section, state, attach, task, project, persist, clearWebSearchHistory, false);
  }

  /** Embedded mode avoids duplicate page titles when hosted under a tabbed workspace. */
  public WorkspacePage(
      String section, AppState state, Runnable attach, Runnable task, Runnable project, Runnable persist,
      Runnable clearWebSearchHistory, boolean embedded) {
    super(18);
    setMinWidth(0);
    setMaxWidth(Double.MAX_VALUE);
    setPadding(new Insets(embedded ? 0 : 24));
    if (!embedded) getChildren().add(Ui.label("Файлы".equals(section) ? "Файловая память" : section,
        "Файлы".equals(section) ? "section-title" : "page-title"));
    switch (section) {
      case "Задачи" -> {
        getChildren().add(Ui.button("Добавить задачу", "tasks", task));
        list(state.tasks, "tasks");
      }
      case "Файлы" -> buildFiles(state, attach, project, persist);
      case "Память" -> {
        TextField entry = new TextField();
        entry.setPromptText("Новая заметка…");
        HBox.setHgrow(entry, Priority.ALWAYS);
        getChildren()
            .add(
                Ui.row(
                    entry,
                    Ui.button(
                        "Сохранить",
                        "memory",
                        () -> {
                          if (!entry.getText().isBlank()) {
                            state.memories.add(entry.getText().trim());
                            entry.clear();
                          }
                        })));
        list(state.memories, "memory");
      }
      case "Голос" -> {
        Label active = Ui.label("", "purple");
        active
            .textProperty()
            .bind(
                javafx.beans.binding.Bindings.when(state.voice)
                    .then("Голосовой режим выбран")
                    .otherwise("Голосовой режим выключен"));
        getChildren()
            .add(
                Ui.card(
                    "Голосовой режим",
                    Ui.row(active, Ui.spacer(), Ui.toggle(state.voice)),
                    Ui.label("Whisper large-v3 → Nina → F5-TTS", "body"),
                    notice(
                        "Переключатель меняет состояние GUI. Распознавание и синтез речи доступны"
                            + " после подключения STT/TTS-сервисов.")));
      }
      case "Агенты" -> {
        getChildren()
            .addAll(
                Ui.card(
                    "Nina · основной агент",
                    notice("Чат, помощь с проектом и доступ к контексту."),
                    Ui.button("Создать задание", "tasks", task)),
                Ui.card(
                    "Code Assistant",
                    notice(
                        "Анализ исходников и планирование изменений. Выполнение требует"
                            + " backend.")));
      }
      case "Интернет" -> buildInternet(state, clearWebSearchHistory);
      case "Компьютер" ->
          getChildren()
              .add(
                  Ui.card(
                      "Компьютер и экран",
                      notice(
                          "Захват экрана и управление компьютером не подключены. Эта сборка не"
                              + " записывает экран и не управляет другими приложениями.")));
      default -> getChildren().add(notice("Выберите раздел в меню."));
    }
  }

  private void buildInternet(AppState state, Runnable clearWebSearchHistory) {
    Label count = Ui.label("", "muted");
    count.textProperty().bind(javafx.beans.binding.Bindings.size(state.webSearches)
        .asString("Всего сохранённых поисков: %d"));

    Button clear = Ui.button("Очистить историю", "trash", clearWebSearchHistory);
    clear.disableProperty().bind(javafx.beans.binding.Bindings.isEmpty(state.webSearches));

    getChildren().add(
        Ui.card(
            "Интернет",
            Ui.row(
                notice("История WEB_SEARCH сохраняется между запусками в web-search-history.json рядом с конфигами."),
                Ui.spacer(),
                clear),
            count));

    IntegerProperty currentPage = new SimpleIntegerProperty(1);
    HBox pageBar = new HBox(6);
    pageBar.setAlignment(Pos.CENTER_LEFT);

    // The journal already renders only 10 searches per page. A ListView adds a
    // second VirtualFlow viewport whose cell preferred width can be driven by
    // extremely long URLs, pushing the Open buttons outside the visible area.
    // Use ordinary width-constrained VBox cards and the page's existing scroll.
    VBox searchCards = new VBox(12);
    searchCards.setFillWidth(true);
    searchCards.setMinWidth(0);
    searchCards.setMaxWidth(Double.MAX_VALUE);

    Runnable refreshPages = () -> {
      int total = state.webSearches.size();
      int pageCount = Math.max(1, (total + INTERNET_PAGE_SIZE - 1) / INTERNET_PAGE_SIZE);
      int selected = Math.max(1, Math.min(currentPage.get(), pageCount));
      if (selected != currentPage.get()) currentPage.set(selected);

      searchCards.getChildren().clear();
      int newestOffset = (selected - 1) * INTERNET_PAGE_SIZE;
      int from = total - 1 - newestOffset;
      int to = Math.max(-1, from - INTERNET_PAGE_SIZE);
      for (int i = from; i > to && i >= 0; i--) {
        searchCards.getChildren().add(internetSearchRecordCard(state, state.webSearches.get(i)));
      }
      if (total == 0) {
        searchCards.getChildren().add(
            notice("Поисков пока не было. Результаты появятся здесь сразу после WEB_SEARCH."));
      }

      rebuildInternetPageBar(pageBar, currentPage, pageCount);
    };

    currentPage.addListener((obs, oldValue, newValue) -> refreshPages.run());
    state.webSearches.addListener((ListChangeListener<WebSearchRecord>) change -> refreshPages.run());
    refreshPages.run();

    getChildren().add(Ui.card("Страницы", pageBar));
    getChildren().add(searchCards);
  }

  private static VBox internetSearchRecordCard(AppState state, WebSearchRecord record) {
    Label query = Ui.label(record.query(), "card-title");
    query.setWrapText(true);
    query.setMinWidth(0);
    query.setMaxWidth(Double.MAX_VALUE);

    Label meta = Ui.label(
        "Источник: " + displayProvider(record.provider()) + " · " + formatSearchTime(record),
        "tiny");
    meta.setMinWidth(0);

    VBox hits = new VBox(10);
    hits.setMinWidth(0);
    hits.setMaxWidth(Double.MAX_VALUE);
    int index = 1;
    for (SearchHit hit : record.hits()) {
      Label title = Ui.label(index + ". " + (hit.title().isBlank() ? hit.url() : hit.title()), "body");
      title.setWrapText(true);
      title.setMinWidth(0);
      title.setMaxWidth(Double.MAX_VALUE);

      Label url = Ui.label(hit.url(), "tiny");
      url.setWrapText(true);
      url.setMinWidth(0);
      url.setMaxWidth(Double.MAX_VALUE);

      // All text belongs to one column, so the action can be vertically
      // centred against the entire result (title + URL + optional snippet).
      VBox hitContent = new VBox(3, title, url);
      hitContent.setMinWidth(0);
      hitContent.setMaxWidth(Double.MAX_VALUE);
      if (!hit.snippet().isBlank()) {
        Label snippet = Ui.label(hit.snippet(), "muted");
        snippet.setWrapText(true);
        snippet.setMinWidth(0);
        snippet.setMaxWidth(Double.MAX_VALUE);
        hitContent.getChildren().add(snippet);
      }

      Button open = Ui.link("Открыть", () -> openUrl(state, hit.url()));
      open.setMinWidth(Region.USE_PREF_SIZE);
      open.setMaxWidth(Region.USE_PREF_SIZE);
      HBox hitRow = new HBox(10, hitContent, open);
      hitRow.setAlignment(Pos.CENTER_LEFT);
      hitRow.setMinWidth(0);
      hitRow.setMaxWidth(Double.MAX_VALUE);
      HBox.setHgrow(hitContent, Priority.ALWAYS);

      VBox hitCard = Ui.card("", hitRow);
      hitCard.setMinWidth(0);
      hitCard.setMaxWidth(Double.MAX_VALUE);
      hits.getChildren().add(hitCard);
      index++;
    }
    if (record.hits().isEmpty()) {
      Label empty = Ui.label("Поиск завершился без отображаемых результатов.", "muted");
      empty.setWrapText(true);
      hits.getChildren().add(empty);
    }

    VBox recordCard = Ui.card("", query, meta, hits);
    recordCard.setMinWidth(0);
    recordCard.setMaxWidth(Double.MAX_VALUE);
    return recordCard;
  }

  private static void rebuildInternetPageBar(HBox bar, IntegerProperty currentPage, int pageCount) {
    bar.getChildren().clear();

    Button previous = new Button("‹");
    previous.setDisable(currentPage.get() <= 1);
    previous.setOnAction(e -> currentPage.set(Math.max(1, currentPage.get() - 1)));
    bar.getChildren().add(previous);

    for (int page : visibleInternetPages(currentPage.get(), pageCount)) {
      if (page == 0) {
        Label dots = Ui.label("…", "muted");
        dots.setPadding(new Insets(0, 4, 0, 4));
        bar.getChildren().add(dots);
        continue;
      }
      Button button = new Button(Integer.toString(page));
      button.setMinWidth(38);
      button.setDisable(page == currentPage.get());
      button.setOnAction(e -> currentPage.set(page));
      bar.getChildren().add(button);
    }

    Button next = new Button("›");
    next.setDisable(currentPage.get() >= pageCount);
    next.setOnAction(e -> currentPage.set(Math.min(pageCount, currentPage.get() + 1)));
    bar.getChildren().add(next);
  }

  private static java.util.List<Integer> visibleInternetPages(int current, int pageCount) {
    if (pageCount <= 7) {
      java.util.List<Integer> pages = new java.util.ArrayList<>();
      for (int page = 1; page <= pageCount; page++) pages.add(page);
      return pages;
    }

    java.util.List<Integer> pages = new java.util.ArrayList<>();
    pages.add(1);
    int start = Math.max(2, current - 2);
    int end = Math.min(pageCount - 1, current + 2);
    if (start > 2) pages.add(0);
    for (int page = start; page <= end; page++) pages.add(page);
    if (end < pageCount - 1) pages.add(0);
    pages.add(pageCount);
    return pages;
  }

  private static String displayProvider(String provider) {
    if (provider == null || provider.isBlank()) return "неизвестно";
    return switch (provider.toLowerCase(java.util.Locale.ROOT)) {
      case "duckduckgo" -> "DuckDuckGo";
      case "wikipedia" -> "Wikipedia";
      case "searxng" -> "SearXNG";
      case "mojeek" -> "Mojeek";
      case "bing" -> "Bing";
      case "brave" -> "Brave";
      default -> provider;
    };
  }

  private static String formatSearchTime(WebSearchRecord record) {
    return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(record.timestamp());
  }

  private static void openUrl(AppState state, String url) {
    try {
      if (url == null || url.isBlank()) return;
      if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
        throw new IllegalStateException("Открытие ссылок не поддерживается системой");
      Desktop.getDesktop().browse(URI.create(url));
    } catch (Exception e) {
      state.status.set("Не удалось открыть ссылку: " + e.getMessage());
    }
  }

  private void buildFiles(AppState state, Runnable attach, Runnable project, Runnable persist) {
    getChildren().add(
        Ui.row(
            Ui.button("Добавить файл", "attach", attach),
            Ui.button("Открыть проект", "folder", project)));

    VBox modelRows = new VBox(10);
    Runnable refreshModels = () -> {
      modelRows.getChildren().clear();
      Profile profile = state.activeProfile.get() == null ? Profile.NORMAL : state.activeProfile.get();
      var models = state.fileSearchModels(profile);
      if (models.isEmpty()) {
        modelRows.getChildren().add(notice("В этом профиле нет моделей с ролью File Search. Настрой их в «Память → Поиск в памяти»."));
        return;
      }
      for (ModelAssignment model : models) {
        var cfg = state.modelProfileConfig(profile, model);
        Label name = Ui.label(model.name, "body");
        Label meta = Ui.label(model.fileName + " · " + (cfg.enabled() ? "включена" : "выключена"), "tiny");
        VBox text = new VBox(2, name, meta);
        HBox.setHgrow(text, Priority.ALWAYS);
        ToggleButton toggle = Ui.toggle(state.profile(profile).fileRouteStorage(model.fileName));
        toggle.setOnAction(e -> { if (persist != null) persist.run(); });
        Label toggleTitle = Ui.label("Хранилище маршрута файлов", "body");
        Label hint = Ui.label("Сначала искать по сохранённым путям, затем по файловой системе ПК", "tiny");
        VBox option = new VBox(2, Ui.row(toggleTitle, toggle), hint);
        option.setMinWidth(390);
        modelRows.getChildren().add(Ui.card("", Ui.row(Icons.of("file"), text, option)));
      }
    };
    this.refreshFileModels = refreshModels;
    refreshModels.run();
    state.activeProfile.addListener((obs, oldProfile, newProfile) -> refreshModels.run());
    state.models.addListener((javafx.collections.ListChangeListener<ModelAssignment>) c -> refreshModels.run());

    Label profileLabel = Ui.label("", "muted");
    profileLabel.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
        () -> "Профиль: " + (state.activeProfile.get() == null ? Profile.NORMAL.label() : state.activeProfile.get().label()),
        state.activeProfile));

    getChildren().add(
        Ui.card(
            "File Search",
            profileLabel,
            notice("Переключатель показывается для моделей с ролью File Search. Роли настраиваются в «Память → Поиск в памяти»."),
            modelRows));

    getChildren().add(Ui.label("Сохранённые пути", "section-title"));
    getChildren().add(notice(
        "Добавленные файлы и проекты принадлежат текущему профилю и используются как приоритетные маршруты поиска, если для File Search-модели включено «Хранилище маршрута файлов»."));

    VBox routesHost = new VBox();
    Runnable refreshRoutes = () -> {
      routesHost.getChildren().clear();
      Profile profile = state.activeProfile.get() == null ? Profile.NORMAL : state.activeProfile.get();
      routesHost.getChildren().add(routeList(state.fileRoutes(profile), persist));
    };
    refreshRoutes.run();
    state.activeProfile.addListener((obs, oldProfile, newProfile) -> refreshRoutes.run());
    getChildren().add(routesHost);
  }

  private ListView<String> routeList(ObservableList<String> items, Runnable persist) {
    ListView<String> view = new ListView<>(items);
    view.setPrefHeight(430);
    view.setCellFactory(
        v ->
            new ListCell<>() {
              @Override
              protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                if (empty || item == null) {
                  setGraphic(null);
                  return;
                }
                Label path = Ui.label(item, "body");
                path.setWrapText(true);
                HBox.setHgrow(path, Priority.ALWAYS);
                Button remove = Ui.button("Удалить", "file", () -> {
                  items.remove(item);
                  if (persist != null) persist.run();
                });
                remove.setTooltip(new Tooltip("Удалить маршрут из текущего профиля"));
                setGraphic(Ui.row(Icons.of("file"), path, Ui.spacer(), remove));
              }
            });
    return view;
  }

  private Label notice(String text) {
    Label label = Ui.label(text, "muted");
    label.setWrapText(true);
    return label;
  }

  private void list(ObservableList<String> items, String icon) {
    ListView<String> view = new ListView<>(items);
    view.setPrefHeight(430);
    view.setCellFactory(
        v ->
            new ListCell<>() {
              @Override
              protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                setGraphic(empty ? null : Ui.row(Icons.of(icon), Ui.label(item, "body")));
              }
            });
    getChildren().add(view);
  }
}
