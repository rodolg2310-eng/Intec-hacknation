package com.apprentice.session;

import com.apprentice.tenant.TenantAwareEntity;
import com.apprentice.workmap.Step;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "predictions")
public class Prediction extends TenantAwareEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "session_id")
  private TrainingSession session;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "step_id")
  private Step step;

  @Column(nullable = false)
  private String answer;

  private String explanation;

  @Column(nullable = false)
  private boolean correct;

  private String feedback;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  public Prediction() {}

  public UUID getId() { return id; }
  public TrainingSession getSession() { return session; }
  public Step getStep() { return step; }
  public String getAnswer() { return answer; }
  public String getExplanation() { return explanation; }
  public boolean isCorrect() { return correct; }
  public String getFeedback() { return feedback; }

  public void setSession(TrainingSession v) { session = v; }
  public void setStep(Step v) { step = v; }
  public void setAnswer(String v) { answer = v; }
  public void setExplanation(String v) { explanation = v; }
  public void setCorrect(boolean v) { correct = v; }
  public void setFeedback(String v) { feedback = v; }
}
