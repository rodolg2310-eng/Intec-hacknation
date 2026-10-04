package com.apprentice.auth;

import com.apprentice.common.ApiException;
import com.apprentice.tenant.*;
import jakarta.servlet.http.*;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  public static final String COOKIE = "traina_session";
  private final AppAccountRepository accounts;
  private final LoginSessionRepository sessions;
  private final TenantRepository tenants;
  private final SecureRandom random = new SecureRandom();
  public AuthService(AppAccountRepository a, LoginSessionRepository s, TenantRepository t) {
    accounts = a; sessions = s; tenants = t;
  }
  @Transactional
  public AppAccount register(String email, String password, String name, String workspace, boolean demo) {
    email = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || email.length() > 180)
      throw ApiException.badRequest("Enter a valid email address.");
    if (password == null || password.length() < 8 || password.length() > 128)
      throw ApiException.badRequest("The password must contain 8–128 characters.");
    if (name == null || name.isBlank() || name.length() > 80 || workspace == null || workspace.isBlank() || workspace.length() > 80)
      throw ApiException.badRequest("Enter your name and workspace name (up to 80 characters).");
    if (accounts.findByEmail(email).isPresent()) throw ApiException.conflict("This email already has an account. Sign in instead.");
    String prefix = workspace.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    if (prefix.isBlank()) prefix = "equipo";
    String slug = prefix.substring(0, Math.min(prefix.length(), 35)) + "-" + UUID.randomUUID().toString().substring(0, 8);
    Tenant tenant = tenants.save(new Tenant(slug, workspace.trim(), TenantFilter.sha256(token())));
    AppAccount a = new AppAccount();
    a.email = email; a.name = name.trim(); a.passwordHash = hash(password); a.workspaceId = tenant.getId(); a.demo = demo;
    return accounts.save(a);
  }
  public AppAccount login(String email, String password) {
    AppAccount a = accounts.findByEmail(email == null ? "" : email.trim().toLowerCase(Locale.ROOT)).orElse(null);
    if (a == null || !a.active || password == null || password.length() > 128 || !verify(password, a.passwordHash))
      throw new ApiException(HttpStatus.UNAUTHORIZED, "Incorrect email or password.");
    return a;
  }
  @Transactional public AppAccount demo(Role role) {
    AppAccount master=register(UUID.randomUUID()+"@demo.traina.local",token(),"Demo Master","Learning Lab",true);
    if(role==Role.MASTER)return master;
    AppAccount a=new AppAccount();a.email=UUID.randomUUID()+"@demo.traina.local";a.name="Demo "+role.name().toLowerCase(Locale.ROOT);a.passwordHash=hash(token());a.workspaceId=master.workspaceId;a.role=role;a.demo=true;
    return accounts.save(a);
  }
  public AppAccount demoLearner(AppAccount a){if(!a.demo||a.getRole()!=Role.SENIOR)throw new IllegalStateException("Only isolated demo accounts can use this conversion.");a.role=Role.LEARNER;a.name="Demo learner";return accounts.save(a);}
  public AppAccount current(HttpServletRequest request) {
    Object cached = request.getAttribute("traina.account");
    if (cached instanceof AppAccount a) return a;
    String value = cookie(request);
    if (value == null) return null;
    LoginSession session = sessions.findById(TenantFilter.sha256(value)).orElse(null);
    if (session == null || session.expiresAt.isBefore(Instant.now())) return null;
    AppAccount a = accounts.findById(session.accountId).orElse(null);
    if (a != null && !a.active) return null;
    if (a != null) request.setAttribute("traina.account", a);
    return a;
  }
  public AppAccount require(HttpServletRequest request) {
    AppAccount a = current(request);
    if (a == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "Sign in to continue.");
    return a;
  }
  public Tenant workspace(AppAccount a) { return tenants.findById(a.workspaceId).orElseThrow(() -> ApiException.notFound("Espacio")); }
  public Map<String, Object> view(AppAccount a) {
    Tenant t = workspace(a);
    return Map.of("id", a.id, "role", a.getRole(), "name", a.name, "email", a.email, "workspace", t.getName(), "slug", t.getSlug(), "demo", a.demo);
  }
  public void issue(AppAccount a, HttpServletRequest req, HttpServletResponse res) {
    String value = token(); LoginSession s = new LoginSession();
    s.tokenHash = TenantFilter.sha256(value); s.accountId = a.id; s.expiresAt = Instant.now().plus(Duration.ofDays(7)); sessions.save(s);
    setCookie(value, Duration.ofDays(7), req, res);
  }
  public void logout(HttpServletRequest req, HttpServletResponse res) {
    String value = cookie(req); if (value != null) sessions.deleteById(TenantFilter.sha256(value));
    setCookie("", Duration.ZERO, req, res);
  }
  private void setCookie(String value, Duration age, HttpServletRequest req, HttpServletResponse res) {
    res.addHeader("Set-Cookie", ResponseCookie.from(COOKIE, value).httpOnly(true).sameSite("Strict").path("/")
      .secure(req.isSecure() || "https".equals(req.getHeader("X-Forwarded-Proto"))).maxAge(age).build().toString());
  }
  private String cookie(HttpServletRequest req) {
    if (req.getCookies() != null) for (Cookie c : req.getCookies()) if (COOKIE.equals(c.getName())) return c.getValue();
    return null;
  }
  public String token() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
  String hash(String password) { byte[] salt = new byte[16]; random.nextBytes(salt); return HexFormat.of().formatHex(salt) + ":" + derive(password, salt); }
  public AppAccount requireCurrent(Role... roles) {
    var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
    if (!(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet)) throw new ApiException(HttpStatus.UNAUTHORIZED, "Sign in to continue.");
    AppAccount a = require(servlet.getRequest());
    if (!a.workspaceId.equals(TenantContext.getId())) throw new ApiException(HttpStatus.FORBIDDEN, "Workspace access denied.");
    if (roles.length > 0 && !Arrays.asList(roles).contains(a.getRole())) throw new ApiException(HttpStatus.FORBIDDEN, "Your role cannot perform this action.");
    return a;
  }
  private boolean verify(String password, String saved) {
    try { String[] parts = saved.split(":"); return MessageDigest.isEqual(derive(password, HexFormat.of().parseHex(parts[0])).getBytes(), parts[1].getBytes()); }
    catch (RuntimeException e) { return false; }
  }
  private String derive(String password, byte[] salt) {
    try { PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 210000, 256);
      byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); spec.clearPassword(); return HexFormat.of().formatHex(key);
    } catch (GeneralSecurityException e) { throw new IllegalStateException("Could not protect the password.", e); }
  }
}
