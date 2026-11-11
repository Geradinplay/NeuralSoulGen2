package com.neuralsoul.web;

import com.neuralsoul.config.*;
import com.neuralsoul.service.api.*;
import com.neuralsoul.service.http.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;

/**
 * Dedicated local web research pipeline.
 *
 * <p>The WEB_SEARCH model is deliberately narrow: it only formulates search queries.
 * A separate reasoning model owns the research state machine and decides whether the
 * current web evidence is enough, whether a discovered URL must be fetched, or whether
 * another search is needed. Java only executes those decisions and enforces safety,
 * deduplication and budgets.</p>
 */
public final class WebSearchAgent implements AutoCloseable {
  private static final int PORT = 8082;

  private static final Pattern SEARCH_TOOL_CALL = Pattern.compile(
      "\\[\\[?\\s*WEB_SEARCH\\b(.*?)\\]\\]?", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern ACTION = Pattern.compile(
      "(?im)^\\s*ACTION\\s*[:=]\\s*(ANSWER|FETCH|SEARCH)\\s*$");
  private static final Pattern ANSWER = Pattern.compile("(?is)\\bANSWER\\s*:\\s*(.+)");
  private static final Pattern URL = Pattern.compile("(?im)^\\s*URL\\s*:\\s*(https?://\\S+)\\s*$");
  private static final Pattern NEED = Pattern.compile("(?is)\\bNEED\\s*:\\s*(.+)");
  private static final Pattern TOOL_LIKE = Pattern.compile(
      "\\[\\[?\\s*WEB_(?:SEARCH|FETCH|TIME)\\b", Pattern.CASE_INSENSITIVE);

  private static final int MAX_CONTROLLER_STEPS = 16;
  private static final int MAX_SEARCHES = 7;
  private static final int MAX_FETCHES = 8;
  private static final int MAX_REASONING_EVIDENCE_CHARS = 9000;

  private final WebToolService tools = new WebToolService();
  private OpenAiLlmService llm;
  /** Tool-free research controller. May point to Main LLM (:8080) or a dedicated reasoning runtime. */
  private volatile LlmService reasoningLlm;
  private volatile String reasoningLabel = "Web Search model";
  private String model = "";
  private volatile String systemPrompt = AgentPrompts.defaultPrompt(com.neuralsoul.model.ModelRole.WEB_SEARCH);
  private volatile Consumer<WebSearchRecord> searchListener = ignored -> {};

  public void setSystemPrompt(String prompt) {
    systemPrompt = Objects.requireNonNull(prompt);
  }

  /** Receives every successful real WEB_SEARCH batch. Listener failures never break research. */
  public void setSearchListener(Consumer<WebSearchRecord> listener) {
    searchListener = listener == null ? ignored -> {} : listener;
  }

  private void publishSearch(WebSearchRecord record) {
    try {
      searchListener.accept(record);
    } catch (Throwable listenerFailure) {
      System.out.println("[WebSearch/UI] search history listener failed: " + listenerFailure);
    }
  }

  public synchronized void configure(
      String modelName, int maxOutputTokens, LlmService reasoningService, String reasoningModelLabel) throws Exception {
    close();
    this.model = ""; // one model per dedicated llama-server; omit alias to avoid mismatches
    EndpointConfig ep = new EndpointConfig("http://127.0.0.1:" + PORT,
        "/v1/chat/completions", "/v1/models", "", 90);
    LlmConfig cfg = new LlmConfig(ep, this.model, "", 0.20, Math.max(64, maxOutputTokens));
    llm = new OpenAiLlmService(() -> cfg, new HttpTransport(),
        Map.of(
            "chat_template_kwargs", Map.of("enable_thinking", false),
            // The query generator needs only one tool call. Keep the per-model token budget as
            // a safety ceiling, but stop as soon as the call is complete.
            "stop", List.of("]]")));
    llm.start();
    this.reasoningLlm = reasoningService == null ? llm : reasoningService;
    this.reasoningLabel = reasoningModelLabel == null || reasoningModelLabel.isBlank()
        ? "Reasoning" : reasoningModelLabel;
    System.out.println("[WebSearch] Query model max output tokens: " + cfg.maxTokens());
    System.out.println("[WebSearch] Research controller: " + this.reasoningLabel);
  }

  public synchronized boolean isReady() {
    return llm != null && llm.isRunning();
  }

  public String research(String task) throws Exception {
    try {
      return researchTurn(task);
    } catch (InterruptedException | java.util.concurrent.CancellationException cancelled) {
      throw cancelled;
    } catch (Exception failure) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Web Search cancelled");
      System.out.println("[WebSearch] failed: " + failure);
      recoverRuntimeAfterRequestFailure();
      return "WEB_SEARCH_RESULT\nstatus=ERROR\nverified=false\nretryable=true\nerror="
          + failure.getClass().getSimpleName() + "\nmessage=" + safeMessage(failure);
    }
  }

