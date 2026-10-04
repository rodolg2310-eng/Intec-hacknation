package com.apprentice.tools;

import com.apprentice.ai.AiClient;
import com.apprentice.common.ApiException;
import com.apprentice.mastery.Mastery;
import com.apprentice.mastery.MasteryRepository;
import com.apprentice.privacy.PiiRedactor;
import com.apprentice.session.*;
import com.apprentice.workmap.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/**
 * Herramientas HTTP de capacitación conservadas para integraciones externas.
 * El Studio usa Claude para su conversación. Todo lo que sale por aqui pasa por el
 * redactor de datos personales y excluye lo marcado off_record.
 */
@RestController
@RequestMapping("/api/t/{slug}/tools")
public class ToolsController {

  private final SessionRepository sessions;
  private final StepRepository steps;
  private final GuardrailRepository guardrails;
  private final PredictionRepository predictions;
  private final InterventionRepository interventions;
  private final MasteryRepository mastery;
  private final SessionEventRepository events;
  private final DecisionMatcher matcher;
  private final PiiRedactor redactor;
  private final AiClient ai;

  public ToolsController(
      SessionRepository sessions, StepRepository steps, GuardrailRepository guardrails,
      PredictionRepository predictions, InterventionRepository interventions,
      MasteryRepository mastery, SessionEventRepository events,
      DecisionMatcher matcher, PiiRedactor redactor, AiClient ai) {
    this.sessions = sessions;
    this.steps = steps;
    this.guardrails = guardrails;
    this.predictions = predictions;
    this.interventions = interventions;
    this.mastery = mastery;
    this.events = events;
    this.matcher = matcher;
    this.redactor = redactor;
    this.ai = ai;
  }

  public record SessionRef(@NotNull UUID session_id) {}

  private TrainingSession requireSession(UUID id) {
    TrainingSession s = sessions.findById(id)
        .orElseThrow(() -> ApiException.notFound("Sesion"));
    if (s.getStatus() == TrainingSession.Status.finished) {
      throw ApiException.conflict("La sesion ya termino");
    }
    return s;
  }

  /** 1. Que paso toca ahora y que debe decidir el aprendiz. */
  @PostMapping("/get_current_step")
  public Map<String, Object> getCurrentStep(@Valid @RequestBody SessionRef body) {
    TrainingSession s = requireSession(body.session_id());
    Step step = steps
        .findByWorkmapIdAndPositionAndOffRecordFalse(s.getWorkmap().getId(), s.getCurrentStep())
        .orElseThrow(() -> ApiException.notFound("Paso"));

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("step", step.getPosition());
    out.put("title", step.getTitle());
    out.put("level", s.getLevel().name());
    out.put("screenshot_url", step.getScreenshotUrl());
    if (s.getPracticeCase() != null) {
      out.put("case_title", s.getPracticeCase().getTitle());
      out.put("case_data", redactor.redactMap(s.getPracticeCase().getData()));
    }
    // En niveles observe/guided el tutor si ve la decision del experto;
    // en predict/exam se oculta para que el aprendiz la anticipe.
    boolean reveal = s.getLevel() == TrainingSession.Level.observe
        || s.getLevel() == TrainingSession.Level.guided;
    out.put("expert_decision", reveal ? redactor.redact(step.getDecision()) : null);
    return out;
  }

  public record CheckDecisionRequest(@NotNull UUID session_id, @NotBlank String decision) {}

  /** 2. Valida la decision del aprendiz contra la del experto y los guardrails. */
  @PostMapping("/check_decision")
  public Map<String, Object> checkDecision(@Valid @RequestBody CheckDecisionRequest body) {
    TrainingSession s = requireSession(body.session_id());
    Step step = steps
        .findByWorkmapIdAndPositionAndOffRecordFalse(s.getWorkmap().getId(), s.getCurrentStep())
        .orElseThrow(() -> ApiException.notFound("Paso"));

    String expected = step.getDecision();
    if (s.getPracticeCase() != null) {
      String caseExpected = s.getPracticeCase().expectedForStep(step.getPosition());
      if (caseExpected != null) expected = caseExpected;
    }

    boolean correct = matcher.matches(body.decision(), expected);
    List<Guardrail> stepGuardrails = guardrails.findByStepIdAndOffRecordFalse(step.getId());

    String message;
    if (correct) {
      message = "Correcto. " + (step.getReasonQuote() != null
          ? "Como dijo " + step.getReasonAuthor() + ": \"" + step.getReasonQuote() + "\""
          : "");
      advance(s);
    } else {
      Guardrail hit = stepGuardrails.stream()
          .filter(g -> matcher.matches(body.decision(), g.getCondition()))
          .findFirst()
          .orElse(null);
      if (hit != null) {
        message = "Alto. " + hit.getCorrectAction()
            + (hit.getExpertQuote() != null ? " El experto lo dice asi: \"" + hit.getExpertQuote() + "\"" : "");
        interventions.save(new Intervention(s, step, hit, body.decision(), message));
      } else {
        message = "No es lo que haria el experto. "
            + (step.getReasonQuote() != null
                ? "Pista: \"" + step.getReasonQuote() + "\" (" + step.getReasonAuthor() + ")"
                : "Revisa el paso e intentalo de nuevo.");
        interventions.save(new Intervention(s, step, null, body.decision(), message));
      }
    }
    message = redactor.redact(message);

    events.save(new SessionEvent(s, null, "decision",
        Map.of("step", step.getPosition(), "correct", correct)));

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("correct", correct);
    out.put("message", message);
    out.put("next_step", s.getCurrentStep());
    out.put("session_finished", s.getStatus() == TrainingSession.Status.finished);
    return out;
  }

