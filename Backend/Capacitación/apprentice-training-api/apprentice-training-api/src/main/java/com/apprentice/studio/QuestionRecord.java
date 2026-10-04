package com.apprentice.studio;
import com.apprentice.tenant.TenantAwareEntity;
import jakarta.persistence.*;
import java.util.UUID;
@Entity @Table(name="studio_questions")
public class QuestionRecord extends TenantAwareEntity {
  @Id UUID id;
  UUID sessionId;
  String eventId;
  String stage;
  @Column(columnDefinition="text") String text;
  String status="pending";
  long issuedAtSeconds;
  Long deliveredAtSeconds;
  String answerMessageId;
  int windowIndex;
}
