package com.apprentice.workmap;

import com.apprentice.tenant.TenantAwareEntity;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "workmaps")
public class Workmap extends TenantAwareEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private String title;

  private String role;

  @Column(name = "onet_code")
  private String onetCode;

  @Column(name = "expert_name")
  private String expertName;

  @Column(nullable = false)
  private String language = "es";

  @Column(columnDefinition = "text")
  private String summary;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  public Workmap() {}

  public UUID getId() { return id; }
  public String getTitle() { return title; }
  public String getRole() { return role; }
  public String getOnetCode() { return onetCode; }
  public String getExpertName() { return expertName; }
  public String getLanguage() { return language; }
  public String getSummary() { return summary; }
  public OffsetDateTime getCreatedAt() { return createdAt; }

  public void setTitle(String v) { title = v; }
  public void setRole(String v) { role = v; }
  public void setOnetCode(String v) { onetCode = v; }
  public void setExpertName(String v) { expertName = v; }
  public void setLanguage(String v) { language = v; }
  public void setSummary(String v) { summary = v; }
}
