package com.apprentice.auth;
import com.apprentice.common.ApiException;
import jakarta.servlet.http.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/auth")
public class AuthController {
  private final AuthService auth;
  private final com.apprentice.studio.StudioService studio;
  private final Map<String, List<Long>> attempts = new ConcurrentHashMap<>();
  public AuthController(AuthService auth, com.apprentice.studio.StudioService studio) { this.auth = auth; this.studio=studio; }
  public record Credentials(String email, String password, String name, String workspace) {}
  @PostMapping("/register") public Map<String, Object> register(@RequestBody Credentials c, HttpServletRequest req, HttpServletResponse res) {
    limit(req); AppAccount a = auth.register(c.email(), c.password(), c.name(), c.workspace(), false); auth.issue(a, req, res); return auth.view(a);
  }
  @PostMapping("/login") public Map<String, Object> login(@RequestBody Credentials c, HttpServletRequest req, HttpServletResponse res) {
    limit(req); AppAccount a = auth.login(c.email(), c.password()); auth.issue(a, req, res); return auth.view(a);
  }
  @PostMapping("/demo") public Map<String, Object> demo(HttpServletRequest req, HttpServletResponse res) {
    limit(req); AppAccount a = auth.demo(Role.SENIOR);
    auth.issue(a, req, res); return auth.view(a);
  }
  @GetMapping("/me") public Map<String, Object> me(HttpServletRequest req) { return auth.view(auth.require(req)); }
  @PostMapping("/demo-learner") public Map<String,Object> demoLearner(HttpServletRequest req,HttpServletResponse res) throws java.io.IOException {
    limit(req);AppAccount a=auth.demo(Role.SENIOR);req.setAttribute("traina.account",a);com.apprentice.tenant.TenantContext.set(auth.workspace(a));
    try{studio.example();a=auth.demoLearner(a);}finally{com.apprentice.tenant.TenantContext.clear();}
    auth.issue(a,req,res);return auth.view(a);
  }
  @PostMapping("/logout") public Map<String, Object> logout(HttpServletRequest req, HttpServletResponse res) { auth.logout(req, res); return Map.of("ok", true); }
  private void limit(HttpServletRequest req) {
    long now = System.currentTimeMillis(); List<Long> list = attempts.computeIfAbsent(req.getRemoteAddr(), k -> Collections.synchronizedList(new ArrayList<>()));
    synchronized (list) { list.removeIf(t -> now - t > 600000); if (list.size() >= 30) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again in a few minutes."); list.add(now); }
  }
}
