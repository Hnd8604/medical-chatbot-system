import { FormEvent, KeyboardEvent, useEffect, useRef, useState } from "react";
import { ArrowUp, Sparkles } from "lucide-react";
import type { PatientCandidate } from "@/features/patients/types";
import { Button } from "@/shared/ui/Button";
import { Spinner } from "@/shared/ui/Spinner";
import { STAFF_QUICK_PROMPTS, USER_QUICK_PROMPTS } from "../constants";
import type { MessageView } from "../types";
import { MessageBubble } from "./MessageBubble";

interface ChatWindowProps {
  staff: boolean;
  displayName: string;
  messages: MessageView[];
  sending: boolean;
  /** Floating controls in the top-right corner (panel toggle, notifications). */
  actions: React.ReactNode;
  /** Shifts the floating controls left of the open patient panel. */
  rightPanelOpen: boolean;
  onSubmitMessage: (message: string) => void;
  onSelectCandidate: (candidate: PatientCandidate, pendingQuestion: string | null) => void;
  onRate: (messageId: string, rating: number, comment: string) => Promise<void>;
  onRemoveRating: (messageId: string) => Promise<void>;
}

export function ChatWindow({
  staff,
  displayName,
  messages,
  sending,
  actions,
  rightPanelOpen,
  onSubmitMessage,
  onSelectCandidate,
  onRate,
  onRemoveRating,
}: ChatWindowProps) {
  const [draft, setDraft] = useState("");
  const listRef = useRef<HTMLDivElement>(null);
  const quickPrompts = staff ? STAFF_QUICK_PROMPTS : USER_QUICK_PROMPTS;
  const hasConversation = messages.length > 0;

  useEffect(() => {
    if (hasConversation) {
      listRef.current?.scrollTo({ top: listRef.current.scrollHeight, behavior: "smooth" });
    }
  }, [messages, hasConversation]);

  function submit(value: string) {
    const message = value.trim();
    if (!message || sending) {
      return;
    }
    setDraft("");
    onSubmitMessage(message);
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    submit(draft);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      submit(draft);
    }
  }

  return (
    <section className="relative flex h-full min-h-0 flex-col overflow-hidden bg-background">
      <div
        className="chat-actions pointer-events-none absolute top-4 z-20 flex flex-wrap items-center justify-end gap-1.5"
        data-panel-open={rightPanelOpen}
      >
        {actions}
      </div>

      <div ref={listRef} className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-5 pt-20">
        {hasConversation ? (
          <div className="mx-auto grid min-h-full max-w-3xl content-start gap-4 pb-2">
            {messages.map((message) => (
              <MessageBubble
                key={message.id}
                message={message}
                sending={sending}
                onSelectCandidate={onSelectCandidate}
                onRate={onRate}
                onRemoveRating={onRemoveRating}
              />
            ))}
          </div>
        ) : (
          <div className="mx-auto flex min-h-full max-w-3xl flex-col items-center justify-center py-10 text-center">
            <div className="mb-5 grid h-12 w-12 place-items-center rounded-2xl gradient-surface text-white shadow-card">
              <Sparkles className="h-6 w-6" />
            </div>
            <p className="mb-3 font-mono text-[11px] uppercase tracking-[0.35em] text-accent">Medical Chatbot</p>
            <h2 className="font-display text-4xl font-bold text-foreground md:text-5xl">Xin chào {displayName}</h2>
            <div className="mt-8 grid w-full gap-3 sm:grid-cols-2">
              {quickPrompts.slice(0, 4).map((prompt) => (
                <button
                  key={prompt}
                  type="button"
                  disabled={sending}
                  onClick={() => submit(prompt)}
                  className="focus-ring rounded-2xl border border-border bg-white px-4 py-3.5 text-left text-sm leading-6 text-foreground transition hover:border-accent/40 hover:bg-accent/5 hover:shadow-card disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {prompt}
                </button>
              ))}
            </div>
          </div>
        )}
      </div>

      <footer className="shrink-0 bg-background px-4 pb-4 pt-2">
        <div className="mx-auto max-w-3xl">
          {hasConversation ? (
            <div className="mb-3 flex gap-2 overflow-x-auto pb-1">
              {quickPrompts.map((prompt) => (
                <Button
                  key={prompt}
                  type="button"
                  variant="ghost"
                  size="sm"
                  className="shrink-0 border border-border bg-white"
                  disabled={sending}
                  onClick={() => submit(prompt)}
                >
                  {prompt}
                </Button>
              ))}
            </div>
          ) : null}
          <form
            className="mx-auto flex w-full max-w-3xl items-end gap-2 rounded-[1.75rem] border border-border bg-white px-4 py-2.5 shadow-card focus-within:border-accent/40"
            onSubmit={handleSubmit}
          >
            <textarea
              className="max-h-40 min-h-9 flex-1 resize-none border-0 bg-transparent py-1.5 text-sm leading-6 outline-none placeholder:text-muted-foreground/70"
              value={draft}
              rows={1}
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="Hỏi bất kỳ điều gì"
              disabled={sending}
              required
            />
            <button
              type="submit"
              disabled={!draft.trim() || sending}
              aria-label="Gửi"
              title="Gửi"
              className="focus-ring mb-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-full bg-accent text-white transition hover:bg-accent/90 disabled:bg-muted disabled:text-muted-foreground/60"
            >
              {sending ? <Spinner className="text-white" /> : <ArrowUp className="h-5 w-5" />}
            </button>
          </form>
          <p className="mt-2 text-center text-[11px] text-muted-foreground/70">
            Chatbot chỉ trả lời dựa trên dữ liệu FHIR hiện có, không thay thế tư vấn y tế.
          </p>
        </div>
      </footer>
    </section>
  );
}
