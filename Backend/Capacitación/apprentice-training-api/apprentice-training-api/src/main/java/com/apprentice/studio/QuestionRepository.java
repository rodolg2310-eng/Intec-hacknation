package com.apprentice.studio;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface QuestionRepository extends JpaRepository<QuestionRecord,UUID> {
  List<QuestionRecord> findBySessionIdOrderByIssuedAtSecondsAsc(UUID sessionId);
}
