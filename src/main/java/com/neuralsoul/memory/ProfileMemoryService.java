package com.neuralsoul.memory;

import com.neuralsoul.model.Profile;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Routes every memory operation to the memory store of the active chat profile. */
public final class ProfileMemoryService implements MemoryService {
  private final EnumMap<Profile, MemoryService> services = new EnumMap<>(Profile.class);
  private final Supplier<Profile> activeProfile;
  private final ThreadLocal<Profile> threadProfile = new ThreadLocal<>();

  public ProfileMemoryService(Map<Profile, MemoryService> services, Supplier<Profile> activeProfile) {
    this.services.putAll(Objects.requireNonNull(services));
    for(Profile p:Profile.values()) if(!this.services.containsKey(p))
      throw new IllegalArgumentException("Missing memory service for " + p);
    this.activeProfile=Objects.requireNonNull(activeProfile);
  }
  public MemoryService service(Profile profile){return services.get(Objects.requireNonNull(profile));}
  public Profile active(){Profile p=threadProfile.get();return p!=null?p:activeProfile.get();}
  public void enter(Profile profile){threadProfile.set(Objects.requireNonNull(profile));}
  public void leave(){threadProfile.remove();}
  private MemoryService current(){return service(active());}
  public List<MemoryRecord> all(){return current().all();}
  public void add(String text){current().add(text);}
  public void delete(String id){current().delete(id);}
  public void clear(){System.out.println("[Memory/Profile] clear profile="+active().label());current().clear();}
  public void save(){services.values().forEach(MemoryService::save);}
  public void importJson(Path p)throws Exception{current().importJson(p);}
  public void exportJson(Path p)throws Exception{current().exportJson(p);}
  public void importXlsx(Path p)throws Exception{current().importXlsx(p);}
  public void exportXlsx(Path p)throws Exception{current().exportXlsx(p);}
  public CompletableFuture<String> summarize(Instant f,Instant t){return current().summarize(f,t);}
  public void flushConversation(List<String> m){System.out.println("[Memory/Profile] flush profile="+active().label()+" messages="+(m==null?0:m.size()));current().flushConversation(m);}
  public List<MemoryRecord> search(String q,int l,double s){System.out.println("[Memory/Profile] search profile="+active().label()+" limit="+l+" minScore="+s);return current().search(q,l,s);}
  public List<MemoryRecord> searchDeep(String q,int l,double s){System.out.println("[Memory/Profile] deep-search profile="+active().label()+" limit="+l+" minScore="+s);return current().searchDeep(q,l,s);}
  public void synchronize() throws Exception {for(MemoryService m:services.values())m.synchronize();}
}
