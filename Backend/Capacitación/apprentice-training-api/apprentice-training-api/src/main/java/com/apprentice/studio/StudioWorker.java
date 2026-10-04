package com.apprentice.studio;
import com.apprentice.tenant.*;
import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
@Component @EnableScheduling
public class StudioWorker {
  private final TenantRepository tenants;
  private final StudioService studio;
  private final ExecutorService vision=Executors.newFixedThreadPool(2),interview=Executors.newFixedThreadPool(2),finalize=Executors.newFixedThreadPool(2);
  private final Set<String> active=ConcurrentHashMap.newKeySet();
  public StudioWorker(TenantRepository tenants,StudioService studio){this.tenants=tenants;this.studio=studio;}
  @EventListener(ApplicationReadyEvent.class) public void recover(){for(Tenant t:tenants.findAll()){TenantContext.set(t);try{for(StudioSession s:studio.store.sessions.findByTenantIdOrderByUpdatedAtDesc(t.getId())){var d=studio.store.data(s);if(s.authorAccountId==null&&s.phase.equals("confirmed")){s.phase="review";d.put("mapConfirmed",false);studio.store.save(s,d);}for(FrameJob f:studio.frames.findBySessionIdOrderBySequence(s.id))if(f.status.equals("processing")){f.status="queued";studio.frames.save(f);}}}finally{TenantContext.clear();}}}
  @Scheduled(fixedDelay=1000) public void scan(){for(Tenant t:tenants.findAll()){TenantContext.set(t);try{for(StudioSession s:studio.store.sessions.findByTenantIdOrderByUpdatedAtDesc(t.getId())){if(Set.of("capture","finishing").contains(s.phase)){submit(vision,"vision",t,s.id,()->studio.processFrames(s.id));if(s.phase.equals("capture"))submit(interview,"interview",t,s.id,()->studio.liveTick(s.id));else submit(finalize,"final",t,s.id,()->studio.finalizeActivity(s.id));}else if(s.phase.equals("review")){if(!studio.store.data(s).has("processingError")&&studio.store.data(s).path("openRecordings").isEmpty()&&studio.media.pending(s.id))submit(finalize,"audio-final",t,s.id,()->studio.media.consolidate(s.id,studio.store.data(studio.store.require(s.id)).path("removedRanges")));}else if(s.phase.equals("debrief")){var d=studio.store.data(s);if(!d.has("processingError")&&!d.has("questionError")&&!d.hasNonNull("pendingQuestionId"))submit(interview,"interview",t,s.id,()->{if(d.path("debriefAnswers").asInt()>=3)studio.buildMap(s.id);else studio.generateQuestion(s.id,"debrief",false);});}}}catch(RuntimeException ignored){/* A workspace failure must not stop other queues. */}finally{TenantContext.clear();}}}
  private void submit(ExecutorService pool,String kind,Tenant tenant,UUID id,Runnable work){String key=kind+id;if(!active.add(key))return;pool.submit(()->{TenantContext.set(tenant);try{work.run();}catch(Exception e){try{studio.store.locked(id,s->{var d=studio.store.data(s);d.put("processingError","Processing was interrupted. Your evidence is saved; use Retry processing.");studio.store.save(s,d);return null;});}catch(Exception ignored){}}finally{TenantContext.clear();active.remove(key);}});}
  @PreDestroy public void close(){vision.shutdownNow();interview.shutdownNow();finalize.shutdownNow();}
}
