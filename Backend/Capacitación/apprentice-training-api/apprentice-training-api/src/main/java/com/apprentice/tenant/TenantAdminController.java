package com.apprentice.tenant;

import com.apprentice.common.ApiException;
import jakarta.validation.constraints.NotBlank;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Alta de empresas. Solo recibe informacion: cualquier sistema externo
 * (pagina web, CRM, script) puede registrar una empresa y obtener su clave.
 * Protegido con la clave de administrador (encabezado x-admin-key).
 */
@RestController
@RequestMapping("/api/tenants")
public class TenantAdminController {

  private final TenantRepository tenants;
  private final String adminKey;
  private final SecureRandom random = new SecureRandom();

  public TenantAdminController(
      TenantRepository tenants, @Value("${apprentice.admin-api-key}") String adminKey) {
    this.tenants = tenants;
    this.adminKey = adminKey;
  }

  public record CreateTenantRequest(@NotBlank String slug, @NotBlank String name) {}

  @PostMapping
  public ResponseEntity<Map<String, Object>> create(
      @RequestHeader("x-admin-key") String key, @RequestBody CreateTenantRequest body) {
    checkAdmin(key);
    if (tenants.findBySlug(body.slug()).isPresent()) {
      throw ApiException.conflict("Ya existe una empresa con ese slug");
    }
    String apiKey = "ak_" + HexFormat.of().formatHex(randomBytes(24));
    Tenant tenant = tenants.save(new Tenant(body.slug(), body.name(), TenantFilter.sha256(apiKey)));
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
        "slug", tenant.getSlug(),
        "name", tenant.getName(),
        "api_key", apiKey,
        "note", "Guarda esta clave: solo se muestra una vez."));
  }

  @PostMapping("/{slug}/rotate-key")
  public Map<String, Object> rotateKey(
      @RequestHeader("x-admin-key") String key, @PathVariable String slug) {
    checkAdmin(key);
    Tenant tenant = tenants.findBySlug(slug)
        .orElseThrow(() -> ApiException.notFound("Empresa"));
    String apiKey = "ak_" + HexFormat.of().formatHex(randomBytes(24));
    tenant.setApiKeyHash(TenantFilter.sha256(apiKey));
    tenants.save(tenant);
    return Map.of("slug", slug, "api_key", apiKey, "note", "La clave anterior dejo de funcionar.");
  }

  private void checkAdmin(String key) {
    if (key == null || !key.equals(adminKey)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "Clave de administrador no valida");
    }
  }

  private byte[] randomBytes(int n) {
    byte[] b = new byte[n];
    random.nextBytes(b);
    return b;
  }
}
