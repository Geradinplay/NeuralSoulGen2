package com.neuralsoul.pipeline;

import com.neuralsoul.config.*;
import com.neuralsoul.service.ServiceLocator;
import com.neuralsoul.memory.*;
import com.neuralsoul.model.Profile;
import com.neuralsoul.service.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import com.neuralsoul.files.FileToolService;
import com.neuralsoul.service.http.Json;
import com.neuralsoul.files.FilePermissionHandler;
import com.neuralsoul.files.FilePermissionRequest;
import com.neuralsoul.model.ModelRole;
import com.neuralsoul.web.WebSearchAgent;
import com.neuralsoul.runtime.GeneralAgentRuntimeManager;
import com.neuralsoul.runtime.SummarizationRuntimeManager;
import com.neuralsoul.service.tts.StreamingSpeechQueue;
import com.neuralsoul.service.tts.RoutingTtsService;
import com.neuralsoul.service.tts.TtsTrace;
import com.neuralsoul.config.SpeechGenerationSettingsStore;

/** Serial worker owns inference; epoch invalidates late results after Stop/New chat. */
public final class GenerationPipeline implements ManagedService {
  private volatile Map<String, String> generalSystemPrompts = Map.of();
  public synchronized void setGeneralAgentSystemPrompts(Map<String, String> prompts) {
    if (running) throw new IllegalStateException("Остановите систему перед изменением промптов");
    Map<String, String> normalized = new LinkedHashMap<>();
    prompts.forEach((key, value) -> normalized.put(normalizeModelKey(key), value));
    generalSystemPrompts = Map.copyOf(normalized);
  }
  private String generalSystemPrompt(String modelKey) {
    return generalSystemPrompts.getOrDefault(normalizeModelKey(modelKey), agentSystemPrompt(ModelRole.GENERAL));
  }
  private volatile Map<ModelRole, String> agentSystemPrompts = Map.of();
  public synchronized void setAgentSystemPrompts(Map<ModelRole, String> prompts) {
    if (running) throw new IllegalStateException("Остановите систему перед изменением промптов агентов");
    agentSystemPrompts = Map.copyOf(prompts);
  }
  private String agentSystemPrompt(ModelRole role) {
    return agentSystemPrompts.getOrDefault(role, AgentPrompts.defaultPrompt(role));
  }

