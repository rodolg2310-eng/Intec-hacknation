package com.apprentice.session;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionEventRepository extends JpaRepository<SessionEvent, UUID> {
  List<SessionEvent> findBySessionIdOrderByCreatedAt(UUID sessionId);
}
