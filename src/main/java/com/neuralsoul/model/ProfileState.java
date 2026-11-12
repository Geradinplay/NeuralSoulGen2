package com.neuralsoul.model;

import com.neuralsoul.model.Profile;
import java.util.*;
import javafx.beans.property.*;
import javafx.collections.*;

/**
 * Complete persistent configuration of one NeuralSoul user profile.
 *
 * <p>This object is the single source of truth for every setting that differs between
 * Main / Roleplay / Profile 1..5. Runtime/UI properties in {@link AppState} and
 * {@link ModelAssignment} are only a projection of the currently active ProfileState.</p>
 */
public final class ProfileState {
  public final Profile id;
  public final StringProperty personalityPrompt = new SimpleStringProperty("");
  public final StringProperty generalPrompt = new SimpleStringProperty("");

  private boolean automatic = true;
  private boolean allowSwap = true;
  private boolean allowRamOffload = true;
  private final LinkedHashMap<String, Boolean> devices = new LinkedHashMap<>();
  private final LinkedHashMap<String, ModelAssignment.ProfileConfig> models = new LinkedHashMap<>();
  private final LinkedHashMap<String, BooleanProperty> fileRouteStorage = new LinkedHashMap<>();
  private final EnumMap<ModelRole, StringProperty> agentCallTemplates = new EnumMap<>(ModelRole.class);
  /** GENERAL is model-scoped: every GENERAL model has its own call syntax. */
  private final LinkedHashMap<String, StringProperty> generalAgentCallTemplates = new LinkedHashMap<>();
  /** Per WEB_SEARCH model: which model judges fetched evidence. Special value MAIN_LLM reuses :8080. */
  private final LinkedHashMap<String, StringProperty> webReasoningModels = new LinkedHashMap<>();
  /** Saved File Search routes belonging only to this profile. */
  private final ObservableList<String> fileRoutes = FXCollections.observableArrayList();
  private final MemoryConfig memory;

  public record MemoryConfig(String fileName, String qdrantCollection, String semanticCollection) {}

  public ProfileState(Profile id) {
    this.id = Objects.requireNonNull(id);
    for (ModelRole role : callTemplateRoles()) {
      agentCallTemplates.put(role, new SimpleStringProperty(defaultAgentCallTemplate(role)));
    }
    String key = id.key();
    this.memory = id == Profile.NORMAL
        ? new MemoryConfig("memory.jsonl", "neuralsoul_memory", "neuralsoul_memory_bge_m3")
        : new MemoryConfig("memory-" + key + ".jsonl",
            "neuralsoul_memory_" + key, "neuralsoul_memory_" + key + "_bge_m3");
  }

  public String label() { return id.label(); }
  public String key() { return id.key(); }

  public boolean automatic() { return automatic; }
  public boolean allowSwap() { return allowSwap; }
  public boolean allowRamOffload() { return allowRamOffload; }
  public Map<String, Boolean> devices() { return Collections.unmodifiableMap(devices); }

  public void setResources(boolean automatic, boolean allowSwap, boolean allowRamOffload,
                           Map<String, Boolean> deviceStates) {
    this.automatic = automatic;
    this.allowSwap = allowSwap;
    this.allowRamOffload = allowRamOffload;
    devices.clear();
    if (deviceStates != null) devices.putAll(deviceStates);
  }

  public ModelAssignment.ProfileConfig model(String fileName) {
    return models.get(normalize(fileName));
  }

  public void setModel(String fileName, ModelAssignment.ProfileConfig config) {
    if (fileName == null || config == null) return;
    models.put(normalize(fileName), config);
  }

  public boolean hasModel(String fileName) {
    return models.containsKey(normalize(fileName));
  }

  public void removeMissingModels(Collection<String> existingFileNames) {
    Set<String> normalized = new LinkedHashSet<>();
    if (existingFileNames != null)
      for (String name : existingFileNames) normalized.add(normalize(name));
    models.keySet().retainAll(normalized);
    fileRouteStorage.keySet().retainAll(normalized);
    generalAgentCallTemplates.keySet().retainAll(normalized);
    webReasoningModels.keySet().retainAll(normalized);
  }

  /** Per-model File Search option for prioritizing routes saved on the Files page. */
  public BooleanProperty fileRouteStorage(String fileName) {
    return fileRouteStorage.computeIfAbsent(normalize(fileName), k -> new SimpleBooleanProperty(false));
  }

