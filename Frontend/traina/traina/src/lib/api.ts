export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...options,
    credentials: "same-origin",
    headers: { "Content-Type": "application/json", ...options.headers },
  });
  if (!response.ok) {
    const body = await response
      .json()
      .catch(() => ({ error: "Could not connect to the server." }));
    throw new ApiError(
      body.error || body.message || "The request could not be completed.",
      response.status,
    );
  }
  return response.json() as Promise<T>;
}
export const post = <T>(path: string, body: unknown = {}, options: RequestInit = {}) =>
  api<T>(path, { ...options, method: "POST", body: JSON.stringify(body) });
export type Account = {
  id: string;
  role: "MASTER" | "SENIOR" | "LEARNER";
  name: string;
  email: string;
  workspace: string;
  slug: string;
  demo: boolean;
};
export type ScreenEvent = {
  id: string;
  ts: string;
  title: string;
  description: string;
  decision: string;
  screenUrl: string;
  possibleGuardrail: string;
  elapsedSeconds?: number;
  sequence?: number;
};
export type Message = {
  id: string;
  role: "user" | "assistant";
  text: string;
  ts: string;
  stage: string;
  eventId: string;
  questionType: string;
  status?: "pending" | "delivered" | "answered" | "deferred";
  elapsedSeconds?: number;
  translated?: boolean;
  sourceLanguage?: string;
  voiceProvider?: string;
  voiceConversationId?: string;
  voiceAgentId?: string;
};
export type MapStep = {
  position: number;
  title: string;
  event_id: string;
  decision: string;
  reason_quote: string;
  risk: string;
  translated?: boolean;
  quoteSeconds?: number;
  source_language?: string;
  source_kind?: string;
  expert_name?: string;
  source_message_id?: string;
};
export type Guardrail = {
  expert_name?: string;
  source_kind?: string;
  kind: string;
  condition: string;
  correct_action: string;
  expert_quote: string;
  step_position: number;
  translated?: boolean;
  quoteSeconds?: number;
};
export type WorkMap = {
  title: string;
  summary: string;
  teachback: string;
  gaps: string[];
  steps: MapStep[];
  guardrails: Guardrail[];
};
export type Practice = {
  id: string;
  title: string;
  scenario: string;
  facts: { label: string; value: string }[];
  choices: { id: string; label: string }[];
};
export type Validation = {
  allowed: boolean;
  feedback: string;
  expert_quote: string;
  step_position: number;
  mastered: string;
  next_practice: string;
  score: number;
  choiceId: string;
};
export type StudioSession = {
  id: string;
  title: string;
  expert: string;
  phase: "capture" | "finishing" | "debrief" | "review" | "confirmed";
  createdAt: string;
  events: ScreenEvent[];
  messages: Message[];
  liveQuestions: number;
  guardrailQuestions: number;
  debriefQuestions: number;
  debriefAnswers: number;
  mapConfirmed: boolean;
  map?: WorkMap;
  workmapId?: string;
  practice?: Practice;
  validation?: Validation;
  practiceSaved?: boolean;
  completedPractices?: number;
  lastScore?: number;
  demo?: boolean;
  activeSeconds?: number;
  pendingQuestionId?: string;
  publicationVersion?: number;
  context?: ActivityContext;
  recordings?: Recording[];
  legacyRecording?: boolean;
  learningMessages?: Message[];
  doubts?: { id: string; text: string; eventId: string; elapsedSeconds: number; status: string }[];
  questionWindows?: { index: number; delivered: number; status: string }[];
  pipeline?: { received: number; nextSequence?: number; missingSequence?: number; pending: number; processed: number; failed: number };
  analysisError?: string;
  questionError?: string;
  processingError?: string;
  mediaPending?: boolean;
  mediaSeconds?: number;
  recordingIncomplete?: boolean;
  cameraContext?: { nextSequence: number; elapsedSeconds?: number };
  openRecordings?: { id: string; kind: string; startSeconds: number }[];
};
export type ActivityContext = { title: string; objective: string; application: string; initialState: string; expectedOutcome: string; prerequisites: string; rules: string; risks: string };
export type Recording = { id: string; kind: string; startSeconds: number; endSeconds: number; url: string };
export function timestamp(seconds: number) { return `${Math.floor(seconds / 60).toString().padStart(2, "0")}:${Math.floor(seconds % 60).toString().padStart(2, "0")}`; }
export type SessionSummary = Pick<
  StudioSession,
  "id" | "title" | "expert" | "phase" | "createdAt"
> & { updatedAt: string };
export function studioPath(slug: string, suffix = "") {
  return "/api/t/" + encodeURIComponent(slug) + "/studio" + suffix;
}
export function download(name: string, content: string, type = "application/json") {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
