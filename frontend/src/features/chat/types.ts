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
  createdAt: string;
  updatedAt: string;
  activePatientId: string | null;
  messageCount: number;
  lastMessagePreview: string | null;
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
  responseStatus?: ResponseStatus | null;
  /** chatbot-service log id, shown so users can report problems. */
  traceId?: string | null;
  answerSource?: string | null;
  grounding?: GroundingInfo | null;
  /** Set when the LLM gateway was unavailable and the answer came from templates. */
  llmUnavailableReason?: string | null;
}

export interface ChatMessage extends ResponseOutcome {
  id: string;
  role: "user" | "assistant";
  content: string;
  createdAt: string;
  feedback: MessageFeedback | null;
}

export interface ChatRequest {
  sessionId: string | null;
  /** Doctors and admins only; USER accounts are always scoped to their own record. */
  patientId: string | null;
  message: string;
}

export interface ChatResponse extends ResponseOutcome {
  sessionId: string;
  messageId: string | null;
  answer: string;
  intent: string | null;
  toolName: string | null;
  intentSource: string | null;
  answerReason: string | null;
  patientId: string | null;
  observationType: string | null;
  allPatients: boolean | null;
  needsPatientSelection: boolean | null;
  patientCandidates: unknown;
  pendingQuestion: string | null;
  evidence: unknown;
  agentRoute: string | null;
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
