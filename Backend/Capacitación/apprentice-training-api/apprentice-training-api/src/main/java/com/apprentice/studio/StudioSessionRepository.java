package com.apprentice.studio;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface StudioSessionRepository extends JpaRepository<StudioSession, UUID> {
  List<StudioSession> findByTenantIdOrderByUpdatedAtDesc(UUID tenantId);
  Optional<StudioSession> findByIdAndTenantId(UUID id, UUID tenantId);
}
