package com.neuralsoul.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Local filesystem operations exposed to the agent. Permission is enforced by GenerationPipeline. */
public final class FileToolService {
  private static final int MAX_READ_CHARS = 180_000;
  private static final int MAX_GREP_MATCHES = 80;
  private final FileSearchService search = new FileSearchService();
  private volatile List<String> preferredRoots = List.of();

  public void setSearchMaxVisited(int value) { search.setMaxVisited(value); }

  public void setPreferredRoots(Collection<String> roots) {
    preferredRoots = roots == null ? List.of() : roots.stream()
        .filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank())
        .map(FileToolService::resolvePath).distinct().toList();
  }

  public String execute(String operation, List<String> args) throws IOException {
    return switch (operation.toLowerCase(Locale.ROOT)) {
      case "roots" -> roots(args);
      case "list" -> list(args);
      case "glob" -> glob(args);
      case "search" -> search(args);
      case "grep" -> grep(args);
      case "read" -> read(args);
      case "write" -> write(args);
      case "edit" -> edit(args);
      case "patch" -> patch(args);
      default -> "FILE_TOOL_ERROR: unknown operation '" + operation
          + "'. Supported: roots, list, glob, search, grep, read, write, edit, patch.";
    };
  }

  private String roots(List<String> args) {
    StringBuilder out = new StringBuilder("FILE_TOOL_RESULT roots\n");
    for (String root : preferredRoots) {
      Path path = Path.of(root);
      if (Files.exists(path)) out.append("[PREFERRED] ").append(path).append('\n');
    }
    for (Path root : FileSystems.getDefault().getRootDirectories()) {
      out.append("[ROOT] ").append(root.toAbsolutePath().normalize()).append('\n');
    }
    return out.toString();
  }

  private String list(List<String> args) throws IOException {
    Path dir = path(require(args, 0, "directory path"));
    if (!Files.isDirectory(dir)) return "FILE_TOOL_ERROR: not a directory: " + dir;
    StringBuilder out = new StringBuilder("FILE_TOOL_RESULT list ").append(dir).append('\n');
    int count = 0;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      List<Path> entries = new ArrayList<>();
      for (Path entry : stream) entries.add(entry);
      entries.sort(Comparator.comparing(p -> String.valueOf(p.getFileName()), String.CASE_INSENSITIVE_ORDER));
      for (Path entry : entries) {
        if (count++ >= 500) {
          out.append("[TRUNCATED: more than 500 entries]\n");
          break;
        }
        out.append(Files.isDirectory(entry) ? "[DIR] " : "[FILE] ")
            .append(entry.toAbsolutePath().normalize()).append('\n');
      }
    }
    if (count == 0) out.append("Directory is empty.\n");
    return out.toString();
  }

  private String search(List<String> args) {
    // Preferred agent form: search|path|name. Keep search|query for compatibility.
    Path root = null;
    String query;
    if (args.size() >= 2 && !args.get(1).isBlank()) {
      root = path(require(args, 0, "search root"));
      query = require(args, 1, "search query");
      if (!Files.isDirectory(root)) return "FILE_TOOL_ERROR: search root is not a directory: " + root;
    } else {
      query = require(args, 0, "search query");
    }
    List<FileSearchResult> found = root == null
        ? search.searchPrioritized(preferredRoots, query, 30)
        : search.searchIn(root, query, 30);
    StringBuilder out = new StringBuilder("FILE_TOOL_RESULT search");
    if (root != null) out.append(" in ").append(root);
    out.append('\n');
    if (found.isEmpty()) return out.append("No matching files or folders found. Do not invent paths.").toString();
    for (FileSearchResult f : found) out.append(f.directory() ? "[DIR] " : "[FILE] ").append(f.path()).append('\n');
    return out.toString();
  }

  /** OpenCode-style path glob below an explicit root. It never reads file contents. */
  private String glob(List<String> args) throws IOException {
    Path root = path(require(args, 0, "glob root"));
    String pattern = require(args, 1, "glob pattern").replace('\\', '/');
    if (!Files.isDirectory(root)) return "FILE_TOOL_ERROR: glob root is not a directory: " + root;
    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    StringBuilder out = new StringBuilder("FILE_TOOL_RESULT glob ").append(pattern)
        .append(" in ").append(root).append('\n');
    int matches = 0;
    int visited = 0;
    try (var stream = Files.walk(root, 20)) {
      Iterator<Path> it = stream.iterator();
      while (it.hasNext() && visited++ < 120_000 && matches < 100) {
        Path item = it.next();
        if (item.equals(root)) continue;
        Path relative = root.relativize(item);
        String unix = relative.toString().replace('\\', '/');
        Path matchPath = Path.of(unix);
        if (matcher.matches(matchPath) || matcher.matches(item.getFileName())) {
          out.append(Files.isDirectory(item) ? "[DIR] " : "[FILE] ")
              .append(item.toAbsolutePath().normalize()).append('\n');
          matches++;
        }
      }
    }
    if (matches == 0) out.append("No matching paths found.\n");
    if (matches >= 100) out.append("[TRUNCATED: 100 matches]\n");
    return out.toString();
  }

  private String grep(List<String> args) throws IOException {
    Path target = path(require(args, 0, "path"));
    String expression = require(args, 1, "regular expression");
    if (!Files.exists(target)) return "FILE_TOOL_ERROR: path does not exist: " + target;
    StringBuilder out = new StringBuilder("FILE_TOOL_RESULT grep ").append(target).append('\n');
    java.util.regex.Pattern pattern;
    try {
      pattern = java.util.regex.Pattern.compile(expression,
          java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    } catch (java.util.regex.PatternSyntaxException e) {
      return "FILE_TOOL_ERROR: invalid grep regular expression: " + e.getDescription();
    }
    int[] count = {0};
    if (Files.isRegularFile(target)) grepFile(target, pattern, out, count);
    else {
      try (var stream = Files.walk(target, 10)) {
        Iterator<Path> it = stream.filter(Files::isRegularFile).iterator();
        while (it.hasNext() && count[0] < MAX_GREP_MATCHES) {
          Path p = it.next();
          if (isTextCandidate(p)) grepFile(p, pattern, out, count);
        }
      }
    }
    if (count[0] == 0) out.append("No matches found.\n");
    return out.toString();
  }

  private void grepFile(Path file, java.util.regex.Pattern pattern, StringBuilder out, int[] count) {
    try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
      Iterator<String> iterator = lines.iterator();
      int line = 0;
      while (iterator.hasNext() && count[0] < MAX_GREP_MATCHES) {
        String value = iterator.next();
        line++;
        if (pattern.matcher(value).find()) {
          out.append(file).append(':').append(line).append(": ").append(value).append('\n');
          count[0]++;
        }
      }
    } catch (IOException | RuntimeException ignored) {}
  }

  private String read(List<String> args) throws IOException {
    Path p = path(require(args, 0, "path"));
    if (!Files.isRegularFile(p)) return "FILE_TOOL_ERROR: not a readable file: " + p;
    int offset = positiveInt(args, 1, 1);
    int limit = Math.min(2000, positiveInt(args, 2, 400));
    StringBuilder out = new StringBuilder("FILE_TOOL_RESULT read ").append(p)
        .append(" lines ").append(offset).append("-").append(offset + limit - 1).append('\n');
    int chars = 0;
    int emitted = 0;
    int line = 0;
    try (var lines = Files.lines(p, StandardCharsets.UTF_8)) {
      Iterator<String> iterator = lines.iterator();
      while (iterator.hasNext() && emitted < limit && chars < MAX_READ_CHARS) {
        String value = iterator.next();
        line++;
        if (line < offset) continue;
        String numbered = line + ": " + value + '\n';
        if (chars + numbered.length() > MAX_READ_CHARS) break;
        out.append(numbered);
        chars += numbered.length();
        emitted++;
      }
    }
    if (emitted == limit || chars >= MAX_READ_CHARS) out.append("[TRUNCATED: request the next offset to continue]\n");
    if (emitted == 0) out.append("[NO LINES AT THIS OFFSET]\n");
    return out.toString();
  }

  private String write(List<String> args) throws IOException {
    Path p = path(require(args, 0, "path"));
    String content = require(args, 1, "content");
    Path parent = p.getParent();
    if (parent != null) Files.createDirectories(parent);
    backup(p);
    Files.writeString(p, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    Path actual = p.toAbsolutePath().normalize();
    if (!Files.isRegularFile(actual))
      return "FILE_TOOL_ERROR: write returned but file was not found after creation: " + actual;
    return "FILE_TOOL_RESULT write\nCreated/Written: " + actual
        + "\nVerified: true\nSize: " + Files.size(actual) + " bytes"
        + "\nContent:\n" + content;
  }

  private String edit(List<String> args) throws IOException {
    Path p = path(require(args, 0, "path"));
    String oldText = require(args, 1, "old text");
    String newText = require(args, 2, "new text");
    if (!Files.isRegularFile(p)) return "FILE_TOOL_ERROR: not a file: " + p;
    String original = Files.readString(p, StandardCharsets.UTF_8);
    int first = original.indexOf(oldText);
    if (first < 0) return "FILE_TOOL_ERROR: old text was not found in " + p;
    if (original.indexOf(oldText, first + oldText.length()) >= 0)
      return "FILE_TOOL_ERROR: old text occurs more than once; provide a more specific edit.";
    backup(p);
    String changed = original.substring(0, first) + newText + original.substring(first + oldText.length());
    Files.writeString(p, changed, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
    return "FILE_TOOL_RESULT edit\nEdited: " + p;
  }

  /** Focused exact replacement; named patch to match the higher-level agent vocabulary. */
  private String patch(List<String> args) throws IOException {
    String result = edit(args);
    return result.replaceFirst("FILE_TOOL_RESULT edit", "FILE_TOOL_RESULT patch");
  }

  private static void backup(Path p) throws IOException {
    if (!Files.exists(p) || !Files.isRegularFile(p)) return;
    Path backup = p.resolveSibling(p.getFileName() + ".neuralsoul.bak");
    Files.copy(p, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
  }

  public static String resolvePath(String raw) {
    if (raw == null) return "";
    String value = raw.trim().replace('/', '\\');

    String envHome = System.getenv("USERPROFILE");
    String home = envHome != null && !envHome.isBlank()
        ? envHome.replace('/', '\\')
        : System.getProperty("user.home", "").replace('/', '\\');
    String appData = System.getenv("APPDATA");
    String localAppData = System.getenv("LOCALAPPDATA");
    String temp = System.getenv("TEMP");

    // NeuralSoul uses local profile folders only. No OneDrive lookup/redirection.
    String desktop = home.isBlank() ? "" : home + "\\Desktop";
    String documents = home.isBlank() ? "" : home + "\\Documents";
    String downloads = home.isBlank() ? "" : home + "\\Downloads";

    value = replaceIgnoreCase(value, "%USERPROFILE%", home);
    value = replaceIgnoreCase(value, "%APPDATA%", appData);
    value = replaceIgnoreCase(value, "%LOCALAPPDATA%", localAppData);
    value = replaceIgnoreCase(value, "%TEMP%", temp);
    value = replaceIgnoreCase(value, "%TMP%", System.getenv("TMP"));
    value = replaceIgnoreCase(value, "%DESKTOP%", desktop);
    value = replaceIgnoreCase(value, "%DOCUMENTS%", documents);
    value = replaceIgnoreCase(value, "%DOWNLOADS%", downloads);

    if (value.equals("~")) value = home;
    else if (value.startsWith("~\\")) value = home + value.substring(1);

    // IMPORTANT: never trust a username invented by the LLM.
    // Any C:\Users\<anything>\... path is rebased onto the actual current user.
    // Example:
    // C:\Users\Ваш_Имя\Документы\x.txt -> C:\Users\rocsa\Documents\x.txt
    // C:\Users\Bob\Desktop\x.txt       -> C:\Users\rocsa\Desktop\x.txt
    value = rebaseWindowsUserPath(value, home);

    // Normalize localized/common standard folder names immediately below the profile.
    value = normalizeProfileStandardFolders(value, home, desktop, documents, downloads);

    // Relative standard folders are also resolved under the actual current profile.
    value = replaceLeadingLocalFolder(value, desktop, "desktop", "рабочий стол");
    value = replaceLeadingLocalFolder(value, documents, "documents", "документы", "my documents");
    value = replaceLeadingLocalFolder(value, downloads, "downloads", "загрузки");

    return Path.of(value).toAbsolutePath().normalize().toString();
  }

  private static String rebaseWindowsUserPath(String value, String home) {
    if (home == null || home.isBlank() || value == null || value.isBlank()) return value;
    String normalized = value.replace('/', '\\');

    // Match C:\Users\<one path segment> case-insensitively.
    java.util.regex.Matcher matcher = java.util.regex.Pattern
        .compile("(?i)^([a-z]:\\\\users\\\\)([^\\\\]+)(.*)$")
        .matcher(normalized);
    if (!matcher.matches()) return value;

    String tail = matcher.group(3);
    String rebased = home + tail;
    if (!normalized.equalsIgnoreCase(rebased)) {
      System.out.println("[FileTool] rebased model user path: " + normalized + " -> " + rebased);
    }
    return rebased;
  }

  private static String normalizeProfileStandardFolders(
      String value, String home, String desktop, String documents, String downloads) {
    if (home == null || home.isBlank()) return value;
    String normalized = value.replace('/', '\\');
    String prefix = home.endsWith("\\") ? home : home + "\\";
    if (!normalized.regionMatches(true, 0, prefix, 0, prefix.length())) return value;

    String rest = normalized.substring(prefix.length());
    int slash = rest.indexOf('\\');
    String folder = slash >= 0 ? rest.substring(0, slash) : rest;
    String tail = slash >= 0 ? rest.substring(slash) : "";
    String f = folder.toLowerCase(Locale.ROOT);

    if (f.equals("desktop") || f.equals("рабочий стол")) return desktop + tail;
    if (f.equals("documents") || f.equals("документы") || f.equals("my documents")) return documents + tail;
    if (f.equals("downloads") || f.equals("загрузки")) return downloads + tail;
    return value;
  }

  private static String replaceLeadingLocalFolder(String value, String actual, String... aliases) {
    if (actual == null || actual.isBlank()) return value;
    String normalized = value.replace('/', '\\');
    String lower = normalized.toLowerCase(Locale.ROOT);
    for (String alias : aliases) {
      String a = alias.toLowerCase(Locale.ROOT);
      if (lower.equals(a)) return actual;
      if (lower.startsWith(a + "\\")) return actual + normalized.substring(alias.length());
    }
    return value;
  }

  private static String replaceIgnoreCase(String value, String token, String replacement) {
    if (replacement == null || replacement.isBlank()) return value;
    return value.replaceAll("(?i)" + java.util.regex.Pattern.quote(token),
        java.util.regex.Matcher.quoteReplacement(replacement));
  }

  private static Path path(String raw) { return Path.of(resolvePath(raw)); }

  private static int positiveInt(List<String> args, int index, int fallback) {
    if (args == null || index >= args.size() || args.get(index) == null || args.get(index).isBlank()) return fallback;
    try { return Math.max(1, Integer.parseInt(args.get(index).trim())); }
    catch (NumberFormatException ignored) { return fallback; }
  }
  private static String require(List<String> args, int i, String name) {
    if (args == null || i >= args.size() || args.get(i) == null || args.get(i).isBlank())
      throw new IllegalArgumentException("Missing " + name);
    return args.get(i);
  }
  private static boolean isTextCandidate(Path p) {
    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
    return n.endsWith(".java") || n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".json")
        || n.endsWith(".xml") || n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".properties")
        || n.endsWith(".css") || n.endsWith(".js") || n.endsWith(".ts") || n.endsWith(".py")
        || n.endsWith(".c") || n.endsWith(".cpp") || n.endsWith(".h") || n.endsWith(".hpp")
        || n.endsWith(".gradle") || n.endsWith(".pom") || n.endsWith(".log");
  }
}
