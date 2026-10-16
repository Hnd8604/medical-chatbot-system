import { api } from "@/shared/api/client";
import type { DateRange } from "@/shared/lib/dates";
import type { ChatMessage, ChatRequest, ChatResponse, ChatSession, ExportFormat, MessageFeedback } from "./types";

const SESSION_LIST_LIMIT = 30;

export const chatKeys = {
  sessions: () => ["chat", "sessions"] as const,
  sessionList: (query: string) => [...chatKeys.sessions(), query] as const,
};

function feedbackPath(messageId: string) {
  return `/chat/messages/${encodeURIComponent(messageId)}/feedback`;
}

function sessionPath(sessionId: string) {
  return `/chat/sessions/${encodeURIComponent(sessionId)}`;
}

export const chatApi = {
  send: (body: ChatRequest) => api.post<ChatResponse>("/chat", body),

  sessions: (query: string) => api.get<ChatSession[]>("/chat/sessions", { query, limit: SESSION_LIST_LIMIT }),
  messages: (sessionId: string) => api.get<ChatMessage[]>(`${sessionPath(sessionId)}/messages`),
  rename: (sessionId: string, title: string) => api.patch<{ id: string; title: string }>(sessionPath(sessionId), { title }),
  remove: (sessionId: string) => api.delete(sessionPath(sessionId)),

  rate: (messageId: string, feedback: MessageFeedback) => api.post(feedbackPath(messageId), feedback),
  updateRating: (messageId: string, feedback: MessageFeedback) => api.put(feedbackPath(messageId), feedback),
  removeRating: (messageId: string) => api.delete(feedbackPath(messageId)),

  /** Every session created in the range, as one PDF or CSV file. */
  exportHistory: (range: DateRange, format: ExportFormat) =>
    api.download("/chat/sessions/export", { ...range, format }, `lich_su_hoi_thoai_${range.from}_${range.to}.${format}`),
};
