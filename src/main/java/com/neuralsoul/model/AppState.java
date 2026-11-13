package com.neuralsoul.model;

import com.neuralsoul.hardware.*;
import com.neuralsoul.memory.MemorySettings;
import com.neuralsoul.model.Profile;
import com.neuralsoul.web.WebSearchRecord;
import java.util.*;
import javafx.beans.property.*;
import javafx.collections.*;

public final class AppState {
  private final Map<String, StringProperty> generalSystemPrompts = new LinkedHashMap<>();
  public StringProperty generalAgentSystemPrompt(String modelKey) {
    return generalSystemPrompts.computeIfAbsent(modelKey.replace('\\', '/'),
        k -> new SimpleStringProperty(agentPrompt(ModelRole.GENERAL).get()));
  }
  public Map<String, String> generalAgentSystemPromptsSnapshot() {
    Map<String, String> result = new LinkedHashMap<>();
    generalSystemPrompts.forEach((k, v) -> result.put(k, v.get()));
    return Map.copyOf(result);
  }

  private final java.util.EnumMap<ModelRole, javafx.beans.property.StringProperty> agentPrompts = new java.util.EnumMap<>(ModelRole.class);
  public javafx.beans.property.StringProperty agentPrompt(ModelRole role) {
    return agentPrompts.computeIfAbsent(role, r -> new javafx.beans.property.SimpleStringProperty(com.neuralsoul.config.AgentPrompts.defaultPrompt(r)));
  }
  public java.util.Map<ModelRole, String> agentPromptsSnapshot() {
    java.util.Map<ModelRole, String> result = new java.util.EnumMap<>(ModelRole.class);
    for (ModelRole role : com.neuralsoul.config.AgentPrompts.roles()) result.put(role, agentPrompt(role).get());
    return java.util.Map.copyOf(result);
  }

  public final ObjectProperty<AgentType> activeAgent = new SimpleObjectProperty<>(AgentType.IDLE);
  public final MemorySettings memorySettings = new MemorySettings();
  public final ObservableList<Device> devices = FXCollections.observableArrayList();
  public final ObservableList<ModelAssignment> models = FXCollections.observableArrayList();

  /** Single source of truth for all profile-specific configuration. */
  private final EnumMap<Profile, ProfileState> profiles = new EnumMap<>(Profile.class);
  public final ObjectProperty<Profile> activeProfile = new SimpleObjectProperty<>(Profile.NORMAL);

  public final StringProperty memoryAgentPrompt =
      new SimpleStringProperty(com.neuralsoul.pipeline.GenerationPipeline.DEFAULT_MEMORY_AGENT_PROMPT);
  public final ObservableMap<String, String> agentPromptModules = FXCollections.observableHashMap();

  // Live projection of the active profile's resource settings.
  public final BooleanProperty automatic = new SimpleBooleanProperty(true),
      allowSwap = new SimpleBooleanProperty(true),
      allowRamOffload = new SimpleBooleanProperty(true),
      voice = new SimpleBooleanProperty(false),
      alwaysListen = new SimpleBooleanProperty(false),
      microphoneMonitor = new SimpleBooleanProperty(false),
      busy = new SimpleBooleanProperty(false),
      systemStarting = new SimpleBooleanProperty(false),
      systemRunning = new SimpleBooleanProperty(false);

  public final DoubleProperty vram = new SimpleDoubleProperty(24),
      ram = new SimpleDoubleProperty(48),
      threads = new SimpleDoubleProperty(8);

  public final StringProperty mode = new SimpleStringProperty("Обычный"),
      selectedModel = new SimpleStringProperty(""),
      status = new SimpleStringProperty("Локальные сервисы · настрой адреса API");

  /** How continuous voice behaves when a response is already being generated. */
  public final ObjectProperty<VoiceInputMode> voiceInputMode =
      new SimpleObjectProperty<>(VoiceInputMode.INTERRUPT);
  /** Approval policy for built-in deterministic filesystem tools. */
  public final ObjectProperty<FileToolApprovalMode> fileToolApprovalMode =
      new SimpleObjectProperty<>(FileToolApprovalMode.MANUAL);
  /** Number of recognized voice phrases waiting for the current response to finish. */
  public final IntegerProperty voiceBufferSize = new SimpleIntegerProperty(0);

