package com.apprentice.workmap;

import com.apprentice.tenant.TenantAwareEntity;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "steps", uniqueConstraints = @UniqueConstraint(columnNames = {"workmap_id", "position"}))
public class Step extends TenantAwareEntity {

  public enum Risk { low, medium, high }

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "workmap_id")
  private Workmap workmap;

  @Column(nullable = false)
  private int position;

  @Column(nullable = false)
  private String title;

  @Column(name = "screen_ts")
  private String screenTs;

  @Column(name = "screenshot_url")
  private String screenshotUrl;

  @Column(columnDefinition = "text")
  private String decision;

  @Column(name = "reason_quote", columnDefinition = "text")
  private String reasonQuote;

  @Column(name = "reason_author")
  private String reasonAuthor;

  @Column(name = "reason_ts")
  private String reasonTs;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Risk risk = Risk.low;

  /** Si el experto lo marco como fuera del registro, nunca se exporta al tutor. */
  @Column(name = "off_record", nullable = false)
  private boolean offRecord = false;

  public Step() {}

  public UUID getId() { return id; }
  public Workmap getWorkmap() { return workmap; }
  public int getPosition() { return position; }
  public String getTitle() { return title; }
  public String getScreenTs() { return screenTs; }
  public String getScreenshotUrl() { return screenshotUrl; }
  public String getDecision() { return decision; }
  public String getReasonQuote() { return reasonQuote; }
  public String getReasonAuthor() { return reasonAuthor; }
  public String getReasonTs() { return reasonTs; }
  public Risk getRisk() { return risk; }
  public boolean isOffRecord() { return offRecord; }

  public void setWorkmap(Workmap v) { workmap = v; }
  public void setPosition(int v) { position = v; }
  public void setTitle(String v) { title = v; }
  public void setScreenTs(String v) { screenTs = v; }
  public void setScreenshotUrl(String v) { screenshotUrl = v; }
  public void setDecision(String v) { decision = v; }
  public void setReasonQuote(String v) { reasonQuote = v; }
  public void setReasonAuthor(String v) { reasonAuthor = v; }
  public void setReasonTs(String v) { reasonTs = v; }
  public void setRisk(Risk v) { risk = v; }
  public void setOffRecord(boolean v) { offRecord = v; }
}