  private String researchTurn(String task) throws Exception {
    OpenAiLlmService searchModel;
    synchronized (this) {
      searchModel = llm;
    }
    if (searchModel == null || !searchModel.isRunning())
      throw new IllegalStateException("Web Search Agent runtime не запущен");

    LlmService controller = reasoningLlm;
    if (controller == null || !controller.isRunning())
      throw new IllegalStateException("Web Reasoning runtime не запущен");

    List<SearchEvidence> searches = new ArrayList<>();
    List<FetchEvidence> fetches = new ArrayList<>();
    LinkedHashMap<String, String> discoveredUrls = new LinkedHashMap<>();
    LinkedHashSet<String> fetchedUrls = new LinkedHashSet<>();
    LinkedHashSet<String> previousQueries = new LinkedHashSet<>();
    List<Set<String>> previousSearchIntents = new ArrayList<>();

    int searchAttempts = 0;
    int successfulSearches = 0;
    int blockedFetches = 0;
    int fetchAttempts = 0;
    String lastRejectedFetchKey = "";
    int repeatedRejectedFetches = 0;
    String searchNeed = "Find current public-web information that directly answers the delegated task.";
    int queryFailures = 0;
    boolean queryModelBroken = false;
    int searchRequestedWhileBroken = 0;
    // When the query model breaks mid-turn, the reasoning controller's SEARCH need text
    // is used directly as the search query instead (bounded fallback searches).
    String forcedQuery = null;
    int fallbackSearches = 0;

    for (int step = 0; step < MAX_CONTROLLER_STEPS; step++) {
      if (queryModelBroken && forcedQuery == null && searchRequestedWhileBroken >= 2) {
        // The controller keeps asking for new searches it can no longer get. Do not burn
        // the step budget: return the evidence collected so far to the Main LLM.
        return partialOrError("SEARCH_BUDGET_EXHAUSTED",
            "Query model stopped producing search queries; collected evidence follows.",
            searches, fetches, searchAttempts, successfulSearches, fetchedUrls.size(), blockedFetches, step + 1);
      }

      if ((searches.isEmpty() || searchNeed != null || forcedQuery != null)
          && (!queryModelBroken || forcedQuery != null)) {
        boolean forced = forcedQuery != null;
        if (searchAttempts >= MAX_SEARCHES) {
          return errorResult("SEARCH_BUDGET_EXHAUSTED", true,
              "Reasoning requested another search after the search budget was exhausted.",
              searchAttempts, successfulSearches, fetchedUrls.size(), blockedFetches, step + 1);
        }

        String query;
        if (forced) {
          query = forcedQuery;
          forcedQuery = null;
        } else {
          try {
            query = formulateSearchQuery(searchModel, task, searchNeed, previousQueries);
          } catch (InterruptedException interrupted) {
            throw interrupted;
          } catch (Exception failure) {
            // A truncated/failed query-model pass must not discard the evidence already
            // collected. Retry a few times, then let the controller work with what exists.
            queryFailures++;
            System.out.println("[WebSearch] QUERY GENERATION failed (" + queryFailures + "/"
                + 3 + "): " + safeMessage(failure));
            if (queryFailures <= 2) continue;
            queryModelBroken = true;
            searches.add(SearchEvidence.failed("(query generation failed)",
                "Search query generation failed: " + safeMessage(failure)));
            searchNeed = null;
            System.out.println("[WebSearch] query model disabled for this turn; controller continues with collected evidence");
            continue;
          }
        }
        searchNeed = null;
        if (!forced && query.isBlank()) {
          queryFailures++;
          System.out.println("[WebSearch] QUERY GENERATION produced an empty query (" + queryFailures + "/3)");
          if (queryFailures <= 2) continue;
          queryModelBroken = true;
          searchNeed = null;
          System.out.println("[WebSearch] query model disabled for this turn; controller continues with collected evidence");
          continue;
        }
        queryFailures = 0;

        String canonicalQuery = canonical(query);
        Set<String> intent = searchIntent(query);
        boolean duplicate = containsCanonicalQuery(previousQueries, canonicalQuery)
            || isNearDuplicate(intent, previousSearchIntents);
        if (duplicate) {
          if (!forced) {
            // Do not let the small search model burn the context by repeating the same query.
            // Ask it once more with an explicit mechanical constraint; no semantic task-specific rule.
            String retryNeed = "The proposed query duplicates a previous search. Formulate a materially different query "
                + "for the same missing information. Change the retrieval angle, terminology, language, source type, or domain.";
            try {
              query = formulateSearchQuery(searchModel, task, retryNeed, previousQueries);
            } catch (InterruptedException interrupted) {
              throw interrupted;
            } catch (Exception failure) {
              // The retry formulation is not allowed to crash the research turn: the
              // controller still has usable evidence and can FETCH or re-angle instead.
              queryFailures++;
              System.out.println("[WebSearch] QUERY GENERATION retry after duplicate failed (" + queryFailures + "/3): "
                  + safeMessage(failure));
              searches.add(SearchEvidence.failed(query,
                  "Search query generation failed while reformulating a duplicate query: " + safeMessage(failure)
                      + ". Re-use the existing evidence: if a plausible unfetched URL can fill the gap, choose FETCH; "
                      + "otherwise request a materially different SEARCH angle."));
              searchNeed = null;
              continue;
            }
            canonicalQuery = canonical(query);
            intent = searchIntent(query);
            duplicate = query.isBlank()
                || containsCanonicalQuery(previousQueries, canonicalQuery)
                || isNearDuplicate(intent, previousSearchIntents);
          }
          if (duplicate) {
            // A repeated query is a failed SEARCH action, not a failed research turn.
            // Feed that mechanical failure back to the reasoning controller. It may now
            // FETCH one of the already discovered candidates instead of letting the 4B
            // query generator terminate the whole research loop.
            searches.add(SearchEvidence.failed(query,
                "SEARCH action rejected because the query duplicates an earlier search. "
                    + "Re-use the existing evidence: if a plausible unfetched URL can fill the gap, choose FETCH; "
                    + "otherwise request a materially different SEARCH angle."));
            searchNeed = null;
            System.out.println("[WebSearch] duplicate SEARCH rejected; returning control to reasoning instead of NO_PROGRESS");
            continue;
          }
        }

        searchAttempts++;
        previousQueries.add(query);
        previousSearchIntents.add(intent);
        System.out.println("[WebSearch] SEARCH step=" + (step + 1) + " query=" + query);

        try {
          WebToolService.SearchBatch batch = tools.searchDetailed(query, 6);
          successfulSearches++;
          SearchEvidence evidence = new SearchEvidence(query, batch.provider(), batch.formatted(), batch.hits());
          searches.add(evidence);
          publishSearch(new WebSearchRecord(java.time.Instant.now(), query, batch.provider(), batch.hits()));
          for (SearchHit hit : batch.hits()) {
            if (hit != null && hit.url() != null && !hit.url().isBlank())
              discoveredUrls.putIfAbsent(canonicalUrl(hit.url()), stripFragment(hit.url()));
          }
          System.out.println("[WebSearch] ===== SEARCH RESULT =====\n" + batch.formatted()
              + "\n[WebSearch] ===== /SEARCH RESULT =====");
        } catch (InterruptedException interrupted) {
          throw interrupted;
        } catch (Exception failure) {
          String message = safeMessage(failure);
          searches.add(SearchEvidence.failed(query, message));
          System.out.println("[WebSearch] SEARCH failed query=" + query + " error=" + message);
        }
      }

      ResearchDecision decision = decideNextAction(task, searches, fetches, previousQueries, fetchedUrls);
      System.out.println("[WebSearch] CONTROLLER action=" + decision.action()
          + (decision.url().isBlank() ? "" : " url=" + decision.url())
          + (decision.need().isBlank() ? "" : " need=" + oneLine(decision.need())));

      if (decision.action() == ResearchAction.ANSWER) {
        if (decision.answer().isBlank()) {
          searchNeed = "The previous reasoning pass selected ANSWER but produced no answer. Re-check the web evidence and find what is missing.";
          continue;
        }
        return verifiedResearchResult(decision.answer(), searchAttempts, successfulSearches,
            fetchedUrls, evidenceSourceUrls(searches, fetches));
      }

      if (decision.action() == ResearchAction.SEARCH) {
        lastRejectedFetchKey = "";
        repeatedRejectedFetches = 0;
        if (queryModelBroken) {
          // The query model cannot formulate queries, but the reasoning controller just
          // described what is missing. Use its own need text as the search query directly
          // (bounded, so a looping controller cannot burn the whole search budget).
          String fallback = decision.need();
          if (!fallback.isBlank() && fallbackSearches < 2) {
            fallbackSearches++;
            forcedQuery = fallback;
            System.out.println("[WebSearch] QUERY MODEL broken; controller need used as fallback query: " + fallback);
            continue;
          }
          searchRequestedWhileBroken++;
          searchNeed = null;
          continue;
        }
        searchNeed = decision.need().isBlank()
            ? "The current web evidence is insufficient. Find the missing information needed to answer the delegated task."
            : decision.need();
        continue;
      }

      if (fetchAttempts >= MAX_FETCHES) {
        searchNeed = "Fetching candidates did not provide enough usable evidence. Search for alternative sources whose relevant information is visible or accessible.";
        continue;
      }

      String requestedUrl = stripFragment(decision.url());
      String canonicalUrl = canonicalUrl(requestedUrl);
      String discovered = discoveredUrls.get(canonicalUrl);
      if (discovered == null) {
        // Invalid controller actions must never grow the evidence forever. Keep one compact
        // mechanical error, give Reasoning one chance to repair itself, then force a fresh
        // SEARCH need if it repeats the exact same impossible FETCH.
        addFetchErrorOnce(fetches, requestedUrl,
            "URL was not present in WEB_SEARCH results; FETCH was not executed.");
        String rejectedKey = "undiscovered:" + canonicalUrl;
        if (rejectedKey.equals(lastRejectedFetchKey)) repeatedRejectedFetches++;
        else {
          lastRejectedFetchKey = rejectedKey;
          repeatedRejectedFetches = 1;
        }
        System.out.println("[WebSearch] FETCH rejected reason=undiscovered repeat=" + repeatedRejectedFetches
            + " url=" + requestedUrl);
        if (repeatedRejectedFetches >= 2) {
          searchNeed = "The reasoning controller repeatedly requested a URL that was not returned by web search. "
              + "Find a materially different real source for the missing information.";
          lastRejectedFetchKey = "";
          repeatedRejectedFetches = 0;
        }
        continue;
      }
      if (fetchedUrls.contains(canonicalUrl)) {
        addFetchErrorOnce(fetches, discovered,
            "This URL was already fetched. Its existing fetch evidence must be reused; FETCH cannot produce new evidence for it.");
        String rejectedKey = "already-fetched:" + canonicalUrl;
        if (rejectedKey.equals(lastRejectedFetchKey)) repeatedRejectedFetches++;
        else {
          lastRejectedFetchKey = rejectedKey;
          repeatedRejectedFetches = 1;
        }
        System.out.println("[WebSearch] FETCH rejected reason=already_fetched repeat=" + repeatedRejectedFetches
            + " url=" + discovered);
        if (repeatedRejectedFetches >= 2) {
          searchNeed = "The reasoning controller repeatedly requested an already-fetched URL. "
              + "Search for a materially different source that can fill the remaining evidence gap.";
          lastRejectedFetchKey = "";
          repeatedRejectedFetches = 0;
        }
        continue;
      }

      fetchedUrls.add(canonicalUrl);
      lastRejectedFetchKey = "";
      repeatedRejectedFetches = 0;
      fetchAttempts++;
      try {
        System.out.println("[WebSearch] FETCH step=" + (step + 1) + " url=" + discovered);
        String content = tools.fetch(discovered);
        fetches.add(FetchEvidence.success(discovered, content));
        System.out.println("[WebSearch] ===== FETCH RESULT =====\n" + content
            + "\n[WebSearch] ===== /FETCH RESULT =====");
      } catch (WebToolService.FetchBlockedException blocked) {
        blockedFetches++;
        String message = "HTTP " + blocked.statusCode() + " blocked automated fetch.";
        fetches.add(FetchEvidence.error(blocked.url(), message));
        System.out.println("[WebSearch] FETCH blocked " + message + " url=" + blocked.url());
      } catch (InterruptedException interrupted) {
        throw interrupted;
      } catch (Exception failure) {
        String message = safeMessage(failure);
        fetches.add(FetchEvidence.error(discovered, message));
        System.out.println("[WebSearch] FETCH failed url=" + discovered + " error=" + message);
      }
      // No scout call here. Every SEARCH result and every FETCH result goes directly back
      // to the reasoning controller, which decides the next action from a fresh context.
    }

    return partialOrError("BUDGET_EXHAUSTED",
        "Research controller step budget exhausted before it selected ANSWER; collected evidence follows.",
        searches, fetches, searchAttempts, successfulSearches, fetchedUrls.size(), blockedFetches, MAX_CONTROLLER_STEPS);
  }

