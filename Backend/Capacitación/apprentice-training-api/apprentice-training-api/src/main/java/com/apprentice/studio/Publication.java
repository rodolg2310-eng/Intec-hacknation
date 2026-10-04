package com.apprentice.studio;
import com.apprentice.tenant.TenantAwareEntity;
import jakarta.persistence.*;
import java.util.UUID;
@Entity @Table(name="studio_publications",uniqueConstraints=@UniqueConstraint(columnNames={"sessionId","mapVersion"}))
public class Publication extends TenantAwareEntity {
  @Id UUID id=UUID.randomUUID();
  UUID sessionId;
  int mapVersion;
  @Column(columnDefinition="text",nullable=false) String payload;
  boolean withdrawn;
}
