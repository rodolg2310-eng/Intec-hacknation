package com.apprentice.auth;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AppAccountRepository extends JpaRepository<AppAccount, UUID> {
  Optional<AppAccount> findByEmail(String email);
  java.util.List<AppAccount> findByWorkspaceId(UUID workspaceId);
}