  /**
   * Returns the evidence collected so far when the research pipeline could not finish
   * normally (query model hit its token limit, controller kept requesting searches, etc.).
   * The Main LLM can still use the real search hits and fetched page content, retry later,
   * or answer best-effort with a confidence estimate. Falls back to a plain error result
   * only when nothing was collected at all.
   */
  private static String partialOrError(
      String status, String message, List<SearchEvidence> searches, List<FetchEvidence> fetches,
      int attempts, int successful, int fetched, int blocked, int steps) {
    StringBuilder out = new StringBuilder("WEB_SEARCH_RESULT\nstatus=")
        .append(status).append("\nverified=false\nretryable=true")
        .append("\nsearchAttempts=").append(attempts)
        .append("\nsuccessfulSearches=").append(successful)
        .append("\nfetchedSources=").append(fetched)
        .append("\nblockedFetches=").append(blocked)
        .append("\nsteps=").append(steps)
        .append("\nmessage=").append(message == null ? "" : message);

    List<SearchEvidence> okSearches = new ArrayList<>();
    for (SearchEvidence s : searches) if (s != null && s.error().isBlank() && !s.hits().isEmpty()) okSearches.add(s);
    List<FetchEvidence> okFetches = new ArrayList<>();
    for (FetchEvidence f : fetches) if (f != null && f.success()) okFetches.add(f);

    if (okSearches.isEmpty() && okFetches.isEmpty())
      return errorResult(status, true, message, attempts, successful, fetched, blocked, steps);

    int budget = 6000;
    if (!okSearches.isEmpty()) {
      out.append("\n\nCOLLECTED_SEARCH_EVIDENCE (real search-engine output, newest first):\n");
      for (int i = okSearches.size() - 1; i >= 0 && budget > 0; i--) {
        String block = okSearches.get(i).formatted();
        if (block.isBlank()) continue;
        String piece = block.length() > budget ? block.substring(0, budget) : block;
        out.append(piece).append('\n');
        budget -= piece.length();
      }
    }
    if (!okFetches.isEmpty()) {
      out.append("\nCOLLECTED_FETCH_EVIDENCE (page content fetched during this research, newest first):\n");
      for (int i = okFetches.size() - 1; i >= 0 && budget > 0; i--) {
        String content = okFetches.get(i).content();
        String piece = content.length() > budget ? content.substring(0, budget) : content;
        out.append("URL: ").append(okFetches.get(i).url()).append('\n').append(piece).append('\n');
        budget -= piece.length();
      }
    }
    out.append("\nNOTE: This research turn ended early. The evidence above is real but possibly incomplete; treat it as unverified.");
    return out.toString();
  }

