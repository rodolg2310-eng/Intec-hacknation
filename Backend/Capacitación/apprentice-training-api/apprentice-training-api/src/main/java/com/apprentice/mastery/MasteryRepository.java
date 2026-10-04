package com.apprentice.mastery;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MasteryRepository extends JpaRepository<Mastery, UUID> {
  Optional<Mastery> findByLearnerIdAndStepId(UUID learnerId, UUID stepId);
  List<Mastery> findByLearnerId(UUID learnerId);
}
