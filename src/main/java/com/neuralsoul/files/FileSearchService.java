package com.neuralsoul.files;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Bounded, read-only filename/path index used by deterministic File Tools. */
public final class FileSearchService {
  private static final int DEFAULT_MAX_VISITED = 120_000;
  private volatile int maxVisited = DEFAULT_MAX_VISITED;
  private static final int MAX_DEPTH = 12;

  public void setMaxVisited(int value) {
    maxVisited = Math.max(1_000, Math.min(5_000_000, value));
  }

  public int maxVisited() { return maxVisited; }

  public List<FileSearchResult> search(String query, int limit) {
    return searchIn(null, query, limit);
  }

  /**
   * Search saved routes first, then the normal PC roots. Preferred-route results receive a score
   * boost so they stay at the top while global results still fill the remaining slots.
   */
  public List<FileSearchResult> searchPrioritized(Collection<String> savedRoutes, String query, int limit) {
    if (savedRoutes == null || savedRoutes.isEmpty()) return search(query, limit);
    int max = Math.max(1, limit);
    LinkedHashMap<String, FileSearchResult> merged = new LinkedHashMap<>();
    List<String> queryTerms = terms(query);
    if (queryTerms.isEmpty()) return List.of();

    for (String raw : savedRoutes) {
      if (raw == null || raw.isBlank()) continue;
      try {
        Path route = Path.of(raw).toAbsolutePath().normalize();
        if (Files.isDirectory(route)) {
          for (FileSearchResult result : searchIn(route, query, max)) {
            FileSearchResult preferred = new FileSearchResult(result.path(), result.directory(), result.score() + 1000);
            merged.merge(key(preferred.path()), preferred,
                (a, b) -> a.score() >= b.score() ? a : b);
          }
        } else if (Files.isRegularFile(route)) {
          int score = score(route, false, queryTerms);
          if (score > 0) {
            FileSearchResult preferred = new FileSearchResult(route.toString(), false, score + 1000);
            merged.put(key(preferred.path()), preferred);
          }
        }
      } catch (InvalidPathException | SecurityException ignored) {}
    }

    for (FileSearchResult result : search(query, max)) {
      merged.merge(key(result.path()), result, (a, b) -> a.score() >= b.score() ? a : b);
    }

    List<FileSearchResult> out = new ArrayList<>(merged.values());
    out.sort(Comparator.comparingInt(FileSearchResult::score).reversed().thenComparing(FileSearchResult::path));
    return out.size() <= max ? out : new ArrayList<>(out.subList(0, max));
  }

  /** Search below an agent-selected directory. A null root keeps the legacy bounded global search. */
  public List<FileSearchResult> searchIn(Path requestedRoot, String query, int limit) {
    if (query == null || query.isBlank()) return List.of();
    List<String> terms = terms(query);
    if (terms.isEmpty()) return List.of();
    PriorityQueue<FileSearchResult> best = new PriorityQueue<>(Comparator.comparingInt(FileSearchResult::score));
    int[] visited = {0};
    List<Path> searchRoots = requestedRoot == null ? roots() : List.of(requestedRoot.toAbsolutePath().normalize());
    for (Path root : searchRoots) {
      if (!Files.isDirectory(root) || visited[0] >= maxVisited) continue;
      try {
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH, new SimpleFileVisitor<>() {
          @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (++visited[0] > maxVisited) return FileVisitResult.TERMINATE;
            if (!dir.equals(root) && skipDirectory(dir)) return FileVisitResult.SKIP_SUBTREE;
            consider(dir, true, terms, limit, best);
            return FileVisitResult.CONTINUE;
          }
          @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (++visited[0] > maxVisited) return FileVisitResult.TERMINATE;
            consider(file, false, terms, limit, best);
            return FileVisitResult.CONTINUE;
          }
          @Override public FileVisitResult visitFileFailed(Path file, IOException exc) { return FileVisitResult.CONTINUE; }
        });
      } catch (IOException | SecurityException ignored) {}
    }
    List<FileSearchResult> result = new ArrayList<>(best);
    result.sort(Comparator.comparingInt(FileSearchResult::score).reversed().thenComparing(FileSearchResult::path));
    return result;
  }

  private static void consider(Path path, boolean dir, List<String> terms, int limit, PriorityQueue<FileSearchResult> best) {
    int score = score(path, dir, terms);
    if (score == 0) return;
    FileSearchResult item = new FileSearchResult(path.toAbsolutePath().normalize().toString(), dir, score);
    best.offer(item);
    while (best.size() > Math.max(1, limit)) best.poll();
  }

  private static int score(Path path, boolean dir, List<String> terms) {
    String name = String.valueOf(path.getFileName()).toLowerCase(Locale.ROOT);
    String full = path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    int score = 0;
    for (String term : terms) {
      if (name.equals(term) || name.equals("." + term)) score += 100;
      else if (name.contains(term)) score += 45;
      else if (full.contains(term)) score += 12;
    }
    if (score > 0 && dir) score += 3;
    return score;
  }

  private static String key(String path) {
    return path == null ? "" : path.replace('\\', '/').toLowerCase(Locale.ROOT);
  }

  private static List<Path> roots() {
    LinkedHashSet<Path> roots = new LinkedHashSet<>();
    String home = System.getProperty("user.home");
    if (home != null && !home.isBlank()) roots.add(Path.of(home));
    roots.add(Path.of("").toAbsolutePath().normalize());
    // Search other filesystem roots after the user's home/project. The visitor is bounded.
    for (Path root : FileSystems.getDefault().getRootDirectories()) roots.add(root);
    return new ArrayList<>(roots);
  }

  private static boolean skipDirectory(Path dir) {
    String n = String.valueOf(dir.getFileName()).toLowerCase(Locale.ROOT);
    return n.equals("windows") || n.equals("program files") || n.equals("program files (x86)")
        || n.equals("programdata") || n.equals("$recycle.bin") || n.equals("system volume information")
        || n.equals("node_modules") || n.equals(".git") || n.equals("target") || n.equals("build");
  }

  private static List<String> terms(String query) {
    Set<String> stop = Set.of("найди","найти","поиск","файл","файлы","папка","папку","папки","корень","игры","игра",
        "где","лежит","мой","моя","мою","the","find","search","file","folder","root","game","where","is");
    List<String> out = new ArrayList<>();
    for (String raw : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}._-]+")) {
      String t = raw.trim();
      if (t.length() >= 2 && !stop.contains(t)) out.add(t);
    }
    return out;
  }
}