  /** Development mode: pending plan text waiting for approve/edit/reject in the chat UI, or null. */
  public final ObjectProperty<String> pendingPlan = new javafx.beans.property.SimpleObjectProperty<>(null);
  /** Wired by AppController to pipeline.resolvePlan(generationId, decision). */
  public volatile java.util.function.Consumer<com.neuralsoul.pipeline.GenerationPipeline.PlanDecision> planDecisionHandler;

  public final BooleanProperty recording = new SimpleBooleanProperty(false);
  public final StringProperty voiceListenerStatus = new SimpleStringProperty("Выключено");
  public final StringProperty llmStatus = new SimpleStringProperty("Отключён"),
      sttStatus = new SimpleStringProperty("Отключён"),
      ttsStatus = new SimpleStringProperty("Отключён");

  public final ObservableList<String> availableModels = FXCollections.observableArrayList();
  public final ObservableList<ChatMessage> messages = FXCollections.observableArrayList();
  /** Persistent WEB_SEARCH journal loaded from web-search-history.json and updated live. */
  public final ObservableList<WebSearchRecord> webSearches = FXCollections.observableArrayList();
  public final ObservableList<String> tasks = FXCollections.observableArrayList();
  public final ObservableList<String> memories = FXCollections.observableArrayList();

  public AppState() {
    for (Profile p : Profile.values()) profiles.put(p, new ProfileState(p));

    refreshHardwareDevices();
    seedResourceDefaults();
    refreshModelFiles();
    snapshotActiveProfile(Profile.NORMAL);

    activeProfile.addListener((obs, oldProfile, newProfile) -> {
      if (newProfile == null || newProfile == oldProfile) return;
      snapshotActiveProfile(oldProfile == null ? Profile.NORMAL : oldProfile);
      applyProfile(newProfile);
    });
  }

  public ProfileState profile(Profile profile) {
    return profiles.get(Objects.requireNonNull(profile));
  }

  public ProfileState activeProfileState() {
    Profile p = activeProfile.get();
    return profile(p == null ? Profile.NORMAL : p);
  }

  public Collection<ProfileState> profiles() {
    return Collections.unmodifiableCollection(profiles.values());
  }

  /** Saved File Search routes of the selected profile. */
  public ObservableList<String> fileRoutes(Profile profile) {
    Profile p = profile == null ? Profile.NORMAL : profile;
    return this.profile(p).fileRoutes();
  }

  public ObservableList<String> activeFileRoutes() {
    return fileRoutes(activeProfile.get());
  }

  private void seedResourceDefaults() {
    Map<String, Boolean> enabledDevices = currentDeviceStates();
    for (ProfileState p : profiles.values())
      p.setResources(automatic.get(), allowSwap.get(), allowRamOffload.get(), enabledDevices);
  }

  private Map<String, Boolean> currentDeviceStates() {
    Map<String, Boolean> result = new LinkedHashMap<>();
    for (Device d : devices) result.put(d.id, d.enabled.get());
    return result;
  }

  /** Saves the live UI/runtime projection into the selected profile object. */
  public void snapshotActiveProfile(Profile target) {
    if (target == null) return;
    ProfileState p = profile(target);
    p.setResources(automatic.get(), allowSwap.get(), allowRamOffload.get(), currentDeviceStates());
    for (ModelAssignment model : models) p.setModel(model.fileName, model.snapshotProfile());
  }

  /** Applies one complete profile object to the live UI/runtime properties. */
  public void applyProfile(Profile target) {
    if (target == null) return;
    ProfileState p = profile(target);
    automatic.set(p.automatic());
    allowSwap.set(p.allowSwap());
    allowRamOffload.set(p.allowRamOffload());
    for (Device d : devices) d.enabled.set(p.devices().getOrDefault(d.id, true));

    ProfileState main = profile(Profile.NORMAL);
    for (ModelAssignment model : models) {
      ModelAssignment.ProfileConfig cfg = p.model(model.fileName);
      if (cfg == null) cfg = main.model(model.fileName);
      if (cfg == null) cfg = model.snapshotProfile();
      model.applyProfile(cfg);
    }
  }

  // Compatibility API retained for existing pages/store while ProfileState owns the data.
  public record ResourceProfileConfig(boolean automatic, boolean allowSwap, boolean allowRamOffload,
                                      Map<String, Boolean> devices) {}

