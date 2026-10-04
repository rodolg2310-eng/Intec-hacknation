package com.apprentice.studio;
import com.apprentice.tenant.TenantAwareEntity;
import jakarta.persistence.*;
import java.util.UUID;
@Entity @Table(name="media_chunks",uniqueConstraints=@UniqueConstraint(columnNames={"sessionId","recordingId","sequence"}))
public class MediaChunk extends TenantAwareEntity {
  @Id UUID id=UUID.randomUUID();
  UUID sessionId;
  UUID recordingId;
  long sequence;
  double startSeconds;
  double endSeconds;
  String kind;
  String path;
  String contentType;
  String checksum;
  boolean finalChunk;
  boolean removed;
}
