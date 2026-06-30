import { FormEvent, KeyboardEvent, useEffect, useRef, useState } from "react";
import { Check, FileText, PanelRightClose, PanelRightOpen, Plus, Star, Table2, Trash2 } from "lucide-react";
import type { ChatResponse, MessageView, NotificationItem, PatientCandidate, UserRole } from "../../lib/types";
import { formatDateTime, genderLabel, safeJson } from "../../lib/formatters";
import { STAFF_QUICK_PROMPTS, TEXT, USER_QUICK_PROMPTS } from "../../lib/constants";
import { cn } from "../../lib/cn";
import { Button } from "../ui/Button";
import { Spinner } from "../ui/Spinner";
import { NotificationPopover } from "./NotificationPopover";

interface ChatWindowProps {
  role: UserRole;
  messages: MessageView[];
  currentSessionId: string | null;
  displayName: string;
  sending: boolean;
  selectedPatientId: string | null;
  notifications: NotificationItem[];
  unreadCount: number;
  notificationOpen: boolean;
  rightPanelCollapsed: boolean;
  onToggleNotifications: () => void;
  onToggleRightPanel: () => void;
  onMarkNotificationRead: (id: string) => void;
  onMarkAllNotificationsRead: () => void;
  onSelectPatientCandidate: (candidate: PatientCandidate, pendingQuestion: string | null) => void;
  onSubmitMessage: (message: string) => void;
  onSubmitFeedback: (messageId: string, rating: number, comment: string) => Promise<void>;
  onDeleteFeedback: (messageId: string) => Promise<void>;
  onExportSession: (format: "pdf" | "csv") => void;
}

function StarRating({
  value,
  onChange,
}: {
  value: number;
  onChange?: (value: number) => void;
}) {
  const [hover, setHover] = useState(0);
  const active = hover || value;

  return (
    <div className="flex items-center gap-0.5">
      {[1, 2, 3, 4, 5].map((star) => (
        <button
          key={star}
          type="button"
          aria-label={`${star} sao`}
          className="focus-ring cursor-pointer rounded p-0.5"
          onMouseEnter={() => setHover(star)}
          onMouseLeave={() => setHover(0)}
          onClick={() => onChange?.(star)}
        >
          <Star className={cn("h-4 w-4 transition", star <= active ? "fill-amber-400 text-amber-400" : "text-muted-foreground/40")} />
        </button>
      ))}
    </div>
  );
}

