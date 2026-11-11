package com.neuralsoul.web;

import com.neuralsoul.config.ConfigLocator;
import com.neuralsoul.config.WebSearchConfig;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

/** Web tools used by the local WEB_SEARCH agent. Search providers are replaceable and can fall back. */
public final class WebToolService {
  /** Typed search batch used both by the agent and by the Internet history UI. */
  public record SearchBatch(String formatted, String provider, List<SearchHit> hits) {
    public SearchBatch {
      formatted = formatted == null ? "" : formatted;
      provider = provider == null ? "" : provider.strip();
      hits = hits == null ? List.of() : List.copyOf(hits);
    }
  }

  public static final class FetchBlockedException extends Exception {
    private final int statusCode;
    private final String url;

    public FetchBlockedException(int statusCode, String url) {
      super("WEB_FETCH HTTP " + statusCode);
      this.statusCode = statusCode;
      this.url = url;
    }

    public int statusCode() { return statusCode; }
    public String url() { return url; }
  }

  static final String BROWSER_UA =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36";

  private final Supplier<WebSearchConfig> config;
  private final HttpClient http;
  private final SearchProvider brave;
  private final SearchProvider searxng;
  private final SearchProvider wikipedia;
  private final SearchProvider mojeek;
  private final SearchProvider bing;
  private final SearchProvider duckDuckGo;

  public WebToolService() {
    this(() -> ConfigLocator.getInstance().webSearch());
  }

  WebToolService(Supplier<WebSearchConfig> config) {
    this.config = Objects.requireNonNull(config);
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    this.brave = new BraveSearchProvider(http,
        () -> this.config.get().braveApiKeyEnv(), () -> this.config.get().timeoutSeconds());
    this.searxng = new SearxngSearchProvider(http,
        () -> this.config.get().searxngBaseUrl(), () -> this.config.get().timeoutSeconds());
    this.wikipedia = new WikipediaSearchProvider(http, () -> this.config.get().timeoutSeconds());
    this.mojeek = new MojeekSearchProvider(http, () -> this.config.get().timeoutSeconds());
    this.bing = new BingSearchProvider(http, () -> this.config.get().timeoutSeconds());
    this.duckDuckGo = new DuckDuckGoSearchProvider(http, () -> this.config.get().timeoutSeconds());
  }

  public String search(String query, int limit) throws Exception {
    return searchDetailed(query, limit).formatted();
  }

  public SearchBatch searchDetailed(String query, int limit) throws Exception {
    if (query == null || query.isBlank()) throw new IllegalArgumentException("WEB_SEARCH query is empty");
    WebSearchConfig cfg = config.get();
    List<SearchProvider> providers = providerOrder(cfg);
    List<String> failures = new ArrayList<>();
    boolean attempted = false;

    for (SearchProvider provider : providers) {
      if (!provider.configured()) {
        System.out.println("[WebSearch/Provider] skip provider=" + provider.id() + " reason=not_configured");
        continue;
      }
      attempted = true;
      try {
        System.out.println("[WebSearch/Provider] try provider=" + provider.id() + " query=" + query);
        List<SearchHit> hits = provider.search(query, Math.max(1, limit));
        if (hits != null && !hits.isEmpty()) {
          List<SearchHit> relevant = SearchResultQuality.filterRelevant(query, hits, Math.max(1, limit));
          if (!relevant.isEmpty()) {
            System.out.println("[WebSearch/Provider] success provider=" + provider.id()
                + " results=" + hits.size() + " relevant=" + relevant.size());
            int max = Math.min(Math.max(1, limit), relevant.size());
            List<SearchHit> visibleHits = List.copyOf(relevant.subList(0, max));
            return new SearchBatch(
                formatResults(query, provider.id(), visibleHits, visibleHits.size()),
                provider.id(),
                visibleHits);
          }
          failures.add(provider.id() + ": results rejected as irrelevant");
          System.out.println("[WebSearch/Provider] reject provider=" + provider.id()
              + " reason=obviously_irrelevant results=" + hits.size());
        } else {
          failures.add(provider.id() + ": zero results");
          System.out.println("[WebSearch/Provider] empty provider=" + provider.id());
        }
      } catch (InterruptedException interrupted) {
        throw interrupted;
      } catch (Exception failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        failures.add(provider.id() + ": " + message);
        System.out.println("[WebSearch/Provider] failed provider=" + provider.id() + " error="
            + failure.getClass().getSimpleName() + " message=" + message);
      }
      if (!cfg.fallbackEnabled()) break;
    }

    if (!attempted) {
      throw new IllegalStateException(
          "No configured Web Search provider. Configure Brave API key or SearXNG URL in Services & API.");
    }
    throw new IllegalStateException("All Web Search providers failed: " + String.join(" | ", failures));
  }

