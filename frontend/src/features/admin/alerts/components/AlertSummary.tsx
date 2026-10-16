import { formatDateTime } from "@/shared/lib/format";
import { Badge } from "@/shared/ui/Badge";
import type { Alert, AlertSeverity } from "../types";

const SEVERITY_TONE: Record<AlertSeverity, "red" | "amber" | "blue"> = {
  CRITICAL: "red",
  WARNING: "amber",
  INFO: "blue",
};

/** Severity, type, message and origin of an alert; `extra` badges go after the severity. */
export function AlertSummary({ alert, extra }: { alert: Alert; extra?: React.ReactNode }) {
  return (
    <div className="min-w-0">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={SEVERITY_TONE[alert.severity]}>{alert.severity}</Badge>
        {extra}
        <span className="font-mono text-xs uppercase tracking-[0.12em] text-muted-foreground">{alert.alertType}</span>
      </div>
      <p className="mt-2 text-sm font-semibold text-foreground">{alert.message || "Không có nội dung alert."}</p>
      <p className="mt-1 text-xs text-muted-foreground">
        {alert.source} · {formatDateTime(alert.createdAt)}
      </p>
    </div>
  );
}
