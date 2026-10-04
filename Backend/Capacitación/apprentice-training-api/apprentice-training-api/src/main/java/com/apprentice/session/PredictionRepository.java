package com.apprentice.session;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PredictionRepository extends JpaRepository<Prediction, UUID> {
  List<Prediction> findBySessionIdOrderByCreatedAt(UUID sessionId);
}
