package com.apprentice.auth;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="workspace_invitations")
public class Invitation {
  @Id UUID id = UUID.randomUUID();
  @Column(nullable=false, unique=true) String tokenHash;
  @Column(nullable=false) UUID workspaceId;
  @Column(nullable=false) String email;
  @Enumerated(EnumType.STRING) @Column(nullable=false) Role role;
  @Column(nullable=false) Instant expiresAt;
  Instant consumedAt;
  boolean revoked;
  @Version long version;
}