function MessageFeedbackControl({
  message,
  onSubmit,
  onDelete,
}: {
  message: MessageView;
  onSubmit: (messageId: string, rating: number, comment: string) => Promise<void>;
  onDelete: (messageId: string) => Promise<void>;
}) {
  const existing = message.feedback ?? null;
  const [open, setOpen] = useState(false);
  const [rating, setRating] = useState(existing?.rating ?? 0);
  const [comment, setComment] = useState(existing?.comment ?? "");
  const [submitting, setSubmitting] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function openForm() {
    setRating(existing?.rating ?? 0);
    setComment(existing?.comment ?? "");
    setError(null);
    setOpen(true);
  }

  async function handleSubmit() {
    if (!rating || submitting) {
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await onSubmit(message.id, rating, comment);
      setOpen(false);
      setSubmitting(false);
    } catch (submitError) {
      setError(submitError instanceof Error ? submitError.message : "Không thể gửi đánh giá.");
      setSubmitting(false);
    }
  }

  async function handleDelete() {
    if (deleting) {
      return;
    }
    setDeleting(true);
    setError(null);
    try {
      await onDelete(message.id);
      setOpen(false);
    } catch (deleteError) {
      setError(deleteError instanceof Error ? deleteError.message : "Không thể xóa đánh giá.");
    } finally {
      setDeleting(false);
    }
  }

  if (!open) {
    if (existing) {
      return (
        <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-border pt-2 text-xs text-muted-foreground">
          <button
            type="button"
            title="Bấm để sửa đánh giá"
            className="focus-ring inline-flex items-center gap-2 rounded-full px-1 py-0.5 transition hover:opacity-80"
            onClick={openForm}
          >
            <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-50 px-2 py-0.5 font-semibold leading-none text-emerald-600">
              <Check className="h-3.5 w-3.5 shrink-0" />
              <span>Đã đánh giá</span>
            </span>
            <span className="flex items-center gap-0.5">
              {[1, 2, 3, 4, 5].map((star) => (
                <Star
                  key={star}
                  className={cn("h-4 w-4", star <= existing.rating ? "fill-amber-400 text-amber-400" : "text-muted-foreground/40")}
                />
              ))}
            </span>
            {existing.comment ? <span className="italic">“{existing.comment}”</span> : null}
          </button>
        </div>
      );
    }
    return (
      <div className="mt-3 border-t border-border pt-2">
        <button
          type="button"
          className="focus-ring inline-flex items-end gap-1.5 rounded-full px-2 py-1 text-xs font-medium leading-none text-muted-foreground transition hover:text-accent"
          onClick={openForm}
        >
          <Star className="h-3.5 w-3.5 shrink-0" />
          <span>Đánh giá</span>
        </button>
      </div>
    );
  }

  return (
    <div className="mt-3 grid gap-2 border-t border-border pt-2">
      <div className="flex items-center gap-2">
        <span className="text-xs font-medium text-muted-foreground">
          {existing ? "Sửa đánh giá" : "Đánh giá câu trả lời"}
        </span>
        <StarRating value={rating} onChange={setRating} />
      </div>
      <textarea
        className="focus-ring min-h-[60px] w-full resize-none rounded-lg border border-border bg-white px-3 py-2 text-sm outline-none placeholder:text-muted-foreground/60"
        placeholder="Nhận xét (không bắt buộc)..."
        value={comment}
        onChange={(event) => setComment(event.target.value)}
      />
      {error ? <p className="text-xs text-danger">{error}</p> : null}
      <div className="flex items-center gap-2">
        <Button type="button" size="sm" disabled={!rating || submitting} onClick={() => void handleSubmit()}>
          {submitting ? "Đang gửi..." : existing ? "Lưu thay đổi" : "Gửi đánh giá"}
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={submitting}
          onClick={() => {
            setOpen(false);
            setError(null);
          }}
        >
          Hủy
        </Button>
        {existing ? (
          <button
            type="button"
            title="Xóa đánh giá"
            disabled={deleting || submitting}
            className="focus-ring ml-auto inline-flex items-center gap-1 rounded-full px-2 py-1 text-xs font-medium leading-none text-muted-foreground transition hover:text-danger disabled:opacity-50"
            onClick={() => void handleDelete()}
          >
            <Trash2 className="h-3.5 w-3.5 shrink-0" />
            <span>{deleting ? "Đang xóa..." : "Xóa"}</span>
          </button>
        ) : null}
      </div>
    </div>
  );
}

function parsePatientCandidates(value: unknown): PatientCandidate[] {
  if (!Array.isArray(value)) {
    return [];
  }

  const candidates: PatientCandidate[] = [];
  value.forEach((item) => {
    if (!item || typeof item !== "object") {
      return;
    }
    const candidate = item as Record<string, unknown>;
    const id = typeof candidate.id === "string" ? candidate.id.trim() : "";
    if (!id) {
      return;
    }
    candidates.push({
      id,
      name: typeof candidate.name === "string" ? candidate.name : null,
      gender: typeof candidate.gender === "string" ? candidate.gender : null,
      birth_date: typeof candidate.birth_date === "string" ? candidate.birth_date : null,
      phone: typeof candidate.phone === "string" ? candidate.phone : null,
      identifier: typeof candidate.identifier === "string" ? candidate.identifier : null,
    });
  });

  return candidates;
}

function candidateMeta(candidate: PatientCandidate) {
  return [
    candidate.gender ? genderLabel(candidate.gender) : null,
    candidate.birth_date ? `ngày sinh ${candidate.birth_date}` : null,
    candidate.phone ? `SĐT ${candidate.phone}` : null,
    candidate.identifier ? `mã ${candidate.identifier}` : null,
  ]
    .filter(Boolean)
    .join(", ");
}

