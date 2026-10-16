import { useState } from "react";
import { ChevronDown, ChevronUp } from "lucide-react";
import { formatDateTime, prettyJson, shortId } from "@/shared/lib/format";
import { Badge } from "@/shared/ui/Badge";
import type { AuditLog } from "../types";

/** Metadata keys worth showing in the one-line preview, in display order. */
const PREVIEW_KEYS = [
  "operation",
  "response_status",
  "intent",
  "tool_name",
  "answer_source",
  "llm_model",
  "latency_ms",
  "patient_id",
  "http_status",
  "trace_id",
];

function actionTone(action: string): "red" | "green" | "amber" | "blue" {
  if (["FAILURE", "FAILED", "ERROR", "DELETED"].some((marker) => action.includes(marker))) return "red";
  if (action.includes("CREATED") || action.includes("UPDATED")) return "green";
  if (action.includes("CACHE") || action.includes("EXPORT")) return "amber";
  return "blue";
}

function metadataPreview(metadata: unknown): string {
  if (!metadata || typeof metadata !== "object") {
    return "-";
  }
  const record = metadata as Record<string, unknown>;
  const pairs = PREVIEW_KEYS.filter((key) => record[key] !== undefined && record[key] !== null && record[key] !== "");
  return pairs.length > 0 ? pairs.map((key) => `${key}: ${String(record[key])}`).join(" | ") : prettyJson(metadata);
}

function ToggleButton({ expanded, onClick, long = false }: { expanded: boolean; onClick: () => void; long?: boolean }) {
  return (
    <button
      type="button"
      className="focus-ring mt-2 inline-flex items-center gap-1 rounded-md border border-border bg-white px-2 py-1 text-xs font-medium text-accent transition-colors hover:bg-accent/5"
      onClick={onClick}
    >
      {expanded ? <ChevronUp className="h-3 w-3" /> : <ChevronDown className="h-3 w-3" />}
      {expanded ? (long ? "Ẩn metadata" : "Ẩn") : long ? "Xem metadata" : "Xem"}
    </button>
  );
}

interface AuditLogListProps {
  logs: AuditLog[];
  usernameOf: (userId: string | null) => string;
}

export function AuditLogList({ logs, usernameOf }: AuditLogListProps) {
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const toggle = (id: string) => setExpandedId((current) => (current === id ? null : id));

  return (
    <>
      <div className="hidden overflow-hidden rounded-lg border border-border lg:block">
        <div className="grid grid-cols-[10rem_12rem_10rem_1fr_8rem_1.4fr] gap-4 border-b border-border bg-muted/60 px-4 py-3 text-xs font-medium uppercase tracking-wide text-muted-foreground">
          <span>Thời gian</span>
          <span>Action</span>
          <span>User</span>
          <span>Resource</span>
          <span>Session</span>
          <span>Metadata</span>
        </div>
        <div className="divide-y divide-border">
          {logs.map((log) => {
            const fullMetadata = prettyJson(log.metadata);
            return (
              <div key={log.id}>
                <article className="grid grid-cols-[10rem_12rem_10rem_1fr_8rem_1.4fr] gap-4 px-4 py-3 text-sm">
                  <span className="text-muted-foreground">{formatDateTime(log.createdAt)}</span>
                  <span>
                    <Badge tone={actionTone(log.action)}>{log.action}</Badge>
                  </span>
                  <span className="min-w-0 truncate font-mono text-xs text-foreground">{usernameOf(log.userId)}</span>
                  <span className="min-w-0">
                    <span className="block font-semibold text-foreground">{log.resourceType || "-"}</span>
                    <span className="block truncate font-mono text-xs text-muted-foreground" title={log.resourceId || undefined}>
                      {log.resourceId || "-"}
                    </span>
                  </span>
                  <span className="font-mono text-xs text-muted-foreground" title={log.sessionId || undefined}>
                    {shortId(log.sessionId)}
                  </span>
                  <span className="min-w-0">
                    <span className="line-clamp-2 font-mono text-xs text-muted-foreground" title={fullMetadata}>
                      {metadataPreview(log.metadata)}
                    </span>
                    <ToggleButton expanded={expandedId === log.id} onClick={() => toggle(log.id)} />
                  </span>
                </article>
                {expandedId === log.id ? (
                  <div className="border-t border-border bg-muted/30 px-4 py-3">
                    <pre className="max-h-80 overflow-auto whitespace-pre-wrap rounded-lg border border-border bg-white p-3 font-mono text-xs leading-5 text-foreground">
                      {fullMetadata}
                    </pre>
                  </div>
                ) : null}
              </div>
            );
          })}
        </div>
      </div>

      <div className="grid gap-3 lg:hidden">
        {logs.map((log) => {
          const fullMetadata = prettyJson(log.metadata);
          return (
            <article key={log.id} className="rounded-lg border border-border bg-white p-4">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <Badge tone={actionTone(log.action)}>{log.action}</Badge>
                <span className="text-xs text-muted-foreground">{formatDateTime(log.createdAt)}</span>
              </div>
              <div className="mt-4 grid gap-2 text-sm">
                <div className="flex justify-between gap-3">
                  <span className="text-muted-foreground">User</span>
                  <span className="font-mono text-xs text-foreground">{usernameOf(log.userId)}</span>
                </div>
                <div className="flex justify-between gap-3">
                  <span className="text-muted-foreground">Resource</span>
                  <span className="min-w-0 truncate text-right font-semibold text-foreground">
                    {log.resourceType || "-"} / {log.resourceId || "-"}
                  </span>
                </div>
                <div className="flex justify-between gap-3">
                  <span className="text-muted-foreground">Session</span>
                  <span className="font-mono text-xs text-muted-foreground">{shortId(log.sessionId)}</span>
                </div>
                <p className="mt-2 line-clamp-3 font-mono text-xs text-muted-foreground" title={fullMetadata}>
                  {metadataPreview(log.metadata)}
                </p>
                <ToggleButton expanded={expandedId === log.id} onClick={() => toggle(log.id)} long />
                {expandedId === log.id ? (
                  <pre className="mt-3 max-h-72 overflow-auto whitespace-pre-wrap rounded-lg border border-border bg-muted/30 p-3 font-mono text-xs leading-5 text-foreground">
                    {fullMetadata}
                  </pre>
                ) : null}
              </div>
            </article>
          );
        })}
      </div>
    </>
  );
}