  /**
   * The small WEB_SEARCH model is only a query generator. It never sees page bodies and it
   * never decides whether evidence is sufficient. This keeps its context tiny and prevents
   * the old SEARCH/FETCH/finalize loops from accumulating inside the 4B model.
   */
  private String formulateSearchQuery(
      OpenAiLlmService searchModel, String task, String need, Set<String> previousQueries) throws Exception {
    List<ConversationTurn> turns = new ArrayList<>();
    turns.add(new ConversationTurn("system", systemPrompt));
    turns.add(new ConversationTurn("system", """
[NEURALSOUL WEB QUERY GENERATOR]
This runtime instruction overrides any older search/fetch workflow in the editable agent prompt.
Your ONLY job in this pass is to formulate ONE useful public-web search query.
You do not browse pages, do not fetch URLs, do not judge evidence, and do not answer the delegated task.

Return exactly one tool call and nothing else:
[[WEB_SEARCH "query"]]

Stop immediately after the closing brackets. Do not describe results, invent search hits, emit WEB_FETCH, or continue reasoning after the tool call.
The query may translate/transliterate names or add retrieval context, but must preserve the delegated task's subject and intent.
If a SEARCH NEED is supplied, target that missing information instead of repeating the previous query.
Do not repeat any query listed under PREVIOUS QUERIES.
"""));
    StringBuilder request = new StringBuilder("DELEGATED TASK:\n").append(task)
        .append("\n\nSEARCH NEED:\n").append(need == null || need.isBlank() ? "General initial search." : need);
    if (previousQueries != null && !previousQueries.isEmpty()) {
      request.append("\n\nPREVIOUS QUERIES (do not repeat):\n");
      for (String previous : previousQueries) request.append("- ").append(previous).append('\n');
    }
    turns.add(new ConversationTurn("user", request.toString()));

    String raw = searchModel.generate(turns, model);
    System.out.println("[WebSearch] ===== QUERY MODEL =====\n" + raw + "\n[WebSearch] ===== /QUERY MODEL =====");
    return parseSearchQuery(raw);
  }