function MessageBubble({
  message,
  selectionDisabled,
  onSelectPatientCandidate,
  onSubmitFeedback,
  onDeleteFeedback,
}: {
  message: MessageView;
  selectionDisabled: boolean;
  onSelectPatientCandidate: (candidate: PatientCandidate, pendingQuestion: string | null) => void;
  onSubmitFeedback: (messageId: string, rating: number, comment: string) => Promise<void>;
  onDeleteFeedback: (messageId: string) => Promise<void>;
}) {
  const isUser = message.role === "user";
  const isError = message.role === "error";
  const patientCandidates = parsePatientCandidates(message.response?.patient_candidates);
  const canRate = !isUser && !isError && !message.pending && Boolean(message.id);

  return (
    <article className={`flex ${isUser ? "justify-end" : "justify-start"}`}>
      <div
        className={`max-w-[82%] rounded-2xl border px-4 py-3 shadow-sm ${
          isUser
            ? "border-slate-200 bg-slate-100 text-foreground"
            : isError
              ? "border-danger/20 bg-danger/10 text-danger"
              : "border-border bg-white text-foreground"
        }`}
      >
        <div className="mb-2 flex items-center gap-2">
          <span className="font-mono text-[11px] uppercase tracking-[0.12em] text-muted-foreground">
            {isUser ? "Bạn" : isError ? "Lỗi" : "Trợ lý"}
          </span>
          {message.pending ? <Spinner className="text-accent" /> : null}
        </div>
        <p className="whitespace-pre-wrap break-words text-sm leading-6">{message.content}</p>
        {message.createdAt ? <p className="mt-3 text-xs text-muted-foreground">{formatDateTime(message.createdAt)}</p> : null}
        {message.response?.needs_patient_selection ? (
          <div className="mt-3 rounded-xl bg-muted p-3 text-xs">
            <div className="mb-2 font-semibold text-foreground">Ứng viên bệnh nhân</div>
            {patientCandidates.length > 0 ? (
              <div className="grid gap-2">
                {patientCandidates.map((candidate) => (
                  <button
                    key={candidate.id}
                    type="button"
                    className="focus-ring rounded-lg border border-border bg-white p-3 text-left transition hover:border-accent/40 hover:bg-accent/5 disabled:opacity-60"
                    disabled={selectionDisabled}
                    onClick={() => onSelectPatientCandidate(candidate, message.response?.pending_question || null)}
                  >
                    <span className="block font-semibold text-foreground">
                      Patient/{candidate.id} - {candidate.name || "Không rõ tên"}
                    </span>
                    <span className="mt-1 block text-muted-foreground">{candidateMeta(candidate) || "Chưa có thông tin bổ sung"}</span>
                  </button>
                ))}
              </div>
            ) : (
              <pre className="max-h-52 overflow-auto whitespace-pre-wrap break-words">
                {safeJson(message.response.patient_candidates)}
              </pre>
            )}
          </div>
        ) : null}
        {canRate ? (
          <MessageFeedbackControl message={message} onSubmit={onSubmitFeedback} onDelete={onDeleteFeedback} />
        ) : null}
      </div>
    </article>
  );
}

