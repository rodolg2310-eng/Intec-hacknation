package com.apprentice.studio;
import com.apprentice.tenant.TenantAwareEntity;
import jakarta.persistence.*;
import java.util.UUID;
@Entity @Table(name="learning_attempts",uniqueConstraints=@UniqueConstraint(columnNames={"sessionId","learnerId","mapVersion"}))
public class LearningAttempt extends TenantAwareEntity {
  @Id UUID id=UUID.randomUUID();
  UUID sessionId;
  UUID learnerId;
  int mapVersion;
  @Column(columnDefinition="text",nullable=false) String payload="{}";
  @Version long version;
}