  /**
   * Stateless reasoning pass. It receives the original task, exactly what was searched,
   * the real search results/snippets, and any fetch results/errors. It alone chooses the
   * next research action. Search snippets are valid web evidence; FETCH is requested only
   * when the controller believes a discovered page must be opened to answer reliably.
   */
  private ResearchDecision decideNextAction(
      String task,
      List<SearchEvidence> searches,
      List<FetchEvidence> fetches,
      Set<String> previousQueries,
      Set<String> fetchedUrls) throws InterruptedException {
    LlmService controller = reasoningLlm;
    if (controller == null || !controller.isRunning())
      return ResearchDecision.search("Reasoning model is not running; obtain fresh web evidence after it is restored.");

    List<ConversationTurn> turns = new ArrayList<>();
    turns.add(new ConversationTurn("system", """
[NEURALSOUL WEB RESEARCH CONTROLLER]
You are the tool-free reasoning/controller stage of a web research pipeline.
You receive the ORIGINAL TASK plus REAL WEB EVIDENCE produced by a search engine and optional page fetches.
Your prior knowledge is not evidence. Judge only what is present below.

Choose exactly ONE next action:

ACTION: ANSWER
ANSWER: <concise answer/report for the Main LLM, grounded in the supplied web evidence; include supporting URL(s) when useful>

ACTION: FETCH
URL: <one exact http/https URL that appears in the supplied WEB SEARCH evidence>

ACTION: SEARCH
NEED: <what information the Search LLM should search for next>

Decision rules:
- SEARCH results, including titles and snippets, ARE web evidence. They may be enough when they directly support the requested fact.
- If the current SEARCH evidence is NOT enough to answer, but it contains a plausible URL whose page could supply the missing information, choose FETCH for that URL BEFORE asking for another SEARCH.
- A title/URL with no useful snippet is normally a candidate to FETCH, not a reason to repeat the same search with a few extra words.
- Choose SEARCH only when the existing result URLs are clearly irrelevant, already fetched/failed, or the missing information genuinely requires a different retrieval angle/source set.
- If FETCH fails, is blocked, or a URL is marked ALREADY FETCHED, NEVER choose FETCH for that URL again. Reuse its existing evidence, choose another unfetched search-result URL, or choose SEARCH.
- FETCHing the same URL twice cannot reveal new information in this pipeline. Treat the mechanical FETCH STATE as authoritative.
- If the current evidence answers the ORIGINAL TASK, choose ANSWER immediately. Do not keep researching merely for confirmation.
- For broad, comparative, multi-part, disputed, or fast-changing questions, require enough evidence for the requested scope before ANSWER.
- Never invent a URL. FETCH may use only a URL literally present in WEB SEARCH evidence.
- Never invent facts missing from the supplied evidence.
- Do not emit WEB_SEARCH/WEB_FETCH/WEB_TIME tool syntax. Java executes your ACTION.
- Return one ACTION block only, with no prose before it.
"""));
    turns.add(new ConversationTurn("user", "ORIGINAL TASK:\n" + task));
    turns.add(new ConversationTurn("system",
        buildEvidenceBlock(searches, fetches, previousQueries, fetchedUrls, MAX_REASONING_EVIDENCE_CHARS)));

    try {
      String raw = controller.generate(turns, "");
      System.out.println("[WebSearch] ===== RESEARCH CONTROLLER: " + reasoningLabel + " =====\n" + raw
          + "\n[WebSearch] ===== /RESEARCH CONTROLLER =====");
      if (TOOL_LIKE.matcher(raw).find()) {
        return ResearchDecision.search(
            "The previous controller response used forbidden WEB_* tool syntax. Re-evaluate the evidence and choose ANSWER, FETCH, or SEARCH using the controller protocol.");
      }
      return parseResearchDecision(raw);
    } catch (InterruptedException interrupted) {
      throw interrupted;
    } catch (Exception failure) {
      System.out.println("[WebSearch] RESEARCH CONTROLLER failed (" + reasoningLabel + "): " + safeMessage(failure));
      recoverReasoningAfterRequestFailure(controller);
      return ResearchDecision.search(
          "The reasoning pass failed. Search for a compact alternative source that directly exposes the information needed by the original task.");
    }
  }

