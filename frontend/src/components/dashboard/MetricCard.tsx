import { TrendingDown, TrendingUp } from "lucide-react";
import { cn } from "../../lib/cn";
import { Badge } from "../ui/Badge";
import { ProgressBar } from "./ProgressBar";

interface MetricCardProps {
  label: string;
  value: string;
  description?: string;
  trend?: string;
  trendTone?: "up" | "down" | "neutral";
  progress?: number;
  progressTone?: "blue" | "green" | "amber" | "red";
  className?: string;
}

export function MetricCard({
  label,
  value,
  description,
  trend,
  trendTone = "neutral",
  progress,
  progressTone = "blue",
  className,
}: MetricCardProps) {
  const TrendIcon = trendTone === "down" ? TrendingDown : TrendingUp;

  return (
    <article className={cn("rounded-lg border border-border bg-white p-5 shadow-sm", className)}>
      <div className="flex items-start justify-between gap-3">
        <p className="font-mono text-xs uppercase tracking-[0.14em] text-muted-foreground">{label}</p>
        {trend ? (
          <Badge tone={trendTone === "down" ? "red" : trendTone === "up" ? "green" : "slate"} className="shrink-0">
            {trendTone === "neutral" ? null : <TrendIcon className="h-3 w-3" />}
            {trend}
          </Badge>
        ) : null}
      </div>
      <div className="mt-4 font-display text-3xl text-foreground">{value}</div>
      {description ? <p className="mt-2 text-sm leading-6 text-muted-foreground">{description}</p> : null}
      {progress !== undefined ? <ProgressBar value={progress} tone={progressTone} className="mt-5" /> : null}
    </article>
  );
}
