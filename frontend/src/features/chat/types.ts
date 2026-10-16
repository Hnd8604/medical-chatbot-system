import type { PatientCandidate } from "@/features/patients/types";

export type ExportFormat = "pdf" | "csv";

export type ResponseStatus =
  | "answered"
  | "partial"
  | "no_data"
  | "clarification_needed"
  | "patient_selection_needed"
  | "tool_error";

export interface ChatSession {
  id: string;
  title: string | null;
  created_at: string;
  updated_at: string;
  active_patient_id: string | null;
  message_count: number;
  last_message_preview: string | null;
}

export interface MessageFeedback {
  rating: number;
  comment: string | null;
}

export interface GroundingInfo {
  checked: boolean;
  passed: boolean | null;
}

/** How a turn was answered; present on fresh answers and on assistant messages loaded from history. */
export interface ResponseOutcome {
  response_status?: ResponseStatus | null;
  /** chatbot-service log id, shown so users can report problems. */
  trace_id?: string | null;
  answer_source?: string | null;
  grounding?: GroundingInfo | null;
  /** Set when the LLM gateway was unavailable and the answer came from templates. */
  llm_unavailable_reason?: string | null;
}

export interface ChatMessage extends ResponseOutcome {
  id: string;
  role: "user" | "assistant";
  content: string;
  created_at: string;
  feedback: MessageFeedback | null;
}

export interface ChatRequest {
  session_id: string | null;
  /** Doctors and admins only; USER accounts are always scoped to their own record. */
  patient_id: string | null;
  message: string;
}

export interface ChatResponse extends ResponseOutcome {
  session_id: string;
  message_id: string | null;
  answer: string;
  intent: string | null;
  tool_name: string | null;
  intent_source: string | null;
  answer_reason: string | null;
  patient_id: string | null;
  observation_type: string | null;
  all_patients: boolean | null;
  needs_patient_selection: boolean | null;
  patient_candidates: unknown;
  pending_question: string | null;
  evidence: unknown;
  agent_route: string | null;
}

/** A message as shown in the conversation, including local-only pending and error bubbles. */
export interface MessageView {
  id: string;
  role: "user" | "assistant" | "error";
  content: string;
  createdAt?: string;
  pending?: boolean;
  /** False for answers the server did not persist, which therefore cannot be rated. */
  rateable?: boolean;
  outcome?: ResponseOutcome;
  feedback?: MessageFeedback | null;
  /** Present when the answer asks the doctor to choose a patient first. */
  patientChoice?: {
    candidates: PatientCandidate[];
    raw: unknown;
    pendingQuestion: string | null;
  };
}
