package com.apprentice.cases;

import com.apprentice.ai.AiClient;
import com.apprentice.common.ApiException;
import com.apprentice.privacy.PiiRedactor;
import com.apprentice.workmap.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/t/{slug}/cases")
public class CasesController {

  private final CaseRepository cases;
  private final WorkmapRepository workmaps;
  private final StepRepository steps;
  private final GuardrailRepository guardrails;
  private final AiClient ai;
  private final PiiRedactor redactor;
  private final ObjectMapper mapper = new ObjectMapper();

  public CasesController(
      CaseRepository cases, WorkmapRepository workmaps, StepRepository steps,
      GuardrailRepository guardrails, AiClient ai, PiiRedactor redactor) {
    this.cases = cases;
    this.workmaps = workmaps;
    this.steps = steps;
    this.guardrails = guardrails;
    this.ai = ai;
    this.redactor = redactor;
  }

  @GetMapping
  public List<Map<String, Object>> list(@RequestParam UUID workmap_id) {
    return cases.findByWorkmapIdOrderByDifficulty(workmap_id).stream()
        .map(c -> Map.<String, Object>of(
            "id", c.getId(),
            "title", c.getTitle(),
            "kind", c.getKind().name(),
            "novelty", c.getNovelty() == PracticeCase.Novelty.new_ ? "new" : "seen",
            "difficulty", c.getDifficulty()))
        .toList();
  }

  @GetMapping("/{id}")
  public Map<String, Object> get(@PathVariable UUID id) {
    PracticeCase c = cases.findById(id).orElseThrow(() -> ApiException.notFound("Caso"));
    return Map.of(
        "id", c.getId(),
        "title", c.getTitle(),
        "kind", c.getKind().name(),
        "novelty", c.getNovelty() == PracticeCase.Novelty.new_ ? "new" : "seen",
        "difficulty", c.getDifficulty(),
        "data", redactor.redactMap(c.getData()));
  }

  public record GenerateRequest(@NotNull UUID workmap_id, String kind, Integer count) {}

  /** Genera casos nuevos con IA a partir del Work Map (novelty = new). */
  @PostMapping("/generate")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> generate(@RequestBody GenerateRequest body) {
    Workmap wm = workmaps.findById(body.workmap_id())
        .orElseThrow(() -> ApiException.notFound("Work Map"));
    int count = body.count() == null ? 1 : Math.min(body.count(), 5);
    String kind = body.kind() == null ? "happy" : body.kind();

    List<Step> stepList = steps.findByWorkmapIdAndOffRecordFalseOrderByPosition(wm.getId());
    StringBuilder context = new StringBuilder();
    for (Step s : stepList) {
      context.append("Paso ").append(s.getPosition()).append(": ").append(s.getTitle())
          .append(" | decision: ").append(redactor.redact(s.getDecision())).append("\n");
    }
    for (Guardrail g : guardrails.findByWorkmapIdAndOffRecordFalse(wm.getId())) {
      context.append("Guardrail (").append(g.getKind()).append("): ")
          .append(redactor.redact(g.getCondition())).append(" -> ")
          .append(redactor.redact(g.getCorrectAction())).append("\n");
    }

    JsonNode generated = ai.completeJson(
        "Generas casos de practica para capacitacion. Responde solo JSON: "
            + "{\"cases\": [{\"title\": string, \"data\": object, "
            + "\"expected\": [{\"step\": number, \"decision\": string}]}]}",
        "Proceso: " + wm.getTitle() + "\n" + context
            + "\nGenera " + count + " caso(s) de tipo " + kind
            + ". Los casos deben ser realistas y distintos a los ejemplos.");

    List<UUID> created = new ArrayList<>();
    if (generated != null && generated.path("cases").isArray()) {
      for (JsonNode node : generated.path("cases")) {
        PracticeCase c = new PracticeCase();
        c.setWorkmap(wm);
        c.setTitle(node.path("title").asText("Caso generado"));
        c.setKind(PracticeCase.Kind.valueOf(kind));
        c.setNovelty(PracticeCase.Novelty.new_);
        c.setDifficulty(2);
        c.setData(mapper.convertValue(node.path("data"), Map.class));
        c.setExpected(mapper.convertValue(node.path("expected"),
            mapper.getTypeFactory().constructCollectionType(
                List.class, PracticeCase.ExpectedDecision.class)));
        cases.save(c);
        created.add(c.getId());
      }
    }
    if (created.isEmpty()) {
      throw new ApiException(HttpStatus.BAD_GATEWAY,
          "Anthropic no genero casos; revisa ANTHROPIC_API_KEY y ANTHROPIC_MODEL");
    }
    return Map.of("created", created);
  }
}
