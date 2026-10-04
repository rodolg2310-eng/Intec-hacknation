package com.apprentice.workmap;

import com.apprentice.tenant.TenantAwareEntity;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "guardrails")
public class Guardrail extends TenantAwareEntity {

  public enum Kind { limit, exception, stop_and_ask }

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "workmap_id")
  private Workmap workmap;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "step_id")
  private Step step;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Kind kind;

  @Column(name = "condition", nullable = false, columnDefinition = "text")
  private String condition;

  @Column(name = "correct_action", nullable = false, columnDefinition = "text")
  private String correctAction;

  @Column(name = "expert_quote", columnDefinition = "text")
  private String expertQuote;

  @Column(name = "expert_ts")
  private String expertTs;

  @Column(name = "off_record", nullable = false)
  private boolean offRecord = false;

  public Guardrail() {}

  public UUID getId() { return id; }
  public Workmap getWorkmap() { return workmap; }
  public Step getStep() { return step; }
  public Kind getKind() { return kind; }
  public String getCondition() { return condition; }
  public String getCorrectAction() { return correctAction; }
  public String getExpertQuote() { return expertQuote; }
  public String getExpertTs() { return expertTs; }
  public boolean isOffRecord() { return offRecord; }

  public void setWorkmap(Workmap v) { workmap = v; }
  public void setStep(Step v) { step = v; }
  public void setKind(Kind v) { kind = v; }
  public void setCondition(String v) { condition = v; }
  public void setCorrectAction(String v) { correctAction = v; }
  public void setExpertQuote(String v) { expertQuote = v; }
  public void setExpertTs(String v) { expertTs = v; }
  public void setOffRecord(boolean v) { offRecord = v; }
}
