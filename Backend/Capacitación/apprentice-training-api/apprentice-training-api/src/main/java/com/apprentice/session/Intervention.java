package com.apprentice.session;

import com.apprentice.tenant.TenantAwareEntity;
import com.apprentice.workmap.Guardrail;
import com.apprentice.workmap.Step;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "interventions")
public class Intervention extends TenantAwareEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "session_id")
  private TrainingSession session;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "step_id")
  private Step step;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "guardrail_id")
  private Guardrail guardrail;

  private String attempted;

  private String message;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  protected Intervention() {}

  public Intervention(TrainingSession session, Step step, Guardrail guardrail, String attempted, String message) {
    this.session = session;
    this.step = step;
    this.guardrail = guardrail;
    this.attempted = attempted;
    this.message = message;
  }

  public UUID getId() { return id; }
  public TrainingSession getSession() { return session; }
  public Step getStep() { return step; }
  public Guardrail getGuardrail() { return guardrail; }
  public String getAttempted() { return attempted; }
  public String getMessage() { return message; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
}
