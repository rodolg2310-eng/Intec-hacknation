package com.apprentice.workmap;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StepRepository extends JpaRepository<Step, UUID> {
  List<Step> findByWorkmapIdOrderByPosition(UUID workmapId);
  List<Step> findByWorkmapIdAndOffRecordFalseOrderByPosition(UUID workmapId);
  Optional<Step> findByWorkmapIdAndPositionAndOffRecordFalse(UUID workmapId, int position);
}
