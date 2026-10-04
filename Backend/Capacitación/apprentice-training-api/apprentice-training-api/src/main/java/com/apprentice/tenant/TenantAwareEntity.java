package com.apprentice.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Base de las entidades con datos por empresa. Hibernate 6 filtra
 * automaticamente por tenant en consultas, cargas y escrituras.
 */
@MappedSuperclass
public abstract class TenantAwareEntity {

  @TenantId
  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  @PrePersist
  void assignTenant() {
    if (tenantId == null) tenantId = TenantContext.getId();
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public void setTenantId(UUID tenantId) {
    this.tenantId = tenantId;
  }
}
