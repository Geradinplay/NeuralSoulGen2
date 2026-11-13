package com.neuralsoul.memory;

import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public interface MemoryService {
  List<MemoryRecord> all();
  void add(String text);
  void delete(String id);
  void clear();
  void save();
  void importJson(Path path) throws Exception;
  void exportJson(Path path) throws Exception;
  void importXlsx(Path path) throws Exception;
  void exportXlsx(Path path) throws Exception;
  CompletableFuture<String> summarize(Instant from, Instant to);
  void flushConversation(List<String> messages);
  default void saveExchange(String request, String response) {
    if (request == null || request.isBlank() || response == null || response.isBlank()) return;
    if (isRefusalShapedResponse(response)) {
      System.out.println("[Memory] exchange save skipped: refusal-shaped answer must not become long-term memory");
      return;
    }
    add("Запрос: " + request.trim() + "\nОтвет: " + response.trim());
  }

  /**
   * Detects apology-only / inability answers ("Извините, но я не могу найти...",
   * "К сожалению, я не могу..."). Such answers carry no facts and must not be
   * written into long-term memory, where they would resurface as false claims.
   */
  static boolean isRefusalShapedResponse(String response) {
    if (response == null || response.isBlank()) return false;
    String s = response.toLowerCase(Locale.ROOT);
    boolean apology =
        s.contains("извин") || s.contains("простите") || s.contains("прости,")
        || s.contains("к сожалению") || s.contains("i'm sorry") || s.contains("i am sorry");
    boolean inability =
        s.contains("не могу") || s.contains("не удалось") || s.contains("не знаю")
        || s.contains("произошла ошибка") || s.contains("ошибка при попытке")
        || s.contains("попробую снова") || s.contains("нет доступа") || s.contains("не нашл") || s.contains("cannot")
        || s.contains("can't") || s.contains("unable to");
    if (apology && inability) return true;
    // Explicit near-zero confidence failure answers are also refusal-shaped.
    return s.contains("уверенность ~0%") || s.contains("confidence ~0%");
  }
  default List<MemoryRecord> search(String query, int limit, double minScore) { return List.of(); }
  default List<MemoryRecord> searchDeep(String query, int limit, double minScore) { return search(query, limit, minScore); }
  default void synchronize() throws Exception {}
  /**
   * Returns the last semantic-index (BGE) save failure in a model-readable form, or null.
   * Reading consumes the value: the next context build injects it as a warning turn.
   */
  default String consumeLastSemanticFailure() { return null; }
}
