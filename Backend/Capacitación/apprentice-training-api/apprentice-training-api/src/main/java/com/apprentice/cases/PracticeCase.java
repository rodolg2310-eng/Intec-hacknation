package com.apprentice.cases;

import com.apprentice.tenant.TenantAwareEntity;
import com.apprentice.workmap.Workmap;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Type;

@Entity
@Table(name = "cases")
public class PracticeCase extends TenantAwareEntity {

  public enum Kind { happy, limit, exception, stop_and_ask }
  public enum Novelty { seen, new_ }

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "workmap_id")
  private Workmap workmap;

  @Column(nullable = false)
  private String title;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Kind kind;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Novelty novelty = Novelty.seen;

  @Column(nullable = false)
  private int difficulty = 1;

  /** Datos del caso (factura, proveedor, monto...). */
  @Type(JsonType.class)
  @Column(columnDefinition = "jsonb", nullable = false)
  private Map<String, Object> data = Map.of();

  /** Decisiones correctas esperadas por paso. */
  @Type(JsonType.class)
  @Column(columnDefinition = "jsonb", nullable = false)
  private List<ExpectedDecision> expected = List.of();

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  public PracticeCase() {}

  /** Decision correcta esperada en un paso del proceso. */
  public record ExpectedDecision(int step, String decision) {}

  public String expectedForStep(int position) {
    return expected.stream()
        .filter(e -> e.step() == position)
        .map(ExpectedDecision::decision)
        .findFirst()
        .orElse(null);
  }

  public UUID getId() { return id; }
  public Workmap getWorkmap() { return workmap; }
  public String getTitle() { return title; }
  public Kind getKind() { return kind; }
  public Novelty getNovelty() { return novelty; }
  public int getDifficulty() { return difficulty; }
  public Map<String, Object> getData() { return data; }
  public List<ExpectedDecision> getExpected() { return expected; }
  public OffsetDateTime getCreatedAt() { return createdAt; }

  public void setWorkmap(Workmap v) { workmap = v; }
  public void setTitle(String v) { title = v; }
  public void setKind(Kind v) { kind = v; }
  public void setNovelty(Novelty v) { novelty = v; }
  public void setDifficulty(int v) { difficulty = v; }
  public void setData(Map<String, Object> v) { data = v; }
  public void setExpected(List<ExpectedDecision> v) { expected = v; }
}
