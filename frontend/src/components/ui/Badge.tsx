import { cn } from "../../lib/cn";

interface BadgeProps {
  children: React.ReactNode;
  tone?: "blue" | "green" | "amber" | "red" | "slate";
  pulse?: boolean;
  className?: string;
}

const toneClass = {
  blue: "border-accent/25 bg-accent/5 text-accent",
  green: "border-success/25 bg-success/10 text-success",
  amber: "border-warning/25 bg-warning/10 text-warning",
  red: "border-danger/25 bg-danger/10 text-danger",
  slate: "border-border bg-muted text-muted-foreground",
};

export function Badge({ children, tone = "slate", pulse = false, className }: BadgeProps) {
  return (
    <span
      className={cn(
        "inline-flex items-center gap-2 rounded-full border px-3 py-1 font-mono text-[11px] uppercase tracking-[0.12em]",
        toneClass[tone],
        className,
      )}
    >
      {pulse ? <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-current" /> : null}
      {children}
    </span>
  );
}
