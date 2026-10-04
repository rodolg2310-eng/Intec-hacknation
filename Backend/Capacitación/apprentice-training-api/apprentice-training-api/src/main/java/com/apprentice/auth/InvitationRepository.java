package com.apprentice.auth;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface InvitationRepository extends JpaRepository<Invitation,UUID> {
  Optional<Invitation> findByTokenHash(String hash);
  List<Invitation> findByWorkspaceIdOrderByExpiresAtDesc(UUID workspaceId);
}
