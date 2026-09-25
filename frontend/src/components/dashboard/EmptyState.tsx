import { cn } from "../../lib/cn";

interface EmptyStateProps {
  title: string;
  description?: string;
  className?: string;
}

export function EmptyState({ title, description, className }: EmptyStateProps) {
  return (
    <div className={cn("rounded-lg border border-dashed border-border bg-white/70 p-5 text-sm", className)}>
      <p className="font-semibold text-foreground">{title}</p>
      {description ? <p className="mt-1 text-muted-foreground">{description}</p> : null}
    </div>
  );
}
