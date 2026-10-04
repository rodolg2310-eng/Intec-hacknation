package com.apprentice.cases;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseRepository extends JpaRepository<PracticeCase, UUID> {
  List<PracticeCase> findByWorkmapIdOrderByDifficulty(UUID workmapId);
}
