package com.apprentice.sessions;

import com.apprentice.cases.CaseRepository;
import com.apprentice.cases.PracticeCase;
import com.apprentice.common.ApiException;
import com.apprentice.learner.Learner;
import com.apprentice.learner.LearnerRepository;
import com.apprentice.session.SessionRepository;
import com.apprentice.session.TrainingSession;
import com.apprentice.workmap.Workmap;
import com.apprentice.workmap.WorkmapRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/t/{slug}")
public class SessionsController {

  private final LearnerRepository learners;
  private final WorkmapRepository workmaps;
  private final CaseRepository cases;
  private final SessionRepository sessions;

  public SessionsController(
      LearnerRepository learners, WorkmapRepository workmaps,
      CaseRepository cases, SessionRepository sessions) {
    this.learners = learners;
    this.workmaps = workmaps;
    this.cases = cases;
    this.sessions = sessions;
  }

  public record LearnerRequest(@NotBlank String name, String external_id) {}

  @PostMapping("/learners")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> createLearner(@Valid @RequestBody LearnerRequest body) {
    Learner learner = learners.save(new Learner(body.name(), body.external_id()));
    return Map.of("learner_id", learner.getId(), "name", learner.getName());
  }

  @GetMapping("/learners")
  public List<Map<String, Object>> listLearners() {
    return learners.findAll().stream()
        .map(l -> Map.<String, Object>of(
            "id", l.getId(), "name", l.getName(), "external_id",
            l.getExternalId() == null ? "" : l.getExternalId()))
        .toList();
  }

  public record SessionRequest(
      @NotNull UUID learner_id,
      @NotNull UUID workmap_id,
      UUID case_id,
      String level) {}

  @PostMapping("/sessions")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> createSession(@Valid @RequestBody SessionRequest body) {
    Learner learner = learners.findById(body.learner_id())
        .orElseThrow(() -> ApiException.notFound("Alumno"));
    Workmap wm = workmaps.findById(body.workmap_id())
        .orElseThrow(() -> ApiException.notFound("Work Map"));

    TrainingSession s = new TrainingSession();
    s.setLearner(learner);
    s.setWorkmap(wm);
    if (body.case_id() != null) {
      PracticeCase c = cases.findById(body.case_id())
          .orElseThrow(() -> ApiException.notFound("Caso"));
      s.setPracticeCase(c);
    }
    if (body.level() != null) s.setLevel(TrainingSession.Level.valueOf(body.level()));
    sessions.save(s);

    return Map.of(
        "session_id", s.getId(),
        "level", s.getLevel().name(),
        "current_step", s.getCurrentStep());
  }

  @GetMapping("/sessions/{id}")
  public Map<String, Object> getSession(@PathVariable UUID id) {
    TrainingSession s = sessions.findById(id)
        .orElseThrow(() -> ApiException.notFound("Sesion"));
    return Map.of(
        "id", s.getId(),
        "level", s.getLevel().name(),
        "status", s.getStatus().name(),
        "current_step", s.getCurrentStep(),
        "score", s.getScore() == null ? -1 : s.getScore());
  }
}
