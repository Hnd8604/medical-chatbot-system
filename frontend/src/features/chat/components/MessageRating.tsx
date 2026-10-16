import { useState } from "react";
import { Check, Star, Trash2 } from "lucide-react";
import { errorMessage } from "@/shared/api/client";
import { cn } from "@/shared/lib/cn";
import { Button } from "@/shared/ui/Button";
import type { MessageFeedback } from "../types";

const STARS = [1, 2, 3, 4, 5];

function Stars({ value, onChange }: { value: number; onChange?: (value: number) => void }) {
  const [hover, setHover] = useState(0);
  const active = hover || value;

  return (
    <div className="flex items-center gap-0.5">
      {STARS.map((star) => (
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

interface MessageRatingProps {
  messageId: string;
  feedback: MessageFeedback | null;
  onRate: (messageId: string, rating: number, comment: string) => Promise<void>;
  onRemove: (messageId: string) => Promise<void>;
}

/** 1–5 star rating with an optional comment under an assistant answer. */
export function MessageRating({ messageId, feedback, onRate, onRemove }: MessageRatingProps) {
  const [editing, setEditing] = useState(false);
  const [rating, setRating] = useState(0);
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState<"saving" | "removing" | null>(null);
  const [error, setError] = useState<string | null>(null);

  function startEditing() {
    setRating(feedback?.rating ?? 0);
    setComment(feedback?.comment ?? "");
    setError(null);
    setEditing(true);
  }

  async function run(kind: "saving" | "removing", action: () => Promise<void>, fallback: string) {
    setBusy(kind);
    setError(null);
    try {
      await action();
      setEditing(false);
    } catch (failure) {
      setError(errorMessage(failure, fallback));
    } finally {
      setBusy(null);
    }
  }

  if (!editing) {
    return (
      <div className="mt-3 border-t border-border pt-2 text-xs text-muted-foreground">
        {feedback ? (
          <button
            type="button"
            title="Bấm để sửa đánh giá"
            className="focus-ring inline-flex flex-wrap items-center gap-2 rounded-full px-1 py-0.5 transition hover:opacity-80"
            onClick={startEditing}
          >
            <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-50 px-2 py-0.5 font-semibold leading-none text-emerald-600">
              <Check className="h-3.5 w-3.5 shrink-0" />
              Đã đánh giá
            </span>
            <span className="flex items-center gap-0.5">
              {STARS.map((star) => (
                <Star
                  key={star}
                  className={cn("h-4 w-4", star <= feedback.rating ? "fill-amber-400 text-amber-400" : "text-muted-foreground/40")}
                />
              ))}
            </span>
            {feedback.comment ? <span className="italic">“{feedback.comment}”</span> : null}
          </button>
        ) : (
          <button
            type="button"
            className="focus-ring inline-flex items-end gap-1.5 rounded-full px-2 py-1 font-medium leading-none transition hover:text-accent"
            onClick={startEditing}
          >
            <Star className="h-3.5 w-3.5 shrink-0" />
            Đánh giá
          </button>
        )}
      </div>
    );
  }

  return (
    <div className="mt-3 grid gap-2 border-t border-border pt-2">
      <div className="flex items-center gap-2">
        <span className="text-xs font-medium text-muted-foreground">{feedback ? "Sửa đánh giá" : "Đánh giá câu trả lời"}</span>
        <Stars value={rating} onChange={setRating} />
      </div>
      <textarea
        className="focus-ring min-h-[60px] w-full resize-none rounded-lg border border-border bg-white px-3 py-2 text-sm outline-none placeholder:text-muted-foreground/60"
        placeholder="Nhận xét (không bắt buộc)..."
        value={comment}
        onChange={(event) => setComment(event.target.value)}
      />
      {error ? <p className="text-xs text-danger">{error}</p> : null}
      <div className="flex items-center gap-2">
        <Button
          type="button"
          size="sm"
          disabled={!rating || busy !== null}
          onClick={() => void run("saving", () => onRate(messageId, rating, comment), "Không thể gửi đánh giá.")}
        >
          {busy === "saving" ? "Đang gửi..." : feedback ? "Lưu thay đổi" : "Gửi đánh giá"}
        </Button>
        <Button type="button" variant="ghost" size="sm" disabled={busy !== null} onClick={() => setEditing(false)}>
          Hủy
        </Button>
        {feedback ? (
          <button
            type="button"
            title="Xóa đánh giá"
            disabled={busy !== null}
            className="focus-ring ml-auto inline-flex items-center gap-1 rounded-full px-2 py-1 text-xs font-medium leading-none text-muted-foreground transition hover:text-danger disabled:opacity-50"
            onClick={() => void run("removing", () => onRemove(messageId), "Không thể xóa đánh giá.")}
          >
            <Trash2 className="h-3.5 w-3.5 shrink-0" />
            {busy === "removing" ? "Đang xóa..." : "Xóa"}
          </button>
        ) : null}
      </div>
    </div>
  );
}
