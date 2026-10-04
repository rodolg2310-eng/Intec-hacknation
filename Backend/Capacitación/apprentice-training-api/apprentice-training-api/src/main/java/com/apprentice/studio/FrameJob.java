package com.apprentice.studio;
import com.apprentice.tenant.TenantAwareEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="frame_jobs",uniqueConstraints=@UniqueConstraint(columnNames={"sessionId","sequence"}))
public class FrameJob extends TenantAwareEntity {
  @Id UUID id=UUID.randomUUID();
  @Column(nullable=false) UUID sessionId;
  @Column(nullable=false) long sequence;
  @Column(nullable=false) long elapsedSeconds;
  @Column(nullable=false) String imagePath;
  String cameraImagePath;
  @Column(nullable=false) String status="queued";
  String error;
  int attempts;
  Instant createdAt=Instant.now();
  @Column(columnDefinition="text") String observation;
}