  public ResourceProfileConfig snapshotResourceProfile() {
    return new ResourceProfileConfig(automatic.get(), allowSwap.get(), allowRamOffload.get(), currentDeviceStates());
  }

  public ResourceProfileConfig resourceProfileConfig(Profile profile) {
    if (profile == activeProfile.get()) snapshotActiveProfile(profile);
    ProfileState p = profile(profile);
    return new ResourceProfileConfig(p.automatic(), p.allowSwap(), p.allowRamOffload(), p.devices());
  }

  public void setResourceProfileConfig(Profile profile, ResourceProfileConfig cfg) {
    ProfileState p = profile(profile);
    p.setResources(cfg.automatic(), cfg.allowSwap(), cfg.allowRamOffload(), cfg.devices());
    if (profile == activeProfile.get()) applyProfile(profile);
  }

  public void switchResourceProfile(Profile profile) {
    if (profile == null) return;
    if (profile != activeProfile.get()) activeProfile.set(profile);
    else applyProfile(profile);
  }

  public void applyResourceProfile(Profile profile) {
    if (profile == null) return;
    ProfileState p = profile(profile);
    automatic.set(p.automatic());
    allowSwap.set(p.allowSwap());
    allowRamOffload.set(p.allowRamOffload());
    for (Device d : devices) d.enabled.set(p.devices().getOrDefault(d.id, true));
  }

  /** Scans model recursively; dedicated GGUF search roles require dedicated folders. */
  public void refreshModelFiles() {
    Profile active = activeProfile.get() == null ? Profile.NORMAL : activeProfile.get();
    if (!models.isEmpty()) snapshotActiveProfile(active);

    Map<String, ModelAssignment> old = new LinkedHashMap<>();
    for (ModelAssignment m : models) old.put(m.fileName.replace('\\', '/'), m);

    List<ModelAssignment> found = new ArrayList<>();
    // Relative to the application's working directory: no drive letters or
    // machine-specific paths are stored for either regular or memory models.
    java.nio.file.Path root = java.nio.file.Path.of("model");
    scanModels(root, old, found);
    models.setAll(found);

    Set<String> existing = new LinkedHashSet<>();
    for (ModelAssignment model : models) existing.add(model.fileName);
    for (ProfileState p : profiles.values()) p.removeMissingModels(existing);

    ProfileState main = profile(Profile.NORMAL);
    for (ModelAssignment model : models) {
      if (!main.hasModel(model.fileName)) main.setModel(model.fileName, model.snapshotProfile());
      ModelAssignment.ProfileConfig mainCfg = main.model(model.fileName);
      for (Profile p : Profile.values()) {
        ProfileState state = profile(p);
        if (!state.hasModel(model.fileName)) state.setModel(model.fileName, mainCfg);
      }
    }
    applyProfile(active);
  }

  private void scanModels(java.nio.file.Path root,
                          Map<String, ModelAssignment> old, List<ModelAssignment> found) {
    if (!java.nio.file.Files.isDirectory(root)) return;
    try (var stream = java.nio.file.Files.walk(root)) {
      stream.filter(java.nio.file.Files::isRegularFile)
          .filter(f -> !java.nio.file.Files.isSymbolicLink(f))
          .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf"))
          .filter(f -> !isInsideExcludedDirectory(root.relativize(f)))
          .sorted()
          .forEach(f -> {
            java.nio.file.Path relativePath = root.relativize(f).normalize();
            String relative = relativePath.toString().replace('\\', '/');
            boolean memoryOnly = relativePath.getNameCount() > 1
                && relativePath.getName(0).toString().equalsIgnoreCase("memory");
            boolean webSearchOnly = relativePath.getNameCount() > 1
                && relativePath.getName(0).toString().equalsIgnoreCase("web_search");

            ModelAssignment existing = old.get(relative);
            if (existing != null) {
              found.add(existing);
              return;
            }

            String file = f.getFileName().toString();
            String display = file.substring(0, file.length() - 5);
            found.add(new ModelAssignment(
                display,
                "model/" + relative,
                memoryOnly ? "memory" : webSearchOnly ? "globe" : "spark",
                "CPU",
                relative,
                memoryOnly,
                webSearchOnly));
          });
    } catch (Exception e) {
      System.out.println("[Models] Scan failed for " + root + ": " + e);
    }
    for (ModelAssignment model : found) model.refreshLoras(root);
  }