  private final ConfigLocator configs;
  private final ServiceLocator services;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "generation-pipeline");
            t.setDaemon(true);
            return t;
          });
  private final List<ConversationTurn> history = new ArrayList<>();
  private final List<Consumer<PipelineEvent>> listeners = new CopyOnWriteArrayList<>();
  private boolean running;
  private long generation;
  private Future<?> task;
  private CompletableFuture<?> pending;
  private volatile StreamingSpeechQueue activeTtsQueue;
  /** User turn currently owned by the active generation; used by voice interruption. */
  private long activeRequestGeneration = -1;
  private String activeRequestText = "";
  private volatile int contextMessageLimit = 20;
  private volatile String personalityPrompt = "";
  private volatile String generalPrompt = "";
  private volatile String roleplayPersonalityPrompt = "";
  private volatile String roleplayGeneralPrompt = "";
  private final EnumMap<Profile, String> profilePersonalityPrompts = new EnumMap<>(Profile.class);
  private final EnumMap<Profile, String> profileGeneralPrompts = new EnumMap<>(Profile.class);
  private volatile String memoryAgentPrompt = DEFAULT_MEMORY_AGENT_PROMPT;
  private volatile boolean memoryAgentEnabled = true;
  private final FileToolService fileTool = new FileToolService();
  private volatile FilePermissionHandler filePermissionHandler = request -> false;
  private volatile Set<ModelRole> availableAgentRoles = Set.of();
  private final EnumMap<Profile, EnumMap<ModelRole, String>> agentCallTemplates = new EnumMap<>(Profile.class);
  private final EnumMap<Profile, LinkedHashMap<String, String>> generalAgentCallTemplates = new EnumMap<>(Profile.class);
  private final EnumMap<Profile, LinkedHashMap<String, String>> generalAgentNames = new EnumMap<>(Profile.class);
  private volatile WebSearchAgent webSearchAgent;
  private volatile GeneralAgentRuntimeManager generalAgentRuntime;
  private volatile SummarizationRuntimeManager summarizationRuntime;
  private volatile String mainModelKey = "";
  private volatile int mainContextWindowTokens = 4096;
  /** Compressed state of conversation turns removed from raw history. Current-chat only; never Qdrant recall. */
  private String conversationSummary = "";

  public void setFileSearchMaxVisited(int value) {
    fileTool.setSearchMaxVisited(value);
    System.out.println("[FileTool] search maxVisited configured=" + value);
  }

  public void setAvailableAgentRoles(Set<ModelRole> roles) {
    this.availableAgentRoles = roles == null ? Set.of() : Set.copyOf(roles);
  }

  public synchronized void setAgentCallTemplates(Profile profile, Map<ModelRole, String> templates) {
    if (running) throw new IllegalStateException("Agent call templates cannot be changed while GenerationPipeline is running");
    Profile p = profile == null ? Profile.NORMAL : profile;
    EnumMap<ModelRole, String> copy = new EnumMap<>(ModelRole.class);
    for (ModelRole role : com.neuralsoul.model.ProfileState.callTemplateRoles()) {
      // Built-in agent/tool syntax is an internal runtime protocol. Only GENERAL templates
      // are user-configurable; keep legacy saved values from changing parser behaviour.
      copy.put(role, com.neuralsoul.model.ProfileState.defaultAgentCallTemplate(role));
    }
    agentCallTemplates.put(p, copy);
  }

  public void setWebSearchAgent(WebSearchAgent agent) {
    this.webSearchAgent = agent;
  }
  public void setGeneralAgentRuntime(GeneralAgentRuntimeManager runtime) {
    this.generalAgentRuntime = runtime;
  }
  public void setSummarizationRuntime(SummarizationRuntimeManager runtime) {
    this.summarizationRuntime = runtime;
  }
  public void setMainContextWindowTokens(int tokens) {
    this.mainContextWindowTokens = Math.max(256, tokens);
  }
  public void setMainModelKey(String modelKey) {
    this.mainModelKey = normalizeModelKey(modelKey);
  }


  public synchronized void setGeneralAgentCallTemplates(
      Profile profile, Map<String, String> templates, Map<String, String> names) {
    if (running) throw new IllegalStateException("General agent call templates cannot be changed while GenerationPipeline is running");
    Profile p = profile == null ? Profile.NORMAL : profile;
    LinkedHashMap<String, String> copy = new LinkedHashMap<>();
    LinkedHashMap<String, String> nameCopy = new LinkedHashMap<>();
    if (templates != null) {
      templates.forEach((key, value) -> {
        String normalized = normalizeModelKey(key);
        if (validAgentTemplate(value)) copy.put(normalized, value);
      });
    }
    if (names != null) names.forEach((key, value) -> nameCopy.put(normalizeModelKey(key), value));
    generalAgentCallTemplates.put(p, copy);
    generalAgentNames.put(p, nameCopy);
  }

  public void setFilePermissionHandler(FilePermissionHandler handler) {
    this.filePermissionHandler = handler == null ? request -> false : handler;
  }

  public static final String DEFAULT_MEMORY_AGENT_PROMPT = """


MEMORY RECALL TOOL:
У тебя есть Memory Agent — саб-агент глубокого поиска по долговременной памяти. Ты сама решаешь, когда использовать его.
ОБЯЗАТЕЛЬНО вызови Memory Agent в любом из случаев:
1) пользователь прямо просит поискать в памяти, вызвать Memory Agent, Memory Recall или саб-агента памяти;
2) ты не знаешь ответа или не уверена в нём, и нужная информация могла быть в прошлых разговорах с пользователем;
3) для глубокого анализа текущей темы полезно поднять прошлые разговоры, факты, решения, предпочтения или другой долговременный контекст пользователя.
Не вызывай Memory Agent для простого уверенного ответа, если текущего контекста и автоматически найденной памяти достаточно.
Обычная автоматически найденная память НЕ запрещает вызов Memory Agent: если выполняется хотя бы одно из трёх правил выше, используй глубокий поиск.
Для вызова вставь команду [[MEMORY_RECALL:что именно нужно найти в прошлых разговорах]]. Команда может появиться после уже начатого обычного ответа: система перехватит её, скроет от пользователя, выполнит Memory Agent и даст тебе продолжить ответ. После начала команды не пиши текст первого прохода после неё.
Внутри команды сформулируй короткий конкретный смысловой запрос своими словами. Не копируй текст шаблона и никогда не отправляй слова 'поисковый запрос', 'краткий поисковый запрос', '<запрос>' или 'query'.
После вызова система пришлёт найденные записи Memory Agent. Проанализируй их вместе с текущим контекстом и затем продолжи/заверши обычный ответ пользователю. Не повторяй уже выведенную до команды часть ответа, не показывай пользователю MEMORY_RECALL и не вызывай его повторно для этого запроса.
""".trim();

  public void setPrompts(String personality, String general) {
    setPromptSnapshot(personality, general, memoryAgentPrompt, memoryAgentEnabled);
  }

  public void setPrompts(String personality, String general, String memoryAgent) {
    setPromptSnapshot(personality, general, memoryAgent, memoryAgentEnabled);
  }

  /**
   * Captures the prompt configuration for one runtime session.
   * AppController calls this before start(); GUI prompt fields are locked while running.
   */
  public synchronized void setPromptSnapshot(
      String personality, String general, String memoryAgent, boolean memoryEnabled) {
    if (running) {
      throw new IllegalStateException("Prompts cannot be changed while GenerationPipeline is running");
    }
    this.personalityPrompt = personality == null ? "" : personality.trim();
    this.generalPrompt = general == null ? "" : general.trim();
    this.memoryAgentPrompt = memoryAgent == null || memoryAgent.isBlank()
        ? DEFAULT_MEMORY_AGENT_PROMPT : memoryAgent.trim();
    this.memoryAgentEnabled = memoryEnabled;
  }

  public synchronized void setPromptSnapshot(
      String personality, String general, String roleplayPersonality, String roleplayGeneral,
      String memoryAgent, boolean memoryEnabled) {
    setPromptSnapshot(personality, general, memoryAgent, memoryEnabled);
    this.roleplayPersonalityPrompt = roleplayPersonality == null ? "" : roleplayPersonality.trim();
    this.roleplayGeneralPrompt = roleplayGeneral == null ? "" : roleplayGeneral.trim();
  }
  public synchronized void setProfilePromptSnapshot(Profile profile, String personality, String general) {
    if (running) throw new IllegalStateException("Prompts cannot be changed while GenerationPipeline is running");
    profilePersonalityPrompts.put(profile, personality == null ? "" : personality.trim());
    profileGeneralPrompts.put(profile, general == null ? "" : general.trim());
  }

  private volatile MemoryService memory;
  private volatile MemorySettings memorySettings;
  private final EnumMap<Profile, FileRouteSearchConfig> fileRouteSearchConfigs = new EnumMap<>(Profile.class);

  private record FileRouteSearchConfig(boolean enabled, List<String> routes) {}

  /** Snapshot supplied by the GUI before a request; avoids coupling the pipeline to JavaFX AppState. */
  public synchronized void setFileRouteSearchConfig(Profile profile, Collection<String> routes, boolean enabled) {
    Profile p = profile == null ? Profile.NORMAL : profile;
    List<String> copy = routes == null ? List.of() : routes.stream()
        .filter(Objects::nonNull).map(String::trim).filter(v -> !v.isBlank()).distinct().toList();
    fileRouteSearchConfigs.put(p, new FileRouteSearchConfig(enabled, copy));
  }

  public GenerationPipeline(ConfigLocator configs, ServiceLocator services) {
    this.configs = configs;
    this.services = services;
  }

  public void addListener(Consumer<PipelineEvent> listener) {
    listeners.add(listener);
  }

  public synchronized long generationId() {
    return generation;
  }

  @Override
  public synchronized void start() {
    if (running) return;
    if (!services.isInitialized()) services.initialize(configs);
    running = true;
  }

  @Override
  public synchronized boolean isRunning() {
    return running;
  }

  public synchronized boolean isGenerating() {
    return pending != null && !pending.isDone();
  }

  public CompletableFuture<GenerationResult> generate(
      String text, String model, String mode, boolean speak) {
    return generate(text, model, mode, Profile.NORMAL, speak);
  }

  public CompletableFuture<GenerationResult> generate(
      String text, String model, String mode, Profile profile, boolean speak) {
    Profile captured = profile == null ? Profile.NORMAL : profile;
    return submit(id -> process(id, text, model, mode, captured, speak));
  }

  /** STT-only path used by the Voice page and hands-free listener. It never calls the LLM. */
  public CompletableFuture<String> transcribe(byte[] wav) {
    return submit(id -> {
      emit(id, PipelineEvent.Type.STATUS, "Распознавание речи…");
      ensure(services.stt());
      check(id);
      String text = services.stt().convertSpeechToText(wav);
      check(id);
      emit(id, PipelineEvent.Type.STATUS, "Речь распознана");
      return text;
    });
  }

  /** STT-only WAV test; unlike generateFromAudioFile this does not send anything to Main LLM. */
  public CompletableFuture<String> transcribeFile(java.nio.file.Path file) {
    return submit(id -> {
      byte[] wav = java.nio.file.Files.readAllBytes(file);
      if (wav.length > 32 * 1024 * 1024) throw new IllegalArgumentException("WAV больше 32 MB");
      emit(id, PipelineEvent.Type.STATUS, "Распознавание WAV…");
      ensure(services.stt());
      check(id);
      String text = services.stt().convertSpeechToText(wav);
      check(id);
      emit(id, PipelineEvent.Type.STATUS, "WAV распознан");
      return text;
    });
  }

  public CompletableFuture<GenerationResult> generateFromSpeech(
      byte[] wav, String model, String mode, Profile profile, boolean speak) {
    return submit(
        id -> {
          emit(id, PipelineEvent.Type.STATUS, "Распознавание речи…");
          ensure(services.stt());
          check(id);
          String text = services.stt().convertSpeechToText(wav);
          check(id);
          emit(id, PipelineEvent.Type.TRANSCRIPT, text);
          return process(id, text, model, mode, profile, speak);
        });
  }

  public CompletableFuture<GenerationResult> generateFromAudioFile(
      java.nio.file.Path file, String model, String mode, Profile profile, boolean speak) {
    return submit(
        id -> {
          if (java.nio.file.Files.size(file) > 32 * 1024 * 1024)
            throw new IllegalArgumentException("WAV больше 32 MB");
          byte[] wav = java.nio.file.Files.readAllBytes(file);
          ensure(services.stt());
          String text = services.stt().convertSpeechToText(wav);
          check(id);
          emit(id, PipelineEvent.Type.TRANSCRIPT, text);
          return process(id, text, model, mode, profile, speak);
        });
  }

  private GenerationResult process(long id, String text, String model, String mode, Profile profile, boolean speak)
      throws Exception {
    // The former speech flag belongs to the legacy Workspace voice toggle. The CosyVoice
    // model card has its own persisted enable flag; respect it for *every* entry point
    // (typed chat, STT -> LLM and API requests), even when the old GUI toggle is off.
    boolean speakThisTurn = speak;
    if (!speakThisTurn && services.tts() instanceof RoutingTtsService routing && routing.isCosyEnabled()) {
      speakThisTurn = true;
      System.out.println("[CosyVoice] LLM voice output enabled by CosyVoice model settings (legacy voice toggle is off)");
    }
    System.out.println("[TTS/Pipeline] generation=" + id + " speak=" + speakThisTurn);
    TtsTrace.log("g" + id, "REQUEST_GATE", "speakArgument=" + speak
        + " finalSpeak=" + speakThisTurn + " profile=" + profile
        + " cosyEnabled=" + (services.tts() instanceof RoutingTtsService routing && routing.isCosyEnabled()));
    ProfileMemoryService profiles = memory instanceof ProfileMemoryService p ? p : null;
    if (profiles != null) profiles.enter(profile);
    try {
      return processInProfile(id, text, model, mode, profile, speakThisTurn);
    } finally {
      if (profiles != null) profiles.leave();
    }
  }

  private GenerationResult processInProfile(long id, String text, String model, String mode, Profile profile, boolean speak)
      throws Exception {
    if (text == null || text.isBlank()) throw new IllegalArgumentException("Пустое сообщение");
    markActiveRequest(id, text);
    emit(id, PipelineEvent.Type.STATUS, "Подключение к LLM…");
    ensure(services.llm());
    check(id);
    List<ConversationTurn> messages = context(id, text, mode, profile);

    // Safety net for obvious live-web requests. This is orchestration logic, not prompt text:
    // if Main LLM refuses/forgets to emit the configured WEB_SEARCH marker, the pipeline can
    // still obtain current public information and hand the structured result back to Main.
    if (shouldAutoRouteWebSearch(text)) {
      WebSearchAgent agent = webSearchAgent;
      if (availableAgentRoles.contains(ModelRole.WEB_SEARCH) && agent != null && agent.isReady()) {
        System.out.println("[WebSearch/Router] auto-route triggered: " + abbreviateForLog(text, 220));
        String webResult = executeSubAgent(
            id, new AgentCall(ModelRole.WEB_SEARCH, null, text.trim(), "[AUTO_WEB_SEARCH]"), model, profile);
        check(id);
        messages = new ArrayList<>(messages);
        messages.add(new ConversationTurn("system",
            "SUB_AGENT_RESULT [WEB_SEARCH]:\n" + webResult));
        System.out.println("[WebSearch/Router] result injected into Main context, chars=" + webResult.length());
      } else {
        System.out.println("[WebSearch/Router] request looks web-dependent, but WEB_SEARCH is unavailable");
      }
    }

    emit(id, PipelineEvent.Type.STATUS, "Генерация ответа…");

    // Every continuation is filtered; tool work remains local to this request.
    return processAgentLoop(id, text, model, profile, speak, messages,
        "Глубокий анализ".equals(mode) ? 16 : 8);
  }

  /** Turn-local agent loop. Only its final tool-free pass is committed to history. */
  private GenerationResult processAgentLoop(
      long id, String text, String model, Profile profile, boolean speak, List<ConversationTurn> initialMessages, int maxSteps)
      throws Exception {
    List<ConversationTurn> working = new ArrayList<>(initialMessages);
    Set<String> executedCalls = new HashSet<>();
    Map<String, String> agentResults = new HashMap<>();
    boolean suppressLongTermMemory = working.stream()
        .map(ConversationTurn::content)
        .anyMatch(GenerationPipeline::isUnverifiedWebResult);
    int consecutiveListCalls = 0;
    String finalPass = null;
    StreamingSpeechQueue pendingSpeechQueue = null;
    AgentCall lastAgentCall = null;
    String lastAgentResult = null;
    String lastToolLabel = null;
    String lastToolResult = null;

    for (int step = 1; step <= maxSteps; step++) {
      check(id);
      System.out.println("[Main/Turn] step " + step + "/" + maxSteps);
      String ttsTraceId = "g" + id + "-p" + step;
      TtsTrace.log(ttsTraceId, "LLM_PASS_START", "step=" + step + "/" + maxSteps
          + " speak=" + speak + " model=" + model + " messages=" + working.size());
      emit(id, PipelineEvent.Type.STATUS, "Обработка запроса · шаг " + step);

      if (step == maxSteps) {
        // Budget exhausted: forbid further tool calls so the model wraps up now
        // instead of looping until the hard cap throws.
        working.add(new ConversationTurn("system",
            "FINAL_STEP: Это последний шаг. Вызовы инструментов больше недоступны — не выдавай [[...]] маркеры. "
                + "Дай финальный ответ пользователю на основе уже собранных данных; если информации неполная, "
                + "честно укажи, чего не хватает."));
      }

      // Buffer the pass until we know it is a final answer, not a tool preamble.
      // Speak text as it arrives. Tool calls cancel speculative speech immediately;
      // each iteration gets a fresh queue so discarded agent drafts never continue playing.
      StreamingSpeechQueue speechQueue = null;
      if (speak && services.tts() instanceof RoutingTtsService routing && routing.isCosyEnabled()) {
        SpeechGenerationSettingsStore.Common ttsOptions =
            new SpeechGenerationSettingsStore(configs.directory()).load().common();
        // Cold start happens once, before LLM streaming, avoiding queue overflow while the GGUF loads.
        // A failed startup must not prevent the main text answer.
        try {
          TtsTrace.log(ttsTraceId, "TTS_INITIALIZE", "requesting persistent runtime");
          ensure(services.tts());
          speechQueue = new StreamingSpeechQueue(services.tts(), services.player(), ttsOptions, ttsTraceId);
          activeTtsQueue = speechQueue;
        } catch (Exception ttsStartError) {
          TtsTrace.log(ttsTraceId, "TTS_INITIALIZE_FAILED", message(ttsStartError));
          System.err.println("[CosyVoice] startup failed: " + message(ttsStartError));
        }
      } else {
        TtsTrace.log(ttsTraceId, "TTS_STREAM_SKIPPED", "speak=" + speak
            + " cosyEnabled=" + (services.tts() instanceof RoutingTtsService routing && routing.isCosyEnabled()));
      }
      final StreamingSpeechQueue activeSpeechQueue = speechQueue;
      StreamingMemoryToolFilter filter = new StreamingMemoryToolFilter(profile, ttsTraceId,
          chunk -> { if (activeSpeechQueue != null) activeSpeechQueue.accept(chunk); });

      MemoryRecallCall memoryCall = null;
      FileToolCall fileToolCall = null;
      AgentCall agentCall = null;
      String assistantTurn;
      try {
        try (TtsTrace.Scope traceScope = TtsTrace.with(ttsTraceId)) {
          services.llm().generateStreaming(working, model, filter::accept);
          filter.finish();
        }
        finalPass = filter.visibleText();
        TtsTrace.log(ttsTraceId, "LLM_PASS_END", "visibleChars=" + finalPass.length()
            + " step=" + step);
        if (activeSpeechQueue != null) {
          // Let audio play concurrently with the final answer/bookkeeping.
          activeSpeechQueue.finish();
          System.out.println("[CosyVoice/TTS] LLM stream complete; queued=" + activeSpeechQueue.submitted() + " phrases");
          TtsTrace.log(ttsTraceId, "LLM_TO_TTS_COMPLETE", "queuedPhrases=" + activeSpeechQueue.submitted());
          pendingSpeechQueue = activeSpeechQueue;
        }
        break; // no tool call => final answer
      } catch (MemoryToolDetected detected) {
        TtsTrace.log(ttsTraceId, "LLM_TOOL_ROUTE", "tool=MemoryToolDetected cancelling speculative voice");
        if (activeSpeechQueue != null) {
          activeSpeechQueue.cancel();
          if (activeTtsQueue == activeSpeechQueue) activeTtsQueue = null;
        }
        memoryCall = detected.call();
        assistantTurn = filter.agentTextForContinuation()
            + formatCall(profile, ModelRole.MEMORY, memoryCall.query());
      } catch (FileToolDetected detected) {
        TtsTrace.log(ttsTraceId, "LLM_TOOL_ROUTE", "tool=FileToolDetected cancelling speculative voice");
        if (activeSpeechQueue != null) {
          activeSpeechQueue.cancel();
          if (activeTtsQueue == activeSpeechQueue) activeTtsQueue = null;
        }
        fileToolCall = detected.call();
        assistantTurn = filter.agentTextForContinuation() + fileToolCall.raw();
      } catch (AgentToolDetected detected) {
        TtsTrace.log(ttsTraceId, "LLM_TOOL_ROUTE", "tool=AgentToolDetected cancelling speculative voice");
        if (activeSpeechQueue != null) {
          activeSpeechQueue.cancel();
          if (activeTtsQueue == activeSpeechQueue) activeTtsQueue = null;
        }
        agentCall = detected.call();
        assistantTurn = filter.agentTextForContinuation() + agentCall.raw();
      } catch (Exception | Error failure) {
        TtsTrace.log(ttsTraceId, "LLM_PASS_FAILED", failure.getClass().getSimpleName()
            + ": " + failure.getMessage());
        if (activeSpeechQueue != null) {
          activeSpeechQueue.cancel();
          if (activeTtsQueue == activeSpeechQueue) activeTtsQueue = null;
        }
        throw failure;
      }

      check(id);
      working.add(new ConversationTurn("assistant", assistantTurn));
      if (step == maxSteps) break; // no budget left to execute another tool call

      if (agentCall != null) {
        String key = "AGENT:" + agentCall.role() + ":" + (agentCall.modelKey() == null ? "" : agentCall.modelKey() + ":") + agentCall.task().trim().toLowerCase(Locale.ROOT);
        if (!executedCalls.add(key)) {
          System.out.println("[DeepAnalysis] duplicate sub-agent call blocked: " + key);
          String previousResult = agentResults.getOrDefault(key,
              "The requested sub-agent task was already executed, but no reusable result was recorded.");
          if (agentCall.role() == ModelRole.WEB_SEARCH && isUnverifiedWebResult(previousResult)) {
            suppressLongTermMemory = true;
          }
          System.out.println("[Main/Turn] duplicate agent call -> soft finalization from previous result");
          finalPass = finalizeFromToolResult(id, text, model, agentCall, previousResult);
          break;
        }
        String result = executeSubAgent(id, agentCall, model, profile);
        if (agentCall.role() == ModelRole.WEB_SEARCH) {
          if (isVerifiedWebResult(result)) suppressLongTermMemory = false;
          else if (isUnverifiedWebResult(result)) suppressLongTermMemory = true;
        }
        lastAgentCall = agentCall;
        lastAgentResult = result;
        agentResults.put(key, result);
        working.add(new ConversationTurn("system", "SUB_AGENT_RESULT [" + agentCall.role() + (agentCall.modelKey() == null ? "" : " · " + agentCall.modelKey()) + "]:\n" + result));
      } else if (memoryCall != null) {
        String key = "MEMORY_RECALL:" + memoryCall.query().trim().toLowerCase(Locale.ROOT);
        if (!executedCalls.add(key)) {
          System.out.println("[DeepAnalysis] duplicate tool call blocked: " + key);
          working.add(new ConversationTurn("system",
              "This exact MEMORY_RECALL call was already executed. Do not repeat it. "
                  + "Use the existing result, reformulate the search, choose another tool, or finish the answer."));
          continue;
        }
        System.out.println("[DeepAnalysis] MEMORY_RECALL: " + memoryCall.query());
        List<MemoryRecord> recalled = recallMemorySubAgent(id, memoryCall.query());
        lastToolLabel = "MEMORY_RECALL: " + memoryCall.query();
        lastToolResult = buildMemoryRecallResultDeep(memoryCall.query(), recalled);
        working.add(new ConversationTurn("system", lastToolResult));
      } else if (fileToolCall != null) {
        String key = "FILE_TOOL:" + fileToolCall.operation().toLowerCase(Locale.ROOT) + ":" + String.join("|", fileToolCall.args());
        if (!executedCalls.add(key)) {
          System.out.println("[DeepAnalysis] duplicate tool call blocked: " + key);
          working.add(new ConversationTurn("system", "This exact FILE_TOOL call was already executed. Use its result, change the operation/arguments, or finish."));
          continue;
        }
        System.out.println("[DeepAnalysis] FILE_TOOL " + fileToolCall.operation() + " args=" + fileToolCall.args().size());
        String toolResult = executeFileTool(id, profile, fileToolCall);
        if ("list".equalsIgnoreCase(fileToolCall.operation())) {
          consecutiveListCalls++;
        } else {
          consecutiveListCalls = 0;
        }
        if (consecutiveListCalls >= 2) {
          toolResult += "\nTOOL_HINT: You have used list repeatedly. Do not continue walking the directory tree with list. "
              + "If the target path is unknown, use FILE_TOOL:search on a known root with the target name. "
              + "Use list again only when you need the immediate contents of a specific known directory.";
        }
        working.add(new ConversationTurn("system", toolResult));
        lastToolLabel = "FILE_TOOL: " + fileToolCall.operation();
        lastToolResult = toolResult;
      }

    }

    boolean leakyMarkers = finalPass != null && TOOL_MARKER_PATTERN.matcher(finalPass).find();
    if (finalPass == null || finalPass.isBlank() || leakyMarkers) {
      // Budget exhausted or the model tried to sneak one more tool call in:
      // finalize gracefully from the collected research instead of failing the whole turn.
      if (lastAgentCall != null && lastAgentResult != null) {
        System.out.println("[Main/Turn] budget exhausted -> forced finalization from last sub-agent result");
        if (pendingSpeechQueue != null) { pendingSpeechQueue.cancel(); pendingSpeechQueue = null; }
        finalPass = finalizeFromToolResult(id, text, model, lastAgentCall, lastAgentResult);
      } else if (lastToolResult != null) {
        System.out.println("[Main/Turn] budget exhausted -> forced finalization from last tool result");
        if (pendingSpeechQueue != null) { pendingSpeechQueue.cancel(); pendingSpeechQueue = null; }
        finalPass = finalizeFromToolResult(id, text, model,
            new AgentCall(ModelRole.GENERAL, null, lastToolLabel, ""), lastToolResult);
      }
    }
    if (finalPass == null || finalPass.isBlank()) {
      if (pendingSpeechQueue != null) pendingSpeechQueue.cancel();
      // No unfiltered "last chance" call: it could leak another tool call into memory.
      throw new IllegalStateException("Main LLM не сформировала финальный ответ за " + maxSteps + " шагов");
    }
    if (TOOL_MARKER_PATTERN.matcher(finalPass).find()) {
      if (pendingSpeechQueue != null) pendingSpeechQueue.cancel();
      throw new IllegalStateException("Main LLM вернула служебный вызов вместо финального ответа");
    }
    String answer = finalPass;
    commitExchange(id, text, answer, profile);
    if (suppressLongTermMemory) {
      System.out.println("[Memory] long-term save suppressed for generation=" + id + " reason=unverified_web_result");
      emit(id, PipelineEvent.Type.MEMORY_SKIP, "unverified_web_result");
    }
    emit(id, PipelineEvent.Type.RESPONSE, answer);

    String warning = "";
    if (speak) {
      try {
        emit(id, PipelineEvent.Type.STATUS, "Синтез речи…");
        ensure(services.tts());
        check(id);
        if (pendingSpeechQueue != null) {
          TtsTrace.log("g" + id, "TTS_AWAIT_STREAM", "queued=" + pendingSpeechQueue.submitted());
          emit(id, PipelineEvent.Type.STATUS, "Потоковое воспроизведение…");
          try { pendingSpeechQueue.await(); }
          finally { if (activeTtsQueue == pendingSpeechQueue) activeTtsQueue = null; }
        } else {
          TtsTrace.log("g" + id, "TTS_FALLBACK", "streamQueueUnavailable=true chars=" + answer.length());
          emit(id, PipelineEvent.Type.STATUS, "Воспроизведение…");
          try (TtsTrace.Scope traceScope = TtsTrace.with("g" + id + "-fallback")) {
            services.tts().speak(answer, services.player());
            services.player().finishSpeech();
          }
        }
        TtsTrace.log("g" + id, "TTS_TURN_DONE", "answerChars=" + answer.length());
        check(id);
      } catch (Exception e) {
        TtsTrace.log("g" + id, "TTS_TURN_FAILED", e.getClass().getSimpleName()
            + ": " + message(e));
        check(id);
        warning = "Ответ получен, но озвучивание недоступно: " + message(e);
        emit(id, PipelineEvent.Type.WARNING, warning);
      }
    }
    emit(id, PipelineEvent.Type.STATUS, warning.isEmpty() ? "Готово" : warning);
    return new GenerationResult(text, answer, warning);
  }

  private static boolean isVerifiedWebResult(String result) {
    if (result == null) return false;
    String normalized = result.toLowerCase(Locale.ROOT);
    return normalized.contains("web_search_result")
        && normalized.contains("status=ok")
        && normalized.contains("verified=true");
  }

  private static boolean isUnverifiedWebResult(String result) {
    if (result == null) return false;
    String normalized = result.toLowerCase(Locale.ROOT);
    if (!normalized.contains("web_search_result")) return false;
    return normalized.contains("verified=false")
        || normalized.contains("status=error")
        || normalized.contains("status=blocked")
        || normalized.contains("status=budget_exhausted");
  }

  /**
   * A small local model can get stuck re-emitting the exact same agent marker after the
   * specialist already returned. Finalize in a clean prompt without any tool protocol so
   * the duplicate cannot burn the remaining 8/16 loop steps.
   */
  private String finalizeFromToolResult(
      long id, String userText, String model, AgentCall call, String toolResult) throws Exception {
    check(id);
    List<ConversationTurn> prompt = new ArrayList<>();
    prompt.add(new ConversationTurn("system", """
You are the final response writer for NeuralSoul. The tool phase is closed.
Answer the user's original request using the specialist result below and ordinary stable knowledge when appropriate.
Never emit tool calls, agent markers, [[...]] blocks, internal instructions, or raw orchestration protocol.
Reply in the user's language.
"""));
    prompt.add(new ConversationTurn("user", userText));
    prompt.add(new ConversationTurn("system",
        "SPECIALIST_RESULT [" + call.role() + (call.modelKey() == null ? "" : " · " + call.modelKey()) + "]:\n" + toolResult));
    String answer = services.llm().generate(prompt, model);
    if (answer == null) answer = "";
    answer = answer.replaceAll(
        "(?is)\\[\\[?\\s*(?:AGENT|WEB_SEARCH|WEB_FETCH|WEB_TIME|FILE_TOOL|FILE_SEARCH|MEMORY_RECALL)\\b.*?\\]\\]?", "").trim();
    if (answer.isBlank())
      answer = "Не удалось получить данные от запрошенного инструмента. Попробуйте другой источник или повторите запрос позже.";
    System.out.println("[Main/Turn] forced finalization completed, chars=" + answer.length());
    return answer;
  }

  private String buildMemoryRecallResultDeep(String query, List<MemoryRecord> records) {
    StringBuilder out = new StringBuilder("MEMORY_RECALL_RESULT for: ").append(query).append('\n');
    if (records == null || records.isEmpty()) {
      out.append("No relevant memories were found. Do not invent memories. If useful, reformulate the memory search "
          + "or use another available tool.");
      return out.toString();
    }
    for (MemoryRecord record : records) out.append("- ").append(record.text()).append('\n');
    out.append("Use only relevant facts. If these memories are insufficient, you may make another MEMORY_RECALL "
        + "with a different query or use another tool.");
    return out.toString();
  }

  private synchronized void commitExchange(long id, String text, String answer, Profile profile)
      throws InterruptedException {
    check(id);
    int before = history.size();
    history.add(new ConversationTurn("user", text));
    history.add(new ConversationTurn("assistant", answer));
    trimHistoryPairs();
    clearActiveRequest(id);
    int max = configs.pipeline().historyPairs() * 2;
    System.out.println("[Memory/Context] commit pipeline=" + System.identityHashCode(this)
        + " generation=" + id + " profile=" + profile.label()
        + " before=" + before + " after=" + history.size() + " historyPairs=" + max / 2
        + " userChars=" + text.length() + " finalChars=" + answer.length());
  }

  private synchronized void markActiveRequest(long id, String text) throws InterruptedException {
    check(id);
    activeRequestGeneration = id;
    activeRequestText = text == null ? "" : text.strip();
  }

  private synchronized void clearActiveRequest(long id) {
    if (activeRequestGeneration != id) return;
    activeRequestGeneration = -1;
    activeRequestText = "";
  }

  private void trimHistoryPairs() {
    // Do not discard ordinary turns here. The next context build rolls old pairs into
    // conversationSummary before removing them. Keep only a very high emergency cap so a
    // pathological interrupted session cannot grow the in-memory list forever.
    final int emergencyMaxMessages = 400;
    while (history.size() > emergencyMaxMessages && history.size() >= 2) {
      history.remove(0);
      history.remove(0);
      System.out.println("[Context/Summary] emergency raw-history drop: summarizer was not reached in time");
    }
  }

  private synchronized List<ConversationTurn> context(long id, String text, String mode, Profile profile) throws InterruptedException {
    PipelineConfig p = configs.pipeline();

    // Keep the beginning of the prompt stable. llama.cpp can then reuse the KV/prompt cache
    // for the system prompt + conversation history instead of re-evaluating a different
    // memory block at the start of every request.
    String activeGeneral = profileGeneralPrompts.getOrDefault(profile,
        profile == Profile.ROLEPLAY ? roleplayGeneralPrompt : generalPrompt);
    String activePersonality = profilePersonalityPrompts.getOrDefault(profile,
        profile == Profile.ROLEPLAY ? roleplayPersonalityPrompt : personalityPrompt);
    String baseGeneral = activeGeneral.isBlank() ? configs.llm().systemPrompt() : activeGeneral;
    StringBuilder stableSystem = new StringBuilder();
    if (!activePersonality.isBlank())
      stableSystem.append("[PERSONALITY]\n").append(activePersonality).append("\n\n");
    stableSystem.append("[GENERAL]\n").append(baseGeneral);
    stableSystem.append("\n\n").append(agentSystemPrompt(ModelRole.FILE_SEARCH));
    if (availableAgentRoles.contains(ModelRole.MEMORY)) {
      stableSystem.append("\n\n[MEMORY SEARCH CALL]\nUse this exact configured syntax when deep memory lookup is needed (it overrides legacy examples elsewhere in the prompt): ")
          .append(agentTemplate(profile, ModelRole.MEMORY));
    }
    if (!availableAgentRoles.isEmpty()) {
      StringBuilder agents = new StringBuilder("\n\n[SUB-AGENTS]\nYou may delegate specialist work to enabled sub-agents. Calls are hidden from the user. Use EXACTLY the configured syntax shown below and replace {task} with a concrete self-contained task:\n");
      for (ModelRole role : availableAgentRoles.stream()
          .filter(com.neuralsoul.model.ProfileState.agentRoles()::contains)
          .sorted(Comparator.comparing(Enum::name)).toList()) {
        agents.append("- ").append(role.name()).append(": ").append(agentTemplate(profile, role)).append('\n');
      }
      LinkedHashMap<String, String> generalTemplates = generalAgentCallTemplates.get(profile);
      LinkedHashMap<String, String> generalNamesForProfile = generalAgentNames.get(profile);
      if (generalTemplates != null) {
        for (Map.Entry<String, String> entry : generalTemplates.entrySet()) {
          String name = generalNamesForProfile == null
              ? entry.getKey()
              : generalNamesForProfile.getOrDefault(entry.getKey(), entry.getKey());
          agents.append("- GENERAL · ").append(name).append(": ").append(entry.getValue()).append('\n');
        }
      }
      agents.append("Delegate only when useful; after receiving SUB_AGENT_RESULT, verify it and continue as Nina. "
          + "REASONING is for difficult planning/analysis, CODING for code, WEB_SEARCH for internet search and web-source analysis, "
          + "COMPUTER_USE for computer-control planning. Every GENERAL entry above is a separate concrete model; use its own syntax "
          + "when you specifically want that model. If the user asks for current/latest information, a web lookup, verification from "
          + "external sources, prices, schedules, news, or facts that may have changed after training, use WEB_SEARCH when enabled instead of guessing.");
      stableSystem.append(agents);
    }
    if ("Глубокий анализ".equals(mode))
      stableSystem.append("\n\n[DEEP ANALYSIS] Действуй как агент-исследователь. Сначала реши, что именно нужно узнать, "
          + "затем выполняй один инструмент за шаг и после каждого результата оценивай: данных достаточно или нужен ещё шаг. "
          + "Если поиск не дал нужного — не повторяй тот же запрос, а уточняй его: другие ключевые слова, другой источник, другой инструмент. "
          + "MEMORY_RECALL и FILE_TOOL можно вызывать несколько раз с разными уточнёнными запросами. "
          + "Для поиска неизвестного файла или каталога предпочитай FILE_TOOL:search, а не последовательный обход через list. "
          + "У тебя ограниченный бюджет шагов: распределяй его с умом и начни финальный ответ заранее, не в последнюю секунду. "
          + "Если запрос неоднозначен для точного поиска (не указан город, часовой пояс, год, версия и т. п.) — задай пользователю один короткий уточняющий вопрос вместо безрезультатных попыток. "
          + "Когда данных достаточно — сразу дай пользователю финальный ответ, не показывая служебные команды.");

    if (memoryAgentEnabled && !memoryAgentPrompt.isBlank()) {
      stableSystem.append("\n\n").append(memoryAgentPrompt);
    }

    List<MemoryRecord> recalledMemories = new ArrayList<>();
    MemoryService memoryService = memory;
    MemorySettings ms = memorySettings;
    if (memoryService != null && ms != null) {
      recalledMemories.addAll(memoryService.search(text, ms.maxResults.get(), ms.minScore.get()));
      System.out.println("[Memory] Normal recall received " + recalledMemories.size() + " memories for prompt");
    }

    String system = stableSystem.toString();
    int pairLimit = Math.max(0, contextMessageLimit - contextMessageLimit % 2);
    int rawHistoryLimit = Math.min(pairLimit, p.historyPairs() * 2);

    // The configured value is the maximum amount of RAW recent history. Once it would be
    // exceeded, roll a small oldest chunk into the persistent current-chat summary instead
    // of silently forgetting it. Leaving a little headroom avoids a summarization call every turn.
    compactRawHistoryToLimit(id, rawHistoryLimit);

    List<ConversationTurn> recent = recentHistory(rawHistoryLimit, rawHistoryLimit);
    String memoryBlock = buildAutomaticMemoryBlock(recalledMemories);
    int budgetTokens = Math.max(128, mainContextWindowTokens - 128);

    // Qdrant recall is optional context. If the complete prompt is too large, discard the
    // weakest tail memories first rather than sacrificing the user's current message.
    while (!recalledMemories.isEmpty()
        && estimatedPromptTokens(system, conversationSummary, recent, text, memoryBlock) > budgetTokens) {
      recalledMemories.remove(recalledMemories.size() - 1);
      memoryBlock = buildAutomaticMemoryBlock(recalledMemories);
    }

    // If raw conversation still does not fit, merge oldest pairs into the rolling summary.
    // The summary model receives only the previous summary + turns being compressed, never
    // the huge Main system prompt, Qdrant block or agent protocols.
    while (estimatedPromptTokens(system, conversationSummary, recent, text, memoryBlock) > budgetTokens
        && recent.size() >= 2) {
      int take = Math.min(recent.size(), recent.size() >= 4 ? 4 : 2);
      if (!summarizeOldestHistory(id, take)) break;
      recent = recentHistory(rawHistoryLimit, rawHistoryLimit);
    }

    int estimated = estimatedPromptTokens(system, conversationSummary, recent, text, memoryBlock);
    if (estimated > budgetTokens) {
      throw new IllegalArgumentException(
          "Контекст не помещается даже после суммаризации (оценка " + estimated + "/" + budgetTokens
              + " токенов). Увеличь ctx-size Main LLM или сократи системный промт.");
    }

    List<ConversationTurn> result = new ArrayList<>();
    result.add(new ConversationTurn("system", system));
    if (!conversationSummary.isBlank()) {
      result.add(new ConversationTurn("system",
          "[CONVERSATION SUMMARY]\n"
              + "This is a compressed factual state of older turns. The latest raw user messages and corrections have priority.\n"
              + conversationSummary));
    }
    result.addAll(recent);

    // Dynamic FAST-memory is attached only to the current user turn. This deliberately keeps
    // all previous turns before the changing memory block, maximizing llama.cpp LCP/KV reuse.
    String currentUser = text;
    if (!memoryBlock.isEmpty()) currentUser += memoryBlock;
    result.add(new ConversationTurn("user", currentUser));

    System.out.println("[Memory/Context] profile=" + profile.label()
        + " pipeline=" + System.identityHashCode(this) + " generation=" + id
        + " historySize=" + history.size() + " retainedMessages=" + recent.size()
        + " droppedMessages=" + Math.max(0, history.size() - recent.size())
        + " historyPairs=" + p.historyPairs()
        + " contextMessageLimit=" + contextMessageLimit
        + " summaryChars=" + conversationSummary.length()
        + " estimatedTokens=" + estimated + "/" + budgetTokens);
    logMainPrompt(profile, result, recent.size(), memoryBlock.length());

    return result;
  }

  private String buildAutomaticMemoryBlock(List<MemoryRecord> found) {
    if (found == null || found.isEmpty()) return "";
    StringBuilder remembered = new StringBuilder(
        "\n\n[Автоматически найденная долговременная память]\n"
            + "Используй только если она действительно относится к запросу:\n");
    for (MemoryRecord r : found) remembered.append("- ").append(r.text()).append('\n');
    return remembered.toString();
  }

  private List<ConversationTurn> recentHistory(int pairLimit, int pipelineLimit) {
    List<ConversationTurn> recent = new ArrayList<>(history);
    int limit = Math.max(0, Math.min(pairLimit, pipelineLimit));
    while (recent.size() > limit && recent.size() >= 2) {
      recent.remove(0);
      recent.remove(0);
    }
    return recent;
  }

  private void compactRawHistoryToLimit(long id, int pairLimit) throws InterruptedException {
    while (history.size() > pairLimit && history.size() >= 2) {
      int overflow = history.size() - pairLimit;
      int take = Math.min(8, Math.max(4, overflow));
      take = Math.min(take, history.size());
      if ((take & 1) != 0) take--;
      if (take < 2 || !summarizeOldestHistory(id, take)) return;
    }
  }

  private boolean summarizeOldestHistory(long id, int messageCount) throws InterruptedException {
    SummarizationRuntimeManager runtime = summarizationRuntime;
    if (runtime == null || !runtime.isReady() || history.size() < 2) {
      System.out.println("[Context/Summary] runtime unavailable; raw history cannot be rolled up");
      return false;
    }
    int take = Math.min(messageCount, history.size());
    if ((take & 1) != 0) take--;
    if (take < 2) return false;

    List<ConversationTurn> chunk = new ArrayList<>(history.subList(0, take));
    try {
      check(id);
      System.out.println("[Context/Summary] merging messages=" + take
          + " previousSummaryChars=" + conversationSummary.length());
      String updated = runtime.summarize(conversationSummary, chunk);
      check(id);
      if (updated == null || updated.isBlank()) {
        System.out.println("[Context/Summary] model returned blank summary; keeping raw history");
        return false;
      }
      conversationSummary = updated.strip();
      history.subList(0, take).clear();
      System.out.println("[Context/Summary] updated summaryChars=" + conversationSummary.length()
          + " rawHistoryRemaining=" + history.size());
      return true;
    } catch (InterruptedException e) {
      throw e;
    } catch (Exception e) {
      System.out.println("[Context/Summary] failed; keeping raw history: " + message(e));
      return false;
    }
  }

  /**
   * Conservative tokenizer-free estimate used before the request reaches llama.cpp.
   * Latin/system-prompt text is roughly 4 chars/token; Cyrillic is denser, so it receives
   * a larger weight. A fixed per-message allowance covers chat-template role markers.
   */
  private int estimatedPromptTokens(String system, String summary, List<ConversationTurn> recent,
                                    String currentText, String memoryBlock) {
    double tokens = estimateTextTokens(system) + estimateTextTokens(currentText) + estimateTextTokens(memoryBlock);
    if (summary != null && !summary.isBlank()) tokens += estimateTextTokens(summary) + 32;
    if (recent != null) {
      for (ConversationTurn turn : recent) tokens += estimateTextTokens(turn.content()) + 8;
    }
    // System/current roles, BOS/EOS and template separators.
    tokens += 64;
    return (int)Math.ceil(tokens);
  }

  private double estimateTextTokens(String value) {
    if (value == null || value.isEmpty()) return 0;
    double tokens = 0;
    for (int i = 0; i < value.length(); ) {
      int cp = value.codePointAt(i);
      i += Character.charCount(cp);
      if (Character.isWhitespace(cp)) tokens += 0.04;
      else if ((cp >= 0x0400 && cp <= 0x052F) || (cp >= 0x2DE0 && cp <= 0x2DFF)) tokens += 0.50;
      else if (cp < 128 && (Character.isLetterOrDigit(cp) || "_-./".indexOf(cp) >= 0)) tokens += 0.25;
      else if (cp < 128) tokens += 0.35;
      else tokens += 0.45;
    }
    return tokens;
  }

  private void logMainPrompt(Profile profile, List<ConversationTurn> turns, int liveHistory, int memoryBlockChars) {
    System.out.println("[Context] ===== MAIN LLM PROMPT =====");
    System.out.println("[Context] profile=" + profile.label()
        + " turns=" + turns.size()
        + " liveHistory=" + liveHistory
        + " memoryBlockChars=" + memoryBlockChars);
    for (int i = 0; i < turns.size(); i++) {
      ConversationTurn turn = turns.get(i);
      String content = turn.content() == null ? "" : turn.content();
      System.out.println("[Context] #" + i + " role=" + turn.role()
          + " chars=" + content.length()
          + " text=" + abbreviateForLog(content, 420));
    }
    System.out.println("[Context] ===== /MAIN LLM PROMPT =====");
  }

  private static String abbreviateForLog(String text, int max) {
    if (text == null) return "";
    String oneLine = text.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
    return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
  }

  private boolean shouldAutoRouteWebSearch(String text) {
    if (text == null || text.isBlank()) return false;
    if (!availableAgentRoles.contains(ModelRole.WEB_SEARCH)) return false;

    String q = text.toLowerCase(Locale.ROOT);

    boolean explicit =
        q.contains("web search")
        || q.contains("web-search")
        || q.contains("веб поиск")
        || q.contains("веб-поиск")
        || q.contains("поиск в интернете")
        || q.contains("поискать в интернете")
        || q.contains("найди в интернете")
        || q.contains("проверь в интернете")
        || q.contains("поищи в интернете")
        || q.contains("используй интернет")
        || q.contains("используй веб")
        || q.contains("используй тул для поиска")
        || q.contains("search the web")
        || q.contains("search online")
        || q.contains("look up online");
    if (explicit) return true;

    boolean currentCue =
        q.contains("сейчас")
        || q.contains("сегодня")
        || q.contains("актуаль")
        || q.contains("последн")
        // Slang forms of "сейчас" ("ща", "щас") must still count as a currency cue.
        || java.util.regex.Pattern.compile("(?<![а-яё])ща(?:с)?(?![а-яё])").matcher(q).find()
        || q.contains("latest")
        || q.contains("current")
        || q.contains("today");

    // "Который час" is inherently a current-time question even without any cue word.
    if (q.contains("который час")) return true;

    if (!currentCue) return false;

    return q.contains("врем")
        || q.contains("дат")
        || q.contains("погод")
        || q.contains("температур")
        || q.contains("верси")
        || q.contains("релиз")
        || q.contains("новост")
        || q.contains("цен")
        || q.contains("расписан")
        || q.contains("курс")
        || q.contains("release")
        || q.contains("version")
        || q.contains("weather")
        || q.contains("time")
        || q.contains("date")
        || q.contains("price")
        || q.contains("schedule")
        || q.contains("news");
  }

  /**
   * Streams normal text immediately, but intercepts an inline [[...]] block.
   *
   * Only the tiny ambiguous tail beginning with '[' is buffered. As soon as the block closes,
   * a valid MEMORY_RECALL call aborts the current LLM stream and is executed by the pipeline.
   * Invalid [[...]] text is released back to the UI unchanged.
   */
  private final class StreamingMemoryToolFilter {
    private final java.util.function.Consumer<String> visibleConsumer;
    private final Profile profile;
    private final String traceId;
    private int chunksSeen;
    private int charactersSeen;
    private int lastVisibleCheckpoint;
    private final StringBuilder visible = new StringBuilder();
    private final StringBuilder pending = new StringBuilder();

    private StreamingMemoryToolFilter(Profile profile, String traceId,
        java.util.function.Consumer<String> visibleConsumer) {
      this.profile = profile == null ? Profile.NORMAL : profile;
      this.traceId = traceId;
      this.visibleConsumer = visibleConsumer;
    }

    private void accept(String chunk) {
      if (chunk == null || chunk.isEmpty()) return;
      chunksSeen++;
      charactersSeen += chunk.length();
      if (chunksSeen == 1) TtsTrace.log(traceId, "FILTER_FIRST_LLM_CHUNK",
          "chunkChars=" + chunk.length());
      else if (chunksSeen % 32 == 0) TtsTrace.log(traceId, "FILTER_LLM_PROGRESS",
          "chunks=" + chunksSeen + " receivedChars=" + charactersSeen
              + " visibleChars=" + visible.length() + " pendingChars=" + pending.length());
      pending.append(chunk);
      process(false);
    }

    private void process(boolean finishing) {
      while (pending.length() > 0) {
        Marker marker = earliestMarker(pending.toString(), profile);
        if (marker == null) {
          int keep = finishing ? 0 : possibleMarkerTail(pending.toString(), profile);
          int publishLength = pending.length() - keep;
          if (publishLength > 0) {
            publish(pending.substring(0, publishLength));
            pending.delete(0, publishLength);
          }
          return;
        }

        if (marker.index() > 0) {
          publish(pending.substring(0, marker.index()));
          pending.delete(0, marker.index());
          continue;
        }

        if (marker.genericDoubleBracket()) {
          int close = pending.indexOf("]]", 2);
          if (close < 0) {
            if (finishing) { publish(pending.toString()); pending.setLength(0); }
            return;
          }
          String block = pending.substring(0, close + 2);
          pending.delete(0, close + 2);

          AgentCall agentCall = parseAgentCall(block, profile);
          if (agentCall != null) throw new AgentToolDetected(agentCall);
          MemoryRecallCall call = parseMemoryRecall(block, profile);
          if (call != null) throw new MemoryToolDetected(call, visible.toString(), block);
          FileToolCall fileToolCall = parseFileTool(block);
          if (fileToolCall != null) throw new FileToolDetected(fileToolCall);
          FileToolCall legacyFileSearch = parseLegacyFileSearch(block);
          if (legacyFileSearch != null) throw new FileToolDetected(legacyFileSearch);
          publish(block);
          continue;
        }

        String suffix = marker.suffix();
        int close = pending.indexOf(suffix, marker.prefix().length());
        if (close < 0) {
          if (finishing) { publish(pending.toString()); pending.setLength(0); }
          return;
        }
        int end = close + suffix.length();
        String block = pending.substring(0, end);
        pending.delete(0, end);
        AgentCall agentCall = parseAgentCall(block, profile);
        if (agentCall != null) throw new AgentToolDetected(agentCall);
        MemoryRecallCall memoryCall = parseMemoryRecall(block, profile);
        if (memoryCall != null) throw new MemoryToolDetected(memoryCall, visible.toString(), block);
        FileToolCall legacyFileSearch = parseLegacyFileSearch(block);
        if (legacyFileSearch != null) throw new FileToolDetected(legacyFileSearch);
        publish(block);
      }
    }

    private void finish() {
      process(true);
      TtsTrace.log(traceId, "FILTER_FINISHED", "chunks=" + chunksSeen
          + " receivedChars=" + charactersSeen + " visibleChars=" + visible.length()
          + " pendingChars=" + pending.length());
    }

    private void publish(String text) {
      if (text == null || text.isEmpty()) return;
      boolean first = visible.length() == 0;
      visible.append(text);
      if (first || visible.length() - lastVisibleCheckpoint >= 300) {
        lastVisibleCheckpoint = visible.length();
        TtsTrace.log(traceId, "FILTER_VISIBLE", "visibleChars=" + visible.length()
            + " latestFragmentChars=" + text.length() + " pendingChars=" + pending.length());
      }
      visibleConsumer.accept(text);
    }

    private String visibleText() { return visible.toString(); }
    private String agentTextForContinuation() { return visible.toString(); }
  }

  private record Marker(int index, String prefix, String suffix, boolean genericDoubleBracket) {}

  private Marker earliestMarker(String text, Profile profile) {
    Marker best = null;
    int generic = text.indexOf("[[");
    if (generic >= 0) best = new Marker(generic, "[[", "]]", true);
    for (ModelRole role : com.neuralsoul.model.ProfileState.callTemplateRoles()) {
      if (!availableAgentRoles.contains(role)) continue;
      String template = agentTemplate(profile, role);
      int taskAt = template.indexOf("{task}");
      if (taskAt <= 0 || taskAt + 6 >= template.length()) continue;
      String prefix = template.substring(0, taskAt);
      if (prefix.startsWith("[[")) continue; // handled by generic [[...]] protocol
      int at = text.indexOf(prefix);
      if (at >= 0 && (best == null || at < best.index() || (at == best.index() && prefix.length() > best.prefix().length())))
        best = new Marker(at, prefix, template.substring(taskAt + 6), false);
    }
    LinkedHashMap<String, String> generalTemplates = generalAgentCallTemplates.get(profile == null ? Profile.NORMAL : profile);
    if (generalTemplates != null) {
      for (String template : generalTemplates.values()) {
        int taskAt = template.indexOf("{task}");
        if (taskAt <= 0 || taskAt + 6 >= template.length()) continue;
        String prefix = template.substring(0, taskAt);
        if (prefix.startsWith("[[")) continue;
        int at = text.indexOf(prefix);
        if (at >= 0 && (best == null || at < best.index() || (at == best.index() && prefix.length() > best.prefix().length())))
          best = new Marker(at, prefix, template.substring(taskAt + 6), false);
      }
    }
    return best;
  }

  private int possibleMarkerTail(String text, Profile profile) {
    List<String> starts = new ArrayList<>();
    starts.add("[[");
    for (ModelRole role : com.neuralsoul.model.ProfileState.callTemplateRoles()) {
      if (!availableAgentRoles.contains(role)) continue;
      String template = agentTemplate(profile, role);
      int taskAt = template.indexOf("{task}");
      if (taskAt > 0) starts.add(template.substring(0, taskAt));
    }
    LinkedHashMap<String, String> generalTemplates = generalAgentCallTemplates.get(profile == null ? Profile.NORMAL : profile);
    if (generalTemplates != null) {
      for (String template : generalTemplates.values()) {
        int taskAt = template.indexOf("{task}");
        if (taskAt > 0) starts.add(template.substring(0, taskAt));
      }
    }
    int max = 0;
    for (String start : starts) {
      int limit = Math.min(text.length(), start.length() - 1);
      for (int n = limit; n > max; n--) {
        if (text.regionMatches(text.length() - n, start, 0, n)) { max = n; break; }
      }
    }
    return max;
  }

  private final class MemoryToolDetected extends RuntimeException {
    private final MemoryRecallCall call;
    private final String visibleText;
    private final String rawToolCall;

    private MemoryToolDetected(MemoryRecallCall call, String visibleText, String rawToolCall) {
      super("MEMORY_RECALL");
      this.call = call;
      this.visibleText = visibleText;
      this.rawToolCall = rawToolCall;
    }

    private MemoryRecallCall call() {
      return call;
    }
  }

  private static boolean validAgentTemplate(String template) {
    if (template == null) return false;
    int at = template.indexOf("{task}");
    return at > 0 && at == template.lastIndexOf("{task}") && at + 6 < template.length();
  }

  private synchronized String agentTemplate(Profile profile, ModelRole role) {
    Profile p = profile == null ? Profile.NORMAL : profile;
    // Built-in protocols are fixed. GENERAL is handled separately by the model-scoped map.
    return com.neuralsoul.model.ProfileState.defaultAgentCallTemplate(role);
  }

  private String formatCall(Profile profile, ModelRole role, String task) {
    return agentTemplate(profile, role).replace("{task}", task == null ? "" : task);
  }

  private String parseConfiguredTask(String text, Profile profile, ModelRole role) {
    if (text == null || text.isBlank()) return null;
    String template = agentTemplate(profile, role);
    int at = template.indexOf("{task}");
    if (at <= 0 || at + 6 >= template.length()) return null;
    String prefix = template.substring(0, at);
    String suffix = template.substring(at + 6);
    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
        java.util.regex.Pattern.quote(prefix) + "(.+?)" + java.util.regex.Pattern.quote(suffix),
        java.util.regex.Pattern.DOTALL);
    java.util.regex.Matcher matcher = pattern.matcher(text);
    if (!matcher.find()) return null;
    String task = matcher.group(1).trim();
    return task.isBlank() ? null : task;
  }

  private AgentCall parseAgentCall(String text, Profile profile) {
    if (text == null || text.isBlank()) return null;
    for (ModelRole role : com.neuralsoul.model.ProfileState.agentRoles()) {
      if (!availableAgentRoles.contains(role)) continue;
      String template = agentTemplate(profile, role);
      int at = template.indexOf("{task}");
      if (at <= 0 || at + 6 >= template.length()) continue;
      String prefix = template.substring(0, at);
      String suffix = template.substring(at + 6);
      java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
          java.util.regex.Pattern.quote(prefix) + "(.+?)" + java.util.regex.Pattern.quote(suffix),
          java.util.regex.Pattern.DOTALL);
      java.util.regex.Matcher matcher = pattern.matcher(text);
      if (!matcher.find()) continue;
      String task = matcher.group(1).trim();
      if (!task.isBlank()) return new AgentCall(role, null, task, matcher.group(0));
    }

    Profile p = profile == null ? Profile.NORMAL : profile;
    LinkedHashMap<String, String> generalTemplates = generalAgentCallTemplates.get(p);
    if (generalTemplates != null) {
      for (Map.Entry<String, String> entry : generalTemplates.entrySet()) {
        String template = entry.getValue();
        int at = template.indexOf("{task}");
        if (at <= 0 || at + 6 >= template.length()) continue;
        String prefix = template.substring(0, at);
        String suffix = template.substring(at + 6);
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            java.util.regex.Pattern.quote(prefix) + "(.+?)" + java.util.regex.Pattern.quote(suffix),
            java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) continue;
        String task = matcher.group(1).trim();
        if (!task.isBlank())
          return new AgentCall(ModelRole.GENERAL, entry.getKey(), task, matcher.group(0));
      }
    }
    return null;
  }

  private String executeSubAgent(long id, AgentCall call, String model, Profile profile) throws Exception {
    String display = call.role() == ModelRole.GENERAL && call.modelKey() != null
        ? generalAgentName(profile, call.modelKey())
        : call.role().title();
    emit(id, PipelineEvent.Type.THINK_START, display);
    System.out.println("[SubAgent] ===== TASK role=" + call.role() + " generation=" + id
        + " profile=" + profile.label() + " modelKey=" + call.modelKey() + " =====\n"
        + call.task() + "\n[SubAgent] ===== /TASK =====");
    try {
      String result;
      if (call.role() == ModelRole.WEB_SEARCH) {
        WebSearchAgent agent = webSearchAgent;
        if (agent == null || !agent.isReady())
          throw new IllegalStateException("Web Search Agent не запущен");
        result = agent.research(call.task());
      } else if (call.role() == ModelRole.GENERAL && call.modelKey() != null) {
        if (normalizeModelKey(call.modelKey()).equals(mainModelKey)) {
          List<ConversationTurn> prompt = List.of(
              new ConversationTurn("system", generalSystemPrompt(call.modelKey())),
              new ConversationTurn("user", call.task()));
          result = services.llm().generate(prompt, model);
        } else {
          GeneralAgentRuntimeManager runtime = generalAgentRuntime;
          if (runtime == null || !runtime.isReady(call.modelKey()))
            throw new IllegalStateException("General Agent не запущен: " + call.modelKey());
          result = runtime.generate(call.modelKey(), call.task(), generalSystemPrompt(call.modelKey()));
        }
      } else {
        List<ConversationTurn> prompt = List.of(
            new ConversationTurn("system", agentSystemPrompt(call.role())),
            new ConversationTurn("user", call.task()));
        result = services.llm().generate(prompt, model);
      }
      System.out.println("[SubAgent] " + call.role()
          + (call.modelKey() == null ? "" : " [" + call.modelKey() + "]")
          + " completed, chars=" + result.length());
      System.out.println("[SubAgent] ===== RESULT role=" + call.role() + " generation=" + id
          + " profile=" + profile.label() + " chars=" + result.length() + " =====\n"
          + result + "\n[SubAgent] ===== /RESULT =====");
      return result;
    } catch (Exception failure) {
      System.out.println("[SubAgent] FAILED role=" + call.role() + " generation=" + id + " error=" + failure);
      throw failure;
    } finally {
      emit(id, PipelineEvent.Type.THINK_END, display + " finished");
    }
  }

  private String generalAgentName(Profile profile, String modelKey) {
    LinkedHashMap<String, String> names = generalAgentNames.get(profile == null ? Profile.NORMAL : profile);
    return names == null ? "General" : names.getOrDefault(normalizeModelKey(modelKey), "General");
  }

  private static String normalizeModelKey(String value) {
    return value == null ? "" : value.replace('\\', '/');
  }

  private record AgentCall(ModelRole role, String modelKey, String task, String raw) {}
  private final class AgentToolDetected extends RuntimeException {
    private final AgentCall call;
    private AgentToolDetected(AgentCall call) { super("AGENT"); this.call = call; }
    private AgentCall call() { return call; }
  }

  private static final String MEMORY_RECALL_PREFIX = "[[MEMORY_RECALL:";
  private static final java.util.regex.Pattern MEMORY_RECALL_PATTERN =
      java.util.regex.Pattern.compile(
          "\\[\\[MEMORY_RECALL\\s*:\\s*(.+?)\\]\\]",
          java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
  /** Any tool/agent marker that must never reach the visible final answer. */
  private static final java.util.regex.Pattern TOOL_MARKER_PATTERN =
      java.util.regex.Pattern.compile(
          "\\[\\[?\\s*(?:AGENT|WEB_SEARCH|WEB_FETCH|WEB_TIME|FILE_TOOL|FILE_SEARCH|MEMORY_RECALL)\\b",
          java.util.regex.Pattern.CASE_INSENSITIVE);

  private MemoryRecallCall parseMemoryRecall(String text, Profile profile) {
    String query = parseConfiguredTask(text, profile, ModelRole.MEMORY);
    if (query == null) return null;
    if (query.isBlank()) return null;

    String normalized = query.toLowerCase(java.util.Locale.ROOT)
        .replace("<", "")
        .replace(">", "")
        .trim();
    if (normalized.equals("поисковый запрос")
        || normalized.equals("краткий поисковый запрос")
        || normalized.equals("запрос")
        || normalized.equals("query")
        || normalized.equals("search query")) {
      System.out.println("[Memory/SubAgent] rejected placeholder query: " + query);
      return null;
    }
    return new MemoryRecallCall(query);
  }

  private static final java.util.regex.Pattern FILE_TOOL_PATTERN =
      java.util.regex.Pattern.compile("\\[\\[FILE_TOOL\\s*:\\s*(.+?)\\]\\]",
          java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

  private FileToolCall parseFileTool(String text) {
    if (text == null || text.isBlank()) return null;
    java.util.regex.Matcher matcher = FILE_TOOL_PATTERN.matcher(text);
    if (!matcher.find()) return null;
    String body = matcher.group(1);
    String[] first = body.split("\\|", 2);
    String operation = first[0].trim().toLowerCase(Locale.ROOT);
    if (!Set.of("roots", "list", "glob", "search", "grep", "read", "write", "edit", "patch").contains(operation)) return null;
    String rest = first.length > 1 ? first[1] : "";
    if (operation.equals("search")) {
      // New form is search|name. Keep the former search|root|name GUI prompt compatible.
      List<String> searchArgs = new ArrayList<>(Arrays.asList(rest.split("\\|", 2)));
      return new FileToolCall(operation, searchArgs, matcher.group(0));
    }
    int expected = switch (operation) {
      case "roots" -> 0;
      case "glob", "grep", "write" -> 2;
      case "read", "edit", "patch" -> 3;
      default -> 1;
    };
    List<String> args = new ArrayList<>();
    if (expected > 0) {
      args.addAll(Arrays.asList(rest.split("\\|", expected)));
      while (args.size() < expected) args.add("");
    }
    return new FileToolCall(operation, args, matcher.group(0));
  }

  private String executeFileTool(long id, Profile profile, FileToolCall call) throws Exception {
    String op = call.operation();
    FileRouteSearchConfig routeConfig;
    synchronized (this) {
      routeConfig = fileRouteSearchConfigs.get(profile == null ? Profile.NORMAL : profile);
    }
    fileTool.setPreferredRoots(routeConfig != null && routeConfig.enabled()
        ? routeConfig.routes() : List.of());
    boolean needsPermission = op.equals("grep") || op.equals("read") || op.equals("write")
        || op.equals("edit") || op.equals("patch");
    List<String> resolvedArgs = new ArrayList<>(call.args());
    if (!resolvedArgs.isEmpty() && !op.equals("roots")) {
      // Paths returned by roots/list/search are already concrete. resolvePath still handles
      // relative/env paths for direct read/write/edit/grep requests.
      resolvedArgs.set(0, FileToolService.resolvePath(resolvedArgs.get(0)));
    }
    if (needsPermission) {
      String path = resolvedArgs.isEmpty() ? "" : resolvedArgs.get(0).trim();
      String details = switch (op) {
        case "read" -> "Nina хочет прочитать содержимое файла.\n\nСтрока начала: "
            + (resolvedArgs.size() > 1 && !resolvedArgs.get(1).isBlank() ? resolvedArgs.get(1) : "1")
            + "\nМаксимум строк: "
            + (resolvedArgs.size() > 2 && !resolvedArgs.get(2).isBlank() ? resolvedArgs.get(2) : "400");
        case "grep" -> "Nina хочет выполнить поиск по содержимому файлов.\n\nРегулярное выражение:\n"
            + (resolvedArgs.size() > 1 ? resolvedArgs.get(1) : "");
        case "write" -> {
          String content = resolvedArgs.size() > 1 ? resolvedArgs.get(1) : "";
          yield writePreview(path, content);
        }
        case "edit", "patch" -> {
          String oldText = resolvedArgs.size() > 1 ? resolvedArgs.get(1) : "";
          String newText = resolvedArgs.size() > 2 ? resolvedArgs.get(2) : "";
          yield "Nina хочет изменить существующий файл.\n\n--- OLD ---\n" + preview(oldText)
              + "\n--- NEW ---\n" + preview(newText);
        }
        default -> "";
      };
      System.out.println("[FileTool] waiting for permission: " + op + " " + path);
      boolean accepted = filePermissionHandler.request(new FilePermissionRequest(op, path, details));
      check(id);
      if (!accepted) {
        System.out.println("[FileTool] permission denied; cancelling generation");
        throw new CancellationException("Пользователь отказал в доступе к файлу");
      }
      System.out.println("[FileTool] permission accepted: " + op + " " + path);
    }
    emit(id, PipelineEvent.Type.THINK_START, "File " + Character.toUpperCase(op.charAt(0)) + op.substring(1));
    try {
      String result = fileTool.execute(op, resolvedArgs);
      System.out.println("[FileTool] " + op + " completed");
      return result;
    } finally {
      emit(id, PipelineEvent.Type.THINK_END, "File tool finished");
    }
  }

  private static String writePreview(String path, String proposed) {
    String old = "[новый файл]";
    try {
      java.nio.file.Path file = java.nio.file.Path.of(path);
      if (java.nio.file.Files.isRegularFile(file)) {
        old = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
      }
    } catch (Exception ignored) {}
    return "Nina хочет создать или перезаписать файл.\n\n--- CURRENT ---\n" + preview(old)
        + "\n--- PROPOSED ---\n" + preview(proposed);
  }

  private static String preview(String value) {
    String text = value == null ? "" : value;
    int max = 30_000;
    return text.length() <= max ? text : text.substring(0, max) + "\n[PREVIEW TRUNCATED]";
  }

  private record FileToolCall(String operation, List<String> args, String raw) {}
  private final class FileToolDetected extends RuntimeException {
    private final FileToolCall call;
    private FileToolDetected(FileToolCall call) { super("FILE_TOOL"); this.call = call; }
    private FileToolCall call() { return call; }
  }

  /** Compatibility for saved GUI prompts from before deterministic File Tools. */
  private FileToolCall parseLegacyFileSearch(String text) {
    if (text == null || text.isBlank()) return null;
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
        "\\[\\[(?:AGENT:)?FILE_SEARCH\\s*[:|]\\s*(.+?)\\]\\]",
        java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL).matcher(text);
    if (!matcher.find() || matcher.group(1).isBlank()) return null;
    return new FileToolCall("search", List.of(matcher.group(1).trim()), matcher.group(0));
  }

  private String buildMemoryRecallResult(String query, List<MemoryRecord> records) {
    StringBuilder out = new StringBuilder();
    out.append("MEMORY_RECALL_RESULT для запроса: ")
        .append(query)
        .append("\n");
    if (records == null || records.isEmpty()) {
      out.append("Подходящих воспоминаний не найдено. "
          + "Ответь пользователю честно и не выдумывай содержимое памяти.");
    } else {
      out.append("Найденные воспоминания:\\n");
      for (MemoryRecord record : records) {
        out.append("- ").append(record.text()).append('\n');
      }
      out.append("Используй только релевантные найденные факты. "
          + "Теперь ответь пользователю; MEMORY_RECALL повторно не вызывай.");
    }
    return out.toString();
  }

  private record MemoryRecallCall(String query) {}

  /**
   * Memory Recall Sub-Agent entry point.
   * Call this ONLY after the main agent explicitly requests MEMORY_RECALL.
   * searchDeep() performs ordinary Qdrant + BGE-M3 recall in parallel.
   */
  public List<MemoryRecord> recallMemorySubAgent(long requestId, String query) {
    MemoryService memoryService = this.memory;
    MemorySettings ms = this.memorySettings;
    if (memoryService == null || ms == null || query == null || query.isBlank()) return List.of();

    try {
      emit(requestId, PipelineEvent.Type.THINK_START, "Ищу в долговременной памяти…");
      List<MemoryRecord> found =
          memoryService.searchDeep(query, ms.maxResults.get(), ms.minScore.get());
      System.out.println("[Memory/SubAgent] query="+query+" results="+found.size());
      return found;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      System.out.println("[Memory/SubAgent] interrupted: " + e);
      return List.of();
    } finally {
      try {
        emit(requestId, PipelineEvent.Type.THINK_END, "Воспоминания проверены");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        System.out.println("[Memory/SubAgent] THINK_END interrupted: " + e);
      }
    }
  }

  public void setMemory(MemoryService memory, MemorySettings settings) {
    this.memory = memory;
    this.memorySettings = settings;
  }

  public void setContextMessageLimit(int value) {
    contextMessageLimit = Math.max(1, value);
  }

  public CompletableFuture<List<String>> refreshModels() {
    return submit(
        id -> {
          ensure(services.llm());
          return services.llm().listModels();
        });
  }

  public CompletableFuture<Void> connect(String service) {
    return submit(
        id -> {
          selected(service).start();
          emit(id, PipelineEvent.Type.STATUS, service + ": подключён");
          return null;
        });
  }

  public CompletableFuture<Void> disconnect(String service) {
    return submit(
        id -> {
          selected(service).stop();
          emit(id, PipelineEvent.Type.STATUS, service + ": отключён");
          return null;
        });
  }

  public CompletableFuture<Void> startRecording() {
    return submit(
        id -> {
          services.recorder().start();
          emit(
              id,
              PipelineEvent.Type.STATUS,
              "Запись микрофона. Нажми микрофон ещё раз для отправки.");
          return null;
        });
  }

  public CompletableFuture<GenerationResult> finishRecording(
      String model, String mode, Profile profile, boolean speak) {
    return submit(
        id -> {
          byte[] wav = services.recorder().finishRecording();
          emit(id, PipelineEvent.Type.STATUS, "Распознавание речи…");
          ensure(services.stt());
          String text = services.stt().convertSpeechToText(wav);
          check(id);
          emit(id, PipelineEvent.Type.TRANSCRIPT, text);
          return process(id, text, model, mode, profile, speak);
        });
  }

  public synchronized void configurationChanged() {
    if (isGenerating())
      throw new IllegalStateException("Останови текущую операцию перед сменой конфигурации");
    services.llm().stop();
    services.stt().stop();
    services.tts().stop();
    for (Consumer<PipelineEvent> listener : listeners)
      listener.accept(
          new PipelineEvent(
              generation,
              PipelineEvent.Type.STATUS,
              "Настройки сервисов обновлены; требуется подключение"));
  }

  public CompletableFuture<Void> testSpeech(String text) {
    return submit(
        id -> {
          try (TtsTrace.Scope traceScope = TtsTrace.with("g" + id + "-test")) {
            TtsTrace.log("TTS_MANUAL_TEST", "chars=" + (text == null ? 0 : text.length()));
            ensure(services.tts());
            services.tts().speak(text, services.player());
            services.player().finishSpeech();
            TtsTrace.log("TTS_MANUAL_TEST_DONE", "completed=true");
          }
          check(id);
          return null;
        });
  }

  private ManagedService selected(String service) {
    return switch (service) {
      case "LLM" -> services.llm();
      case "STT" -> services.stt();
      case "TTS" -> services.tts();
      default -> throw new IllegalArgumentException(service);
    };
  }

  private void ensure(ManagedService service) throws Exception {
    if (!service.isRunning()) {
      if (!configs.pipeline().autoConnect())
        throw new IllegalStateException("Сервис отключён; подключи его в настройках");
      service.start();
    }
  }

  private synchronized void check(long id) throws InterruptedException {
    if (id != generation || !running || Thread.currentThread().isInterrupted())
      throw new InterruptedException("Операция отменена");
  }

  private void emit(long id, PipelineEvent.Type type, String text) throws InterruptedException {
    check(id);
    for (Consumer<PipelineEvent> listener : listeners)
      try {
        listener.accept(new PipelineEvent(id, type, text));
      } catch (RuntimeException ignored) {
      }
  }

  @FunctionalInterface
  private interface Work<T> {
    T run(long id) throws Exception;
  }

  private synchronized <T> CompletableFuture<T> submit(Work<T> work) {
    if (!running)
      return CompletableFuture.failedFuture(new IllegalStateException("Пайплайн остановлен"));
    if (isGenerating())
      return CompletableFuture.failedFuture(
          new IllegalStateException("Дождись текущей операции или нажми Стоп"));
    long id = ++generation;
    CompletableFuture<T> result = new CompletableFuture<>();
    pending = result;
    task =
        executor.submit(
            () -> {
              try {
                check(id);
                T value = work.run(id);
                check(id);
                result.complete(value);
              } catch (InterruptedException | CancellationException e) {
                StreamingSpeechQueue speechQueue = activeTtsQueue;
                activeTtsQueue = null;
                if (speechQueue != null) speechQueue.cancel();
                services.tts().cancelSpeech();
                services.player().cancelSpeech();
                services.recorder().stop();
                Thread.currentThread().interrupt();
                result.cancel(false);
              } catch (Exception e) {
                StreamingSpeechQueue speechQueue = activeTtsQueue;
                activeTtsQueue = null;
                if (speechQueue != null) speechQueue.cancel();
                result.completeExceptionally(e);
              } finally {
                clearActiveRequest(id);
              }
            });
    return result;
  }

  /**
   * Cancels the active generation because the user started a new voice request, but keeps the
   * interrupted user turn in short-term conversation context. The interrupted turn is paired with
   * either the already visible partial answer or an explicit interruption marker so pair-based
   * history trimming remains valid. Nothing from this incomplete turn is written to long-term memory.
   */
  public synchronized boolean cancelPreservingContext(String visiblePartialAnswer) {
    if (!isGenerating()) return false;
    long interruptedId = generation;
    TtsTrace.log("g" + interruptedId, "CANCEL_PRESERVE_CONTEXT",
        "activeQueue=" + (activeTtsQueue != null));
    String interruptedUser = activeRequestGeneration == interruptedId ? activeRequestText : "";

    generation++;
    if (services.isInitialized()) services.llm().cancelGeneration();
    StreamingSpeechQueue speechQueue = activeTtsQueue;
    activeTtsQueue = null;
    if (speechQueue != null) speechQueue.cancel();
    if (task != null) task.cancel(true);
    if (pending != null) pending.cancel(false);
    if (services.isInitialized()) {
      services.tts().cancelSpeech();
      services.player().cancelSpeech();
      services.recorder().stop();
    }

    if (!interruptedUser.isBlank()) {
      String partial = visiblePartialAnswer == null ? "" : visiblePartialAnswer.strip();
      String assistantContext = partial.isBlank()
          ? "[Ответ был прерван пользователем до завершения.]"
          : partial + "\n[Ответ был прерван пользователем до завершения.]";
      history.add(new ConversationTurn("user", interruptedUser));
      history.add(new ConversationTurn("assistant", assistantContext));
      trimHistoryPairs();
      System.out.println("[Context] voice interruption preserved generation=" + interruptedId
          + " userChars=" + interruptedUser.length() + " partialChars=" + partial.length());
    }
    activeRequestGeneration = -1;
    activeRequestText = "";
    return !interruptedUser.isBlank();
  }

  public synchronized void cancel() {
    TtsTrace.log("g" + generation, "CANCEL_USER", "activeQueue=" + (activeTtsQueue != null));
    generation++;
    if (services.isInitialized()) services.llm().cancelGeneration();
    StreamingSpeechQueue speechQueue = activeTtsQueue;
    activeTtsQueue = null;
    if (speechQueue != null) speechQueue.cancel();
    if (task != null) task.cancel(true);
    if (pending != null) pending.cancel(false);
    if (services.isInitialized()) {
      services.tts().cancelSpeech();
      services.player().cancelSpeech();
      services.recorder().stop();
    }
    activeRequestGeneration = -1;
    activeRequestText = "";
  }

  /**
   * Persists the complete in-memory conversation context before dropping it.
   * The visible chat is intentionally left untouched; only the context sent to the LLM is reset.
   */
  public synchronized int flushAndClearConversationContext() {
    if (isGenerating()) throw new IllegalStateException("Нельзя очищать контекст во время генерации");
    if (history.isEmpty() && conversationSummary.isBlank()) {
      System.out.println("[Context] clear requested; history is empty");
      return 0;
    }
    if (memory == null) throw new IllegalStateException("Memory service is not configured");

    List<String> snapshot = new ArrayList<>(history.size() + 1);
    if (!conversationSummary.isBlank())
      snapshot.add("Сводка более раннего контекста: " + conversationSummary);
    for (ConversationTurn turn : history) {
      String role = "assistant".equalsIgnoreCase(turn.role()) ? "Nina" : turn.role();
      snapshot.add(role + ": " + turn.content());
    }

    // Persist first. If persistence fails, history remains intact so no context is lost.
    memory.flushConversation(snapshot);
    int count = history.size() + (conversationSummary.isBlank() ? 0 : 1);
    history.clear();
    conversationSummary = "";
    System.out.println("[Context] clear reason=explicitClear pipeline=" + System.identityHashCode(this)
        + " removed=" + count + " persisted=true");
    return count;
  }

  public synchronized void resetConversation() {
    cancel();
    System.out.println("[Memory/Context] reset reason=newChat pipeline="
        + System.identityHashCode(this) + " removed=" + history.size()
        + " summaryChars=" + conversationSummary.length());
    history.clear();
    conversationSummary = "";
  }

  @Override
  public synchronized void stop() {
    cancel();
    running = false;
    services.close();
  }

  @Override
  public void close() {
    stop();
    executor.shutdownNow();
    listeners.clear();
  }

  public static String message(Throwable error) {
    while (error.getCause() != null
        && (error instanceof CompletionException || error instanceof ExecutionException))
      error = error.getCause();
    return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
  }
}
