package com.apprentice.studio;
import com.apprentice.auth.*;
import com.apprentice.tenant.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
@Component
public class StudioEvents {
  private final StudioService studio;
  private final AppAccountRepository accounts;
  private final Map<SseEmitter,Subscription> subscribers=new ConcurrentHashMap<>();
  private static class Subscription { final Tenant tenant;final UUID session,account;String last="";Subscription(Tenant tenant,UUID session,UUID account){this.tenant=tenant;this.session=session;this.account=account;} }
  public StudioEvents(StudioService studio,AppAccountRepository accounts){this.studio=studio;this.accounts=accounts;}
  SseEmitter subscribe(UUID id,AppAccount account){SseEmitter emitter=new SseEmitter(120000L);subscribers.put(emitter,new Subscription(TenantContext.get(),id,account.getId()));emitter.onCompletion(()->subscribers.remove(emitter));emitter.onTimeout(()->{subscribers.remove(emitter);emitter.complete();});emitter.onError(e->subscribers.remove(emitter));return emitter;}
  @Scheduled(fixedDelay=1500) public void broadcast(){for(var entry:subscribers.entrySet()){SseEmitter emitter=entry.getKey();Subscription sub=entry.getValue();TenantContext.set(sub.tenant);try{AppAccount account=accounts.findById(sub.account).filter(a->a.isActive()&&a.getWorkspaceId().equals(sub.tenant.getId())).orElseThrow();String snapshot=studio.store.locked(sub.session,s->{studio.store.check(s,account,false);return studio.view(s,account).toString();});if(!snapshot.equals(sub.last)){emitter.send(SseEmitter.event().name("snapshot").id(Integer.toUnsignedString(snapshot.hashCode())).data(snapshot));sub.last=snapshot;}else emitter.send(SseEmitter.event().comment("keep-alive"));}catch(Exception e){subscribers.remove(emitter);emitter.complete();}finally{TenantContext.clear();}}}
}
