package com.apprentice.studio;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LearningAttemptRepository extends JpaRepository<LearningAttempt,UUID> {
  Optional<LearningAttempt> findBySessionIdAndLearnerIdAndMapVersion(UUID sessionId,UUID learnerId,int version);
  List<LearningAttempt> findBySessionId(UUID id);
}
