package com.neuralsoul.service;

import com.neuralsoul.config.*;
import com.neuralsoul.model.*;
import com.neuralsoul.model.Profile;
import java.util.*;
import java.util.prefs.Preferences;

public final class SettingsStore {
  private final ConfigLocator configs;
  private final ConfigLoader<GuiConfig> legacyLoader;
  private Map<String, String> values = new LinkedHashMap<>();

  public SettingsStore() {
    this(ConfigLocator.getInstance());
  }

  public SettingsStore(ConfigLocator configs) {
    this.configs = configs;
    this.legacyLoader = null;
  }

  /** Legacy constructor retained for existing integrations and isolated GUI tests. */
  public SettingsStore(Preferences prefs) {
    this.configs = null;
    this.legacyLoader =
        new ConfigLoader<>() {
          public GuiConfig get() {
            return reload();
          }

          public GuiConfig reload() {
            try {
              Map<String, String> m = new LinkedHashMap<>();
              for (String k : prefs.keys()) m.put(k, prefs.get(k, ""));
              return new GuiConfig(m);
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }

          public void save(GuiConfig c) {
            c.values().forEach(prefs::put);
            try {
              prefs.flush();
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }
        };
  }

  public ConfigLocator configs() {
    return configs != null ? configs : ConfigLocator.getInstance();
  }

  private GuiConfig gui() {
    return legacyLoader != null ? legacyLoader.get() : configs.gui();
  }

  private void saveGui(GuiConfig config) {
    if (legacyLoader != null) legacyLoader.save(config);
    else configs.saveGui(config);
  }

  private boolean getBoolean(String k, boolean fallback) {
    return Boolean.parseBoolean(values.getOrDefault(k, "" + fallback));
  }

  private double getDouble(String k, double fallback) {
    try {
      return Double.parseDouble(values.getOrDefault(k, "" + fallback));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private String get(String k, String fallback) {
    return values.getOrDefault(k, fallback);
  }

  private void put(String k, Object v) {
    values.put(k, Objects.toString(v, ""));
  }

  public void load(AppState s) {
    values = new LinkedHashMap<>(gui().values());
    for (var role : com.neuralsoul.config.AgentPrompts.roles())
      s.agentPrompt(role).set(get("agents.system." + role.name(), com.neuralsoul.config.AgentPrompts.defaultPrompt(role)));
    values.forEach((key, value) -> {
      if (key.startsWith("agents.general.system.")) {
        try {
          String modelKey = new String(java.util.Base64.getUrlDecoder().decode(key.substring("agents.general.system.".length())), java.nio.charset.StandardCharsets.UTF_8);
          s.generalAgentSystemPrompt(modelKey).set(value);
        } catch (IllegalArgumentException error) { System.out.println("[Agents] Invalid saved model key: " + key); }
      }
    });
    s.voice.set(getBoolean("voice", false));
    s.alwaysListen.set(getBoolean("voice.alwaysListen", false));
    try {
      s.voiceInputMode.set(VoiceInputMode.valueOf(get("voice.inputMode", VoiceInputMode.INTERRUPT.name())));
    } catch (IllegalArgumentException e) {
      s.voiceInputMode.set(VoiceInputMode.INTERRUPT);
    }
    try {
      s.fileToolApprovalMode.set(FileToolApprovalMode.valueOf(
          get("files.approvalMode", FileToolApprovalMode.MANUAL.name())));
    } catch (IllegalArgumentException e) {
      s.fileToolApprovalMode.set(FileToolApprovalMode.MANUAL);
    }
    s.mode.set(get("mode", "Обычный"));
    String savedProfile = get("activeProfile", "NORMAL");
    try { s.activeProfile.set(Profile.valueOf(savedProfile)); } catch (IllegalArgumentException e) { s.activeProfile.set(Profile.NORMAL); }
    // Migrate the old Roleplay chat mode to the new independent profile selector.
    if ("Автономный".equals(s.mode.get()) || "Roleplay".equals(s.mode.get())) {
      s.activeProfile.set(Profile.ROLEPLAY);
      s.mode.set("Обычный");
    }
    // Fresh installations start with the canonical editable general prompt. Existing
    // installations keep their saved copy; the GUI reset button restores the canonical one.
    String defaultPrompt = com.neuralsoul.config.AgentPrompts.defaultGeneralPrompt();
    s.personalityPrompt(Profile.NORMAL).set(get("prompt.personality", ""));
    String generalPrompt = get("prompt.general", defaultPrompt);
    String legacyMemoryPrompt = get("prompt.memoryAgent",
        com.neuralsoul.pipeline.GenerationPipeline.DEFAULT_MEMORY_AGENT_PROMPT);
    // Since Gen2 prompt UI no longer has a separate Memory Agent editor, migrate its
    // instructions into the general prompt once. This keeps existing installations intact.
    if (legacyMemoryPrompt != null && !legacyMemoryPrompt.isBlank()
        && !generalPrompt.contains("MEMORY RECALL TOOL:")) {
      generalPrompt = generalPrompt.stripTrailing() + "\n\n" + legacyMemoryPrompt.trim();
      System.out.println("[Prompt] Migrated Memory Agent instructions into General prompt");
    }
    s.generalPrompt(Profile.NORMAL).set(generalPrompt);
    for (Profile profile : Profile.values()) {
      if (profile == Profile.NORMAL) continue;
      String prefix = promptPrefix(profile);
      s.personalityPrompt(profile).set(get(prefix + ".personality", ""));
      s.generalPrompt(profile).set(get(prefix + ".general", generalPrompt));
    }
    s.memoryAgentPrompt.set(""); // legacy field; no longer a separate prompt layer

    // Built-in agent/tool call syntax is fixed. Ignore legacy custom values that older
    // versions allowed to edit; only model-scoped GENERAL call templates remain editable.
    for (Profile profile : Profile.values()) {
      for (ModelRole role : ProfileState.callTemplateRoles()) {
        s.agentCallTemplate(profile, role).set(ProfileState.defaultAgentCallTemplate(role));
      }
    }

    String defaultModel = configs != null ? configs.llm().model() : "";
    s.selectedModel.set(get("selectedModel", defaultModel));

    // File Search routes are profile-owned. Keep the old "files" key for Main migration/compatibility.
    loadList("files", s.fileRoutes(Profile.NORMAL));
    for (Profile profile : Profile.values()) {
      if (profile == Profile.NORMAL) continue;
      loadList("profile." + profile.key() + ".files", s.fileRoutes(profile));
    }
    loadList("tasks", s.tasks);
    loadList("memories", s.memories);
    boolean mainAutomatic = getBoolean("automatic", true);
    boolean mainAllowSwap = getBoolean("modelManagement.allowSwap", getBoolean("unload", true) && getBoolean("load", true));
    boolean mainAllowRam = getBoolean("modelManagement.allowRamOffload", getBoolean("offload", true));
    s.vram.set(clamp(getDouble("vram", 24), 0, 28));
    s.ram.set(clamp(getDouble("ram", 48), 0, 64));
    s.threads.set(clamp(getDouble("threads", 8), 1, 12));
    s.memorySettings.minScore.set(clamp(getDouble("memory.minScore", 0.60), 0, 1));
    s.memorySettings.maxResults.set((int) clamp(getDouble("memory.maxResults", 10), 1, 100));
    s.memorySettings.contextMessages.set((int) clamp(getDouble("memory.contextMessages", 20), 1, 200));
    s.memorySettings.summarizationModel.set(get("memory.summarizationModel", com.neuralsoul.memory.MemorySettings.MAIN_LLM_SUMMARIZER));

    Map<String, Boolean> mainDevices = new LinkedHashMap<>();
    for (Device d : s.devices) mainDevices.put(d.id, getBoolean("device." + d.id, true));
    AppState.ResourceProfileConfig mainResources =
        new AppState.ResourceProfileConfig(mainAutomatic, mainAllowSwap, mainAllowRam, mainDevices);
    s.setResourceProfileConfig(Profile.NORMAL, mainResources);
    for (Profile profile : Profile.values()) {
      if (profile == Profile.NORMAL) continue;
      String pk = "profile." + profile.key() + ".resources.";
      Map<String, Boolean> devices = new LinkedHashMap<>();
      for (Device d : s.devices)
        devices.put(d.id, getBoolean(pk + "device." + d.id, mainDevices.getOrDefault(d.id, true)));
      s.setResourceProfileConfig(profile, new AppState.ResourceProfileConfig(
          getBoolean(pk + "automatic", mainAutomatic),
          getBoolean(pk + "allowSwap", mainAllowSwap),
          getBoolean(pk + "allowRamOffload", mainAllowRam), devices));
    }
    s.applyResourceProfile(s.activeProfile.get());

    Set<String> detected = new LinkedHashSet<>();
    for (Device d : s.devices) detected.add(d.id);

    for (ModelAssignment m : s.models) {
      ModelAssignment.ProfileConfig main = loadModelProfile(s, m, Profile.NORMAL, null);
      s.setModelProfileConfig(Profile.NORMAL, m, main);
      s.profile(Profile.NORMAL).fileRouteStorage(m.fileName)
          .set(getBoolean(modelSettingsKey(m, Profile.NORMAL) + ".fileRouteStorage", false));
      s.webReasoningModel(Profile.NORMAL, m.fileName).set(
          get(modelSettingsKey(m, Profile.NORMAL) + ".webReasoningModel", ProfileState.MAIN_LLM_REASONING));
      String mainGeneralDefault = ProfileState.defaultGeneralAgentCallTemplate(m.fileName, m.name);
      String mainLegacyGeneral = values.get("profile." + Profile.NORMAL.key() + ".agentCall.GENERAL");
      String mainGeneralSaved = get(modelSettingsKey(m, Profile.NORMAL) + ".generalCallTemplate",
          mainLegacyGeneral == null || mainLegacyGeneral.isBlank() ? mainGeneralDefault : mainLegacyGeneral);
      if (mainGeneralSaved == null || mainGeneralSaved.isBlank() || !mainGeneralSaved.contains("{task}"))
        mainGeneralSaved = mainGeneralDefault;
      s.generalAgentCallTemplate(Profile.NORMAL, m).set(mainGeneralSaved);
      for (Profile profile : Profile.values()) {
        if (profile == Profile.NORMAL) continue;
        s.setModelProfileConfig(profile, m, loadModelProfile(s, m, profile, main));
        s.profile(profile).fileRouteStorage(m.fileName)
            .set(getBoolean(modelSettingsKey(m, profile) + ".fileRouteStorage",
                s.profile(Profile.NORMAL).fileRouteStorageEnabled(m.fileName)));
        s.webReasoningModel(profile, m.fileName).set(
            get(modelSettingsKey(m, profile) + ".webReasoningModel",
                s.webReasoningModelValue(Profile.NORMAL, m.fileName)));
        String def = ProfileState.defaultGeneralAgentCallTemplate(m.fileName, m.name);
        String legacyGeneral = values.get("profile." + profile.key() + ".agentCall.GENERAL");
        String saved = get(modelSettingsKey(m, profile) + ".generalCallTemplate",
            legacyGeneral == null || legacyGeneral.isBlank() ? def : legacyGeneral);
        if (saved == null || saved.isBlank() || !saved.contains("{task}")) saved = def;
        s.generalAgentCallTemplate(profile, m).set(saved);
      }
    }
    for (Profile profile : Profile.values()) normalizeMainRole(s, profile);
    s.applyProfile(s.activeProfile.get());

  }


  private void normalizeMainRole(AppState s, Profile profile) {
    boolean claimed = false;
    for (ModelAssignment m : s.models) {
      if (!m.generalModel()) continue;
      ModelAssignment.ProfileConfig cfg = s.modelProfileConfig(profile, m);
      if (!cfg.roles().contains(ModelRole.MAIN_LLM)) continue;
      if (!claimed) { claimed = true; continue; }
      Set<ModelRole> roles = new LinkedHashSet<>(cfg.roles());
      roles.remove(ModelRole.MAIN_LLM);
      s.setModelProfileConfig(profile, m, new ModelAssignment.ProfileConfig(
          cfg.enabled(), cfg.device(), cfg.ctxSize(), cfg.maxOutputTokens(), cfg.fileSearchMaxVisited(), cfg.parallel(), cfg.batchSize(), cfg.ubatchSize(),
          cfg.cacheTypeK(), cfg.cacheTypeV(), cfg.isolation(), roles, cfg.placements(), cfg.loras()));
    }
  }

  public void save(AppState s) {
    s.generalAgentSystemPromptsSnapshot().forEach((key, value) ->
      put("agents.general.system." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)), value));
    for (var role : com.neuralsoul.config.AgentPrompts.roles())
      put("agents.system." + role.name(), s.agentPrompt(role).get());
    s.snapshotActiveProfile(s.activeProfile.get());
    put("voice", s.voice.get());
    put("voice.alwaysListen", s.alwaysListen.get());
    put("voice.inputMode", s.voiceInputMode.get() == null ? VoiceInputMode.INTERRUPT.name() : s.voiceInputMode.get().name());
    put("files.approvalMode", s.fileToolApprovalMode.get() == null
        ? FileToolApprovalMode.MANUAL.name() : s.fileToolApprovalMode.get().name());
    put("mode", s.mode.get());
    put("activeProfile", s.activeProfile.get().name());
    put("prompt.personality", s.personalityPrompt(Profile.NORMAL).get());
    put("prompt.general", s.generalPrompt(Profile.NORMAL).get());
    for (Profile profile : Profile.values()) {
      if (profile == Profile.NORMAL) continue;
      String prefix = promptPrefix(profile);
      put(prefix + ".personality", s.personalityPrompt(profile).get());
      put(prefix + ".general", s.generalPrompt(profile).get());
    }
    put("prompt.memoryAgent", "");
    for (Profile profile : Profile.values()) {
      for (ModelRole role : ProfileState.callTemplateRoles()) {
        // Overwrite legacy editable values with the canonical built-in protocol.
        put("profile." + profile.key() + ".agentCall." + role.name(),
            ProfileState.defaultAgentCallTemplate(role));
      }
    }
    put("selectedModel", s.selectedModel.get());
    // Persist each profile's File Search routes as JSON arrays in gui.json.
    put("files", com.neuralsoul.service.http.Json.write(s.fileRoutes(Profile.NORMAL)));
    for (Profile profile : Profile.values()) {
      if (profile == Profile.NORMAL) continue;
      put("profile." + profile.key() + ".files",
          com.neuralsoul.service.http.Json.write(s.fileRoutes(profile)));
    }
    put("tasks", com.neuralsoul.service.http.Json.write(s.tasks));
    put("memories", com.neuralsoul.service.http.Json.write(s.memories));
    AppState.ResourceProfileConfig mainResources = s.resourceProfileConfig(Profile.NORMAL);
    put("automatic", mainResources.automatic());
    put("modelManagement.allowSwap", mainResources.allowSwap());
    put("modelManagement.allowRamOffload", mainResources.allowRamOffload());
    for (Profile profile : Profile.values()) {
      if (profile == Profile.NORMAL) continue;
      AppState.ResourceProfileConfig cfg = s.resourceProfileConfig(profile);
      String pk = "profile." + profile.key() + ".resources.";
      put(pk + "automatic", cfg.automatic());
      put(pk + "allowSwap", cfg.allowSwap());
      put(pk + "allowRamOffload", cfg.allowRamOffload());
      for (Device d : s.devices) put(pk + "device." + d.id, cfg.devices().getOrDefault(d.id, true));
    }
    put("vram", s.vram.get());
    put("ram", s.ram.get());
    put("threads", s.threads.get());
    put("memory.minScore", s.memorySettings.minScore.get());
    put("memory.maxResults", s.memorySettings.maxResults.get());
    put("memory.contextMessages", s.memorySettings.contextMessages.get());
    put("memory.summarizationModel", s.memorySettings.summarizationModel.get());

    for (Device d : s.devices) put("device." + d.id, mainResources.devices().getOrDefault(d.id, true));
    for (ModelAssignment m : s.models) {
      for (Profile profile : Profile.values()) {
        saveModelProfile(s, m, profile, s.modelProfileConfig(profile, m));
        put(modelSettingsKey(m, profile) + ".fileRouteStorage",
            s.profile(profile).fileRouteStorageEnabled(m.fileName));
        put(modelSettingsKey(m, profile) + ".webReasoningModel",
            s.webReasoningModelValue(profile, m.fileName));
        String generalTemplate = s.generalAgentCallTemplate(profile, m).get();
        if (generalTemplate == null || generalTemplate.isBlank() || !generalTemplate.contains("{task}"))
          generalTemplate = ProfileState.defaultGeneralAgentCallTemplate(m.fileName, m.name);
        put(modelSettingsKey(m, profile) + ".generalCallTemplate", generalTemplate);
      }
    }

    saveGui(new GuiConfig(values));
  }

  private String modelSettingsKey(ModelAssignment m, Profile profile) {
    String base = "model." + (m.memoryOnly ? "memory." : "") + m.fileName;
    return profile == Profile.NORMAL ? base : "profile." + profile.key() + "." + base;
  }

  private ModelAssignment.ProfileConfig loadModelProfile(
      AppState s, ModelAssignment m, Profile profile, ModelAssignment.ProfileConfig fallback) {
    String base = "model." + (m.memoryOnly ? "memory." : "") + m.fileName;
    String k = profile == Profile.NORMAL ? base : "profile." + profile.key() + "." + base;

    boolean enabled = getBoolean(k + ".enabled", fallback == null ? m.generalModel() : fallback.enabled());
    int ctx = (int) clamp(getDouble(k + ".ctxSize", fallback == null ? 4096 : fallback.ctxSize()), 256, 262144);
    int maxOutputTokens = (int) clamp(getDouble(k + ".maxOutputTokens", fallback == null ? 1024 : fallback.maxOutputTokens()), 64, 131072);
    int fileSearchMaxVisited = (int) clamp(getDouble(k + ".fileSearchMaxVisited", fallback == null ? 120_000 : fallback.fileSearchMaxVisited()), 1_000, 5_000_000);
    int parallel = (int) clamp(getDouble(k + ".parallel", fallback == null ? 1 : fallback.parallel()), 1, 16);
    int batch = (int) clamp(getDouble(k + ".batchSize", fallback == null ? 512 : fallback.batchSize()), 32, 8192);
    int ubatch = (int) clamp(getDouble(k + ".ubatchSize", fallback == null ? 128 : fallback.ubatchSize()), 32, 8192);
    String cacheK = get(k + ".cacheTypeK", fallback == null ? "q8_0" : fallback.cacheTypeK());
    String cacheV = get(k + ".cacheTypeV", fallback == null ? "q8_0" : fallback.cacheTypeV());
    String isolation = get(k + ".isolation", fallback == null ? "Нет" : fallback.isolation());
    if (!"По устройству".equals(isolation)) isolation = "Нет";

    Set<String> detected = new LinkedHashSet<>();
    for (Device d : s.devices) detected.add(d.id);
    String fallbackDevice = fallback == null ? m.device.get() : fallback.device();
    String device = get(k, fallbackDevice);
    if (!("Auto".equals(device) || detected.contains(device))) device = "Auto";

    // Existing search role assignments remain active after an upgrade. New GGUFs
    // are merely discovered, NOT auto-enabled, until the user clicks Connect.
    // Legacy search roles on regular LLMs are ignored; those roles can only be
    // assigned to models physically discovered inside model/memory.
    Set<ModelRole> roles = new LinkedHashSet<>();
    String defaultRoles = fallback == null
        ? m.roles.stream().map(ModelRole::name).sorted().collect(java.util.stream.Collectors.joining(","))
        : fallback.roles().stream().map(ModelRole::name).sorted().collect(java.util.stream.Collectors.joining(","));
    String rolesKey = k + ".roles";
    boolean rolesWereSaved = values.containsKey(rolesKey);
    String savedRoles = rolesWereSaved ? values.getOrDefault(rolesKey, "") : defaultRoles;
    for (String roleName : savedRoles.split(",")) {
      if (roleName.isBlank()) continue;
      String normalizedRole = roleName.trim();
      if ("RESEARCH".equalsIgnoreCase(normalizedRole)) normalizedRole = "WEB_SEARCH";
      try {
        ModelRole role = ModelRole.valueOf(normalizedRole);
        if (m.memoryOnly) {
          if (role == ModelRole.MEMORY) roles.add(role);
        } else if (m.webSearchOnly) {
          if (role == ModelRole.WEB_SEARCH) roles.add(role);
        } else if (role != ModelRole.MEMORY && role != ModelRole.FILE_SEARCH
            && role != ModelRole.WEB_SEARCH) {
          roles.add(role);
        }
      } catch (IllegalArgumentException ignored) {}
    }
    // An explicitly saved empty role list is meaningful for both types.
    if (m.generalModel() && roles.isEmpty() && fallback == null && !rolesWereSaved)
      roles.add(ModelRole.MAIN_LLM);

    Map<String, Boolean> placements = new LinkedHashMap<>();
    for (Device d : s.devices) {
      boolean fb = fallback == null ? d.id.equals(device) : fallback.placements().getOrDefault(d.id, d.id.equals(device));
      boolean value = getBoolean(k + ".placement." + d.id, fb);
      placements.put(d.id, d.enabled.get() && value);
    }
    Map<String, ModelAssignment.LoraConfig> loras = new LinkedHashMap<>();
    for (LoraSetting adapter : m.loras) {
      ModelAssignment.LoraConfig fb = fallback == null ? null : fallback.loras().get(adapter.relativePath);
      String lk = k + ".lora." + keyPart(adapter.relativePath);
      boolean loraEnabled = getBoolean(lk + ".enabled", fb != null && fb.enabled());
      double loraScale = clamp(getDouble(lk + ".scale", fb == null ? 1.0 : fb.scale()), -16.0, 16.0);
      loras.put(adapter.relativePath, new ModelAssignment.LoraConfig(loraEnabled, loraScale));
    }
    return new ModelAssignment.ProfileConfig(enabled, device, ctx, maxOutputTokens, fileSearchMaxVisited, parallel, batch, ubatch,
        cacheK, cacheV, isolation, roles, placements, loras);
  }

  private void saveModelProfile(
      AppState s, ModelAssignment m, Profile profile, ModelAssignment.ProfileConfig cfg) {
    String base = "model." + (m.memoryOnly ? "memory." : "") + m.fileName;
    String k = profile == Profile.NORMAL ? base : "profile." + profile.key() + "." + base;
    put(k + ".enabled", cfg.enabled());
    put(k + ".ctxSize", cfg.ctxSize());
    put(k + ".maxOutputTokens", cfg.maxOutputTokens());
    put(k + ".fileSearchMaxVisited", cfg.fileSearchMaxVisited());
    put(k + ".parallel", cfg.parallel());
    put(k + ".batchSize", cfg.batchSize());
    put(k + ".ubatchSize", cfg.ubatchSize());
    put(k + ".cacheTypeK", cfg.cacheTypeK());
    put(k + ".cacheTypeV", cfg.cacheTypeV());
    put(k + ".isolation", cfg.isolation());
    put(k, cfg.device());
    put(k + ".roles", cfg.roles().stream().map(ModelRole::name).sorted()
        .collect(java.util.stream.Collectors.joining(",")));
    for (Device d : s.devices)
      put(k + ".placement." + d.id, cfg.placements().getOrDefault(d.id, false));
    for (Map.Entry<String, ModelAssignment.LoraConfig> entry : cfg.loras().entrySet()) {
      String lk = k + ".lora." + keyPart(entry.getKey());
      put(lk + ".enabled", entry.getValue().enabled());
      put(lk + ".scale", entry.getValue().scale());
    }
  }

  private String promptPrefix(Profile profile) {
    return switch (profile) {
      case NORMAL -> "prompt";
      case ROLEPLAY -> "prompt.roleplay";
      case PROFILE_1 -> "prompt.profile1";
      case PROFILE_2 -> "prompt.profile2";
      case PROFILE_3 -> "prompt.profile3";
      case PROFILE_4 -> "prompt.profile4";
      case PROFILE_5 -> "prompt.profile5";
    };
  }

  private String keyPart(String value) {
    return java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private void loadList(String key, javafx.collections.ObservableList<String> target) {
    String raw = values.get(key);
    if (raw == null) return;
    try {
      Object parsed = com.neuralsoul.service.http.Json.read(raw);
      if (parsed instanceof List<?> list)
        target.setAll(
            list.stream().filter(String.class::isInstance).map(String.class::cast).toList());
    } catch (IllegalArgumentException ignored) {
    }
  }

  private double clamp(double v, double min, double max) {
    return Double.isFinite(v) ? Math.max(min, Math.min(max, v)) : min;
  }
}
