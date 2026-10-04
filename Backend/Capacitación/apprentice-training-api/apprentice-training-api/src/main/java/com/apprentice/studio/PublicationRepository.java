package com.apprentice.studio;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PublicationRepository extends JpaRepository<Publication,UUID> {
  List<Publication> findBySessionId(UUID sessionId);
}
