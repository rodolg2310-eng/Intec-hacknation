package com.apprentice.workmap;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuardrailRepository extends JpaRepository<Guardrail, UUID> {
  List<Guardrail> findByWorkmapId(UUID workmapId);
  List<Guardrail> findByWorkmapIdAndOffRecordFalse(UUID workmapId);
  List<Guardrail> findByStepIdAndOffRecordFalse(UUID stepId);
}
