package com.apprentice.session;

import com.apprentice.tenant.TenantAwareEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Type;

@Entity
@Table(name = "session_events")
public class SessionEvent extends TenantAwareEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "session_id")
  private TrainingSession session;

  private String ts;

  @Column(nullable = false)
  private String type;

  @Type(JsonType.class)
  @Column(columnDefinition = "jsonb", nullable = false)
  private Map<String, Object> payload = Map.of();

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  protected SessionEvent() {}

  public SessionEvent(TrainingSession session, String ts, String type, Map<String, Object> payload) {
    this.session = session;
    this.ts = ts;
    this.type = type;
    this.payload = payload;
  }

  public UUID getId() { return id; }
  public TrainingSession getSession() { return session; }
  public String getTs() { return ts; }
  public String getType() { return type; }
  public Map<String, Object> getPayload() { return payload; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
}
