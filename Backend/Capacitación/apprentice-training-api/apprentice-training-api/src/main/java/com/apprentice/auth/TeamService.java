package com.apprentice.auth;
import com.apprentice.common.ApiException;
import com.apprentice.tenant.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class TeamService {
  private final AuthService auth;
  private final AppAccountRepository accounts;
  private final InvitationRepository invitations;
  public TeamService(AuthService auth, AppAccountRepository accounts, InvitationRepository invitations) {
    this.auth=auth;this.accounts=accounts;this.invitations=invitations;
  }
  public List<Map<String,Object>> members() { auth.requireCurrent(Role.MASTER); return accounts.findByWorkspaceId(TenantContext.getId()).stream().map(this::member).toList(); }
  private Map<String,Object> member(AppAccount a) { return Map.of("id",a.id,"name",a.name,"email",a.email,"role",a.getRole(),"active",a.active); }
  public List<Map<String,Object>> invites() {
    auth.requireCurrent(Role.MASTER);
    return invitations.findByWorkspaceIdOrderByExpiresAtDesc(TenantContext.getId()).stream().map(i -> Map.<String,Object>of("id",i.id,"email",i.email,"role",i.role,"expiresAt",i.expiresAt,"status",i.revoked?"revoked":i.consumedAt!=null?"accepted":i.expiresAt.isBefore(Instant.now())?"expired":"pending")).toList();
  }
  @Transactional public Map<String,Object> invite(String email,Role role) {
    auth.requireCurrent(Role.MASTER); checkRole(role);
    email=email==null?"":email.trim().toLowerCase(Locale.ROOT);
    if(!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")||email.length()>180)throw ApiException.badRequest("Enter a valid email address.");
    if(accounts.findByEmail(email).isPresent())throw ApiException.conflict("This email already has an account. Update its role in its workspace.");
    for(Invitation old:invitations.findByWorkspaceIdOrderByExpiresAtDesc(TenantContext.getId()))if(old.email.equals(email)&&old.consumedAt==null){old.revoked=true;invitations.save(old);}
    String token=auth.token();Invitation i=new Invitation();i.tokenHash=TenantFilter.sha256(token);i.email=email;i.role=role;i.workspaceId=TenantContext.getId();i.expiresAt=Instant.now().plus(Duration.ofDays(7));invitations.save(i);
    return Map.of("id",i.id,"email",email,"role",role,"expiresAt",i.expiresAt,"path","/signin?invite="+token);
  }
  @Transactional public void revoke(UUID id) {
    auth.requireCurrent(Role.MASTER);Invitation i=invitations.findById(id).filter(x->x.workspaceId.equals(TenantContext.getId())).orElseThrow(()->ApiException.notFound("Invitation"));i.revoked=true;invitations.save(i);
  }
  @Transactional public Map<String,Object> update(UUID id,Role role,boolean active) {
    auth.requireCurrent(Role.MASTER);checkRole(role);
    AppAccount a=accounts.findById(id).filter(x->x.workspaceId.equals(TenantContext.getId())).orElseThrow(()->ApiException.notFound("Member"));
    if(a.getRole()==Role.MASTER)throw ApiException.conflict("The workspace must keep its Master account.");
    a.role=role;a.active=active;return member(accounts.save(a));
  }
  private void checkRole(Role role){if(role==null||role==Role.MASTER)throw ApiException.badRequest("Choose Senior or Learner.");}
  private Invitation valid(String token){if(token==null||token.length()>128)throw ApiException.badRequest("Invalid invitation.");return invitations.findByTokenHash(TenantFilter.sha256(token)).filter(i->!i.revoked&&i.consumedAt==null&&i.expiresAt.isAfter(Instant.now())).orElseThrow(()->ApiException.conflict("This invitation has expired, was revoked, or has already been accepted."));}
  public Map<String,Object> preview(String token){Invitation i=valid(token);return Map.of("email",i.email,"role",i.role,"expiresAt",i.expiresAt);}
  @Transactional public AppAccount accept(String token,String name,String password) {
    Invitation i=valid(token);
    if(name==null||name.isBlank()||name.length()>80||password==null||password.length()<8||password.length()>128)throw ApiException.badRequest("Enter your name and a password of 8–128 characters.");
    if(accounts.findByEmail(i.email).isPresent())throw ApiException.conflict("This email already has an account.");
    AppAccount a=new AppAccount();a.email=i.email;a.name=name.trim();a.passwordHash=auth.hash(password);a.workspaceId=i.workspaceId;a.role=i.role;a.demo=false;
    i.consumedAt=Instant.now();invitations.save(i);return accounts.save(a);
  }
  public void validateSenior(UUID id){if(accounts.findById(id).filter(a->a.workspaceId.equals(TenantContext.getId())&&a.active&&a.getRole()==Role.SENIOR).isEmpty())throw ApiException.badRequest("Select an active Senior in this workspace.");}
  public String name(UUID id){return accounts.findById(id).orElseThrow().name;}
}
