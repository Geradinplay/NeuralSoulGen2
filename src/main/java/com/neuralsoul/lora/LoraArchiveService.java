package com.neuralsoul.lora;

import com.github.junrar.Junrar;
import com.github.junrar.exception.RarException;
import com.neuralsoul.model.ModelAssignment;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Imports LoRA archives next to the selected model without hard-coded absolute paths. */
public final class LoraArchiveService {
  private final Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

  public Path loraDirectory(ModelAssignment model) throws IOException {
    Path modelFile = projectRoot.resolve("model").resolve(model.fileName).normalize();
    Path modelsRoot = projectRoot.resolve("model").normalize();
    if (!modelFile.startsWith(modelsRoot)) throw new IOException("Некорректный путь модели: " + model.fileName);
    Path parent = modelFile.getParent();
    if (parent == null) throw new IOException("Не удалось определить папку модели");
    return parent.resolve("lora").normalize();
  }

  public Path importArchive(ModelAssignment model, Path archive) throws IOException {
    if (model == null || !model.generalModel()) throw new IOException("Выбери основную LLM модель");
    if (archive == null || !Files.isRegularFile(archive)) throw new FileNotFoundException("Архив LoRA не найден");

    String fileName = archive.getFileName().toString();
    String lower = fileName.toLowerCase(Locale.ROOT);
    if (!lower.endsWith(".zip") && !lower.endsWith(".rar"))
      throw new IOException("Поддерживаются только ZIP и RAR");

    String folderName = fileName.replaceFirst("(?i)\\.(zip|rar)$", "").replaceAll("[^\\p{L}\\p{N}._ -]", "_").trim();
    if (folderName.isBlank()) folderName = "lora";

    Path root = loraDirectory(model);
    Files.createDirectories(root);
    Path target = uniqueDirectory(root, folderName);
    Files.createDirectories(target);

    try {
      if (lower.endsWith(".zip")) extractZip(archive, target);
      else {
        try {
          Junrar.extract(archive.toFile(), target.toFile());
        } catch (RarException e) {
          throw new IOException("Не удалось распаковать RAR-архив: " + archive.getFileName(), e);
        }
        verifyTreeInside(target);
      }
      return target;
    } catch (IOException | RuntimeException e) {
      deleteQuietly(target);
      throw e;
    }
  }

  private void extractZip(Path archive, Path target) throws IOException {
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(archive))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        Path out = safeResolve(target, entry.getName());
        if (entry.isDirectory()) Files.createDirectories(out);
        else {
          Path parent = out.getParent();
          if (parent != null) Files.createDirectories(parent);
          Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
        in.closeEntry();
      }
    }
  }

  private Path safeResolve(Path target, String entry) throws IOException {
    Path out = target.resolve(entry).normalize();
    if (!out.startsWith(target)) throw new IOException("Архив пытается записать файл за пределы папки LoRA: " + entry);
    return out;
  }

  private void verifyTreeInside(Path target) throws IOException {
    try (var stream = Files.walk(target)) {
      for (Path p : stream.toList()) {
        if (!p.normalize().startsWith(target)) throw new IOException("RAR содержит небезопасный путь: " + p);
      }
    }
  }

  private Path uniqueDirectory(Path root, String name) {
    Path candidate = root.resolve(name);
    int n = 2;
    while (Files.exists(candidate)) candidate = root.resolve(name + "-" + n++);
    return candidate;
  }

  private void deleteQuietly(Path root) {
    try {
      if (!Files.exists(root)) return;
      try (var stream = Files.walk(root)) {
        for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
      }
    } catch (Exception ignored) {}
  }
}
