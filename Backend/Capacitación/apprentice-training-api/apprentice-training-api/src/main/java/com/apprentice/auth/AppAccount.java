package com.apprentice.auth;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "app_accounts")
public class AppAccount {
  @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
  @Column(nullable = false, unique = true) String email;
  @Column(nullable = false) String name;
  @Column(nullable = false) String passwordHash;
  @Column(nullable = false) UUID workspaceId;
  @Column(nullable = false) boolean demo;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, columnDefinition = "varchar(16) default 'MASTER'") Role role = Role.MASTER;
  @Column(nullable = false, columnDefinition = "boolean default true") boolean active = true;
  public UUID getId() { return id; }
  public UUID getWorkspaceId() { return workspaceId; }
  public String getName() { return name; }
  public Role getRole() { return role == null ? Role.MASTER : role; }
  public boolean isDemo() { return demo; }
  public boolean isActive() { return active; }
  protected AppAccount() {}
}
