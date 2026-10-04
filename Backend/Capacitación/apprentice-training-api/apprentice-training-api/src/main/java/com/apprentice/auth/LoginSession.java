package com.apprentice.auth;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name = "login_sessions")
public class LoginSession {
  @Id String tokenHash;
  @Column(nullable = false) UUID accountId;
  @Column(nullable = false) Instant expiresAt;
  protected LoginSession() {}
}
