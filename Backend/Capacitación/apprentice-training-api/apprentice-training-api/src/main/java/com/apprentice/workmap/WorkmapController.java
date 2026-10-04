package com.apprentice.workmap;

import com.apprentice.common.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/t/{slug}/workmaps")
public class WorkmapController {

  private final WorkmapRepository workmaps;
  private final StepRepository steps;
  private final GuardrailRepository guardrails;

  public WorkmapController(
      WorkmapRepository workmaps, StepRepository steps, GuardrailRepository guardrails) {
    this.workmaps = workmaps;
    this.steps = steps;
    this.guardrails = guardrails;
  }

  @GetMapping
  public List<Map<String, Object>> list() {
    return workmaps.findAllByOrderByCreatedAtDesc().stream()
        .map(w -> Map.<String, Object>of(
            "id", w.getId(),
            "title", w.getTitle(),
            "role", w.getRole() == null ? "" : w.getRole()))
        .toList();
  }

  /** Exporta el Work Map completo (sin pasos off_record) para el tutor. */
  @GetMapping("/{id}")
  public Map<String, Object> get(@PathVariable UUID id) {
    Workmap w = workmaps.findById(id).orElseThrow(() -> ApiException.notFound("Work Map"));
    List<Map<String, Object>> stepList = steps
        .findByWorkmapIdAndOffRecordFalseOrderByPosition(id).stream()
        .map(s -> {
          Map<String, Object> m = new java.util.LinkedHashMap<>();
          m.put("position", s.getPosition());
          m.put("title", s.getTitle());
          m.put("decision", s.getDecision());
          m.put("reason_quote", s.getReasonQuote());
          m.put("reason_author", s.getReasonAuthor());
          m.put("reason_ts", s.getReasonTs());
          m.put("risk", s.getRisk().name());
          return m;
        })
        .toList();
    List<Map<String, Object>> guardList = guardrails
        .findByWorkmapIdAndOffRecordFalse(id).stream()
        .map(g -> Map.<String, Object>of(
            "kind", g.getKind().name(),
            "condition", g.getCondition(),
            "correct_action", g.getCorrectAction(),
            "expert_quote", g.getExpertQuote() == null ? "" : g.getExpertQuote()))
        .toList();
    return Map.of(
        "id", w.getId(),
        "title", w.getTitle(),
        "role", w.getRole() == null ? "" : w.getRole(),
        "expert", w.getExpertName() == null ? "" : w.getExpertName(),
        "language", w.getLanguage(),
        "steps", stepList,
        "guardrails", guardList);
  }
}