export function ChatWindow({
  role,
  messages,
  currentSessionId,
  displayName,
  sending,
  selectedPatientId,
  notifications,
  unreadCount,
  notificationOpen,
  rightPanelCollapsed,
  onToggleNotifications,
  onToggleRightPanel,
  onMarkNotificationRead,
  onMarkAllNotificationsRead,
  onSelectPatientCandidate,
  onSubmitMessage,
  onSubmitFeedback,
  onDeleteFeedback,
  onExportSession,
}: ChatWindowProps) {
  const [draft, setDraft] = useState("");
  const listRef = useRef<HTMLDivElement>(null);
  const quickPrompts = role === "USER" ? USER_QUICK_PROMPTS : STAFF_QUICK_PROMPTS;
  const staffPromptDisabled = role !== "USER" && !selectedPatientId;
  const sessionExportDisabled = !currentSessionId;
  const hasConversation = messages.length > 0;

  useEffect(() => {
    if (!hasConversation) {
      return;
    }
    const pane = listRef.current;
    if (!pane) {
      return;
    }
    pane.scrollTo({ top: pane.scrollHeight, behavior: "smooth" });
  }, [messages, hasConversation]);

  function submitText(value: string) {
    const message = value.trim();
    if (!message || sending) {
      return;
    }
    setDraft("");
    onSubmitMessage(message);
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    submitText(draft);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      submitText(draft);
    }
  }

  function renderQuickPrompts(centered = false) {
    return (
      <div
        className={cn(
          "flex gap-2 pb-1",
          centered ? "mt-4 flex-wrap justify-center" : "mb-3 overflow-x-auto",
        )}
      >
        {quickPrompts.map((prompt) => (
          <Button
            key={prompt}
            type="button"
            variant="ghost"
            size="sm"
            className="shrink-0 border border-border bg-white"
            disabled={staffPromptDisabled || sending}
            onClick={() => submitText(prompt)}
          >
            {prompt}
          </Button>
        ))}
      </div>
    );
  }

  function renderComposer() {
    return (
      <form className="mx-auto w-full max-w-3xl rounded-[1.75rem] border border-border bg-white px-3 py-2 shadow-card" onSubmit={handleSubmit}>
        <div className="flex items-end gap-2">
          <Button type="button" variant="ghost" size="icon" className="h-10 w-10 shrink-0 rounded-full" aria-label="Thêm nội dung">
            <Plus className="h-5 w-5" />
          </Button>
          <textarea
            className="focus-ring min-h-10 max-h-28 flex-1 resize-none rounded-2xl border-0 bg-transparent px-2 py-2 text-sm leading-6 outline-none placeholder:text-muted-foreground/70"
            value={draft}
            rows={1}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={role === "USER" ? "Hỏi về hồ sơ của tôi..." : "Nhập câu hỏi cho chatbot..."}
            disabled={sending}
            required
          />
          {sending ? (
            <div className="grid h-10 w-10 shrink-0 place-items-center text-accent" aria-label="Đang gửi">
              <Spinner />
            </div>
          ) : null}
        </div>
      </form>
    );
  }

  return (
    <section className="relative flex h-full min-h-0 flex-col overflow-hidden bg-background">
      <div
        className="chat-actions pointer-events-none absolute top-4 z-20 flex flex-wrap items-center justify-end gap-2"
        data-panel-open={!rightPanelCollapsed}
      >
        <Button
          type="button"
          variant="secondary"
          size="icon"
          className="pointer-events-auto hidden bg-white xl:inline-flex"
          onClick={onToggleRightPanel}
          aria-label={rightPanelCollapsed ? "Mở panel bên phải" : "Đóng panel bên phải"}
          title={rightPanelCollapsed ? "Mở panel bên phải" : "Đóng panel bên phải"}
        >
          {rightPanelCollapsed ? <PanelRightOpen className="h-4 w-4" /> : <PanelRightClose className="h-4 w-4" />}
        </Button>
        <div className="pointer-events-auto">
          <NotificationPopover
            open={notificationOpen}
            notifications={notifications}
            unreadCount={unreadCount}
            onToggle={onToggleNotifications}
            onMarkRead={onMarkNotificationRead}
            onMarkAllRead={onMarkAllNotificationsRead}
          />
        </div>
        <Button
          type="button"
          variant="secondary"
          size="sm"
          className="pointer-events-auto bg-white"
          disabled={sessionExportDisabled}
          onClick={() => onExportSession("pdf")}
          aria-label="Xuất PDF cho phiên hiện tại"
        >
          <FileText className="h-4 w-4" />
          {TEXT.exportPdf}
        </Button>
        <Button
          type="button"
          variant="secondary"
          size="sm"
          className="pointer-events-auto bg-white"
          disabled={sessionExportDisabled}
          onClick={() => onExportSession("csv")}
          aria-label="Xuất CSV cho phiên hiện tại"
        >
          <Table2 className="h-4 w-4" />
          {TEXT.exportCsv}
        </Button>
      </div>

      <div ref={listRef} className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-5 pt-20">
        {hasConversation ? (
          <div className="mx-auto grid min-h-full max-w-3xl content-start gap-4 pb-2">
            {messages.map((message) => (
              <MessageBubble
                key={message.id}
                message={message}
                selectionDisabled={sending}
                onSelectPatientCandidate={onSelectPatientCandidate}
                onSubmitFeedback={onSubmitFeedback}
                onDeleteFeedback={onDeleteFeedback}
              />
            ))}
          </div>
        ) : (
          <div className="mx-auto flex min-h-full max-w-3xl flex-col items-center justify-center py-10 text-center">
            <h2 className="font-display text-4xl text-foreground md:text-5xl">Xin chào {displayName}</h2>
            <p className="mt-3 max-w-xl text-sm leading-6 text-muted-foreground">Hôm nay bạn muốn hỏi gì về dữ liệu y tế?</p>
            <div className="mt-8 w-full">
              {renderComposer()}
              {renderQuickPrompts(true)}
            </div>
          </div>
        )}
      </div>

      {hasConversation ? (
        <footer className="shrink-0 bg-white px-4 py-3">
          <div className="mx-auto max-w-3xl">
            {renderQuickPrompts(false)}
            {renderComposer()}
          </div>
        </footer>
      ) : null}
    </section>
  );
}
