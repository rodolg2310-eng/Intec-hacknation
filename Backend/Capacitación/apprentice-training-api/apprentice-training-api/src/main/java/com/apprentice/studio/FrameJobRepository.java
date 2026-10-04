package com.apprentice.studio;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FrameJobRepository extends JpaRepository<FrameJob,UUID> {
  List<FrameJob> findBySessionIdOrderBySequence(UUID sessionId);
  Optional<FrameJob> findBySessionIdAndSequence(UUID sessionId,long sequence);
}
