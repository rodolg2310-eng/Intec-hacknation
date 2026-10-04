package com.apprentice.session;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterventionRepository extends JpaRepository<Intervention, UUID> {
  List<Intervention> findBySessionIdOrderByCreatedAt(UUID sessionId);
}