  /**
   * Directories whose GGUF files belong to dedicated runtimes and must never appear as general
   * models: "lora" adapters are managed by the LoRA page, "tts" is CosyVoice (Voice tab),
   * "stt" is whisper.cpp (Voice tab). Scanning them would leak voice models into the Models tab.
   */
  private boolean isInsideExcludedDirectory(java.nio.file.Path relative) {
    for (java.nio.file.Path part : relative) {
      String name = part.toString();
      if (name.equalsIgnoreCase("lora")
          || name.equalsIgnoreCase("tts")
          || name.equalsIgnoreCase("stt")) return true;
    }
    return false;
  }

  /** Called by future agent-goal checkboxes: enabled goal immediately affects effective prompt. */
  public void setAgentGoalPrompt(String id, String prompt, boolean enabled) {
    if (enabled && prompt != null && !prompt.isBlank()) agentPromptModules.put(id, prompt.trim());
    else agentPromptModules.remove(id);
  }

  public String effectiveGeneralPrompt() {
    return effectiveGeneralPrompt(Profile.NORMAL);
  }

  public String effectiveRoleplayGeneralPrompt() {
    return effectiveGeneralPrompt(Profile.ROLEPLAY);
  }

  public StringProperty personalityPrompt(Profile p) {
    return profile(p).personalityPrompt;
  }

  public StringProperty generalPrompt(Profile p) {
    return profile(p).generalPrompt;
  }

  public String effectiveGeneralPrompt(Profile p) {
    return effectivePrompt(profile(p).generalPrompt.get());
  }

  public StringProperty agentCallTemplate(Profile p, ModelRole role) {
    return profile(p == null ? Profile.NORMAL : p).agentCallTemplate(role);
  }

  public Map<ModelRole, String> agentCallTemplates(Profile p) {
    return profile(p == null ? Profile.NORMAL : p).agentCallTemplatesSnapshot();
  }
  public StringProperty generalAgentCallTemplate(Profile p, ModelAssignment model) {
    Profile profileId = p == null ? Profile.NORMAL : p;
    return profile(profileId).generalAgentCallTemplate(model.fileName, model.name);
  }

  public Map<String, String> generalAgentCallTemplates(Profile p) {
    Profile profileId = p == null ? Profile.NORMAL : p;
    LinkedHashMap<String, String> out = new LinkedHashMap<>();
    for (ModelAssignment model : models) {
      if (!model.generalModel()) continue;
      ModelAssignment.ProfileConfig cfg = modelProfileConfig(profileId, model);
      if (!cfg.enabled() || !cfg.roles().contains(ModelRole.GENERAL)) continue;
      out.put(model.fileName.replace('\\', '/'), generalAgentCallTemplate(profileId, model).get());
    }
    return Collections.unmodifiableMap(out);
  }

  public Map<String, String> generalAgentNames(Profile p) {
    Profile profileId = p == null ? Profile.NORMAL : p;
    LinkedHashMap<String, String> out = new LinkedHashMap<>();
    for (ModelAssignment model : models) {
      if (!model.generalModel()) continue;
      ModelAssignment.ProfileConfig cfg = modelProfileConfig(profileId, model);
      if (cfg.enabled() && cfg.roles().contains(ModelRole.GENERAL))
        out.put(model.fileName.replace('\\', '/'), model.name);
    }
    return Collections.unmodifiableMap(out);
  }

  /** Evidence-judge model selected for one WEB_SEARCH model in one profile. */
  public StringProperty webReasoningModel(Profile p, String webModelFileName) {
    Profile profileId = p == null ? Profile.NORMAL : p;
    return profile(profileId).webReasoningModel(webModelFileName);
  }

  public String webReasoningModelValue(Profile p, String webModelFileName) {
    Profile profileId = p == null ? Profile.NORMAL : p;
    return profile(profileId).webReasoningModelValue(webModelFileName);
  }

  public ModelAssignment.ProfileConfig modelProfileConfig(Profile p, ModelAssignment model) {
    if (p == activeProfile.get()) snapshotActiveProfile(p);
    ModelAssignment.ProfileConfig cfg = profile(p).model(model.fileName);
    if (cfg != null) return cfg;
    cfg = profile(Profile.NORMAL).model(model.fileName);
    return cfg != null ? cfg : model.snapshotProfile();
  }

