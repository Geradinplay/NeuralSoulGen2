package com.neuralsoul.model;

import java.util.*;
import javafx.beans.property.*;
import javafx.collections.*;

/** Physical model discovered on disk plus the live properties currently shown by the UI/runtime. */
public final class ModelAssignment {
  public final String name, details, icon, fileName;
  public final boolean memoryOnly;
  /** True only for GGUF files physically discovered under model/web_search. */
  public final boolean webSearchOnly;

  /** Excludes dedicated memory and web models from general LLM selectors. */
  public boolean generalModel() { return !memoryOnly && !webSearchOnly; }

  public final BooleanProperty enabled = new SimpleBooleanProperty(true);
  public final StringProperty device;
  public final IntegerProperty ctxSize = new SimpleIntegerProperty(4096);
  /** Maximum completion length for one generation request. Stored per physical model/profile. */
  public final IntegerProperty maxOutputTokens = new SimpleIntegerProperty(1024);
  /** Maximum filesystem entries File Search may inspect before stopping. */
  public final IntegerProperty fileSearchMaxVisited = new SimpleIntegerProperty(120_000);
  public final IntegerProperty parallel = new SimpleIntegerProperty(1);
  public final IntegerProperty batchSize = new SimpleIntegerProperty(512);
  public final IntegerProperty ubatchSize = new SimpleIntegerProperty(128);
  public final StringProperty cacheTypeK = new SimpleStringProperty("q8_0");
  public final StringProperty cacheTypeV = new SimpleStringProperty("q8_0");
  public final StringProperty isolation = new SimpleStringProperty("Нет");
  public final ObservableSet<ModelRole> roles = FXCollections.observableSet();
  public final ObservableList<LoraSetting> loras = FXCollections.observableArrayList();
  private final Map<String, BooleanProperty> placements = new LinkedHashMap<>();

  public record LoraConfig(boolean enabled, double scale) {}

  public record ProfileConfig(boolean enabled, String device, int ctxSize, int maxOutputTokens, int fileSearchMaxVisited, int parallel,
                              int batchSize, int ubatchSize, String cacheTypeK, String cacheTypeV,
                              String isolation, Set<ModelRole> roles, Map<String, Boolean> placements,
                              Map<String, LoraConfig> loras) {}

  public ModelAssignment(String name, String details, String icon, String device) {
    this(name, details, icon, device, extractGguf(details), false);
  }

  public ModelAssignment(String name, String details, String icon, String device, String fileName) {
    this(name, details, icon, device, fileName, false, false);
  }

  public ModelAssignment(String name, String details, String icon, String device, String fileName, boolean memoryOnly) {
    this(name, details, icon, device, fileName, memoryOnly, false);
  }

  public ModelAssignment(String name, String details, String icon, String device, String fileName,
                         boolean memoryOnly, boolean webSearchOnly) {
    if (memoryOnly && webSearchOnly) throw new IllegalArgumentException("conflicting model categories");
    this.name = name;
    this.details = details;
    this.icon = icon;
    this.device = new SimpleStringProperty(device);
    this.fileName = fileName;
    this.memoryOnly = memoryOnly;
    this.webSearchOnly = webSearchOnly;
    if (memoryOnly || webSearchOnly) {
      // Dedicated GGUF files are discovered but require an explicit role assignment.
      enabled.set(false);
    } else {
      roles.add(ModelRole.MAIN_LLM);
    }
  }

  /** Dedicated roles are reserved for GGUF files in their respective folders. */
  public void setRole(ModelRole role, boolean enabled) {
    if (!allowedRole(role)) return;
    if (enabled) roles.add(role);
    else roles.remove(role);
  }

  private boolean allowedRole(ModelRole role) {
    if (role == null) return false;
    if (memoryOnly) return role == ModelRole.MEMORY || role == ModelRole.FILE_SEARCH;
    if (webSearchOnly) return role == ModelRole.WEB_SEARCH;
    return role != ModelRole.MEMORY && role != ModelRole.FILE_SEARCH
        && role != ModelRole.WEB_SEARCH;
  }

