package com.apprentice.auth;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController
public class TeamController {
  private final TeamService team;private final AuthService auth;
  public TeamController(TeamService team,AuthService auth){this.team=team;this.auth=auth;}
  public record Invite(String email,Role role){}
  public record Update(Role role,boolean active){}
  public record Accept(String token,String name,String password){}
  @GetMapping("/api/t/{slug}/team/members") public Object members(){return team.members();}
  @PatchMapping("/api/t/{slug}/team/members/{id}") public Object update(@PathVariable UUID id,@RequestBody Update u){return team.update(id,u.role(),u.active());}
  @GetMapping("/api/t/{slug}/team/invitations") public Object list(){return team.invites();}
  @PostMapping("/api/t/{slug}/team/invitations") public Object invite(@RequestBody Invite i){return team.invite(i.email(),i.role());}
  @DeleteMapping("/api/t/{slug}/team/invitations/{id}") public Object revoke(@PathVariable UUID id){team.revoke(id);return Map.of("revoked",true);}
  @GetMapping("/api/auth/invitation") public Object preview(@RequestParam String token){return team.preview(token);}
  @PostMapping("/api/auth/accept-invitation") public Object accept(@RequestBody Accept i,HttpServletRequest req,HttpServletResponse res){AppAccount a=team.accept(i.token(),i.name(),i.password());auth.issue(a,req,res);return auth.view(a);}
}