  private static void addFetchErrorOnce(List<FetchEvidence> fetches, String url, String error) {
    if (fetches == null) return;
    String canonical = canonicalUrl(url);
    String normalizedError = error == null ? "" : error.strip();
    for (FetchEvidence item : fetches) {
      if (item == null || item.success()) continue;
      if (canonicalUrl(item.url()).equals(canonical) && item.error().equals(normalizedError)) return;
    }
    fetches.add(FetchEvidence.error(url, normalizedError));
  }

  private static String buildEvidenceBlock(
      List<SearchEvidence> searches,
      List<FetchEvidence> fetches,
      Set<String> previousQueries,
      Set<String> fetchedUrls,
      int maxChars) {
    List<String> chunks = new ArrayList<>();

    // Successful/failed FETCH evidence is usually the most information-dense, so keep the
    // newest fetches first when the controller context has to be capped. Search evidence
    // is still included immediately afterwards, including the query that produced the URL.
    if (fetches != null) {
      for (int i = fetches.size() - 1; i >= 0; i--) {
        FetchEvidence item = fetches.get(i);
        String text = item.success()
            ? "WEB FETCH\nURL: " + item.url() + "\n" + item.content()
            : "WEB FETCH ERROR\nURL: " + item.url() + "\nERROR: " + item.error();
        chunks.add(text);
      }
    }
    if (searches != null) {
      for (int i = searches.size() - 1; i >= 0; i--) {
        SearchEvidence item = searches.get(i);
        String text = item.error().isBlank()
            ? "WEB SEARCH\nQUERY: " + item.query() + "\nPROVIDER: " + item.provider() + "\n" + item.formatted()
            : "WEB SEARCH ERROR\nQUERY: " + item.query() + "\nERROR: " + item.error();
        chunks.add(text);
      }
    }

    StringBuilder out = new StringBuilder("REAL WEB EVIDENCE:\n");
    if (fetchedUrls != null && !fetchedUrls.isEmpty()) {
      out.append("\nFETCH STATE (mechanical, authoritative):\n");
      for (String url : fetchedUrls) {
        if (url == null || url.isBlank()) continue;
        out.append("- ALREADY FETCHED: ").append(url).append('\n');
      }
      out.append("Do not select FETCH for any ALREADY FETCHED URL. Reuse its evidence, choose another URL, or SEARCH.\n");
    }
    int used = out.length();
    for (String chunk : chunks) {
      if (chunk == null || chunk.isBlank()) continue;
      int room = maxChars - used;
      if (room <= 200) break;
      String value = chunk.length() > room ? chunk.substring(0, room) + "\n[controller evidence truncated]" : chunk;
      out.append("\n---\n").append(value).append('\n');
      used = out.length();
    }

    if (previousQueries != null && !previousQueries.isEmpty() && out.length() < maxChars - 200) {
      out.append("\nSEARCHES ALREADY EXECUTED:\n");
      for (String query : previousQueries) {
        if (out.length() + query.length() + 4 > maxChars) break;
        out.append("- ").append(query).append('\n');
      }
    }
    return out.toString();
  }

  private static ResearchDecision parseResearchDecision(String raw) {
    if (raw == null || raw.isBlank())
      return ResearchDecision.search("The reasoning model returned an empty decision. Find a direct source for the original task.");

    Matcher action = ACTION.matcher(raw);
    if (!action.find()) {
      // Local models occasionally omit the redundant ACTION line while still returning
      // exactly one valid controller field (for example: "ANSWER: ..."). Repair that
      // formatting deviation here instead of throwing away an otherwise grounded decision.
      // This is protocol recovery only; Java does not judge the semantic truth of the answer.
      Matcher answerOnly = ANSWER.matcher(raw);
      if (answerOnly.find())
        return ResearchDecision.answer(answerOnly.group(1).strip());

      Matcher urlOnly = URL.matcher(raw);
      if (urlOnly.find())
        return ResearchDecision.fetch(urlOnly.group(1).strip());

      Matcher needOnly = NEED.matcher(raw);
      if (needOnly.find())
        return ResearchDecision.search(needOnly.group(1).strip());

      return ResearchDecision.search(
          "The reasoning model did not return a recognizable ANSWER, FETCH URL, or SEARCH NEED. "
              + "Find a direct source for the original task.");
    }

    String firstBlock = raw.substring(action.start());
    Matcher second = ACTION.matcher(firstBlock);
    if (second.find() && second.find()) firstBlock = firstBlock.substring(0, second.start());

    return switch (action.group(1).toUpperCase(Locale.ROOT)) {
      case "ANSWER" -> {
        Matcher answer = ANSWER.matcher(firstBlock);
        yield answer.find() ? ResearchDecision.answer(answer.group(1).strip())
            : ResearchDecision.search("The controller selected ANSWER without supplying ANSWER text.");
      }
      case "FETCH" -> {
        Matcher url = URL.matcher(firstBlock);
        yield url.find() ? ResearchDecision.fetch(url.group(1).strip())
            : ResearchDecision.search("The controller selected FETCH without a URL from the search evidence.");
      }
      default -> {
        Matcher need = NEED.matcher(firstBlock);
        yield ResearchDecision.search(need.find() ? need.group(1).strip()
            : "The current evidence is insufficient; search for the missing information required by the original task.");
      }
    };
  }

