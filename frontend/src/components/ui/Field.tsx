import { cn } from "../../lib/cn";

interface FieldProps {
  label: string;
  children: React.ReactNode;
  hint?: string;
  className?: string;
}

export function Field({ label, children, hint, className }: FieldProps) {
  return (
    <label className={cn("grid gap-2 text-sm font-semibold text-foreground", className)}>
      <span>{label}</span>
      {children}
      {hint ? <span className="text-xs font-normal text-muted-foreground">{hint}</span> : null}
    </label>
  );
}

export function inputClass(className?: string) {
  return cn(
    "focus-ring min-h-12 w-full rounded-xl border border-border bg-white px-4 py-3 text-sm text-foreground shadow-sm outline-none transition placeholder:text-muted-foreground/70",
    className,
  );
}
