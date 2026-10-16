import { cn } from "@/shared/lib/cn";

type NoticeTone = "danger" | "success" | "warning" | "info";

const toneClass: Record<NoticeTone, string> = {
  danger: "border-danger/20 bg-danger/10 text-danger",
  success: "border-success/20 bg-success/10 text-success",
  warning: "border-warning/30 bg-warning/10 text-foreground",
  info: "border-accent/25 bg-accent/5 text-foreground",
};

interface NoticeProps {
  tone?: NoticeTone;
  title?: string;
  children?: React.ReactNode;
  className?: string;
}

/** Inline banner for errors, confirmations and warnings. */
export function Notice({ tone = "danger", title, children, className }: NoticeProps) {
  return (
    <div role={tone === "danger" ? "alert" : "status"} className={cn("rounded-xl border px-4 py-3 text-sm", toneClass[tone], className)}>
      {title ? <p className="font-semibold">{title}</p> : null}
      {children ? <div className={cn(title ? "mt-1 text-muted-foreground" : "font-medium")}>{children}</div> : null}
    </div>
  );
}
