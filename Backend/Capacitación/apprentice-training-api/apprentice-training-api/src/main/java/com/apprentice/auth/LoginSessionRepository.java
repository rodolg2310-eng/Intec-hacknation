package com.apprentice.auth;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LoginSessionRepository extends JpaRepository<LoginSession, String> {}
