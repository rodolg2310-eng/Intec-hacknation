package com.apprentice.auth;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
@Component @Order(0)
public class OriginFilter extends OncePerRequestFilter {
  private final Set<String> origins;
  public OriginFilter(@Value("${apprentice.allowed-origins}") String allowed) { origins = Set.of(allowed.split(",")); }
  @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
    String origin = req.getHeader("Origin");
    if (req.getRequestURI().startsWith("/api/") && !Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod()) && origin != null && !origins.contains(origin)) {
      res.setStatus(403); res.setContentType("application/json"); res.getWriter().write("{\"error\":\"Origin is not allowed.\"}"); return;
    }
    res.setHeader("X-Content-Type-Options", "nosniff");
    if (req.getRequestURI().startsWith("/api/")) res.setHeader("Cache-Control", "no-store");
    chain.doFilter(req, res);
  }
}
