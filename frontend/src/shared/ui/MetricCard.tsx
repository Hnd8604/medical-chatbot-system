import { cn } from "@/shared/lib/cn";
import { ProgressBar, type ProgressTone } from "./ProgressBar";

interface MetricCardProps {
  label: string;
  value: string;
  description?: string;
  progress?: number;
  progressTone?: ProgressTone;
  className?: string;
}

export function MetricCard({ label, value, description, progress, progressTone = "blue", className }: MetricCardProps) {
  return (
    <article className={cn("rounded-lg border border-border bg-white p-5 shadow-sm", className)}>
      <p className="font-mono text-xs uppercase tracking-[0.14em] text-muted-foreground">{label}</p>
      <div className="mt-4 font-display text-3xl text-foreground">{value}</div>
      {description ? <p className="mt-2 text-sm leading-6 text-muted-foreground">{description}</p> : null}
      {progress !== undefined ? <ProgressBar value={progress} tone={progressTone} className="mt-5" /> : null}
    </article>
  );
}
