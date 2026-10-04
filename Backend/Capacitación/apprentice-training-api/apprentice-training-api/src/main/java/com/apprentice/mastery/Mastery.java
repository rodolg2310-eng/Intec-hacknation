package com.apprentice.mastery;

import com.apprentice.learner.Learner;
import com.apprentice.tenant.TenantAwareEntity;
import com.apprentice.workmap.Step;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "mastery", uniqueConstraints = @UniqueConstraint(columnNames = {"learner_id", "step_id"}))
public class Mastery extends TenantAwareEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private java.util.UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "learner_id")
  private Learner learner;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "step_id")
  private Step step;

  @Column(nullable = false)
  private int attempts = 0;

  @Column(nullable = false)
  private int correct = 0;

  @Column(nullable = false)
  private int streak = 0;

  @Column(name = "seen_new_case", nullable = false)
  private boolean seenNewCase = false;

  @Column(name = "last_practiced")
  private OffsetDateTime lastPracticed;

  @Column(name = "next_due")
  private OffsetDateTime nextDue;

  protected Mastery() {}

  public Mastery(Learner learner, Step step) {
    this.learner = learner;
    this.step = step;
  }

  /** Repeticion espaciada: acierto duplica el plazo; fallo vuelve en 10 minutos. */
  public void record(boolean ok, boolean isNewCase) {
    attempts++;
    if (ok) {
      correct++;
      streak++;
      seenNewCase = seenNewCase || isNewCase;
      nextDue = OffsetDateTime.now().plusDays(1L << Math.min(streak, 20));
    } else {
      streak = 0;
      nextDue = OffsetDateTime.now().plusMinutes(10);
    }
    lastPracticed = OffsetDateTime.now();
  }

  public boolean isMastered() {
    return streak >= 3 && seenNewCase;
  }

  public java.util.UUID getId() { return id; }
  public Learner getLearner() { return learner; }
  public Step getStep() { return step; }
  public int getAttempts() { return attempts; }
  public int getCorrect() { return correct; }
  public int getStreak() { return streak; }
  public boolean isSeenNewCase() { return seenNewCase; }
  public OffsetDateTime getLastPracticed() { return lastPracticed; }
  public OffsetDateTime getNextDue() { return nextDue; }
}
