package com.neuralsoul.memory;

import com.neuralsoul.service.http.Json;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class QdrantMemoryService implements MemoryService {
  private final String collection;
  private final String bgeCollection;
  private final LocalMemoryService local;
  private final HashingTextVectorizer vectorizer = new HashingTextVectorizer();
  private final HttpClient http = HttpClient.newHttpClient();
  private final BgeM3EmbeddingClient bge;
  private final ExecutorService recallPool = Executors.newFixedThreadPool(2, r -> { Thread t=new Thread(r,"memory-recall"); t.setDaemon(true); return t; });
  private final URI base;
  private volatile boolean semanticEnabled = true;
  /** Last semantic (BGE) save failure, model-readable; consumed by the next context build. */
  private volatile String lastSemanticFailure;

  public QdrantMemoryService(LocalMemoryService local, String baseUrl) {
    this(local, baseUrl, "neuralsoul_memory", "neuralsoul_memory_bge_m3");
  }

  public QdrantMemoryService(LocalMemoryService local, String baseUrl, String collection, String bgeCollection) {
    this.local=local;
    this.base=URI.create(baseUrl.endsWith("/")?baseUrl:baseUrl+"/");
    this.collection=Objects.requireNonNull(collection, "collection");
    this.bgeCollection=Objects.requireNonNull(bgeCollection, "bgeCollection");
    this.bge=new BgeM3EmbeddingClient("http://127.0.0.1:8081");
  }

  public void setSemanticEnabled(boolean enabled) { this.semanticEnabled = enabled; }
  public boolean isSemanticEnabled() { return semanticEnabled; }

  public List<MemoryRecord> all(){return local.all();}
  public void add(String text){
    System.out.println("[Memory] ADD requested text=" + abbrev(text, 220));
    local.add(text);
    upsertNewest();
  }
  public void delete(String id){
    // Qdrant point deletion is a POST action with a PointIdsList selector.
    request("POST","collections/"+collection+"/points/delete?wait=true", Json.write(Map.of("points",List.of(id))));
    // Semantic index may hold the record split into "#pN" chunk points after a
    // too-large embedding retry; delete those alongside the base id.
    List<Object> semanticIds=new ArrayList<>();
    semanticIds.add(id);
    for(int i=1;i<=8;i++) semanticIds.add(id+"#p"+i);
    try { request("POST","collections/"+bgeCollection+"/points/delete?wait=true", Json.write(Map.of("points",semanticIds))); } catch (Exception e) { System.out.println("[Memory/BGE] delete warning: "+e); }
    local.delete(id);
  }
  public void clear(){
    System.out.println("[Memory] CLEAR requested collection=" + collection
        + " bgeCollection=" + bgeCollection + " localRecords=" + local.all().size());
    local.clear();
    request("DELETE","collections/"+collection,null);
    try{request("DELETE","collections/"+bgeCollection,null);}catch(Exception e){
      System.out.println("[Memory/BGE] clear warning: " + e);
    }
    try{ensureCollection();ensureBgeCollection();}catch(Exception e){
      System.out.println("[Memory] recreate-after-clear warning: " + e);
    }
    System.out.println("[Memory] CLEAR completed collection=" + collection
        + " localRecords=" + local.all().size());
  }
  public void save(){local.save();}
  public void importJson(Path p)throws Exception{local.importJson(p);synchronize();}
  public void exportJson(Path p)throws Exception{local.exportJson(p);}
  public void importXlsx(Path p)throws Exception{local.importXlsx(p);synchronize();}
  public void exportXlsx(Path p)throws Exception{local.exportXlsx(p);}
  public CompletableFuture<String> summarize(Instant f,Instant t){return local.summarize(f,t);}
  public void flushConversation(List<String> m){
    System.out.println("[Memory] FLUSH conversation messages=" + (m == null ? 0 : m.size())
        + " localBefore=" + local.all().size());
    local.flushConversation(m);
    System.out.println("[Memory] FLUSH localAfter=" + local.all().size());
    upsertNewest();
  }

  public synchronized void synchronize() throws Exception {
    ensureCollection();
    if (semanticEnabled) ensureBgeCollection();
    for(MemoryRecord r:local.all()) upsert(r);
  }

  public List<MemoryRecord> search(String query,int limit,double minScore) {
    if(query==null||query.isBlank()||limit<=0)return List.of();
    System.out.println("[Memory] ===== FAST RECALL =====");
    System.out.println("[Memory] Query: "+query+" · limit="+limit+" · minScore="+minScore);
    List<ScoredMemory> hits=searchCollection(collection, floats(vectorizer.vectorize(query)), limit, "FAST");
    List<MemoryRecord> result=hits.stream()
        .filter(h -> h.score>=minScore)
        .limit(limit)
        .map(ScoredMemory::record)
        .toList();
    System.out.println("[Memory] fast accepted="+result.size()+"/"+hits.size());
    System.out.println("[Memory] ===== /FAST RECALL =====");
    return result;
  }

  @Override
  public List<MemoryRecord> searchDeep(String query,int limit,double minScore) {
    if(query==null||query.isBlank()||limit<=0)return List.of();
    System.out.println("[Memory] ===== DEEP PARALLEL RECALL =====");
    System.out.println("[Memory] Query: "+query+" · limit="+limit+" · minScore="+minScore);
    long started=System.nanoTime();

    CompletableFuture<List<ScoredMemory>> fast=CompletableFuture.supplyAsync(
        () -> searchCollection(collection, floats(vectorizer.vectorize(query)), limit, "FAST"), recallPool);
    CompletableFuture<List<ScoredMemory>> semantic = semanticEnabled
        ? CompletableFuture.supplyAsync(() -> {
      try { return searchCollection(bgeCollection, floats(bge.embed(query)), limit, "BGE"); }
      catch(Exception e){ System.out.println("[Memory/BGE] recall failed: "+e); return List.of(); }
        }, recallPool)
        : CompletableFuture.completedFuture(List.of());

    List<ScoredMemory> fastHits=fast.join();
    List<ScoredMemory> bgeHits=semantic.join();
    Map<String,ScoredMemory> merged=new LinkedHashMap<>();
    // BGE is semantic evidence; fast hash keeps the existing threshold behavior.
    for(ScoredMemory hit:bgeHits) merged.merge(hit.record.id(),hit,(a,b)->a.score>=b.score?a:b);
    for(ScoredMemory hit:fastHits) if(hit.score>=minScore) merged.merge(hit.record.id(),hit,(a,b)->a.score>=b.score?a:b);
    List<ScoredMemory> ranked=new ArrayList<>(merged.values());
    ranked.sort(Comparator.comparingDouble(ScoredMemory::score).reversed());
    List<MemoryRecord> result=ranked.stream().limit(limit).map(ScoredMemory::record).toList();
    long ms=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
    System.out.println("[Memory] merged="+result.size()+" fast="+fastHits.size()+" bge="+bgeHits.size()+" in "+ms+" ms");
    System.out.println("[Memory] ===== /DEEP PARALLEL RECALL =====");
    return result;
  }

  private List<ScoredMemory> searchCollection(String collection,List<Double> vector,int limit,String source){
    try{
      Map<String,Object> payload=new LinkedHashMap<>();
      payload.put("vector",vector); payload.put("limit",limit); payload.put("with_payload",true);
      String raw=request("POST","collections/"+collection+"/points/search",Json.write(payload));
      Object result=Json.object(Json.read(raw)).get("result");
      if(!(result instanceof List<?> list))return List.of();
      List<ScoredMemory> hits=new ArrayList<>(); int n=0;
      for(Object o:list){
        Map<String,Object> hit=Json.object(o), pl=Json.object(hit.get("payload"));
        double score=hit.get("score") instanceof Number number?number.doubleValue():0.0;
        try{
          MemoryRecord r=new MemoryRecord(Objects.toString(pl.get("id"),""),Instant.parse(Objects.toString(pl.get("createdAt"))),Objects.toString(pl.get("type"),"memory"),Objects.toString(pl.get("text"),""));
          hits.add(new ScoredMemory(r,score,source));
          System.out.println("[Memory/"+source+"] #"+(++n)+" score="+score+" text="+abbrev(r.text(),180));
        }catch(Exception e){System.out.println("[Memory/"+source+"] bad payload: "+e);}
      }
      return hits;
    }catch(Exception e){System.out.println("[Memory/"+source+"] search failed: "+e);return List.of();}
  }

  private record ScoredMemory(MemoryRecord record,double score,String source) {}

  private String abbrev(String text,int max){
    if(text==null)return "";
    String one=text.replace('\n',' ').replace('\r',' ');
    return one.length()<=max?one:one.substring(0,max)+"…";
  }

  private void upsertNewest(){
    try{
      ensureCollection();
      List<MemoryRecord> a=local.all();
      if(!a.isEmpty()) {
        MemoryRecord newest = a.get(0);
        System.out.println("[Memory] upsertNewest id=" + newest.id()
            + " type=" + newest.type() + " text=" + abbrev(newest.text(), 260));
        upsert(newest);
      } else {
        System.out.println("[Memory] upsertNewest skipped: local memory is empty");
      }
    }
    catch(Exception e){System.out.println("[Memory] Qdrant upsert failed: "+e);}
  }
  private void ensureBgeCollection() throws Exception {
    HttpResponse<String> get=send("GET","collections/"+bgeCollection,null);
    if(get.statusCode()==200)return;
    Map<String,Object> vectors=Map.of("size",BgeM3EmbeddingClient.DIMENSIONS,"distance","Cosine");
    HttpResponse<String> put=send("PUT","collections/"+bgeCollection,Json.write(Map.of("vectors",vectors)));
    if(put.statusCode()<200||put.statusCode()>=300) throw new IllegalStateException("BGE Qdrant collection: HTTP "+put.statusCode()+" "+put.body());
  }

  private void ensureCollection() throws Exception {
    HttpResponse<String> get=send("GET","collections/"+collection,null);
    if(get.statusCode()==200)return;
    Map<String,Object> vectors=Map.of("size",HashingTextVectorizer.DIMENSIONS,"distance","Cosine");
    HttpResponse<String> put=send("PUT","collections/"+collection,Json.write(Map.of("vectors",vectors)));
    if(put.statusCode()<200||put.statusCode()>=300)
      throw new IllegalStateException("Qdrant collection: HTTP "+put.statusCode()+" "+put.body());
  }
  private void upsert(MemoryRecord r)throws Exception{
    Map<String,Object> point=new LinkedHashMap<>();
    point.put("id",r.id());
    point.put("vector",floats(vectorizer.vectorize(r.text())));
    point.put("payload",Map.of("id",r.id(),"createdAt",r.createdAt().toString(),"type",r.type(),"text",r.text()));
    String body=Json.write(Map.of("points",List.of(point)));
    request("PUT","collections/"+collection+"/points?wait=true",body);
    if (!semanticEnabled) return;
    try {
      ensureBgeCollection();
      upsertSemantic(r.id(), r.text(), point.get("payload"));
      lastSemanticFailure = null;
    } catch(Exception e) {
      System.out.println("[Memory/BGE] upsert failed, fast index kept: "+e);
      String reason = describeSemanticFailure(e);
      // BGE-M3 rejects inputs above its embedding batch size (512 tokens by default).
      // Split the record into smaller parts and index each part, so long reports are
      // not silently lost from semantic recall.
      if (isTooLargeError(e) && r.text().length() > 600) {
        try {
          int n = 0;
          for (String part : splitForEmbedding(r.text())) {
            n++;
            upsertSemantic(r.id() + "#p" + n, part, point.get("payload"));
          }
          lastSemanticFailure = null;
          System.out.println("[Memory/BGE] upsert recovered: text split into " + n + " parts");
        } catch(Exception e2) {
          System.out.println("[Memory/BGE] chunked upsert failed: "+e2);
          lastSemanticFailure = reason;
        }
      } else {
        lastSemanticFailure = reason;
      }
    }
  }

  private void upsertSemantic(String id,String text,Object payload)throws Exception{
    ensureBgeCollection();
    Map<String,Object> semantic=new LinkedHashMap<>();
    semantic.put("id",id);
    semantic.put("vector",floats(bge.embed(text)));
    semantic.put("payload",payload);
    request("PUT","collections/"+bgeCollection+"/points?wait=true",Json.write(Map.of("points",List.of(semantic))));
  }

  /**
   * True when the embedding server rejected the input because it exceeds the
   * physical batch size ("input (N tokens) is too large to process...").
   */
  private static boolean isTooLargeError(Exception e){
    return String.valueOf(e).contains("is too large to process");
  }

  /** Model-readable reason line; used to tell the Main LLM why a memory save failed. */
  private static String describeSemanticFailure(Exception e){
    String m=String.valueOf(e);
    java.util.regex.Matcher t=java.util.regex.Pattern
        .compile("input \\((\\d+) tokens\\) is too large to process[^\\\\\"]*(?:batch size: (\\d+))?")
        .matcher(m);
    if (t.find()) {
      String batch = t.group(2) == null ? "" : " (лимит батча: " + t.group(2) + " токенов)";
      return "запись " + t.group(1) + " токенов слишком длинная для эмбеддинга" + batch;
    }
    return m.length() > 200 ? m.substring(0, 200) + "…" : m;
  }

  /** Splits text into parts of ~1200 chars at line/space boundaries for BGE embedding. */
  static List<String> splitForEmbedding(String text){
    List<String> parts=new ArrayList<>();
    int max=1200;
    int from=0;
    while(from<text.length()){
      int end=Math.min(from+max,text.length());
      if(end<text.length()){
        int cut=Math.max(text.lastIndexOf('\n',end),text.lastIndexOf(' ',end));
        if(cut>from+200) end=cut+1;
      }
      String part=text.substring(from,end).strip();
      if(!part.isBlank()) parts.add(part);
      from=end;
    }
    if(parts.isEmpty()) parts.add(text.strip());
    return parts;
  }

  @Override
  public String consumeLastSemanticFailure(){
    String f=lastSemanticFailure;
    lastSemanticFailure=null;
    return f;
  }
  private List<Double> floats(float[] v){List<Double>x=new ArrayList<>(v.length);for(float f:v)x.add((double)f);return x;}
  private String request(String method,String path,String body){
    try{
      HttpResponse<String> r=send(method,path,body);
      if(r.statusCode()<200||r.statusCode()>=300)
        throw new IllegalStateException("Qdrant HTTP "+r.statusCode()+": "+r.body());
      return r.body();
    }catch(Exception e){throw new IllegalStateException(e);}
  }
  private HttpResponse<String> send(String method,String path,String body)throws Exception{
    HttpRequest.Builder b=HttpRequest.newBuilder(base.resolve(path)).header("Content-Type","application/json");
    b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8));
    return http.send(b.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }
}