  public BooleanProperty placement(String deviceId) {
    return placements.computeIfAbsent(deviceId, id -> new SimpleBooleanProperty(false));
  }

  public Set<String> placementIds() {
    return Collections.unmodifiableSet(placements.keySet());
  }

  public void retainDevices(Collection<String> deviceIds) {
    placements.keySet().retainAll(deviceIds);
  }

  /** Captures the live editor/runtime view. AppState stores this inside the active ProfileState. */
  public ProfileConfig snapshotProfile() {
    Map<String, Boolean> p = new LinkedHashMap<>();
    placements.forEach((id, value) -> p.put(id, value.get()));
    Map<String, LoraConfig> l = new LinkedHashMap<>();
    for (LoraSetting adapter : loras)
      l.put(adapter.relativePath, new LoraConfig(adapter.enabled.get(), adapter.scale.get()));
    return new ProfileConfig(enabled.get(), device.get(), ctxSize.get(), maxOutputTokens.get(), fileSearchMaxVisited.get(), parallel.get(),
        batchSize.get(), ubatchSize.get(), cacheTypeK.get(), cacheTypeV.get(), isolation.get(),
        Set.copyOf(roles), p, l);
  }

  /** Applies one profile's model configuration to the live JavaFX properties. */
  public void applyProfile(ProfileConfig c) {
    if (c == null) return;
    enabled.set(c.enabled());
    device.set(c.device());
    ctxSize.set(c.ctxSize());
    maxOutputTokens.set(c.maxOutputTokens());
    fileSearchMaxVisited.set(c.fileSearchMaxVisited());
    parallel.set(c.parallel());
    batchSize.set(c.batchSize());
    ubatchSize.set(c.ubatchSize());
    cacheTypeK.set(c.cacheTypeK());
    cacheTypeV.set(c.cacheTypeV());
    isolation.set(c.isolation());
    roles.clear();
    if (c.roles() != null) {
      for (ModelRole role : c.roles()) if (allowedRole(role)) roles.add(role);
    }
    for (String id : placements.keySet()) placement(id).set(false);
    c.placements().forEach((id, value) -> placement(id).set(value));
    for (LoraSetting adapter : loras) {
      LoraConfig cfg = c.loras().get(adapter.relativePath);
      adapter.enabled.set(cfg != null && cfg.enabled());
      adapter.scale.set(cfg == null ? 1.0 : cfg.scale());
    }
  }

  public void refreshLoras(java.nio.file.Path modelRoot) {
    if (memoryOnly || webSearchOnly) { loras.clear(); return; }
    Map<String, LoraSetting> old = new LinkedHashMap<>();
    for (LoraSetting l : loras) old.put(l.relativePath, l);
    java.nio.file.Path modelFile = modelRoot.resolve(fileName).normalize();
    java.nio.file.Path dir = modelFile.getParent();
    if (dir == null) { loras.clear(); return; }
    java.nio.file.Path loraRoot = dir.resolve("lora");
    List<LoraSetting> found = new ArrayList<>();
    if (java.nio.file.Files.isDirectory(loraRoot)) {
      try (var stream = java.nio.file.Files.walk(loraRoot)) {
        stream.filter(java.nio.file.Files::isRegularFile)
            .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf"))
            .sorted()
            .forEach(f -> {
              String relative = loraRoot.relativize(f).toString().replace('\\', '/');
              LoraSetting setting = old.get(relative);
              if (setting == null) setting = new LoraSetting(f.getFileName().toString(), relative);
              found.add(setting);
            });
      } catch (Exception e) {
        System.out.println("[LoRA] Scan failed for " + loraRoot + ": " + e);
      }
    }
    loras.setAll(found);
  }

  private static String extractGguf(String details) {
    if (details == null) return "";
    int end = details.toLowerCase(Locale.ROOT).indexOf(".gguf");
    if (end < 0) return "";
    int start = end;
    while (start > 0 && !Character.isWhitespace(details.charAt(start - 1))) start--;
    return details.substring(start, end + 5);
  }
}
