import { cn } from "@/shared/lib/cn";
import { Spinner } from "./Spinner";

export function LoadingPanel({ label, className }: { label: string; className?: string }) {
  return (
    <div className={cn("grid min-h-[18rem] place-items-center rounded-lg border border-border bg-white", className)}>
      <div className="flex items-center gap-3 text-muted-foreground">
        <Spinner className="text-accent" />
        {label}
      </div>
    </div>
  );
}
