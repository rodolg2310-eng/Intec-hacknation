package com.apprentice.studio;
import com.apprentice.auth.*;
import com.apprentice.common.ApiException;
import com.apprentice.tenant.TenantContext;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.springframework.stereotype.Service;
@Service
public class StudioStore {
  final StudioSessionRepository sessions;
  final ObjectMapper json=new ObjectMapper();
  private final Map<UUID,ReentrantLock> locks=new ConcurrentHashMap<>();
  public StudioStore(StudioSessionRepository sessions){this.sessions=sessions;}
  StudioSession require(UUID id){return sessions.findByIdAndTenantId(id,TenantContext.getId()).orElseThrow(()->ApiException.notFound("Activity"));}
  void check(StudioSession s,AppAccount a,boolean write){
    if(a.getRole()==Role.MASTER || a.getRole()==Role.SENIOR&&!a.getId().equals(s.authorAccountId) || a.getRole()==Role.LEARNER&&(write||!s.phase.equals("confirmed")))throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"This activity is not available to your role.");
  }
  ObjectNode data(StudioSession s){return parse(s.payload);}
  ObjectNode parse(String payload){try{return (ObjectNode)json.readTree(payload);}catch(Exception e){throw new IllegalStateException("Activity data could not be read.",e);}}
  void save(StudioSession s,ObjectNode d){s.payload=d.toString();s.updatedAt=Instant.now();sessions.save(s);}
  <T>T locked(UUID id,Function<StudioSession,T> fn){ReentrantLock l=locks.computeIfAbsent(id,x->new ReentrantLock());l.lock();try{return fn.apply(require(id));}finally{l.unlock();}}
  static String time(long seconds){return String.format("%02d:%02d",Math.max(0,seconds)/60,Math.max(0,seconds)%60);}
  static JsonNode event(ObjectNode d,String id){for(JsonNode e:d.path("events"))if(e.path("id").asText().equals(id))return e;return null;}
  ObjectNode message(ObjectNode d,String role,String text,String stage,String type,String event,long seconds,String language){
    ObjectNode m=d.withArray("messages").addObject();m.put("id",UUID.randomUUID().toString());m.put("role",role);m.put("text",text);m.put("ts",Instant.now().toString());m.put("stage",stage);m.put("questionType",type);m.put("eventId",event==null?"":event);m.put("elapsedSeconds",seconds);m.put("sourceLanguage",language);m.put("translated",!language.equals("en"));return m;
  }
  String relevant(ObjectNode d,String query){
    Set<String> terms=new HashSet<>(Arrays.asList(query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")));terms.removeIf(t->t.length()<4);List<JsonNode> candidates=new ArrayList<>();d.path("events").forEach(candidates::add);d.path("messages").forEach(candidates::add);
    candidates.sort(Comparator.comparingLong((JsonNode n)->terms.stream().filter(t->n.toString().toLowerCase(Locale.ROOT).contains(t)).count()).reversed());ArrayNode found=json.createArrayNode();for(JsonNode n:candidates){if(found.size()>=12)break;if(terms.stream().anyMatch(t->n.toString().toLowerCase(Locale.ROOT).contains(t)))found.add(n);}return "\nRelevant earlier evidence:"+found;
  }
  String context(StudioSession s,ObjectNode d){
    ObjectNode context=json.createObjectNode();context.put("title",s.title);context.put("expert",s.expert);context.set("context",d.path("context"));context.set("memory",d.path("memory"));context.set("map",d.path("map"));ArrayNode doubts=context.putArray("doubts");for(int i=Math.max(0,d.path("doubts").size()-25);i<d.path("doubts").size();i++)doubts.add(d.path("doubts").get(i));
    ArrayNode e=context.putArray("recentEvents"),m=context.putArray("recentMessages");JsonNode events=d.path("events"),messages=d.path("messages");
    for(int i=Math.max(0,events.size()-25);i<events.size();i++)e.add(events.get(i));
    for(int i=Math.max(0,messages.size()-25);i<messages.size();i++)m.add(messages.get(i));
    return context.toString();
  }
}