  private static String parseSearchQuery(String raw) {
    if (raw == null || raw.isBlank()) return "";

    Matcher matcher = SEARCH_TOOL_CALL.matcher(raw);
    if (matcher.find()) return normalizeSearchArgument(matcher.group(1));

    // The small query model can hit its completion limit after it has already emitted
    // a perfectly usable query but before it closes the quote/brackets, e.g.
    // [[WEB_SEARCH "some useful query
    // Recover only the first line after an explicit WEB_SEARCH marker. This is a
    // transport/protocol repair; it does not invent or semantically rewrite the query.
    Matcher partial = Pattern.compile(
        "(?is)\\[\\[?\\s*WEB_SEARCH\\b\\s*[:(]?\\s*(.+)$").matcher(raw);
    if (!partial.find()) return "";

    String tail = partial.group(1);
    int newline = firstLineBreak(tail);
    if (newline >= 0) tail = tail.substring(0, newline);
    int closing = tail.indexOf("]]" );
    if (closing >= 0) tail = tail.substring(0, closing);

    return normalizeSearchArgument(tail);
  }

  private static int firstLineBreak(String value) {
    int rn = value.indexOf('\r');
    int nn = value.indexOf('\n');
    if (rn < 0) return nn;
    if (nn < 0) return rn;
    return Math.min(rn, nn);
  }

  private static String normalizeSearchArgument(String raw) {
    String value = raw == null ? "" : raw.trim();
    if (value.startsWith(":")) value = value.substring(1).trim();
    if (value.length() >= 2 && value.startsWith("(") && value.endsWith(")"))
      value = value.substring(1, value.length() - 1).trim();

    if (value.startsWith("{") && value.endsWith("}")) {
      try {
        Map<String, Object> json = Json.object(Json.read(value));
        for (String key : List.of("query", "q", "search", "text")) {
          Object candidate = json.get(key);
          if (candidate instanceof String text && !text.isBlank()) return text.strip();
        }
      } catch (RuntimeException ignored) {}
    }

    Matcher assignment = Pattern.compile("(?is)^(?:query|q|search|text)\\s*[:=]\\s*(.+)$").matcher(value);
    if (assignment.matches()) value = assignment.group(1).trim();
    if (value.endsWith(")") && !value.startsWith("(")) value = value.substring(0, value.length() - 1).trim();
    if (!value.isEmpty() && (value.charAt(0) == '"' || value.charAt(0) == '\'')) {
      char quote = value.charAt(0);
      int closingQuote = value.indexOf(quote, 1);
      if (closingQuote >= 0) {
        value = value.substring(1, closingQuote).trim();
      } else {
        // Accept a truncated tool call whose opening quote was emitted but whose closing
        // quote was cut off by max_tokens. The explicit WEB_SEARCH marker is required by
        // parseSearchQuery before this recovery path can be reached.
        value = value.substring(1).trim();
      }
    }
    while (value.endsWith("]")) value = value.substring(0, value.length() - 1).trim();
    return value;
  }

  private void recoverReasoningAfterRequestFailure(LlmService controller) {
    if (controller == null || controller == llm || controller.isRunning()) {
      if (controller == llm) recoverRuntimeAfterRequestFailure();
      return;
    }
    try {
      controller.start();
      System.out.println("[WebSearch] research-controller HTTP client recovered: " + reasoningLabel);
    } catch (Exception recoveryFailure) {
      System.out.println("[WebSearch] research-controller recovery failed: " + safeMessage(recoveryFailure));
    }
  }

  /** Restore the lightweight HTTP client after request-level llama.cpp errors. */
  private synchronized void recoverRuntimeAfterRequestFailure() {
    if (llm == null || llm.isRunning()) return;
    try {
      llm.start();
      System.out.println("[WebSearch] runtime probe succeeded after request failure; agent remains ready");
    } catch (Exception recoveryFailure) {
      System.out.println("[WebSearch] runtime recovery failed: " + safeMessage(recoveryFailure));
    }
  }

  private static Set<String> evidenceSourceUrls(List<SearchEvidence> searches, List<FetchEvidence> fetches) {
    LinkedHashSet<String> urls = new LinkedHashSet<>();
    if (fetches != null) {
      for (FetchEvidence fetch : fetches)
        if (fetch.success() && fetch.url() != null && !fetch.url().isBlank()) urls.add(stripFragment(fetch.url()));
    }
    if (searches != null) {
      for (int i = searches.size() - 1; i >= 0 && urls.size() < 8; i--) {
        for (SearchHit hit : searches.get(i).hits()) {
          if (hit != null && hit.url() != null && !hit.url().isBlank()) urls.add(stripFragment(hit.url()));
          if (urls.size() >= 8) break;
        }
      }
    }
    return urls;
  }

