package com.apprentice.learner;

import com.apprentice.tenant.TenantAwareEntity;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "learners", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "external_id"}))
public class Learner extends TenantAwareEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private String name;

  @Column(name = "external_id")
  private String externalId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  protected Learner() {}

  public Learner(String name, String externalId) {
    this.name = name;
    this.externalId = externalId;
  }

  public UUID getId() { return id; }
  public String getName() { return name; }
  public String getExternalId() { return externalId; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
}
