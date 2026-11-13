package com.neuralsoul.files;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileToolServiceTest {
  @TempDir Path temp;

  @Test void globAndRegexGrepUseDeterministicFilesystemTools() throws Exception {
    Path source = temp.resolve("src/demo/Example.java");
    Files.createDirectories(source.getParent());
    Files.writeString(source, "class Example {\n  int timeout = 30;\n}\n", StandardCharsets.UTF_8);
    FileToolService tools = new FileToolService();

    String glob = tools.execute("glob", List.of(temp.toString(), "**/*.java"));
    assertTrue(glob.contains(source.toAbsolutePath().normalize().toString()));

    String grep = tools.execute("grep", List.of(temp.toString(), "timeout\\s*=\\s*\\d+"));
    assertTrue(grep.contains("Example.java:2"));
  }

  @Test void readSupportsLineOffsetAndLimit() throws Exception {
    Path file = temp.resolve("notes.txt");
    Files.writeString(file, "one\ntwo\nthree\nfour\n", StandardCharsets.UTF_8);

    String result = new FileToolService().execute("read", List.of(file.toString(), "2", "2"));

    assertTrue(result.contains("2: two"));
    assertTrue(result.contains("3: three"));
    assertFalse(result.contains("1: one"));
    assertFalse(result.contains("4: four"));
  }

  @Test void searchChecksSavedRoutesBeforeGlobalRoots() throws Exception {
    Path preferred = temp.resolve("saved-project");
    Path target = preferred.resolve("UniqueSavedRouteFile.java");
    Files.createDirectories(preferred);
    Files.writeString(target, "class UniqueSavedRouteFile {}", StandardCharsets.UTF_8);
    FileToolService tools = new FileToolService();
    tools.setPreferredRoots(List.of(preferred.toString()));

    String roots = tools.execute("roots", List.of());
    String result = tools.execute("search", List.of("UniqueSavedRouteFile"));

    assertTrue(roots.contains("[PREFERRED] " + preferred.toAbsolutePath().normalize()));
    assertTrue(result.contains(target.toAbsolutePath().normalize().toString()));
  }

  @Test void patchIsExactAndCreatesBackup() throws Exception {
    Path file = temp.resolve("config.txt");
    Files.writeString(file, "timeout=60\n", StandardCharsets.UTF_8);

    String result = new FileToolService().execute(
        "patch", List.of(file.toString(), "timeout=60", "timeout=30"));

    assertTrue(result.startsWith("FILE_TOOL_RESULT patch"));
    assertEquals("timeout=30\n", Files.readString(file));
    assertEquals("timeout=60\n", Files.readString(temp.resolve("config.txt.neuralsoul.bak")));
  }
}