  private static String verifiedResearchResult(
      String report, int attempts, int successful, Set<String> fetchedUrls, Set<String> sourceUrls) {
    return "WEB_SEARCH_RESULT\nstatus=OK\nverified=true\nsearchAttempts=" + attempts
        + "\nsuccessfulSearches=" + successful
        + "\nfetchedSources=" + (fetchedUrls == null ? 0 : fetchedUrls.size())
        + "\nsourceUrls=" + (sourceUrls == null ? "" : String.join(" | ", sourceUrls))
        + "\nreport:\n" + (report == null ? "" : report.strip());
  }

  private static String errorResult(
      String status, boolean retryable, String message, int attempts, int successful,
      int fetched, int blocked, int steps) {
    return "WEB_SEARCH_RESULT\nstatus=" + status + "\nverified=false\nretryable=" + retryable
        + "\nsearchAttempts=" + attempts + "\nsuccessfulSearches=" + successful
        + "\nfetchedSources=" + fetched + "\nblockedFetches=" + blocked
        + "\nsteps=" + steps + "\nmessage=" + (message == null ? "" : message);
  }

  private static String canonical(String value) {
    return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
  }

  private static String canonicalUrl(String value) {
    if (value == null || value.isBlank()) return "";
    try {
      java.net.URI uri = java.net.URI.create(value.strip());
      java.net.URI clean = new java.net.URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), uri.getQuery(), null);
      return clean.toString().toLowerCase(Locale.ROOT);
    } catch (Exception ignored) {
      int hash = value.indexOf('#');
      return canonical(hash >= 0 ? value.substring(0, hash) : value);
    }
  }

  private static String stripFragment(String value) {
    if (value == null) return "";
    int hash = value.indexOf('#');
    return (hash >= 0 ? value.substring(0, hash) : value).strip();
  }

  private static boolean containsCanonicalQuery(Set<String> queries, String canonicalQuery) {
    if (queries == null || queries.isEmpty()) return false;
    for (String query : queries) if (canonical(query).equals(canonicalQuery)) return true;
    return false;
  }

  private static Set<String> searchIntent(String query) {
    return new LinkedHashSet<>(SearchResultQuality.anchors(query));
  }

  private static boolean isNearDuplicate(Set<String> candidate, List<Set<String>> previous) {
    if (candidate == null || candidate.isEmpty()) return false;
    for (Set<String> old : previous) {
      if (old == null || old.isEmpty()) continue;
      int intersection = 0;
      for (String token : candidate) if (old.contains(token)) intersection++;
      int union = candidate.size() + old.size() - intersection;
      double jaccard = union == 0 ? 1.0 : intersection / (double) union;
      int smaller = Math.min(candidate.size(), old.size());
      boolean subsetLike = smaller >= 2 && intersection >= smaller
          && Math.max(candidate.size(), old.size()) <= smaller + 1;
      if (jaccard >= 0.80 || subsetLike) return true;
    }
    return false;
  }

  private static String safeMessage(Exception failure) {
    String message = failure == null ? "unknown error" : failure.getMessage();
    if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
    return message.replace('\n', ' ').replace('\r', ' ');
  }

  private static String oneLine(String value) {
    return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').strip();
  }

  private enum ResearchAction { ANSWER, FETCH, SEARCH }

  private record ResearchDecision(ResearchAction action, String answer, String url, String need) {
    static ResearchDecision answer(String answer) {
      return new ResearchDecision(ResearchAction.ANSWER, answer == null ? "" : answer.strip(), "", "");
    }
    static ResearchDecision fetch(String url) {
      return new ResearchDecision(ResearchAction.FETCH, "", url == null ? "" : url.strip(), "");
    }
    static ResearchDecision search(String need) {
      return new ResearchDecision(ResearchAction.SEARCH, "", "", need == null ? "" : need.strip());
    }
  }

  private record SearchEvidence(
      String query, String provider, String formatted, List<SearchHit> hits, String error) {
    SearchEvidence {
      query = query == null ? "" : query.strip();
      provider = provider == null ? "" : provider.strip();
      formatted = formatted == null ? "" : formatted;
      hits = hits == null ? List.of() : List.copyOf(hits);
      error = error == null ? "" : error.strip();
    }
    SearchEvidence(String query, String provider, String formatted, List<SearchHit> hits) {
      this(query, provider, formatted, hits, "");
    }
    static SearchEvidence failed(String query, String error) {
      return new SearchEvidence(query, "", "", List.of(), error);
    }
  }

  private record FetchEvidence(String url, boolean success, String content, String error) {
    static FetchEvidence success(String url, String content) {
      return new FetchEvidence(url == null ? "" : url.strip(), true, content == null ? "" : content, "");
    }
    static FetchEvidence error(String url, String error) {
      return new FetchEvidence(url == null ? "" : url.strip(), false, "", error == null ? "" : error.strip());
    }
  }

  @Override
  public synchronized void close() {
    if (llm != null) llm.stop();
    llm = null;
    reasoningLlm = null;
    reasoningLabel = "Web Search model";
    model = "";
  }
}
