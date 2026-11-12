package com.neuralsoul;

import com.neuralsoul.model.*;
import com.neuralsoul.pipeline.*;
import com.neuralsoul.files.FilePermissionRequest;
import com.neuralsoul.service.*;
import com.neuralsoul.service.audio.*;
import com.neuralsoul.config.SttConfig;
import com.neuralsoul.runtime.*;
import com.neuralsoul.memory.*;
import com.neuralsoul.ui.components.*;
import com.neuralsoul.ui.styles.UiStyles;
import com.neuralsoul.ui.pages.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import com.neuralsoul.web.WebSearchAgent;
import com.neuralsoul.web.WebSearchHistoryStore;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;

public final class AppController implements AutoCloseable {
  private record MemoryLaunchSettings(int threads, java.nio.file.Path modelPath, String device,
                                      boolean automatic, boolean allowRamOffload) {}
  private final Stage stage;
  private final AppState state;
  private final AssistantBackend backend;
  private final SettingsStore store;
  private final ServiceLocator services;
  /** Optional CosyVoice startup error; retained in the TTS status after the startup dialog closes. */
  private volatile String ttsStartupError;
  private final GenerationPipeline pipeline;
  private final RuntimeLifecycleManager runtimeLifecycle = new RuntimeLifecycleManager();
  private final ModelRuntimeManager runtime = runtimeLifecycle.model();
  private final QdrantRuntimeManager qdrantRuntime = runtimeLifecycle.qdrant();
  private final MemoryEmbeddingRuntimeManager memoryEmbeddingRuntime = runtimeLifecycle.memoryEmbedding();
  private final WebSearchRuntimeManager webSearchRuntime = runtimeLifecycle.webSearch();
  private final WebReasoningRuntimeManager webReasoningRuntime = runtimeLifecycle.webReasoning();
  private final GeneralAgentRuntimeManager generalAgentRuntime = runtimeLifecycle.generalAgents();
  private final SummarizationRuntimeManager summarizationRuntime = runtimeLifecycle.summarization();
  private final WhisperRuntimeManager whisperRuntime = runtimeLifecycle.whisper();
  private final ContinuousSpeechListener continuousVoice = new ContinuousSpeechListener();
  private final MicrophoneMonitor microphoneMonitor = new MicrophoneMonitor();
  /** Completed WAV phrases waiting to be transcribed. Never drop a phrase just because Whisper is busy. */
  private record PendingVoiceWav(byte[] wav, boolean bufferAfterRecognition) {}
  private final java.util.concurrent.ConcurrentLinkedQueue<PendingVoiceWav> voiceWavQueue =
      new java.util.concurrent.ConcurrentLinkedQueue<>();
  private final java.util.concurrent.atomic.AtomicBoolean voiceTranscriptionWorker =
      new java.util.concurrent.atomic.AtomicBoolean(false);
  private final java.util.concurrent.atomic.AtomicBoolean bufferCurrentVoiceUtterance =
      new java.util.concurrent.atomic.AtomicBoolean(false);
  /** Recognized phrases collected while Main LLM is busy in BUFFER mode. FX thread only. */
  private final Deque<String> bufferedVoiceMessages = new ArrayDeque<>();
  private volatile boolean resumeAlwaysListenAfterManual;
  private volatile boolean manualRecordingShouldBuffer;
  private final WebSearchAgent webSearchAgent = new WebSearchAgent();
  private final WebSearchHistoryStore webSearchHistory = new WebSearchHistoryStore();
  private final MemoryService memory;
  private boolean responseShown;
  private int streamingMessageIndex = -1;
  private int thinkMessageIndex = -1;
  private final StringBuilder streamingAnswer = new StringBuilder();
  private volatile boolean memoryAgentActive;
  private volatile long memoryAgentVisibleSinceNanos;
  private final BorderPane root = new BorderPane(), content = new BorderPane();
  private final Sidebar sidebar;
  private final ChatPage chat;
  private MemoryHubPage memoryHub;
  private ScrollPane memoryScroll;
  private InternetHubPage internetHub;
  private ScrollPane internetScroll;
  private ModelsHubPage modelsHub;
  private ScrollPane modelsScroll;
  private final Composer composer;
  private final VBox footer;
  private final Button headerSystemButton;
  private final AppHeader appHeader;
  private long conversationVersion;
  private boolean closed;
  private final ExecutorService memoryWriter =
      Executors.newSingleThreadExecutor(r -> {
        Thread t=new Thread(r,"memory-writer");
        t.setDaemon(true);
        return t;
      });
  private volatile String pendingMemoryRequest;
  private volatile Profile pendingMemoryProfile = Profile.NORMAL;
  private volatile boolean pendingMemorySuppressed;

  public AppController(Stage stage, AppState state, AssistantBackend backend, SettingsStore store) {
    this(stage, state, backend, store, null);
  }