  private List<SearchProvider> providerOrder(WebSearchConfig cfg) {
    // Prefer general web indexes first. Wikipedia is a stable keyless fallback when
    // browser-oriented engines fail or return obviously unrelated results.
    List<SearchProvider> auto = List.of(brave, searxng, mojeek, bing, duckDuckGo, wikipedia);
    SearchProvider primary = switch (cfg.primaryProvider()) {
      case BRAVE -> brave;
      case SEARXNG -> searxng;
      case BING -> bing;
      case DUCKDUCKGO -> duckDuckGo;
      case AUTO -> null;
    };
    if (primary == null) return auto;
    if (!cfg.fallbackEnabled()) return List.of(primary);
    List<SearchProvider> result = new ArrayList<>();
    result.add(primary);
    for (SearchProvider provider : auto) if (provider != primary) result.add(provider);
    return result;
  }

  /**
   * Cheap sanity gate for broken/geo-personalized HTML/RSS search responses.
   * It deliberately rejects only the obvious case where not one result shares any
   * meaningful query token. Semantic relevance is still decided by the Web Search LLM.
   */
  static List<SearchHit> filterObviouslyIrrelevant(String query, List<SearchHit> hits) {
    return SearchResultQuality.filterRelevant(query, hits, hits == null ? 1 : Math.max(1, hits.size()));
  }

  private static String formatResults(String query, String provider, List<SearchHit> hits, int limit) {
    StringBuilder out = new StringBuilder("WEB_SEARCH_RESULTS for: ").append(query)
        .append("\nPROVIDER: ").append(provider)
        .append("\nEVIDENCE_TYPE: SEARCH")
        .append("\nNOTE: Titles and snippets are real search-engine evidence. The reasoning controller decides whether they are sufficient or whether a URL must be fetched.\n");
    int max = Math.min(Math.max(1, limit), hits.size());
    for (int i = 0; i < max; i++) {
      SearchHit hit = hits.get(i);
      out.append(i + 1).append(". ").append(hit.title()).append('\n')
          .append("URL: ").append(hit.url()).append('\n');
      if (!hit.snippet().isBlank()) out.append("SNIPPET: ").append(hit.snippet()).append('\n');
      out.append('\n');
    }
    return out.toString();
  }

