package com.neuralsoul.memory;

import com.neuralsoul.service.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.zip.*;

public final class LocalMemoryService implements MemoryService {
  private final Path file;
  private final List<MemoryRecord> records = new CopyOnWriteArrayList<>();
  private final Supplier<LlmService> llm;
  private final MemorySettings settings;

  public LocalMemoryService(Supplier<LlmService> llm, MemorySettings settings) {
    this(llm, settings, Path.of(System.getProperty("user.dir"), "data", "memory.jsonl"));
  }

  public LocalMemoryService(Supplier<LlmService> llm, MemorySettings settings, Path file) {
    this.llm=llm;
    this.settings=settings;
    this.file=Objects.requireNonNull(file, "file");
    load();
  }

  public List<MemoryRecord> all(){ return records.stream().sorted(Comparator.comparing(MemoryRecord::createdAt).reversed()).toList(); }
  public void add(String text){ if(text!=null&&!text.isBlank()){records.add(MemoryRecord.manual(text.trim()));save();}}
  public void delete(String id){records.removeIf(r->r.id().equals(id));save();}
  public void clear(){records.clear();save();}

  private void load(){
    if(!Files.exists(file)) return;
    try{
      for(String line:Files.readAllLines(file,StandardCharsets.UTF_8)){
        String[] p=line.split("\\t",4);
        if(p.length==4) records.add(new MemoryRecord(p[0],Instant.parse(p[1]),p[2],unescape(p[3])));
      }
    }catch(Exception e){System.out.println("[Memory] load failed: "+e);}
  }
  public synchronized void save(){
    try{
      Files.createDirectories(file.getParent());
      List<String> lines=new ArrayList<>();
      for(MemoryRecord r:records) lines.add(r.id()+"\t"+r.createdAt()+"\t"+r.type()+"\t"+escape(r.text()));
      Files.write(file,lines,StandardCharsets.UTF_8);
    }catch(Exception e){System.out.println("[Memory] save failed: "+e);}
  }
  private String escape(String s){return s.replace("\\","\\\\").replace("\t","\\t").replace("\n","\\n");}
  private String unescape(String s){
    StringBuilder o=new StringBuilder(); boolean esc=false;
    for(char c:s.toCharArray()){if(esc){o.append(c=='n'?'\n':c=='t'?'\t':c);esc=false;}else if(c=='\\')esc=true;else o.append(c);}
    if(esc)o.append('\\'); return o.toString();
  }

  public void exportJson(Path path)throws Exception{
    StringBuilder s=new StringBuilder("[\n");
    for(int i=0;i<records.size();i++){MemoryRecord r=records.get(i);
      s.append("  {\"id\":\"").append(j(r.id())).append("\",\"createdAt\":\"").append(r.createdAt())
       .append("\",\"type\":\"").append(j(r.type())).append("\",\"text\":\"").append(j(r.text())).append("\"}");
      if(i+1<records.size())s.append(',');s.append('\n');
    }s.append("]\n");Files.writeString(path,s,StandardCharsets.UTF_8);
  }
  public void importJson(Path path)throws Exception{
    String s=Files.readString(path,StandardCharsets.UTF_8);
    java.util.regex.Matcher m=java.util.regex.Pattern.compile(
      "\\{\\s*\"id\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"createdAt\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"type\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"text\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"\\s*\\}",
      java.util.regex.Pattern.DOTALL).matcher(s);
    while(m.find())records.add(new MemoryRecord(uj(m.group(1)),Instant.parse(m.group(2)),uj(m.group(3)),uj(m.group(4))));
    save();
  }
  private String j(String s){return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}
  private String uj(String s){return s.replace("\\n","\n").replace("\\r","\r").replace("\\\"","\"").replace("\\\\","\\");}

  public void exportXlsx(Path path) throws Exception {
    try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(path))) {
      put(z, "[Content_Types].xml",
          "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
              + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
              + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
              + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
              + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
              + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
              + "</Types>");
      put(z, "_rels/.rels",
          "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
              + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
              + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
              + "</Relationships>");
      put(z, "xl/workbook.xml",
          "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
              + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
              + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
              + "<sheets><sheet name=\"Memory\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
      put(z, "xl/_rels/workbook.xml.rels",
          "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
              + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
              + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
              + "</Relationships>");

      StringBuilder x = new StringBuilder(
          "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
              + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
      row(x, 1, List.of("id", "createdAt", "type", "text"));
      int n = 2;
      for (MemoryRecord r : all())
        row(x, n++, List.of(r.id(), r.createdAt().toString(), r.type(), r.text()));
      x.append("</sheetData></worksheet>");
      put(z, "xl/worksheets/sheet1.xml", x.toString());
    }
  }

  public void importXlsx(Path path)throws Exception{
    try(ZipFile z=new ZipFile(path.toFile())){
      ZipEntry e=z.getEntry("xl/worksheets/sheet1.xml"); if(e==null)throw new IOException("sheet1.xml not found");
      String x=new String(z.getInputStream(e).readAllBytes(),StandardCharsets.UTF_8);
      java.util.regex.Matcher rows=java.util.regex.Pattern.compile("<row[^>]*>(.*?)</row>",java.util.regex.Pattern.DOTALL).matcher(x);
      boolean header=true;
      while(rows.find()){if(header){header=false;continue;}List<String> v=new ArrayList<>();
        java.util.regex.Matcher cells=java.util.regex.Pattern.compile("<t>(.*?)</t>",java.util.regex.Pattern.DOTALL).matcher(rows.group(1));
        while(cells.find())v.add(xmlU(cells.group(1)));
        if(v.size()>=4)records.add(new MemoryRecord(v.get(0),Instant.parse(v.get(1)),v.get(2),v.get(3)));
      }
    }save();
  }
  private void row(StringBuilder x,int n,List<String> v){x.append("<row r=\"").append(n).append("\">");for(String s:v)x.append("<c t=\"inlineStr\"><is><t>").append(xml(s)).append("</t></is></c>");x.append("</row>");}
  private String xml(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
  private String xmlU(String s){return s.replace("&quot;","\"").replace("&gt;",">").replace("&lt;","<").replace("&amp;","&");}
  private void put(ZipOutputStream z,String name,String s)throws Exception{z.putNextEntry(new ZipEntry(name));z.write(s.getBytes(StandardCharsets.UTF_8));z.closeEntry();}

  public CompletableFuture<String> summarize(Instant from,Instant to){
    return CompletableFuture.supplyAsync(()->{
      try{
        List<MemoryRecord> selected=records.stream().filter(r->(from==null||!r.createdAt().isBefore(from))&&(to==null||!r.createdAt().isAfter(to))).toList();
        if(selected.isEmpty())throw new IllegalStateException("За выбранный период воспоминаний нет");
        StringBuilder text=new StringBuilder("Суммаризируй следующие воспоминания кратко, сохрани факты и решения:\\n");
        for(MemoryRecord r:selected)text.append("- ").append(r.text()).append('\n');
        LlmService service=llm.get(); if(!service.isRunning())service.start();
        return service.generate(List.of(new ConversationTurn("user",text.toString())),"");
      }catch(Exception e){throw new CompletionException(e);}
    });
  }
  public void flushConversation(List<String> messages){
    if(messages==null||messages.isEmpty())return;
    String text=String.join("\n",messages);
    records.add(new MemoryRecord(UUID.randomUUID().toString(),Instant.now(),"conversation",text));
    save();
  }
}
