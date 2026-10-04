import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { MapPanel } from "@/components/studio/map-panel";
import type { StudioSession } from "@/lib/api";
describe("English evidence summary", () => {
  it("renders the requested rows and translated quote provenance", () => {
    const session: StudioSession = { id: "test", title: "Invoices", expert: "Sabine", phase: "confirmed", createdAt: "2026-01-01", liveQuestions: 3, guardrailQuestions: 1, debriefQuestions: 3, debriefAnswers: 3, mapConfirmed: true, messages: [], events: [{ id: "event", ts: "03:12", title: "Classify invoice", description: "Invoice 4471", decision: "Capex", screenUrl: "/screen", possibleGuardrail: "Asset number", elapsedSeconds: 192 }], map: { title: "Invoices", summary: "Classify with evidence", teachback: "Verify before booking", gaps: [], steps: [{ position: 1, title: "Classify invoice", event_id: "event", decision: "Use capex", reason_quote: "Equipment over EUR 5,000 is capex.", risk: "high", translated: true, source_language: "es", quoteSeconds: 195 }], guardrails: [{ kind: "stop_and_ask", condition: "No asset number", correct_action: "Stop booking", expert_quote: "No asset number, no capex booking.", step_position: 1 }] } };
    render(<MapPanel session={session} learner busy={false} action={async () => {}} onCapture={() => {}} onPractice={() => {}} />);
    for (const label of ["Example step", "Screen moment", "Decision", "Reason", "Guardrails", "Required", "Teach-back"]) expect(screen.getByRole("rowheader", { name: label })).toBeInTheDocument();
    expect(screen.getByText(/Translated from es/)).toBeInTheDocument(); expect(screen.getByText(/no audio or video was recorded/)).toBeInTheDocument(); expect(screen.queryByText("Confirm & publish for learners")).not.toBeInTheDocument();
  });
});