  public void setModelProfileConfig(Profile p, ModelAssignment model, ModelAssignment.ProfileConfig config) {
    profile(p).setModel(model.fileName, config);
    if (p == activeProfile.get()) model.applyProfile(config);
  }

  /**
   * Atomically moves the exclusive MAIN_LLM role to one physical model in a profile.
   *
   * <p>Do not implement this by only mutating the live {@link ModelAssignment#roles} sets:
   * ProfileState is the source of truth and a later profile apply/rebuild would otherwise restore
   * the previous Main LLM. This method updates every stored model config first and only then
   * projects the profile back to the live editor.</p>
   */
  public void assignMainLlm(Profile p, ModelAssignment selected) {
    if (selected == null || !selected.generalModel()) return;
    Profile target = p == null ? Profile.NORMAL : p;
    if (target == activeProfile.get()) snapshotActiveProfile(target);

    ProfileState targetState = profile(target);
    ProfileState mainState = profile(Profile.NORMAL);
    for (ModelAssignment model : models) {
      if (!model.generalModel()) continue;
      ModelAssignment.ProfileConfig cfg = targetState.model(model.fileName);
      if (cfg == null) cfg = mainState.model(model.fileName);
      if (cfg == null) cfg = model.snapshotProfile();

      Set<ModelRole> roles = new LinkedHashSet<>(cfg.roles());
      if (model == selected) roles.add(ModelRole.MAIN_LLM);
      else roles.remove(ModelRole.MAIN_LLM);

      targetState.setModel(model.fileName, new ModelAssignment.ProfileConfig(
          cfg.enabled(), cfg.device(), cfg.ctxSize(), cfg.maxOutputTokens(), cfg.fileSearchMaxVisited(), cfg.parallel(), cfg.batchSize(), cfg.ubatchSize(),
          cfg.cacheTypeK(), cfg.cacheTypeV(), cfg.isolation(), roles, cfg.placements(), cfg.loras()));
    }

    if (target == activeProfile.get()) applyProfile(target);
  }

  private String effectivePrompt(String base) {
    StringBuilder b = new StringBuilder(base == null ? "" : base.trim());
    agentPromptModules.forEach((id, prompt) -> {
      if (prompt != null && !prompt.isBlank())
        b.append("\n\n[AGENT GOAL: ").append(id).append("]\n").append(prompt.trim());
    });
    return b.toString();
  }

  /** Rebuilds the GUI device list while preserving per-profile device switches. */
  public void refreshHardwareDevices() {
    Map<String, Boolean> oldEnabled = new HashMap<>();
    for (Device d : devices) oldEnabled.put(d.id, d.enabled.get());

    HardwareInfo hw = HardwareLocator.getInstance().info();
    if (hw == null) return;

    List<Device> detected = new ArrayList<>();
    CpuInfo cpu = hw.cpu();
    if (cpu != null) {
      String details = "Ядер / потоков: " + cpu.physicalCores() + " / " + cpu.logicalCores()
          + "\nRAM: " + formatGiB(hw.memory() == null ? 0 : hw.memory().totalBytes());
      detected.add(new Device("CPU", cpu.name(), details, oldEnabled.getOrDefault("CPU", true)));
    }

    for (GpuInfo gpu : hw.gpus()) {
      String id = "GPU " + gpu.index();
      StringBuilder details = new StringBuilder();
      if (gpu.totalVramBytes() > 0) details.append("VRAM: ").append(formatGiB(gpu.totalVramBytes()));
      if (gpu.computeCapability() != null && !gpu.computeCapability().isBlank())
        details.append(details.length() == 0 ? "" : "\n").append("Compute: ").append(gpu.computeCapability());
      detected.add(new Device(id, gpu.name(), details.length() == 0 ? gpu.vendor() : details.toString(),
          oldEnabled.getOrDefault(id, true)));
    }

    devices.setAll(detected);
    Set<String> ids = new LinkedHashSet<>();
    for (Device d : devices) ids.add(d.id);
    for (ModelAssignment model : models) model.retainDevices(ids);
  }

  private static String formatGiB(long bytes) {
    if (bytes <= 0) return "N/A";
    return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1073741824.0);
  }
}
