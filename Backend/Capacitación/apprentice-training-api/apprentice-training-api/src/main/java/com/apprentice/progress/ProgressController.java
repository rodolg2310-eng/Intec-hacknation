package com.apprentice.progress;

import com.apprentice.common.ApiException;
import com.apprentice.learner.Learner;
import com.apprentice.learner.LearnerRepository;
import com.apprentice.mastery.Mastery;
import com.apprentice.mastery.MasteryRepository;
import com.apprentice.workmap.Step;
import com.apprentice.workmap.StepRepository;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Progreso por alumno: dominio por paso y nivel sugerido. */
@RestController
@RequestMapping("/api/t/{slug}/learners/{learnerId}/progress")
public class ProgressController {

  private final LearnerRepository learners;
  private final MasteryRepository mastery;
  private final StepRepository steps;

  public ProgressController(
      LearnerRepository learners, MasteryRepository mastery, StepRepository steps) {
    this.learners = learners;
    this.mastery = mastery;
    this.steps = steps;
  }

  @GetMapping
  @org.springframework.transaction.annotation.Transactional(readOnly = true)
  public Map<String, Object> progress(@PathVariable UUID learnerId, @RequestParam UUID workmap_id) {
    Learner learner = learners.findById(learnerId)
        .orElseThrow(() -> ApiException.notFound("Alumno"));
    List<Step> allSteps = steps.findByWorkmapIdAndOffRecordFalseOrderByPosition(workmap_id);
    Map<UUID, Mastery> byStep = new HashMap<>();
    for (Mastery m : mastery.findByLearnerId(learner.getId())) {
      byStep.put(m.getStep().getId(), m);
    }

    int mastered = 0;
    List<Map<String, Object>> perStep = new ArrayList<>();
    for (Step s : allSteps) {
      Mastery m = byStep.get(s.getId());
      boolean isMastered = m != null && m.isMastered();
      if (isMastered) mastered++;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("step", s.getPosition());
      row.put("title", s.getTitle());
      row.put("attempts", m == null ? 0 : m.getAttempts());
      row.put("streak", m == null ? 0 : m.getStreak());
      row.put("mastered", isMastered);
      row.put("next_due", m == null || m.getNextDue() == null ? null : m.getNextDue().toString());
      perStep.add(row);
    }

    String suggestedLevel;
    if (allSteps.isEmpty()) suggestedLevel = "observe";
    else if (mastered == allSteps.size()) suggestedLevel = "exam";
    else if (mastered == 0) suggestedLevel = "observe";
    else if (mastered * 2 < allSteps.size()) suggestedLevel = "predict";
    else suggestedLevel = "guided";

    return Map.of(
        "learner", learner.getName(),
        "steps_mastered", mastered,
        "steps_total", allSteps.size(),
        "suggested_level", suggestedLevel,
        "steps", perStep);
  }
}
