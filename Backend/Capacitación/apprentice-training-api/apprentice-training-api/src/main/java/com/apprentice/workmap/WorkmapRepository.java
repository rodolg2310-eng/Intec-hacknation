package com.apprentice.workmap;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkmapRepository extends JpaRepository<Workmap, UUID> {
  List<Workmap> findAllByOrderByCreatedAtDesc();
}