  public record PredictionRequest(
      @NotNull UUID session_id, @NotBlank String answer, String explanation) {}

  /** 3. Registra una prediccion (nivel predict/exam) y la califica. */
  @PostMapping("/record_prediction")
  public Map<String, Object> recordPrediction(@Valid @RequestBody PredictionRequest body) {
    TrainingSession s = requireSession(body.session_id());
    Step step = steps
        .findByWorkmapIdAndPositionAndOffRecordFalse(s.getWorkmap().getId(), s.getCurrentStep())
        .orElseThrow(() -> ApiException.notFound("Paso"));

    boolean correct = matcher.matches(body.answer(), step.getDecision());

    String feedback = null;
    if (body.explanation() != null && ai.isConfigured()) {
      JsonNode graded = ai.completeJson(
          "Eres un evaluador de formacion. Responde solo JSON: "
              + "{\"reasoning_ok\": boolean, \"feedback\": string corto}.",
          "Decision correcta del experto: " + redactor.redact(step.getDecision())
              + "\nRazon del experto: " + redactor.redact(step.getReasonQuote())
              + "\nExplicacion del aprendiz: " + redactor.redact(body.explanation()));
      if (graded != null) {
        feedback = graded.path("feedback").asText(null);
        if (!graded.path("reasoning_ok").asBoolean(true)) correct = false;
      }
    }
    if (feedback == null) {
      feedback = correct
          ? "Bien anticipado."
          : "El experto decidio: " + redactor.redact(step.getDecision());
    }

    Prediction p = new Prediction();
    p.setSession(s);
    p.setStep(step);
    p.setAnswer(body.answer());
    p.setExplanation(body.explanation());
    p.setCorrect(correct);
    p.setFeedback(feedback);
    predictions.save(p);

    boolean isNewCase = s.getPracticeCase() != null
        && s.getPracticeCase().getNovelty() == com.apprentice.cases.PracticeCase.Novelty.new_;
    Mastery m = mastery
        .findByLearnerIdAndStepId(s.getLearner().getId(), step.getId())
        .orElseGet(() -> new Mastery(s.getLearner(), step));
    m.record(correct, isNewCase);
    mastery.save(m);

    advance(s);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("correct", correct);
    out.put("feedback", redactor.redact(feedback));
    out.put("expert_decision", redactor.redact(step.getDecision()));
    out.put("streak", m.getStreak());
    out.put("mastered", m.isMastered());
    return out;
  }

  /** 4. Devuelve el momento exacto del recording donde el experto explica este paso. */
  @PostMapping("/get_expert_moment")
  public Map<String, Object> getExpertMoment(@Valid @RequestBody SessionRef body) {
    TrainingSession s = requireSession(body.session_id());
    Step step = steps
        .findByWorkmapIdAndPositionAndOffRecordFalse(s.getWorkmap().getId(), s.getCurrentStep())
        .orElseThrow(() -> ApiException.notFound("Paso"));

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("step", step.getPosition());
    out.put("title", step.getTitle());
    out.put("screen_ts", step.getScreenTs());
    out.put("reason_ts", step.getReasonTs());
    out.put("quote", redactor.redact(step.getReasonQuote()));
    out.put("author", step.getReasonAuthor());
    return out;
  }

  /** 5. Cierra la sesion con puntaje y que practicar despues. */
  @PostMapping("/finish_session")
  @org.springframework.transaction.annotation.Transactional
  public Map<String, Object> finishSession(@Valid @RequestBody SessionRef body) {
    TrainingSession s = sessions.findById(body.session_id())
        .orElseThrow(() -> ApiException.notFound("Sesion"));
    if (s.getStatus() == TrainingSession.Status.finished) {
      return Map.of("score", s.getScore(), "summary", s.getSummary());
    }

    List<Prediction> preds = predictions.findBySessionIdOrderByCreatedAt(s.getId());
    List<Intervention> ints = interventions.findBySessionIdOrderByCreatedAt(s.getId());
    long correct = preds.stream().filter(Prediction::isCorrect).count();
    double score = preds.isEmpty() ? 0 : (100.0 * correct) / preds.size();

    List<String> toPractice = mastery.findByLearnerId(s.getLearner().getId()).stream()
        .filter(m -> !m.isMastered())
        .map(m -> m.getStep().getTitle())
        .toList();

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("predictions", preds.size());
    summary.put("correct", correct);
    summary.put("interventions", ints.size());
    summary.put("to_practice", toPractice);

    s.setScore(score);
    s.setSummary(summary);
    s.setStatus(TrainingSession.Status.finished);
    s.setFinishedAt(OffsetDateTime.now());
    sessions.save(s);

    return Map.of("score", score, "summary", summary);
  }

  private void advance(TrainingSession s) {
    int total = steps.findByWorkmapIdAndOffRecordFalseOrderByPosition(s.getWorkmap().getId()).size();
    if (s.getCurrentStep() >= total) {
      s.setStatus(TrainingSession.Status.finished);
      s.setFinishedAt(OffsetDateTime.now());
    } else {
      s.setCurrentStep(s.getCurrentStep() + 1);
    }
    sessions.save(s);
  }
}
