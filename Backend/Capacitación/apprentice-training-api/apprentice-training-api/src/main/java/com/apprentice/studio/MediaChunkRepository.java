package com.apprentice.studio;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MediaChunkRepository extends JpaRepository<MediaChunk,UUID> {
  List<MediaChunk> findBySessionIdOrderByStartSecondsAscSequenceAsc(UUID id);
  Optional<MediaChunk> findBySessionIdAndRecordingIdAndSequence(UUID sessionId,UUID recordingId,long sequence);
}