  public AppController(
      Stage stage,
      AppState state,
      AssistantBackend backend,
      SettingsStore store,
      ServiceLocator services) {
    this.services = services;
    this.pipeline = backend instanceof PipelineBackend p ? p.pipeline() : null;
    this.stage = stage;
    this.state = state;
    this.backend = backend;
    this.store = store;
    java.util.function.Supplier<com.neuralsoul.service.api.LlmService> memoryLlm = () -> {
      if (services == null) throw new IllegalStateException("LLM сервис недоступен");
      return services.llm();
    };
    String qdrantUrl = "http://127.0.0.1:" + QdrantRuntimeManager.PORT;
    java.util.EnumMap<Profile, MemoryService> profileMemories =
        new java.util.EnumMap<>(Profile.class);
    for (Profile profile : Profile.values()) {
      ProfileState.MemoryConfig memoryCfg = state.profile(profile).memory();
      LocalMemoryService local = new LocalMemoryService(memoryLlm, state.memorySettings,
          java.nio.file.Path.of(System.getProperty("user.dir"), "data", memoryCfg.fileName()));
      MemoryService service = new QdrantMemoryService(local, qdrantUrl,
          memoryCfg.qdrantCollection(), memoryCfg.semanticCollection());
      profileMemories.put(profile, service);
    }
    this.memory = new ProfileMemoryService(profileMemories, state.activeProfile::get);
    // The Internet workspace is a persistent journal of every real WEB_SEARCH execution.
    // Load the JSON journal once on startup, then persist each new batch before exposing it in UI.
    state.webSearches.setAll(webSearchHistory.load());
    webSearchAgent.setSearchListener(record -> {
      try {
        webSearchHistory.append(record);
      } catch (Exception e) {
        System.out.println("[WebSearch/History] append failed: " + e);
      }
      Platform.runLater(() -> {
        if (!closed) state.webSearches.add(record);
      });
    });
    if (pipeline != null) {
      pipeline.setMemory(memory, state.memorySettings);
      pipeline.setFilePermissionHandler(this::requestFilePermission);
      pipeline.setWebSearchAgent(webSearchAgent);
      pipeline.setGeneralAgentRuntime(generalAgentRuntime);
      pipeline.setSummarizationRuntime(summarizationRuntime);
    }
    sidebar = new Sidebar(this::navigate, this::toggleSystem);
    sidebar.setSystemRunning(pipeline != null && pipeline.isRunning());
    chat = new ChatPage(state);
    headerSystemButton = Ui.button("Запустить систему", "play", this::toggleSystem);
    headerSystemButton.getStyleClass().add("header-system-button");
    updateHeaderSystemButton(pipeline != null && pipeline.isRunning());
    appHeader = new AppHeader(pipeline == null, state, headerSystemButton);
    root.setMinSize(0, 0);
    content.setMinSize(0, 0);
    root.setTop(appHeader);
    ScrollPane navigation = Ui.scroll(sidebar);
    navigation.setFitToHeight(true);
    navigation.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    navigation.prefWidthProperty().bind(sidebar.prefWidthProperty().add(10));
    navigation.setMinWidth(0);
    root.setLeft(navigation);
    root.setCenter(content);
    composer =
        new Composer(
            state,
            this::send,
            this::attachFile,
            this::newChat,
            this::cancelGeneration,
            this::microphone,
            this::clearContext);
    // The input belongs to ChatPage itself, never to the shared application footer.
    chat.setBottom(composer);
    BorderPane.setMargin(composer, new Insets(0, 16, 4, 4));
    state.alwaysListen.addListener((obs, oldValue, enabled) -> {
      store.save(state);
      if (enabled) startContinuousListening(); else stopContinuousListening();
    });
    state.voiceInputMode.addListener((obs, oldValue, mode) -> {
      store.save(state);
      // If buffered phrases already exist and generation has ended, changing the mode must not
      // strand them indefinitely.
      flushBufferedVoiceIfReady();
    });
    Label status = Ui.label("", "status");
    status.textProperty().bind(state.status);
    status.setWrapText(true);
    footer = new VBox(7, status);
    footer.setPadding(new Insets(0, 16, 10, 4));
    content.setBottom(footer);
    Scene scene = new Scene(root, 1180, 960);
    scene
        .getStylesheets()
        .add(getClass().getResource("/com/neuralsoul/theme.css").toExternalForm());
    scene
        .getAccelerators()
        .put(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN), this::newChat);
    stage.setScene(scene);
    stage.setTitle("NeuralSoul — Nina");
    Runtime.getRuntime().addShutdownHook(
        new Thread(
            () -> {
              try { flushMemory(); } catch (Throwable ignored) {}
            },
            "neuralsoul-memory-flush"));
    WindowSizing.install(stage, null, 1180, 960);
    scene
        .widthProperty()
        .addListener(
            (o, old, width) -> {
              sidebar.setCompact(width.doubleValue() < 900);
              appHeader.setCompact(width.doubleValue() < 760);
            });
    navigate("Главная");
    if (pipeline != null)
      pipeline.addListener(
          event ->
              Platform.runLater(
                  () -> {
                    if (closed || event.generation() != pipeline.generationId()) return;
                    refreshStatuses();
                    switch (event.type()) {
                      case STATUS -> {
                        // Do not let low-level pipeline status text replace the active agent name.
                        // While a response is running the footer must consistently show
                        // Generating / Memory Search / etc.
                        if (state.busy.get() && state.activeAgent.get() != AgentType.IDLE) {
                          state.status.set(state.activeAgent.get().displayName());
                        } else {
                          state.status.set(event.text());
                        }
                      }
                      case WARNING -> state.status.set(event.text());
                      case MEMORY_SKIP -> {
                        pendingMemorySuppressed = true;
                        System.out.println("[Memory] UI writer suppression armed: " + event.text());
                      }
                      case THINK_START -> {
                        memoryAgentActive = true;
                        memoryAgentVisibleSinceNanos = System.nanoTime();
                        if ("File Search".equalsIgnoreCase(event.text()))
                          state.activeAgent.set(AgentType.FILE_SEARCH);
                        else if ("Web Search".equalsIgnoreCase(event.text()))
                          state.activeAgent.set(AgentType.WEB_SEARCH);
                        else
                          state.activeAgent.set(AgentType.MEMORY_SEARCH);
                        state.status.set(state.activeAgent.get().displayName());
                        if (streamingMessageIndex >= 0 && streamingMessageIndex < state.messages.size()) {
                          // Keep text Nina already streamed before [[MEMORY_RECALL:...]].
                          // Only the author/status changes while the tool is working.
                          state.messages.set(
                              streamingMessageIndex,
                              new ChatMessage(state.activeAgent.get().displayName(), streamingAnswer.toString()));
                        }
                      }
                      case THINK_END -> {
                        // BGE recall is often only ~50-100 ms. JavaFX can process START and END
                        // before a visible pulse, so keep the Memory Agent state on screen briefly.
                        long elapsedMs =
                            (System.nanoTime() - memoryAgentVisibleSinceNanos) / 1_000_000L;
                        long delayMs = Math.max(0L, 450L - elapsedMs);
                        javafx.animation.PauseTransition pause =
                            new javafx.animation.PauseTransition(
                                javafx.util.Duration.millis(delayMs));
                        pause.setOnFinished(
                            ignored -> {
                              if (!memoryAgentActive) return;
                              memoryAgentActive = false;
                              state.activeAgent.set(AgentType.MAIN_LLM);
                              state.status.set(state.activeAgent.get().displayName());
                              if (streamingMessageIndex >= 0
                                  && streamingMessageIndex < state.messages.size()) {
                                state.messages.set(
                                    streamingMessageIndex,
                                    new ChatMessage(state.activeAgent.get().displayName(), streamingAnswer.toString()));
                              }
                            });
                        pause.play();
                      }
                      case TRANSCRIPT -> state.messages.add(new ChatMessage("Вы", event.text()));
                      case CHUNK -> {
                        memoryAgentActive = false;
                        state.activeAgent.set(AgentType.MAIN_LLM);
                        if (streamingMessageIndex < 0) {
                          streamingAnswer.setLength(0);
                          streamingMessageIndex = state.messages.size();
                          state.messages.add(new ChatMessage(state.activeAgent.get().displayName(), ""));
                        }
                        streamingAnswer.append(event.text());
                        state.messages.set(
                            streamingMessageIndex,
                            new ChatMessage(state.activeAgent.get().displayName(), streamingAnswer.toString()));
                        responseShown = true;
                        state.status.set(state.activeAgent.get().displayName());
                      }
                      case RESPONSE -> {
                        memoryAgentActive = false;
                        if (streamingMessageIndex < 0) {
                          state.messages.add(new ChatMessage("Nina", event.text()));
                        } else {
                          state.messages.set(
                              streamingMessageIndex, new ChatMessage("Nina", event.text()));
                        }
                        responseShown = true;
                        state.activeAgent.set(AgentType.IDLE);
                        state.status.set("");
                        streamingMessageIndex = -1;
                        streamingAnswer.setLength(0);
                        String memoryRequest = pendingMemoryRequest;
                        Profile memoryProfile = pendingMemoryProfile;
                        boolean skipMemory = pendingMemorySuppressed;
                        pendingMemoryRequest = null;
                        pendingMemorySuppressed = false;
                        if (skipMemory) {
                          System.out.println("[Memory] Background exchange save skipped: unverified web result");
                        } else if (memoryRequest != null && !memoryRequest.isBlank()
                            && event.text() != null && !event.text().isBlank()) {
                          memoryWriter.submit(() -> {
                            try {
                              MemoryService target = memory instanceof ProfileMemoryService profiles
                                  ? profiles.service(memoryProfile)
                                  : memory;
                              target.saveExchange(memoryRequest, event.text());
                            } catch (Exception e) {
                              System.out.println("[Memory] Background exchange save failed: " + e);
                              e.printStackTrace(System.out);
                            }
                          });
                        }
                        streamingMessageIndex = -1;
                        streamingAnswer.setLength(0);
                      }
                    }
                  }));
  }

  private void updateHeaderSystemButton(boolean running) {
    if (headerSystemButton == null) return;
    headerSystemButton.setText(running ? "Остановить систему" : "Запустить систему");
    headerSystemButton.setGraphic(Icons.of(running ? "stop" : "play"));
    headerSystemButton.setTooltip(new Tooltip(running ? "Остановить NeuralSoul" : "Запустить NeuralSoul"));
    headerSystemButton.setAccessibleText(running ? "Остановить систему" : "Запустить систему");
    headerSystemButton.getStyleClass().remove("system-running");
    if (running) headerSystemButton.getStyleClass().add("system-running");
  }

  private void toggleSystem() {
    if (pipeline == null || state.systemStarting.get()) {
      if (pipeline == null) state.status.set("Системный пайплайн недоступен в тестовом режиме");
      return;
    }

    if (state.systemRunning.get()) {
      ttsStartupError = null;
      flushMemory();
      stopContinuousListening();
      clearVoiceBuffer();
      microphoneMonitor.stop();
      state.microphoneMonitor.set(false);
      pipeline.stop();
      webSearchAgent.close();
      runtimeLifecycle.stopAll();
      state.systemRunning.set(false);
      sidebar.setSystemRunning(false);
      updateHeaderSystemButton(false);
      updateHeaderSystemButton(false);
      state.busy.set(false);
      state.status.set("Система остановлена");
      refreshStatuses();
      return;
    }

    ttsStartupError = null;
    System.out.println("[System] Start button pressed");
    java.util.concurrent.atomic.AtomicBoolean startupCancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
    StartupDialog startupDialog = new StartupDialog(stage, null, () -> {
      if (!startupCancelled.compareAndSet(false, true)) return;
      System.out.println("[System] EMERGENCY startup stop requested");
      state.status.set("Экстренная остановка запуска…");
      CompletableFuture.runAsync(() -> {
        try { if (pipeline != null) pipeline.stop(); } catch (Throwable ignored) {}
        runtimeLifecycle.stopAll();
      });
    });
    startupDialog.show();
    final String[] startupStage = {"Чтение конфигурации"};
    state.systemStarting.set(true);
    state.busy.set(true);
    state.status.set("Чтение конфигурации…");

    CompletableFuture.runAsync(
            () -> {
              try {
                // Snapshot is made once from the GUI. From this point until Stop,
                // the Models page is locked and the runtime uses this immutable configuration.
                checkStartupCancelled(startupCancelled);
                System.out.println("[System] Reading model configuration from GUI...");
                AutomaticModelPlacement.Plan placementPlan = AutomaticModelPlacement.plan(state);
                placementPlan.printToLog();
                ModelLaunchConfig config = runtime.snapshot(state, placementPlan);
                if (services != null) services.setLlmMaxOutputTokens(config.maxOutputTokens());
                SummarizationRuntimeManager.SummarizationTarget summarizationConfig =
                    summarizationRuntime.snapshot(state, placementPlan);
                ModelLaunchConfig webSearchConfig = webSearchRuntime.snapshot(state, placementPlan);
                WebReasoningRuntimeManager.ReasoningTarget webReasoningConfig =
                    webSearchConfig == null ? null : webReasoningRuntime.snapshot(state, placementPlan);
                Profile startupProfileForModels = state.activeProfile.get() == null ? Profile.NORMAL : state.activeProfile.get();
                String mainModelKey = state.models.stream()
                    .filter(m -> m.generalModel())
                    .filter(m -> {
                      ModelAssignment.ProfileConfig mc = state.modelProfileConfig(startupProfileForModels, m);
                      return mc.enabled() && mc.roles().contains(ModelRole.MAIN_LLM);
                    })
                    .map(m -> m.fileName.replace('\\', '/'))
                    .findFirst().orElse("");
                java.util.List<GeneralAgentRuntimeManager.GeneralLaunch> generalConfigs =
                    generalAgentRuntime.snapshot(state, placementPlan);
                System.out.println("[System] GUI configuration captured");
                startupStatus("Конфигурация прочитана · сохранение настроек…");
                System.out.println("[System] Saving settings...");
                store.save(state);
                checkStartupCancelled(startupCancelled);
                ModelAssignment memoryModel = state.models.stream()
                    .filter(m -> m.memoryOnly && m.enabled.get())
                    .findFirst().orElse(null);
                boolean semanticMemory = memoryModel != null;
                if (memory instanceof ProfileMemoryService profiles) {
                  for (Profile profile : Profile.values()) {
                    MemoryService service = profiles.service(profile);
                    if (service instanceof QdrantMemoryService q) q.setSemanticEnabled(semanticMemory);
                  }
                } else if (memory instanceof QdrantMemoryService q) q.setSemanticEnabled(semanticMemory);
                // Qdrant storage is persistent. Re-indexing every memory on every application start
                // is both redundant and extremely expensive when semantic memory is enabled because
                // every record would be embedded through BGE-M3 again. Explicit import/refresh actions
                // still call MemoryService.synchronize() when a rebuild is actually requested.
                System.out.println("[System] Skipping full memory re-index on startup (persistent Qdrant)");
                SttConfig startupStt = store.configs().stt();
                ModelStartupPlan startupPlan = new ModelStartupPlan(runtimeLifecycle.registry());
                startupPlan.add(new ModelLaunch<>(RuntimeKey.QDRANT, QdrantRuntimeManager.class,
                    "Qdrant Memory Runtime", null, (Void) null, false,
                    (selectedRuntime, ignored) -> selectedRuntime.start()));

                if (semanticMemory) {
                  java.nio.file.Path memoryPath = java.nio.file.Path.of(
                      System.getProperty("user.dir"), "model").resolve(memoryModel.fileName).normalize();
                  String memoryDevice = state.automatic.get()
                      ? placementPlan.device(AutomaticModelPlacement.Purpose.MEMORY,
                          memoryModel.fileName, memoryModel.device.get())
                      : memoryModel.device.get();
                  MemoryLaunchSettings memorySettings = new MemoryLaunchSettings(
                      Math.max(1, (int)Math.round(state.threads.get())), memoryPath, memoryDevice,
                      state.automatic.get(), state.allowRamOffload.get());
                  startupPlan.add(new ModelLaunch<>(RuntimeKey.MEMORY_SEARCH,
                      MemoryEmbeddingRuntimeManager.class, memoryModel.name, memoryPath,
                      memorySettings, false, (selectedRuntime, settings) -> selectedRuntime.start(
                          settings.threads(), settings.modelPath(), settings.device(),
                          settings.automatic(), settings.allowRamOffload())));
                } else {
                  memoryEmbeddingRuntime.stop();
                  System.out.println("[System] Memory Search model disabled; FAST memory remains active");
                }

                startupPlan.add(new ModelLaunch<>(RuntimeKey.MAIN_LLM, ModelRuntimeManager.class,
                    config.name(), config.modelPath(), config, false,
                    ModelRuntimeManager::start));

                startupPlan.add(new ModelLaunch<>(RuntimeKey.SUMMARIZATION,
                    SummarizationRuntimeManager.class, summarizationConfig.displayName(),
                    summarizationConfig.config() == null ? config.modelPath()
                        : summarizationConfig.config().modelPath(),
                    summarizationConfig, false, SummarizationRuntimeManager::start));

                if (startupStt.runtimeEnabled()) {
                  startupPlan.add(new ModelLaunch<>(RuntimeKey.WHISPER_STT,
                      WhisperRuntimeManager.class, java.nio.file.Path.of(startupStt.modelPath()).getFileName().toString(),
                      java.nio.file.Path.of(startupStt.modelPath()), startupStt, true,
                      WhisperRuntimeManager::start));
                } else {
                  whisperRuntime.stop();
                }

                if (webSearchConfig != null) {
                  startupPlan.add(new ModelLaunch<>(RuntimeKey.WEB_REASONING,
                      WebReasoningRuntimeManager.class, webReasoningConfig.displayName(),
                      webReasoningConfig.config() == null ? null : webReasoningConfig.config().modelPath(),
                      webReasoningConfig, false, WebReasoningRuntimeManager::start));
                  startupPlan.add(new ModelLaunch<>(RuntimeKey.WEB_SEARCH,
                      WebSearchRuntimeManager.class, webSearchConfig.name(), webSearchConfig.modelPath(),
                      webSearchConfig, false, WebSearchRuntimeManager::start));
                } else {
                  webSearchAgent.close();
                  webReasoningRuntime.stop();
                  webSearchRuntime.stop();
                  System.out.println("[System] Web Search role is not assigned; agent disabled");
                }

                if (!generalConfigs.isEmpty()) {
                  generalAgentRuntime.prepareStart();
                  for (GeneralAgentRuntimeManager.GeneralLaunch general : generalConfigs) {
                    startupPlan.add(new ModelLaunch<>(RuntimeKey.GENERAL_AGENTS,
                        GeneralAgentRuntimeManager.class, general.displayName(),
                        general.config().modelPath(), general, false,
                        GeneralAgentRuntimeManager::start));
                  }
                } else {
                  generalAgentRuntime.stop();
                  System.out.println("[System] General role is not assigned; agents disabled");
                }

                var ttsSettings = new com.neuralsoul.config.SpeechGenerationSettingsStore(
                    store.configs().directory()).load();
                if (ttsSettings.common().enabled() && services != null && services.isInitialized()) {
                  runtimeLifecycle.registry().register(RuntimeKey.COSYVOICE_TTS, services.tts());
                  java.nio.file.Path ttsModelPath = java.nio.file.Path.of(System.getProperty("user.dir"))
                      .resolve(ttsSettings.cosy().modelPath()).normalize();
                  startupPlan.add(new ModelLaunch<>(RuntimeKey.COSYVOICE_TTS,
                      com.neuralsoul.service.api.TtsService.class,
                      "CosyVoice · " + ttsModelPath.getFileName(), ttsModelPath,
                      ttsSettings, true, (selectedRuntime, ignored) -> selectedRuntime.start()));
                }

                ModelStartupPlan.Listener startupEvents = new ModelStartupPlan.Listener() {
                  @Override public void planReady(java.util.List<ModelLaunch<?, ?>> launches) {
                    Platform.runLater(() -> startupDialog.planReady(launches));
                  }
                  @Override public void starting(ModelLaunch<?, ?> launch, int index, int total) {
                    startupStage[0] = "Загрузка " + launch.displayName();
                    startupStatus("Загрузка " + launch.displayName() + "…");
                    Platform.runLater(() -> startupDialog.modelStarting(launch, index, total));
                  }
                  @Override public void ready(ModelLaunch<?, ?> launch, int index, int total) {
                    Platform.runLater(() -> startupDialog.modelReady(launch, index, total));
                  }
                  @Override public void failed(ModelLaunch<?, ?> launch, int index, int total, Throwable error) {
                    if (launch.runtimeKey() == RuntimeKey.COSYVOICE_TTS)
                      ttsStartupError = GenerationPipeline.message(error);
                    Platform.runLater(() -> startupDialog.modelFailed(launch, index, total, error));
                  }
                };
                startupPlan.execute(startupEvents, () -> checkStartupCancelled(startupCancelled));

                if (webSearchConfig != null) {
                  webSearchAgent.configure(webSearchConfig.name(), webSearchConfig.maxOutputTokens(),
                      webReasoningRuntime.service(), webReasoningConfig.displayName());
                  System.out.println("[System] Web Search Agent ready; reasoning="
                      + webReasoningConfig.displayName());
                }

                startupStatus("Модели готовы · подготовка пайплайна…");
                System.out.println("[System] Capturing prompt snapshot...");
                Profile startupProfile = state.activeProfile.get() == null ? Profile.NORMAL : state.activeProfile.get();
                Set<ModelRole> availableRoles = new LinkedHashSet<>();
                for (ModelAssignment m : state.models) {
                  if (m.memoryOnly) continue; // Memory/file search use their own routes.
                  ModelAssignment.ProfileConfig mc = state.modelProfileConfig(startupProfile, m);
                  if (mc.enabled()) availableRoles.addAll(mc.roles());
                }
                pipeline.setAgentSystemPrompts(state.agentPromptsSnapshot());
                pipeline.setGeneralAgentSystemPrompts(state.generalAgentSystemPromptsSnapshot());
                webSearchAgent.setSystemPrompt(state.agentPrompt(ModelRole.WEB_SEARCH).get());
                pipeline.setAvailableAgentRoles(availableRoles);
                int fileSearchMaxVisited = 120_000;
                for (ModelAssignment m : state.models) {
                  ModelAssignment.ProfileConfig mc = state.modelProfileConfig(startupProfile, m);
                  if (!mc.enabled()) continue;
                  if (m.memoryOnly || mc.roles().contains(ModelRole.MEMORY)
                      || mc.roles().contains(ModelRole.FILE_SEARCH)) {
                    fileSearchMaxVisited = mc.fileSearchMaxVisited();
                    break;
                  }
                }
                pipeline.setFileSearchMaxVisited(fileSearchMaxVisited);
                pipeline.setMainModelKey(mainModelKey);
                pipeline.setMainContextWindowTokens(config.ctxSize());
                pipeline.setPromptSnapshot(
                    state.personalityPrompt(Profile.NORMAL).get(),
                    state.effectiveGeneralPrompt(),
                    state.personalityPrompt(Profile.ROLEPLAY).get(),
                    state.effectiveRoleplayGeneralPrompt(),
                    "",
                    false);
                for (Profile profile : Profile.values()) {
                  pipeline.setProfilePromptSnapshot(profile,
                      state.personalityPrompt(profile).get(), state.effectiveGeneralPrompt(profile));
                  pipeline.setAgentCallTemplates(profile, state.agentCallTemplates(profile));
                  pipeline.setGeneralAgentCallTemplates(
                      profile, state.generalAgentCallTemplates(profile), state.generalAgentNames(profile));
                }
                startupStage[0] = "Запуск GenerationPipeline";
                System.out.println("[System] Starting GenerationPipeline...");
                checkStartupCancelled(startupCancelled);
                pipeline.start();
                checkStartupCancelled(startupCancelled);

                // Model startup is owned by ModelStartupPlan. Audio output is a
                // post-start resource, not a model runtime, so it stays here.
                if (ttsSettings.common().enabled() && services != null && services.isInitialized()
                    && services.tts().isRunning()) {
                  try {
                    services.player().preparePcmOutput(24000, 1);
                    System.out.println("[CosyVoice/Audio] persistent output ready (24000 Hz mono)");
                  } catch (Exception audioError) {
                    System.err.println("[CosyVoice/Audio] pre-open failed; will retry on playback: " + audioError);
                  }
                }
                checkStartupCancelled(startupCancelled);
                if (whisperRuntime.isRunning() && services != null) {
                  try { services.stt().start(); }
                  catch (Exception e) { System.out.println("[Whisper] client connect failed: " + e); }
                }
                System.out.println("[System] GenerationPipeline started");
                // One-shot measured OS/device allocation, not the intended GPU in the GUI.
                // Windows WDDM per-process counters are read only after EVERY runtime is ready.
                startupStage[0] = "Измерение фактической VRAM";
                startupStatus("Измерение фактической памяти GPU по PID процессов…");
                try {
                  java.util.List<com.neuralsoul.hardware.GpuMemoryAudit.Target> audit =
                      new java.util.ArrayList<>();
                  if (runtime.pid() > 0) audit.add(new com.neuralsoul.hardware.GpuMemoryAudit.Target(
                      "Main LLM: " + (runtime.active() == null ? "unknown" : runtime.active().name()), runtime.pid()));
                  if (memoryEmbeddingRuntime.pid() > 0) audit.add(new com.neuralsoul.hardware.GpuMemoryAudit.Target(
                      "Memory Search: " + (memoryModel == null ? "BGE" : memoryModel.name), memoryEmbeddingRuntime.pid()));
                  if (whisperRuntime.pid() > 0) audit.add(new com.neuralsoul.hardware.GpuMemoryAudit.Target(
                      "Whisper: " + java.nio.file.Path.of(startupStt.modelPath()).getFileName(), whisperRuntime.pid()));
                  if (webSearchRuntime.pid() > 0) audit.add(new com.neuralsoul.hardware.GpuMemoryAudit.Target(
                      "Web Search: " + (webSearchRuntime.active() == null ? "unknown" : webSearchRuntime.active().name()), webSearchRuntime.pid()));
                  if (webReasoningRuntime.pid() > 0) audit.add(new com.neuralsoul.hardware.GpuMemoryAudit.Target(
                      "Web Reasoning: " + (webReasoningConfig == null ? "unknown" : webReasoningConfig.displayName()), webReasoningRuntime.pid()));
                  if (summarizationRuntime.pid() > 0) audit.add(new com.neuralsoul.hardware.GpuMemoryAudit.Target(
                      "Summary: " + (summarizationConfig == null ? "unknown" : summarizationConfig.displayName()), summarizationRuntime.pid()));
                  for (var entry : generalAgentRuntime.activePids().entrySet()) audit.add(
                      new com.neuralsoul.hardware.GpuMemoryAudit.Target("General: " + entry.getKey(), entry.getValue()));
                  if (services != null && services.isInitialized()
                      && services.tts() instanceof com.neuralsoul.service.tts.RoutingTtsService routing) {
                    long cosyPid = routing.cosyOwnedPid();
                    if (cosyPid > 0) audit.add(new com.neuralsoul.hardware.GpuMemoryAudit.Target(
                        "CosyVoice", cosyPid));
                    else if (routing.isCosyEnabled()) System.out.println(
                        "[GPU/ACTUAL] CosyVoice has no owned PID (external server or startup failure); "
                        + "cannot safely attribute another process's memory");
                  }
                  com.neuralsoul.hardware.GpuMemoryAudit.printAfterStartup(audit);
                } catch (Throwable auditError) {
                  System.out.println("[GPU/ACTUAL] Audit failed without affecting model startup: " + auditError);
                }
              } catch (Exception e) {
                System.out.println("[System] START FAILED: " + e);
                e.printStackTrace(System.out);
                try { pipeline.stop(); } catch (Exception stopError) {
                  System.out.println("[System] Pipeline cleanup failed: " + stopError);
                }
                webSearchAgent.close();
                runtimeLifecycle.stopAll();
                throw new CompletionException(e);
              }
            })
        .whenComplete(
            (ignored, error) ->
                Platform.runLater(
                    () -> {
                      state.systemStarting.set(false);
                      state.busy.set(false);
                      if (startupCancelled.get()) {
                        System.out.println("[System] Startup cancelled by user");
                        try { pipeline.stop(); } catch (Throwable ignored2) {}
                        webSearchAgent.close();
                        runtimeLifecycle.stopAll();
                        state.systemRunning.set(false);
                        sidebar.setSystemRunning(false);
      updateHeaderSystemButton(false);
                        state.status.set("Запуск системы остановлен");
                        startupDialog.close();
                      } else if (error != null) {
                        System.out.println("[System] Startup completed with ERROR");
                        error.printStackTrace(System.out);
                        state.systemRunning.set(false);
                        sidebar.setSystemRunning(false);
      updateHeaderSystemButton(false);
                        state.status.set(
                            "Не удалось запустить систему: " + GenerationPipeline.message(error)
                                + " · детали в System.out");
                        Throwable cause = error;
                        while (cause.getCause() != null && cause != cause.getCause()) cause = cause.getCause();
                        startupDialog.fail(startupStage[0], cause);
                      } else {
                        System.out.println("[System] Startup completed successfully");
                        state.systemRunning.set(true);
                        sidebar.setSystemRunning(true);
                        updateHeaderSystemButton(true);
                        state.availableModels.setAll(
                            state.models.stream()
                                .filter(m -> m.roles.contains(ModelRole.MAIN_LLM))
                                .map(m -> m.name)
                                .toList());
                        if (!state.availableModels.isEmpty())
                          state.selectedModel.set(state.availableModels.get(0));
                        state.status.set(ttsStartupError == null
                            ? "Система запущена · Main LLM готова"
                            : "Система запущена · TTS не запущен: " + ttsStartupError);
                        if (state.alwaysListen.get()) startContinuousListening();
                        startupDialog.successAndClose();
                      }
                      refreshStatuses();
                    }));
  }

  private static void checkStartupCancelled(java.util.concurrent.atomic.AtomicBoolean cancelled) {
    if (cancelled.get()) throw new CancellationException("Startup cancelled by user");
  }

  private void startupStatus(String text) {
    Platform.runLater(() -> {
      if (state.systemStarting.get()) state.status.set(text);
    });
  }

  public void show() {
    stage.show();
    WindowSizing.fitToScreen(stage);
  }

  private MemoryHubPage memoryHub() {
    if (memoryHub == null) {
      memoryHub = new MemoryHubPage(state, memory, this::attachFile, this::openProject,
          () -> store.save(state));
      memoryScroll = Ui.scroll(memoryHub);
    }
    return memoryHub;
  }

  private InternetHubPage internetHub() {
    if (internetHub == null) {
      internetHub = new InternetHubPage(state, () -> store.save(state), this::clearWebSearchHistory);
      internetScroll = Ui.scroll(internetHub);
    }
    return internetHub;
  }

  private ModelsHubPage modelsHub() {
    if (modelsHub == null) {
      modelsHub = new ModelsHubPage(state, () -> store.save(state));
      modelsScroll = Ui.scroll(modelsHub);
    }
    return modelsHub;
  }

  public void navigate(String name) {
    sidebar.select("Файлы".equals(name) ? "Память" :
        "LoRA".equals(name) ? "Модели" : name);
    switch (name) {
      case "Главная" ->
          content.setCenter(
              Ui.scroll(
                  new DashboardPage(
                      state,
                      this::navigate,
                      () -> navigate("Мониторинг"),
                      this::addTask,
                      this::openProject)));
      case "Мониторинг" ->
          content.setCenter(Ui.scroll(new SystemMonitorPage(() -> navigate("Схема"))));
      case "Чат" -> content.setCenter(chat);
      case "Модели" -> {
        modelsHub();
        content.setCenter(modelsScroll);
      }
      // Compatibility for older navigation calls: open the LoRA subpage inside "Модели".
      case "LoRA" -> {
        modelsHub().showLora();
        content.setCenter(modelsScroll);
      }
      case "Текущий промт" ->
          content.setCenter(Ui.scroll(new PromptPage(state, () -> {
            store.save(state);
            state.status.set("Промты сохранены");
          })));
      case "Схема" -> content.setCenter(Ui.scroll(new SchemaPage(state)));
      case "Агенты" -> content.setCenter(Ui.scroll(new AgentsPage(state, () -> {
        store.save(state);
        state.status.set("Промпты агентов сохранены");
      })));
      case "Память" -> {
        memoryHub();
        content.setCenter(memoryScroll);
      }
      case "Интернет" -> {
        internetHub();
        content.setCenter(internetScroll);
      }
      // Older calls to the removed sidebar item still open the correct subpage.
      case "Файлы" -> {
        memoryHub().showFileMemory();
        content.setCenter(memoryScroll);
      }
      case "Голос" ->
          content.setCenter(
              Ui.scroll(
                  new VoiceHubPage(
                      state, store.configs(), this::toggleMicrophoneMonitor,
                      this::transcribeFileForVoicePage, this::speakTest, this::saveSttSettings)));
      default ->
          content.setCenter(
              Ui.scroll(
                  new WorkspacePage(
                      name, state, this::attachFile, this::addTask, this::openProject,
                      () -> store.save(state), this::clearWebSearchHistory)));
    }
  }

  private void clearWebSearchHistory() {
    try {
      webSearchHistory.clear();
      state.webSearches.clear();
      state.status.set("История интернет-поиска очищена");
    } catch (Exception e) {
      state.status.set("Не удалось очистить историю поиска: " + e.getMessage());
    }
  }

  public void send(String message) {
    if (message == null || message.isBlank() || state.busy.get() || state.recording.get()) return;
    if (!state.systemRunning.get()) {
      state.status.set("Сначала запусти систему");
      return;
    }
    pendingMemoryRequest = (message == null || message.isBlank()) ? null : message;
    pendingMemoryProfile = state.activeProfile.get();
    pendingMemorySuppressed = false;
    if (pipeline != null) {
      pipeline.setFileRouteSearchConfig(
          pendingMemoryProfile,
          java.util.List.copyOf(state.fileRoutes(pendingMemoryProfile)),
          state.useSavedFileRoutes(pendingMemoryProfile));
    }
    pipeline.setContextMessageLimit(state.memorySettings.contextMessages.get());
    state.messages.add(new ChatMessage("Вы", message.trim()));

    // One response bubble per request. It exists immediately and is reused for
    // Generating -> Memory Agent -> Generating -> Nina.
    streamingAnswer.setLength(0);
    // Set the agent BEFORE creating the reusable response bubble. Previously the
    // bubble was created while the agent was still IDLE, so its title was blank
    // until the first streamed chunk arrived.
    state.activeAgent.set(AgentType.MAIN_LLM);
    state.status.set(state.activeAgent.get().displayName());
    streamingMessageIndex = state.messages.size();
    state.messages.add(new ChatMessage(state.activeAgent.get().displayName(), ""));
    state.busy.set(true);
    responseShown = false;
    thinkMessageIndex = -1;
    navigate("Чат");
    // A newly sent message must be visible immediately, before generation starts.
    chat.scrollToBottom();
    try {
      if (backend instanceof PipelineBackend pb)
        complete(pb.send(message, state.selectedModel.get(), state.mode.get(), pendingMemoryProfile));
      else complete(backend.send(message, state.selectedModel.get(), state.mode.get()));
    } catch (RuntimeException e) {
      state.busy.set(false);
      state.status.set(GenerationPipeline.message(e));
    }
  }

  private void complete(CompletionStage<String> result) {
    long version = conversationVersion;
    result.whenComplete(
        (answer, error) ->
            Platform.runLater(
                () -> {
                  if (closed || version != conversationVersion) return;
                  state.busy.set(false);
                  refreshStatuses();
                  if (error != null) {
                    String text = "Ошибка: " + GenerationPipeline.message(error);
                    state.messages.add(new ChatMessage("Система", text));
                    state.status.set(text);
                  } else {
                    if (!responseShown) {
                      if (streamingMessageIndex >= 0 && streamingMessageIndex < state.messages.size()) {
                        state.messages.set(streamingMessageIndex, new ChatMessage("Nina", answer));
                        streamingMessageIndex = -1;
                        streamingAnswer.setLength(0);
                      } else {
                        state.messages.add(new ChatMessage("Nina", answer));
                      }
                    }
                    if (pipeline == null) state.status.set("Ответ получен");
                  }
                  flushBufferedVoiceIfReady();
                }));
  }

  private void refreshStatuses() {
    if (services == null || !services.isInitialized()) {
      state.llmStatus.set("Отключён");
      state.sttStatus.set("Отключён");
      state.ttsStatus.set("Отключён");
      return;
    }
    state.llmStatus.set(services.llm().isRunning() ? "Подключён" : "Отключён");
    state.sttStatus.set(services.stt().isRunning() ? "Подключён" : "Отключён");
    try {
      if (services.tts().isRunning()) state.ttsStatus.set("Подключён");
      else if (ttsStartupError != null) state.ttsStatus.set("Ошибка запуска: " + ttsStartupError);
      else state.ttsStatus.set("Отключён");
    } catch (Exception ttsError) {
      state.ttsStatus.set("Ошибка настроек TTS: " + GenerationPipeline.message(ttsError));
    }
  }

  private void cancelGeneration() {
    conversationVersion++;
    streamingMessageIndex = -1;
    thinkMessageIndex = -1;
    streamingAnswer.setLength(0);
    if (pipeline != null) pipeline.cancel();
    state.busy.set(false);
    state.recording.set(false);
    state.status.set("Операция остановлена");
  }

  private void microphone() {
    if (pipeline == null || services == null) {
      state.status.set("Аудиосервис не подключён");
      return;
    }
    if (!state.systemRunning.get()) {
      state.status.set("Сначала запусти систему");
      return;
    }

    if (state.recording.get()) {
      state.recording.set(false);
      state.status.set("Микрофон · подготавливаю запись…");
      final boolean forceBuffer = manualRecordingShouldBuffer;
      manualRecordingShouldBuffer = false;
      CompletableFuture.supplyAsync(() -> {
        try {
          return services.recorder().finishRecording();
        } catch (Exception e) {
          throw new CompletionException(e);
        }
      }).whenComplete((wav, error) -> Platform.runLater(() -> {
        if (error != null) {
          state.status.set("Микрофон: " + GenerationPipeline.message(error));
        } else {
          voiceWavQueue.add(new PendingVoiceWav(wav, forceBuffer));
          drainVoiceWavQueue();
          state.status.set(forceBuffer ? "Микрофон · фраза добавляется в буфер" : "Whisper · распознавание речи…");
        }
        if (resumeAlwaysListenAfterManual && state.alwaysListen.get() && state.systemRunning.get()) {
          resumeAlwaysListenAfterManual = false;
          javafx.animation.PauseTransition resume =
              new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));
          resume.setOnFinished(e -> startContinuousListening());
          resume.play();
        }
      }));
      return;
    }

    boolean generating = pipeline.isGenerating();
    if (generating && state.voiceInputMode.get() == VoiceInputMode.INTERRUPT) {
      interruptForVoice();
      generating = false;
    }
    manualRecordingShouldBuffer = generating && state.voiceInputMode.get() == VoiceInputMode.BUFFER;
    resumeAlwaysListenAfterManual = state.alwaysListen.get() && continuousVoice.isRunning();
    stopContinuousListening();
    try {
      if (!services.isInitialized()) services.initialize(store.configs());
      services.recorder().start();
      state.recording.set(true);
      state.status.set(manualRecordingShouldBuffer
          ? "Запись микрофона · текущий ответ продолжает генерироваться"
          : "Запись микрофона · нажми микрофон ещё раз для отправки");
    } catch (Exception e) {
      manualRecordingShouldBuffer = false;
      state.recording.set(false);
      state.status.set("Микрофон: " + GenerationPipeline.message(e));
      if (resumeAlwaysListenAfterManual && state.alwaysListen.get()) {
        resumeAlwaysListenAfterManual = false;
        startContinuousListening();
      }
    }
  }

  private void transcribeFileForVoicePage(java.util.function.Consumer<String> resultConsumer) {
    if (state.recording.get()) return;
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Проверить Whisper на WAV");
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("WAV audio", "*.wav"));
    File file = chooser.showOpenDialog(stage);
    if (file == null) return;
    state.busy.set(true);
    state.status.set("Whisper · распознавание WAV…");
    CompletableFuture.supplyAsync(() -> {
      try {
        ensureWhisperReady();
        byte[] wav = java.nio.file.Files.readAllBytes(file.toPath());
        if (wav.length > 32 * 1024 * 1024) throw new IllegalArgumentException("WAV больше 32 MB");
        return services.stt().convertSpeechToText(wav);
      } catch (Exception e) { throw new CompletionException(e); }
    }).whenComplete((text, error) -> Platform.runLater(() -> {
      state.busy.set(false);
      refreshStatuses();
      if (error != null) state.status.set("Whisper: " + GenerationPipeline.message(error));
      else {
        resultConsumer.accept(text);
        state.status.set("WAV распознан · в Main LLM не отправлялся");
      }
    }));
  }

  private void toggleMicrophoneMonitor(java.util.function.DoubleConsumer levelConsumer) {
    if (microphoneMonitor.isRunning()) {
      microphoneMonitor.stop();
      state.microphoneMonitor.set(false);
      state.status.set("Проверка микрофона остановлена");
      if (state.alwaysListen.get() && state.systemRunning.get()) startContinuousListening();
      return;
    }
    stopContinuousListening();
    try {
      microphoneMonitor.start(store.configs(), level -> Platform.runLater(() -> levelConsumer.accept(level)));
      state.microphoneMonitor.set(true);
      state.status.set("Проверка микрофона · говори в микрофон");
    } catch (Exception e) {
      state.microphoneMonitor.set(false);
      state.status.set("Микрофон: " + GenerationPipeline.message(e));
    }
  }

  private void saveSttSettings(SttConfig config) {
    store.configs().saveStt(config);
    if (!state.systemRunning.get()) return;
    stopContinuousListening();
    state.status.set("Перезапуск Whisper с новыми настройками…");
    CompletableFuture.runAsync(() -> {
      try {
        if (services != null && services.isInitialized()) services.stt().stop();
        whisperRuntime.restart(config);
        if (services != null) {
          if (!services.isInitialized()) services.initialize(store.configs());
          services.stt().start();
        }
      } catch (Exception e) { throw new CompletionException(e); }
    }).whenComplete((v, e) -> Platform.runLater(() -> {
      refreshStatuses();
      if (e != null) state.status.set("Whisper: " + GenerationPipeline.message(e));
      else {
        state.status.set("Whisper перезапущен");
        if (state.alwaysListen.get()) startContinuousListening();
      }
    }));
  }

  private void ensureWhisperReady() throws Exception {
    if (services == null) throw new IllegalStateException("Аудиосервисы недоступны");
    if (!services.isInitialized()) services.initialize(store.configs());
    SttConfig cfg = store.configs().stt();
    if (cfg.runtimeEnabled() && !whisperRuntime.isRunning()) whisperRuntime.start(cfg);
    if (!services.stt().isRunning()) services.stt().start();
  }

  private void startContinuousListening() {
    if (closed || !state.alwaysListen.get()) return;
    if (!state.systemRunning.get()) {
      state.voiceListenerStatus.set("Ждёт запуска системы");
      return;
    }
    if (state.recording.get() || microphoneMonitor.isRunning() || continuousVoice.isRunning()) return;
    try {
      continuousVoice.start(store.configs(), store.configs().stt(),
          () -> Platform.runLater(this::onContinuousSpeechStarted),
          this::dispatchContinuousUtterance,
          text -> Platform.runLater(() -> state.voiceListenerStatus.set(text)));
      state.voiceListenerStatus.set("Всегда слушаю · ожидание речи");
    } catch (Exception e) {
      state.voiceListenerStatus.set("Ошибка микрофона: " + GenerationPipeline.message(e));
      state.status.set(state.voiceListenerStatus.get());
    }
  }

  private void stopContinuousListening() {
    continuousVoice.stop();
    if (!state.alwaysListen.get()) state.voiceListenerStatus.set("Выключено");
    else state.voiceListenerStatus.set("Приостановлено");
  }

  private void onContinuousSpeechStarted() {
    boolean generating = pipeline != null && pipeline.isGenerating();
    boolean buffer = state.voiceInputMode.get() == VoiceInputMode.BUFFER && generating;
    bufferCurrentVoiceUtterance.set(buffer);
    if (state.voiceInputMode.get() == VoiceInputMode.INTERRUPT) {
      interruptForVoice();
    } else if (buffer) {
      state.voiceListenerStatus.set("Слышу речь · добавлю в буфер после распознавания");
    }
  }

  private void interruptForVoice() {
    if (!state.busy.get() || pipeline == null || !pipeline.isGenerating()) return;
    conversationVersion++;
    String partial = streamingAnswer.toString();
    pipeline.cancelPreservingContext(partial);
    if (services != null && services.isInitialized()) services.player().cancelSpeech();
    if (streamingMessageIndex >= 0 && streamingMessageIndex < state.messages.size()) {
      ChatMessage current = state.messages.get(streamingMessageIndex);
      if (current.text() == null || current.text().isBlank()) {
        state.messages.remove(streamingMessageIndex);
      } else {
        state.messages.set(streamingMessageIndex, new ChatMessage("Nina", current.text()));
      }
    }
    streamingMessageIndex = -1;
    thinkMessageIndex = -1;
    streamingAnswer.setLength(0);
    responseShown = false;
    pendingMemoryRequest = null;
    pendingMemorySuppressed = false;
    state.activeAgent.set(AgentType.IDLE);
    state.busy.set(false);
    state.status.set("Перебивание · слушаю тебя…");
  }

  private void dispatchContinuousUtterance(byte[] wav) {
    if (wav == null || wav.length == 0) return;
    voiceWavQueue.add(new PendingVoiceWav(wav, bufferCurrentVoiceUtterance.getAndSet(false)));
    drainVoiceWavQueue();
  }

  /**
   * Whisper is independent from the serial GenerationPipeline, so voice can keep being recognized
   * while Main LLM is answering. A single worker drains WAVs in order instead of dropping phrases.
   */
  private void drainVoiceWavQueue() {
    if (!voiceTranscriptionWorker.compareAndSet(false, true)) return;
    CompletableFuture.runAsync(() -> {
      try {
        PendingVoiceWav pendingWav;
        while ((pendingWav = voiceWavQueue.poll()) != null) {
          final PendingVoiceWav item = pendingWav;
          try {
            ensureWhisperReady();
            String text = services.stt().convertSpeechToText(item.wav());
            Platform.runLater(() -> handleContinuousTranscript(text, null, item.bufferAfterRecognition()));
          } catch (Exception error) {
            Platform.runLater(() -> handleContinuousTranscript(null, error, item.bufferAfterRecognition()));
          }
        }
      } finally {
        voiceTranscriptionWorker.set(false);
        if (!voiceWavQueue.isEmpty()) drainVoiceWavQueue();
        else Platform.runLater(this::flushBufferedVoiceIfReady);
      }
    });
  }

  private void handleContinuousTranscript(String text, Throwable error, boolean forceBuffer) {
    if (error != null) {
      state.status.set("Whisper: " + GenerationPipeline.message(error));
      return;
    }
    String recognized = text == null ? "" : text.strip();
    if (recognized.isBlank()) {
      state.voiceListenerStatus.set("Whisper · речь не распознана");
      return;
    }

    if (forceBuffer || (state.voiceInputMode.get() == VoiceInputMode.BUFFER && state.busy.get())) {
      bufferedVoiceMessages.addLast(recognized);
      state.voiceBufferSize.set(bufferedVoiceMessages.size());
      state.voiceListenerStatus.set("Буфер · " + bufferedVoiceMessages.size() + " фраз");
      state.status.set("Голосовой буфер: " + bufferedVoiceMessages.size() + " фраз");
      return;
    }

    // If an interruption was requested, speechStarted already cancelled the old generation.
    // In the tiny race where the UI is still busy, keep the phrase rather than losing it.
    if (state.busy.get() || state.recording.get()) {
      bufferedVoiceMessages.addLast(recognized);
      state.voiceBufferSize.set(bufferedVoiceMessages.size());
      state.voiceListenerStatus.set("Буфер · " + bufferedVoiceMessages.size() + " фраз");
      return;
    }

    if (!bufferedVoiceMessages.isEmpty()) {
      bufferedVoiceMessages.addLast(recognized);
      state.voiceBufferSize.set(bufferedVoiceMessages.size());
      flushBufferedVoiceIfReady();
    } else {
      state.status.set("Распознано: " + recognized);
      send(recognized);
    }
  }

  /** Sends all phrases accumulated during one busy period as a single coherent next user turn. */
  private void flushBufferedVoiceIfReady() {
    if (closed || bufferedVoiceMessages.isEmpty() || state.busy.get() || state.recording.get()) return;
    if (voiceTranscriptionWorker.get() || !voiceWavQueue.isEmpty()) return;
    if (!state.systemRunning.get()) return;
    StringBuilder combined = new StringBuilder();
    while (!bufferedVoiceMessages.isEmpty()) {
      String part = bufferedVoiceMessages.removeFirst();
      if (part == null || part.isBlank()) continue;
      if (combined.length() > 0) combined.append(' ');
      combined.append(part.strip());
    }
    state.voiceBufferSize.set(0);
    if (combined.length() == 0) return;
    String message = combined.toString();
    state.status.set("Отправляю голосовой буфер…");
    send(message);
  }

  private void clearVoiceBuffer() {
    bufferedVoiceMessages.clear();
    voiceWavQueue.clear();
    bufferCurrentVoiceUtterance.set(false);
    manualRecordingShouldBuffer = false;
    state.voiceBufferSize.set(0);
  }

  private void speakTest(String text) {
    if (pipeline == null || state.busy.get() || text.isBlank()) return;
    state.busy.set(true);
    long version = conversationVersion;
    pipeline
        .testSpeech(text)
        .whenComplete(
            (v, e) ->
                Platform.runLater(
                    () -> {
                      if (closed || version != conversationVersion) return;
                      state.busy.set(false);
                      refreshStatuses();
                      state.status.set(
                          e == null ? "Тест TTS завершён" : GenerationPipeline.message(e));
                    }));
  }

  private void refreshModels() {
    if (pipeline == null || state.busy.get()) return;
    state.busy.set(true);
    long version = conversationVersion;
    pipeline
        .refreshModels()
        .whenComplete(
            (models, e) ->
                Platform.runLater(
                    () -> {
                      if (closed || version != conversationVersion) return;
                      state.busy.set(false);
                      refreshStatuses();
                      if (e != null) state.status.set(GenerationPipeline.message(e));
                      else {
                        state.availableModels.setAll(models);
                        if ((state.selectedModel.get() == null
                                || state.selectedModel.get().isBlank())
                            && !models.isEmpty()) state.selectedModel.set(models.get(0));
                        state.status.set("Список моделей обновлён");
                        navigate("Модели");
                      }
                    }));
  }

  public void clearContext() {
    if (pipeline == null) {
      state.status.set("Системный пайплайн недоступен");
      return;
    }
    if (state.busy.get() || state.recording.get()) return;
    try {
      int saved = pipeline.flushAndClearConversationContext();
      conversationVersion++;
      pendingMemoryRequest = null;
      state.status.set(saved > 0
          ? "Context cleared · сохранено в память: " + saved + " сообщений"
          : "Context cleared · контекст был пуст");
    } catch (RuntimeException e) {
      state.status.set("Не удалось очистить контекст: " + GenerationPipeline.message(e));
      System.out.println("[Context] clear failed: " + e);
    }
  }

  public void newChat() {
    if (pipeline != null) pipeline.resetConversation();
    clearVoiceBuffer();
    state.recording.set(false);
    conversationVersion++;
    state.messages.clear();
    state.busy.set(false);
    state.status.set("Новый чат");
    navigate("Чат");
  }


  private void attachFile() {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Добавить файл в проект");
    File file = chooser.showOpenDialog(stage);
    if (file != null) {
      Profile profile = state.activeProfile.get() == null ? Profile.NORMAL : state.activeProfile.get();
      var routes = state.fileRoutes(profile);
      routes.remove(file.getAbsolutePath());
      routes.add(0, file.getAbsolutePath());
      store.save(state);
      state.status.set(
          "Файл добавлен в профиль " + profile.label() + ": " + file.getName()
              + ". Содержимое не отправлено в модель.");
    }
  }

  private void openProject() {
    DirectoryChooser chooser = new DirectoryChooser();
    chooser.setTitle("Открыть проект");
    File file = chooser.showDialog(stage);
    if (file != null) {
      Profile profile = state.activeProfile.get() == null ? Profile.NORMAL : state.activeProfile.get();
      var routes = state.fileRoutes(profile);
      routes.remove(file.getAbsolutePath());
      routes.add(0, file.getAbsolutePath());
      store.save(state);
      state.status.set("Проект добавлен в профиль " + profile.label() + ": " + file.getName());
      navigate("Файлы"); // Legacy route opens Память → Файловая память.
    }
  }

  private void addTask() {
    TextInputDialog d = new TextInputDialog();
    d.initOwner(stage);
    d.setTitle("Новое задание");
    d.setHeaderText("Какую задачу добавить?");
    d.getDialogPane()
        .getStylesheets()
        .add(getClass().getResource("/com/neuralsoul/theme.css").toExternalForm());
    d.showAndWait()
        .filter(s -> !s.isBlank())
        .ifPresent(
            s -> {
              state.tasks.add(s.trim());
              state.status.set("Задача добавлена в список. Исполнитель не подключён.");
              navigate("Задачи");
            });
  }


  private void flushMemory() {
    // Request/answer pairs are persisted continuously. Do not duplicate the
    // complete chat as one huge memory record on shutdown.
    memory.save();
  }

  private boolean requestFilePermission(FilePermissionRequest request) {
    java.util.concurrent.CompletableFuture<Boolean> decision = new java.util.concurrent.CompletableFuture<>();
    Runnable show = () -> {
      try {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle("NeuralSoul · Подтверждение инструмента");
        String action = switch (request.operation()) {
          case "read" -> "Прочитать файл";
          case "write" -> "Записать файл";
          case "edit" -> "Изменить файл";
          default -> request.operation();
        };
        alert.setHeaderText(action + "?");
        alert.setContentText("Путь:\n"
            + request.path() + "\n\nОтказ остановит текущую генерацию.");
        TextArea preview = new TextArea(request.details());
        preview.setEditable(false);
        preview.setWrapText(false);
        preview.setPrefColumnCount(80);
        preview.setPrefRowCount(Math.min(22, Math.max(6, request.details().split("\\R", -1).length + 2)));
        UiStyles.monospace(preview);
        alert.getDialogPane().setExpandableContent(preview);
        alert.getDialogPane().setExpanded(true);
        ButtonType accept = new ButtonType("Accept", ButtonBar.ButtonData.OK_DONE);
        ButtonType deny = new ButtonType("Deny", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(accept, deny);
        decision.complete(alert.showAndWait().orElse(deny) == accept);
      } catch (Throwable e) {
        decision.complete(false);
      }
    };
    if (Platform.isFxApplicationThread()) show.run(); else Platform.runLater(show);
    try { return decision.get(); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    catch (java.util.concurrent.ExecutionException e) { return false; }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    System.out.println("[Lifecycle] AppController closing");
    memoryWriter.shutdown();
    try {
      if (!memoryWriter.awaitTermination(3, TimeUnit.SECONDS))
        System.out.println("[Memory] memory-writer did not finish within 3 seconds");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    flushMemory();
    stopContinuousListening();
    microphoneMonitor.close();
    webSearchAgent.close();
    runtimeLifecycle.close();
    backend.close();
    try {
      store.save(state);
    } catch (RuntimeException error) {
      System.err.println(error.getMessage());
    }
  }
}
