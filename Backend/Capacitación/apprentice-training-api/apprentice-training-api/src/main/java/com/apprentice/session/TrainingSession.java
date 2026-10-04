package com.apprentice.session;

import com.apprentice.cases.PracticeCase;
import com.apprentice.learner.Learner;
import com.apprentice.tenant.TenantAwareEntity;
import com.apprentice.workmap.Workmap;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Type;

@Entity
@Table(name = "training_sessions")
public class TrainingSession extends TenantAwareEntity {

  public enum Level { observe, predict, guided, exam }
  public enum Status { active, finished }

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "learner_id")
  private Learner learner;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "workmap_id")
  private Workmap workmap;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "case_id")
  private PracticeCase practiceCase;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Level level = Level.observe;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status = Status.active;

  @Column(name = "current_step", nullable = false)
  private int currentStep = 1;

  private Double score;

  @Type(JsonType.class)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> summary;

  @Column(name = "started_at", nullable = false)
  private OffsetDateTime startedAt = OffsetDateTime.now();

  @Column(name = "finished_at")
  private OffsetDateTime finishedAt;

  public TrainingSession() {}

  public UUID getId() { return id; }
  public Learner getLearner() { return learner; }
  public Workmap getWorkmap() { return workmap; }
  public PracticeCase getPracticeCase() { return practiceCase; }
  public Level getLevel() { return level; }
  public Status getStatus() { return status; }
  public int getCurrentStep() { return currentStep; }
  public Double getScore() { return score; }
  public Map<String, Object> getSummary() { return summary; }
  public OffsetDateTime getStartedAt() { return startedAt; }
  public OffsetDateTime getFinishedAt() { return finishedAt; }

  public void setLearner(Learner v) { learner = v; }
  public void setWorkmap(Workmap v) { workmap = v; }
  public void setPracticeCase(PracticeCase v) { practiceCase = v; }
  public void setLevel(Level v) { level = v; }
  public void setStatus(Status v) { status = v; }
  public void setCurrentStep(int v) { currentStep = v; }
  public void setScore(Double v) { score = v; }
  public void setSummary(Map<String, Object> v) { summary = v; }
  public void setFinishedAt(OffsetDateTime v) { finishedAt = v; }
}
