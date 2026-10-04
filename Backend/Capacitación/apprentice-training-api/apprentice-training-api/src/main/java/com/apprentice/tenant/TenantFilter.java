package com.apprentice.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autentica cada llamada a /api/t/{slug}/... con el encabezado x-api-key.
 * La clave se compara por su hash SHA-256 contra la tabla tenants.
 */
@Component
@Order(1)
public class TenantFilter extends OncePerRequestFilter {

  private final TenantRepository tenants;
  private final com.apprentice.auth.AuthService auth;
  private final ObjectMapper mapper = new ObjectMapper();

  public TenantFilter(TenantRepository tenants, com.apprentice.auth.AuthService auth) {
    this.tenants = tenants;
    this.auth = auth;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/t/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String uri = req.getRequestURI();
    String slug = uri.substring("/api/t/".length());
    int slash = slug.indexOf('/');
    if (slash >= 0) slug = slug.substring(0, slash);

    String key = req.getHeader("x-api-key");
    Tenant tenant = tenants.findBySlug(slug).orElse(null);
    var account = auth.current(req);
    boolean cookieAuthorized = account != null && tenant != null && auth.workspace(account).getId().equals(tenant.getId());
    boolean keyAuthorized = key != null && !key.isBlank() && tenant != null && tenant.getApiKeyHash().equals(sha256(key));
    boolean productRoute = uri.contains("/studio/") || uri.endsWith("/studio") || uri.contains("/team/");
    if (productRoute && !cookieAuthorized) { reject(res,401,"Sign in to this workspace."); return; }
    if (cookieAuthorized && !productRoute) { reject(res,403,"Use the role-controlled workspace API."); return; }
    if (!cookieAuthorized && !keyAuthorized) {
      reject(res, 401, "Workspace access denied.");
      return;
    }

    try {
      TenantContext.set(tenant);
      chain.doFilter(req, res);
    } finally {
      TenantContext.clear();
    }
  }

  private void reject(HttpServletResponse res, int status, String message) throws IOException {
    res.setStatus(status);
    res.setContentType("application/json");
    res.getWriter().write(mapper.writeValueAsString(Map.of("error", message)));
  }

  public static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
