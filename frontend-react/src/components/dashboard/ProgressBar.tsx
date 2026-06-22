import { cn } from "../../lib/cn";

interface ProgressBarProps {
  value: number;
  tone?: "blue" | "green" | "amber" | "red";
  label?: string;
  className?: string;
}

const toneClass = {
  blue: "from-[var(--accent)] to-[var(--accent-secondary)]",
  green: "from-success to-emerald-400",
  amber: "from-warning to-amber-300",
  red: "from-danger to-red-400",
};

export function ProgressBar({ value, tone = "blue", label, className }: ProgressBarProps) {
  const safeValue = Math.max(0, Math.min(100, Number.isFinite(value) ? value : 0));

  return (
    <div className={cn("grid gap-2", className)}>
      {label ? <div className="text-xs font-semibold text-muted-foreground">{label}</div> : null}
      <div className="h-2.5 overflow-hidden rounded-full bg-muted">
        <div
          className={cn("h-full rounded-full bg-gradient-to-r transition-all duration-500", toneClass[tone])}
          style={{ width: `${safeValue}%` }}
        />
      </div>
    </div>
  );
}
