package com.apprentice.tenant;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Una empresa (tenant). Las claves se guardan como hash SHA-256. */
@Entity
@Table(name = "tenants")
public class Tenant {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, unique = true)
  private String slug;

  @Column(nullable = false)
  private String name;

  @Column(name = "api_key_hash", nullable = false)
  private String apiKeyHash;

  @Column(name = "elevenlabs_agent_id")
  private String elevenlabsAgentId;

  /** Opcional: base de datos dedicada para empresas grandes. */
  @Column(name = "db_url")
  private String dbUrl;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  protected Tenant() {}

  public Tenant(String slug, String name, String apiKeyHash) {
    this.slug = slug;
    this.name = name;
    this.apiKeyHash = apiKeyHash;
  }

  public UUID getId() { return id; }
  public String getSlug() { return slug; }
  public String getName() { return name; }
  public String getApiKeyHash() { return apiKeyHash; }
  public String getElevenlabsAgentId() { return elevenlabsAgentId; }
  public String getDbUrl() { return dbUrl; }
  public OffsetDateTime getCreatedAt() { return createdAt; }

  public void setApiKeyHash(String apiKeyHash) { this.apiKeyHash = apiKeyHash; }
  public void setElevenlabsAgentId(String elevenlabsAgentId) { this.elevenlabsAgentId = elevenlabsAgentId; }
}