  public boolean fileRouteStorageEnabled(String fileName) {
    BooleanProperty value = fileRouteStorage.get(normalize(fileName));
    return value != null && value.get();
  }

  public Map<String, ModelAssignment.ProfileConfig> modelConfigs() {
    return Collections.unmodifiableMap(models);
  }

  /** Persistent saved files/directories used as priority File Search routes for this profile. */
  public ObservableList<String> fileRoutes() {
    return fileRoutes;
  }


  public StringProperty generalAgentCallTemplate(String fileName, String displayName) {
    String key = normalize(fileName);
    return generalAgentCallTemplates.computeIfAbsent(
        key, k -> new SimpleStringProperty(defaultGeneralAgentCallTemplate(fileName, displayName)));
  }

  public Map<String, String> generalAgentCallTemplatesSnapshot() {
    LinkedHashMap<String, String> out = new LinkedHashMap<>();
    generalAgentCallTemplates.forEach((k, v) -> out.put(k, v.get()));
    return Collections.unmodifiableMap(out);
  }

  public static final String MAIN_LLM_REASONING = "MAIN_LLM";

  /** Stable selection used by a WEB_SEARCH model for evidence judging. */
  public StringProperty webReasoningModel(String webModelFileName) {
    return webReasoningModels.computeIfAbsent(
        normalize(webModelFileName), k -> new SimpleStringProperty(MAIN_LLM_REASONING));
  }

  public String webReasoningModelValue(String webModelFileName) {
    String value = webReasoningModel(webModelFileName).get();
    return value == null || value.isBlank() ? MAIN_LLM_REASONING : value;
  }

  public static String defaultGeneralAgentCallTemplate(String fileName, String displayName) {
    String base = displayName == null || displayName.isBlank() ? fileName : displayName;
    if (base == null || base.isBlank()) base = "general";
    String alias = base.toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "_")
        .replaceAll("^_+|_+$", "");
    if (alias.isBlank()) alias = "general";
    String normalized = normalize(fileName);
    String hash = Integer.toUnsignedString(normalized.hashCode(), 36);
    return "[[AGENT:GENERAL:" + alias + "_" + hash + "|{task}]]";
  }

  public StringProperty agentCallTemplate(ModelRole role) {
    if (role == null || !callTemplateRoles().contains(role))
      throw new IllegalArgumentException("Role does not support a call template: " + role);
    return agentCallTemplates.computeIfAbsent(role, r -> new SimpleStringProperty(defaultAgentCallTemplate(r)));
  }

  public Map<ModelRole, String> agentCallTemplatesSnapshot() {
    EnumMap<ModelRole, String> out = new EnumMap<>(ModelRole.class);
    for (ModelRole role : callTemplateRoles()) out.put(role, agentCallTemplate(role).get());
    return Collections.unmodifiableMap(out);
  }

  public static Set<ModelRole> agentRoles() {
    return EnumSet.of(ModelRole.REASONING, ModelRole.CODING, ModelRole.WEB_SEARCH,
        ModelRole.COMPUTER_USE);
  }

  /** Every Models-page role that can be invoked from a prompt. MAIN_LLM is not callable. */
  public static Set<ModelRole> callTemplateRoles() {
    return EnumSet.of(ModelRole.CODING, ModelRole.WEB_SEARCH, ModelRole.REASONING,
        ModelRole.COMPUTER_USE, ModelRole.MEMORY, ModelRole.FILE_SEARCH);
  }

  public static String defaultAgentCallTemplate(ModelRole role) {
    if (role == null) throw new IllegalArgumentException("role");
    return switch (role) {
      case MEMORY -> "[[MEMORY_RECALL:{task}]]";
      case FILE_SEARCH -> "[[FILE_SEARCH:{task}]]";
      case MAIN_LLM -> throw new IllegalArgumentException("MAIN_LLM is not a callable role");
      case GENERAL -> throw new IllegalArgumentException("GENERAL call template is model-scoped");
      default -> "[[AGENT:" + role.name() + "|{task}]]";
    };
  }

  /** Stable per-profile memory storage/collection identity. */
  public String memoryKey() { return id.key(); }
  public MemoryConfig memory() { return memory; }

  private static String normalize(String value) {
    return value == null ? "" : value.replace('\\', '/');
  }
}
