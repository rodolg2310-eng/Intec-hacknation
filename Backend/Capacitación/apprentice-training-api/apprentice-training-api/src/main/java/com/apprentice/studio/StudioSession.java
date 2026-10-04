package com.apprentice.studio;
import com.apprentice.tenant.TenantAwareEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="studio_sessions")
public class StudioSession extends TenantAwareEntity {
  @Id @GeneratedValue(strategy=GenerationType.UUID) UUID id;
  @Column(nullable=false) String title;
  @Column(nullable=false) String expert;
  UUID authorAccountId;
  @Column(nullable=false, columnDefinition="integer default 0") int publicationVersion;
  @Column(nullable=false) String phase = "capture";
  @Column(nullable=false) Instant createdAt = Instant.now();
  @Column(nullable=false) Instant updatedAt = Instant.now();
  @Column(columnDefinition="text", nullable=false) String payload = "{}";
  @Version long version;
  public StudioSession() {}
}
