import { useState } from "react";
import { parsePatientCandidates } from "@/features/patients/fhir";
import { chatApi } from "../api";
import type { ChatMessage, ChatResponse, MessageFeedback, MessageView, ResponseOutcome } from "../types";

function localId(): string {
  return crypto.randomUUID?.() || `${Date.now()}-${Math.random()}`;
}

function outcomeOf(source: ResponseOutcome): ResponseOutcome {
  return {
    responseStatus: source.responseStatus,
    traceId: source.traceId,
    answerSource: source.answerSource,
    grounding: source.grounding,
    llmUnavailableReason: source.llmUnavailableReason,
  };
}

function fromHistory(message: ChatMessage): MessageView {
  return {
    id: message.id,
    role: message.role === "user" ? "user" : "assistant",
    content: message.content,
    createdAt: message.createdAt,
    rateable: message.role !== "user",
    feedback: message.feedback,
    outcome: outcomeOf(message),
  };
}

function fromAnswer(response: ChatResponse, fallbackId: string): MessageView {
  return {
    id: response.messageId || fallbackId,
    role: "assistant",
    content: response.answer || "Không có nội dung phản hồi.",
    createdAt: new Date().toISOString(),
    rateable: Boolean(response.messageId),
    outcome: outcomeOf(response),
    patientChoice: response.needsPatientSelection
      ? {
          candidates: parsePatientCandidates(response.patientCandidates),
          raw: response.patientCandidates,
          pendingQuestion: response.pendingQuestion,
        }
      : undefined,
  };
}

interface SendOptions {
  patientId: string | null;
  /** Shown instead of the sent text, e.g. "Chọn Patient/…" when answering a patient choice. */
  visibleMessage?: string;
}

/** The open conversation: its messages, sending state and per-message ratings. */
export function useConversation() {
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<MessageView[]>([]);
  const [lastResponse, setLastResponse] = useState<ChatResponse | null>(null);
  const [sending, setSending] = useState(false);

  function replaceMessage(id: string, next: (message: MessageView) => MessageView) {
    setMessages((current) => current.map((message) => (message.id === id ? next(message) : message)));
  }

  async function send(text: string, { patientId, visibleMessage }: SendOptions): Promise<ChatResponse> {
    const pendingId = localId();
    setSending(true);
    setMessages((current) => [
      ...current,
      { id: localId(), role: "user", content: visibleMessage || text, createdAt: new Date().toISOString() },
      { id: pendingId, role: "assistant", content: "Đang xử lý câu hỏi...", pending: true },
    ]);
    try {
      const response = await chatApi.send({ sessionId: sessionId, patientId: patientId, message: text });
      setSessionId(response.sessionId);
      setLastResponse(response);
      replaceMessage(pendingId, () => fromAnswer(response, pendingId));
      return response;
    } catch (error) {
      const content = error instanceof Error ? error.message : "Không thể gửi câu hỏi.";
      replaceMessage(pendingId, () => ({ id: pendingId, role: "error", content, createdAt: new Date().toISOString() }));
      throw error;
    } finally {
      setSending(false);
    }
  }

  async function open(id: string) {
    setSessionId(id);
    setLastResponse(null);
    const history = await chatApi.messages(id);
    setMessages(history.map(fromHistory));
  }

  function reset() {
    setSessionId(null);
    setLastResponse(null);
    setMessages([]);
  }

  async function rate(messageId: string, rating: number, comment: string) {
    const feedback: MessageFeedback = { rating, comment: comment.trim() || null };
    const alreadyRated = Boolean(messages.find((message) => message.id === messageId)?.feedback);
    await (alreadyRated ? chatApi.updateRating(messageId, feedback) : chatApi.rate(messageId, feedback));
    replaceMessage(messageId, (message) => ({ ...message, feedback }));
  }

  async function removeRating(messageId: string) {
    await chatApi.removeRating(messageId);
    replaceMessage(messageId, (message) => ({ ...message, feedback: null }));
  }

  return { sessionId, messages, lastResponse, sending, send, open, reset, rate, removeRating };
}