  public String fetch(String url) throws Exception {
    URI uri = URI.create(url);
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https"))
      throw new IllegalArgumentException("Only http/https URLs are allowed");
    int timeout = config.get().timeoutSeconds();
    HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeout))
        .header("User-Agent", BROWSER_UA)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,application/json;q=0.7,*/*;q=0.5")
        .header("Accept-Language", "en-US,en;q=0.9,ru;q=0.8")
        .header("Cache-Control", "no-cache")
        .header("Pragma", "no-cache")
        .header("Upgrade-Insecure-Requests", "1")
        .GET().build();
    HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    if (res.statusCode() == 403 || res.statusCode() == 429)
      throw new FetchBlockedException(res.statusCode(), url);
    if (res.statusCode() < 200 || res.statusCode() >= 300)
      throw new IllegalStateException("WEB_FETCH HTTP " + res.statusCode());

    String contentType = res.headers().firstValue("content-type").orElse("");
    DecodedBody decoded = decodeResponseBody(res.body(), contentType);
    String body = decoded.text();
    System.out.println("[WebSearch/HTTP] provider=fetch status=" + res.statusCode() + " url=" + url
        + " bodyBytes=" + (res.body() == null ? 0 : res.body().length)
        + " bodyChars=" + body.length() + " charset=" + decoded.charset());
    String contentTypeLower = contentType.toLowerCase(Locale.ROOT);
    if (contentTypeLower.contains("html") || body.toLowerCase(Locale.ROOT).contains("<html")) body = readableHtml(body);
    body = body.replaceAll("[\\t ]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    // Keep evidence small enough for a 4k-token local-agent context. Search/fetch cycles
    // otherwise crowd the model out and make it repeat tools instead of producing a report.
    int max = 3500;
    if (body.length() > max) body = body.substring(0, max) + "\n[truncated]";
    return "WEB_FETCH_RESULT\nURL: " + url + "\n\n" + body;
  }

  private record DecodedBody(String text, String charset) {}

  /**
   * Decode fetched pages using the charset declared by HTTP/HTML instead of assuming UTF-8.
   * A number of Russian-language sites still serve Windows-1251. Forcing UTF-8 turns role
   * labels such as "Автор" / "Перевод" into replacement characters, which can make the
   * evidence judge attribute a translator as the author simply because the Latin nickname
   * is the only readable text left.
   */
  private static DecodedBody decodeResponseBody(byte[] bytes, String contentType) {
    if (bytes == null || bytes.length == 0) return new DecodedBody("", StandardCharsets.UTF_8.name());

    Charset bom = bomCharset(bytes);
    if (bom != null) return new DecodedBody(stripBom(new String(bytes, bom)), bom.name());

    Charset declared = charsetFromContentType(contentType);
    if (declared == null) declared = charsetFromHtmlMeta(bytes);
    if (declared != null) {
      String strict = decodeStrict(bytes, declared);
      if (strict != null) return new DecodedBody(stripBom(strict), declared.name());
      System.out.println("[WebSearch/HTTP] declared charset failed strict decode: " + declared.name());
    }

    String utf8 = decodeStrict(bytes, StandardCharsets.UTF_8);
    if (utf8 != null) return new DecodedBody(stripBom(utf8), StandardCharsets.UTF_8.name());

    // Legacy Cyrillic fallback. Windows-1251 maps every byte, so use it only after UTF-8
    // validation fails and no usable declared charset was available.
    Charset cp1251 = Charset.forName("windows-1251");
    return new DecodedBody(stripBom(new String(bytes, cp1251)), cp1251.name());
  }

  private static Charset charsetFromContentType(String contentType) {
    if (contentType == null || contentType.isBlank()) return null;
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
        "(?i)(?:^|;)\\s*charset\\s*=\\s*['\"]?([^;\\s'\"]+)").matcher(contentType);
    if (!matcher.find()) return null;
    return safeCharset(matcher.group(1));
  }

  private static Charset charsetFromHtmlMeta(byte[] bytes) {
    int length = Math.min(bytes.length, 32768);
    // ISO-8859-1 is a byte-preserving view, sufficient for ASCII HTML attributes even
    // when the actual document text is Windows-1251.
    String head = new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
        "(?i)charset\\s*=\\s*['\"]?\\s*([A-Za-z0-9._:-]+)").matcher(head);
    if (!matcher.find()) return null;
    return safeCharset(matcher.group(1));
  }

  private static Charset safeCharset(String name) {
    if (name == null || name.isBlank()) return null;
    try {
      return Charset.forName(name.strip());
    } catch (IllegalCharsetNameException | UnsupportedCharsetException ignored) {
      return null;
    }
  }

  private static Charset bomCharset(byte[] bytes) {
    if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb
        && (bytes[2] & 0xff) == 0xbf) return StandardCharsets.UTF_8;
    if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xfe)
      return StandardCharsets.UTF_16LE;
    if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff)
      return StandardCharsets.UTF_16BE;
    return null;
  }

  private static String decodeStrict(byte[] bytes, Charset charset) {
    try {
      CharsetDecoder decoder = charset.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT);
      return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException ignored) {
      return null;
    }
  }

  private static String stripBom(String value) {
    return value != null && !value.isEmpty() && value.charAt(0) == '\ufeff' ? value.substring(1) : value;
  }

  private static String readableHtml(String html) {
    if (html == null || html.isBlank()) return "";
    String reduced = html
        .replaceAll("(?is)<script\\b.*?</script>", " ")
        .replaceAll("(?is)<style\\b.*?</style>", " ")
        .replaceAll("(?is)<svg\\b.*?</svg>", " ")
        .replaceAll("(?is)<noscript\\b.*?</noscript>", " ")
        .replaceAll("(?is)<nav\\b.*?</nav>", " ")
        .replaceAll("(?is)<footer\\b.*?</footer>", " ")
        .replaceAll("(?is)<form\\b.*?</form>", " ");

    // Prefer the article/main payload when the site exposes semantic HTML. This keeps
    // navigation/cookie chrome from consuming the 18k character evidence budget.
    java.util.regex.Matcher article = java.util.regex.Pattern.compile(
        "(?is)<article\\b[^>]*>(.*?)</article>").matcher(reduced);
    if (article.find() && article.group(1).length() > 300) reduced = article.group(1);
    else {
      java.util.regex.Matcher main = java.util.regex.Pattern.compile(
          "(?is)<main\\b[^>]*>(.*?)</main>").matcher(reduced);
      if (main.find() && main.group(1).length() > 300) reduced = main.group(1);
    }
    return cleanHtml(reduced);
  }

  private static String cleanHtml(String s) {
    return unescape((s == null ? "" : s)
        .replaceAll("(?is)<br\\s*/?>", "\n")
        .replaceAll("(?is)</(?:p|div|li|h[1-6]|tr|section)>", "\n")
        .replaceAll("(?is)<[^>]+>", " ")
        .replaceAll("[\\t ]+", " ")
        .replaceAll(" ?\n ?", "\n")
        .replaceAll("\n{3,}", "\n\n").trim());
  }

  private static String unescape(String s) {
    return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
  }
}
