package com.apprentice.ingest;

import com.apprentice.cases.CaseRepository;
import com.apprentice.cases.PracticeCase;
import com.apprentice.session.SessionEvent;
import com.apprentice.session.SessionEventRepository;
import com.apprentice.session.SessionRepository;
import com.apprentice.session.TrainingSession;
import com.apprentice.common.ApiException;
import com.apprentice.workmap.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Punto de entrada de informacion por empresa. El backend no decide como
 * entra cada empresa: cualquier sistema externo publica aqui el Work Map
 * (resultado del recording), casos de practica y eventos de sesiones.
 */
@RestController
@RequestMapping("/api/t/{slug}/ingest")
public class IngestController {

  private final WorkmapRepository workmaps;
  private final StepRepository steps;
  private final GuardrailRepository guardrails;
  private final CaseRepository cases;
  private final SessionRepository sessions;
  private final SessionEventRepository events;

  public IngestController(
      WorkmapRepository workmaps, StepRepository steps, GuardrailRepository guardrails,
      CaseRepository cases, SessionRepository sessions, SessionEventRepository events) {
    this.workmaps = workmaps;
    this.steps = steps;
    this.guardrails = guardrails;
    this.cases = cases;
    this.sessions = sessions;
    this.events = events;
  }

  // ---- Contrato del Work Map (lo que produce el modulo de recording) ----

  public record StepInput(
      @NotNull Integer position,
      @NotBlank String title,
      String screen_ts,
      String screenshot_url,
      String decision,
      String reason_quote,
      String reason_author,
      String reason_ts,
      String risk,
      Boolean off_record) {}

  public record GuardrailInput(
      @NotBlank String kind,
      @NotBlank String condition,
      @NotBlank String correct_action,
      String expert_quote,
      String expert_ts,
      Integer step_position,
      Boolean off_record) {}

  public record WorkMapInput(
      @NotBlank String title,
      String role,
      String onet_code,
      String expert_name,
      String language,
      String summary,
      @NotNull List<@Valid StepInput> steps,
      List<@Valid GuardrailInput> guardrails) {}

  @PostMapping("/workmaps")
  @org.springframework.transaction.annotation.Transactional
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> ingestWorkmap(@Valid @RequestBody WorkMapInput input) {
    Workmap wm = new Workmap();
    wm.setTitle(input.title());
    wm.setRole(input.role());
    wm.setOnetCode(input.onet_code());
    wm.setExpertName(input.expert_name());
    wm.setLanguage(input.language() != null ? input.language() : "es");
    wm.setSummary(input.summary());
    workmaps.save(wm);

    Map<Integer, Step> byPosition = new java.util.HashMap<>();
    for (StepInput s : input.steps()) {
      Step step = new Step();
      step.setWorkmap(wm);
      step.setPosition(s.position());
      step.setTitle(s.title());
      step.setScreenTs(s.screen_ts());
      step.setScreenshotUrl(s.screenshot_url());
      step.setDecision(s.decision());
      step.setReasonQuote(s.reason_quote());
      step.setReasonAuthor(s.reason_author());
      step.setReasonTs(s.reason_ts());
      if (s.risk() != null) step.setRisk(Step.Risk.valueOf(s.risk()));
      step.setOffRecord(Boolean.TRUE.equals(s.off_record()));
      steps.save(step);
      byPosition.put(s.position(), step);
    }

    if (input.guardrails() != null) {
      for (GuardrailInput g : input.guardrails()) {
        Guardrail gr = new Guardrail();
        gr.setWorkmap(wm);
        if (g.step_position() != null) gr.setStep(byPosition.get(g.step_position()));
        gr.setKind(Guardrail.Kind.valueOf(g.kind()));
        gr.setCondition(g.condition());
        gr.setCorrectAction(g.correct_action());
        gr.setExpertQuote(g.expert_quote());
        gr.setExpertTs(g.expert_ts());
        gr.setOffRecord(Boolean.TRUE.equals(g.off_record()));
        guardrails.save(gr);
      }
    }

    return Map.of("workmap_id", wm.getId(), "steps", input.steps().size());
  }

  public record CaseInput(
      @NotBlank String title,
      @NotNull UUID workmap_id,
      @NotBlank String kind,
      String novelty,
      Integer difficulty,
      @NotNull Map<String, Object> data,
      @NotNull List<PracticeCase.ExpectedDecision> expected) {}

  @PostMapping("/cases")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> ingestCase(@Valid @RequestBody CaseInput input) {
    Workmap wm = workmaps.findById(input.workmap_id())
        .orElseThrow(() -> ApiException.notFound("Work Map"));
    PracticeCase c = new PracticeCase();
    c.setWorkmap(wm);
    c.setTitle(input.title());
    c.setKind(PracticeCase.Kind.valueOf(input.kind()));
    if (input.novelty() != null) {
      // "new" es palabra reservada en Java; en la API se escribe "new" y aqui se traduce.
      String n = input.novelty().equals("new") ? "new_" : input.novelty();
      c.setNovelty(PracticeCase.Novelty.valueOf(n));
    }
    if (input.difficulty() != null) c.setDifficulty(input.difficulty());
    c.setData(input.data());
    c.setExpected(input.expected());
    cases.save(c);
    return Map.of("case_id", c.getId());
  }

  public record EventInput(
      @NotNull UUID session_id, String ts, @NotBlank String type, Map<String, Object> payload) {}

  @PostMapping("/events")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> ingestEvent(@Valid @RequestBody EventInput input) {
    TrainingSession session = sessions.findById(input.session_id())
        .orElseThrow(() -> ApiException.notFound("Sesion"));
    SessionEvent event = new SessionEvent(
        session, input.ts(), input.type(), input.payload() != null ? input.payload() : Map.of());
    events.save(event);
    return Map.of("event_id", event.getId());
  }
}
