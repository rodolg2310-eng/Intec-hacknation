package com.apprentice.tools;

import com.apprentice.common.ApiException;
import com.apprentice.privacy.PiiRedactor;
import com.apprentice.workmap.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Exporta instrucciones de referencia del tutor para un Work Map.
 * El Studio usa Claude directamente; ElevenLabs únicamente sintetiza texto.
 */
@RestController
@RequestMapping("/api/t/{slug}/agent-prompt")
public class AgentPromptController {

  private final WorkmapRepository workmaps;
  private final StepRepository steps;
  private final GuardrailRepository guardrails;
  private final PiiRedactor redactor;

  public AgentPromptController(
      WorkmapRepository workmaps, StepRepository steps,
      GuardrailRepository guardrails, PiiRedactor redactor) {
    this.workmaps = workmaps;
    this.steps = steps;
    this.guardrails = guardrails;
    this.redactor = redactor;
  }

  @GetMapping("/{workmapId}")
  public String prompt(@PathVariable UUID workmapId) {
    Workmap wm = workmaps.findById(workmapId)
        .orElseThrow(() -> ApiException.notFound("Work Map"));

    StringBuilder sb = new StringBuilder();
    sb.append("Eres el Tutor de capacitacion del proceso \"").append(wm.getTitle()).append("\".\n");
    if (wm.getExpertName() != null) {
      sb.append("El conocimiento viene de ").append(wm.getExpertName()).append(".\n");
    }
    sb.append("\nReglas:\n");
    sb.append("- Guia al aprendiz paso a paso con voz natural y breve.\n");
    sb.append("- Llama a get_current_step al inicio y tras cada acierto.\n");
    sb.append("- Cuando el aprendiz diga que haria, llama a check_decision (niveles observe/guided) ");
    sb.append("o record_prediction (niveles predict/exam).\n");
    sb.append("- Si duda, ofrece get_expert_moment para escuchar al experto.\n");
    sb.append("- Nunca inventes decisiones: usa solo las del Work Map.\n");
    sb.append("- Al terminar, llama a finish_session y resume el puntaje.\n");
    sb.append("\nProceso (").append(wm.getLanguage()).append("):\n");
    for (Step s : steps.findByWorkmapIdAndOffRecordFalseOrderByPosition(wm.getId())) {
      sb.append(s.getPosition()).append(". ").append(s.getTitle())
          .append(" -> ").append(redactor.redact(s.getDecision())).append("\n");
    }
    sb.append("\nGuardrails:\n");
    for (Guardrail g : guardrails.findByWorkmapIdAndOffRecordFalse(wm.getId())) {
      sb.append("- [").append(g.getKind()).append("] Si ")
          .append(redactor.redact(g.getCondition())).append(" entonces ")
          .append(redactor.redact(g.getCorrectAction())).append("\n");
    }
    return sb.toString();
  }
}
