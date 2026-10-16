import { genderLabel } from "@/features/patients/fhir";
import type { PatientCandidate } from "@/features/patients/types";
import { cn } from "@/shared/lib/cn";
import { formatDateTime, prettyJson } from "@/shared/lib/format";
import { Spinner } from "@/shared/ui/Spinner";
import type { MessageView, ResponseOutcome } from "../types";
import { MessageRating } from "./MessageRating";

interface Notice {
  tone: "warning" | "danger" | "muted";
  text: string;
}

/** Caveats for answers that are not complete or not fully verified; empty for normal answers. */
function responseNotices(outcome: ResponseOutcome | undefined): Notice[] {
  if (!outcome) {
    return [];
  }
  const notices: Notice[] = [];
  if (outcome.response_status === "tool_error") {
    notices.push({ tone: "danger", text: "Không truy xuất được dữ liệu do sự cố hệ thống." });
  } else if (outcome.response_status === "partial") {
    notices.push({ tone: "warning", text: "Một phần dữ liệu chưa truy xuất được; câu trả lời có thể chưa đầy đủ." });
  } else if (outcome.llm_unavailable_reason) {
    notices.push({ tone: "muted", text: "Trả lời ở chế độ rút gọn (không dùng AI) do dịch vụ AI tạm không khả dụng." });
  }
  // Only a failed check is reported: cached and template answers are not re-checked, so a
  // "verified" label would come and go for the same answer.
  if (outcome.grounding?.checked && outcome.grounding.passed === false) {
    notices.push(
      outcome.answer_source === "template_grounding_fallback"
        ? {
            tone: "muted",
            text: "Câu trả lời do AI viết có dữ kiện không khớp hồ sơ nên đã được thay bằng nội dung lấy trực tiếp từ dữ liệu.",
          }
        : {
            tone: "warning",
            text: "Câu trả lời có số liệu, ngày hoặc mã chưa đối chiếu được với hồ sơ. Vui lòng kiểm tra lại với dữ liệu gốc.",
          },
    );
  }
  return notices;
}

function ResponseNotices({ outcome }: { outcome: ResponseOutcome | undefined }) {
  const notices = responseNotices(outcome);
  if (notices.length === 0) {
    return null;
  }
  return (
    <div className="mt-3 grid gap-2">
      {notices.map((notice, index) => (
        <div
          key={notice.text}
          className={cn(
            "rounded-lg border px-3 py-2 text-xs",
            notice.tone === "danger" && "border-danger/20 bg-danger/10 text-danger",
            notice.tone === "warning" && "border-warning/30 bg-warning/10 text-foreground",
            notice.tone === "muted" && "border-border bg-muted text-muted-foreground",
          )}
        >
          <p>{notice.text}</p>
          {index === 0 && outcome?.trace_id ? (
            <p className="mt-1 font-mono text-[11px] text-muted-foreground">Mã tra cứu: {outcome.trace_id}</p>
          ) : null}
        </div>
      ))}
    </div>
  );
}

function candidateDetails(candidate: PatientCandidate): string {
  return [
    candidate.gender ? genderLabel(candidate.gender) : null,
    candidate.birth_date ? `ngày sinh ${candidate.birth_date}` : null,
    candidate.phone ? `SĐT ${candidate.phone}` : null,
    candidate.identifier ? `mã ${candidate.identifier}` : null,
  ]
    .filter(Boolean)
    .join(", ");
}

function PatientChoice({
  choice,
  disabled,
  onSelect,
}: {
  choice: NonNullable<MessageView["patientChoice"]>;
  disabled: boolean;
  onSelect: (candidate: PatientCandidate, pendingQuestion: string | null) => void;
}) {
  return (
    <div className="mt-3 rounded-xl bg-muted p-3 text-xs">
      <div className="mb-2 font-semibold text-foreground">Ứng viên bệnh nhân</div>
      {choice.candidates.length > 0 ? (
        <div className="grid gap-2">
          {choice.candidates.map((candidate) => (
            <button
              key={candidate.id}
              type="button"
              className="focus-ring rounded-lg border border-border bg-white p-3 text-left transition hover:border-accent/40 hover:bg-accent/5 disabled:opacity-60"
              disabled={disabled}
              onClick={() => onSelect(candidate, choice.pendingQuestion)}
            >
              <span className="block font-semibold text-foreground">
                Patient/{candidate.id} - {candidate.name || "Không rõ tên"}
              </span>
              <span className="mt-1 block text-muted-foreground">{candidateDetails(candidate) || "Chưa có thông tin bổ sung"}</span>
            </button>
          ))}
        </div>
      ) : (
        <pre className="max-h-52 overflow-auto whitespace-pre-wrap break-words">{prettyJson(choice.raw)}</pre>
      )}
    </div>
  );
}

interface MessageBubbleProps {
  message: MessageView;
  sending: boolean;
  onSelectCandidate: (candidate: PatientCandidate, pendingQuestion: string | null) => void;
  onRate: (messageId: string, rating: number, comment: string) => Promise<void>;
  onRemoveRating: (messageId: string) => Promise<void>;
}

export function MessageBubble({ message, sending, onSelectCandidate, onRate, onRemoveRating }: MessageBubbleProps) {
  const isUser = message.role === "user";
  const isError = message.role === "error";
  const isAnswer = !isUser && !isError;

  return (
    <article className={cn("flex", isUser ? "justify-end" : "justify-start")}>
      <div
        className={cn(
          "max-w-[82%] rounded-2xl border px-4 py-3 shadow-sm",
          isUser && "border-slate-200 bg-slate-100 text-foreground",
          isError && "border-danger/20 bg-danger/10 text-danger",
          isAnswer && "border-border bg-white text-foreground",
        )}
      >
        <div className="mb-2 flex items-center gap-2">
          <span className="font-mono text-[11px] uppercase tracking-[0.12em] text-muted-foreground">
            {isUser ? "Bạn" : isError ? "Lỗi" : "Trợ lý"}
          </span>
          {message.pending ? <Spinner className="text-accent" /> : null}
        </div>
        <p className="whitespace-pre-wrap break-words text-sm leading-6">{message.content}</p>
        {isAnswer ? <ResponseNotices outcome={message.outcome} /> : null}
        {message.createdAt ? <p className="mt-3 text-xs text-muted-foreground">{formatDateTime(message.createdAt)}</p> : null}
        {message.patientChoice ? <PatientChoice choice={message.patientChoice} disabled={sending} onSelect={onSelectCandidate} /> : null}
        {isAnswer && !message.pending && message.rateable ? (
          <MessageRating messageId={message.id} feedback={message.feedback ?? null} onRate={onRate} onRemove={onRemoveRating} />
        ) : null}
      </div>
    </article>
  );
}
