import { CalendarDays, ListFilter } from "lucide-react";
import { formatNumber } from "../../lib/formatters";
import { Badge } from "../ui/Badge";
import { Button } from "../ui/Button";
import { SectionLabel } from "../ui/SectionLabel";
import { Spinner } from "../ui/Spinner";

export const DEFAULT_ANALYTICS_LIMIT = 10;
export const DEFAULT_ANALYTICS_DAYS = 7;

export type AnalyticsFilter = {
  from: string;
  to: string;
  limit: number;
};

export function formatAnalyticsDate(value: string) {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime())
    ? value
    : new Intl.DateTimeFormat("vi-VN", {
        weekday: "short",
        day: "2-digit",
        month: "2-digit",
        year: "numeric",
      }).format(parsed);
}

export function toIsoDate(date: Date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

export function analyticsRange(days: number) {
  const to = new Date();
  const from = new Date(to);
  from.setDate(to.getDate() - days + 1);
  return {
    from: toIsoDate(from),
    to: toIsoDate(to),
  };
}

export function defaultAnalyticsFilter(): AnalyticsFilter {
  return {
    ...analyticsRange(DEFAULT_ANALYTICS_DAYS),
    limit: DEFAULT_ANALYTICS_LIMIT,
  };
}

export function analyticsParams(filter: AnalyticsFilter) {
  return `from=${filter.from}&to=${filter.to}&limit=${filter.limit}`;
}

export function analyticsWindowDays(from: string, to: string) {
  const start = new Date(from);
  const end = new Date(to);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return 0;
  return Math.max(1, Math.round((end.getTime() - start.getTime()) / 86_400_000) + 1);
}

export function displayKey(value: string) {
  return value.replace(/_/g, " ");
}

export function summarizeByKey<T extends { date: string; count: number }>(items: T[], keyOf: (item: T) => string) {
  const groups = new Map<string, T[]>();
  for (const item of items) {
    const key = keyOf(item);
    const bucket = groups.get(key) ?? [];
    bucket.push(item);
    groups.set(key, bucket);
  }

  return Array.from(groups.entries())
    .map(([key, rows]) => ({
      key,
      total: rows.reduce((sum, row) => sum + row.count, 0),
      rows: [...rows].sort((a, b) => b.count - a.count),
      days: [...rows].sort((a, b) => b.date.localeCompare(a.date)),
    }))
    .sort((a, b) => b.total - a.total);
}

export function totalCount(items: Array<{ count: number }>) {
  return items.reduce((sum, item) => sum + item.count, 0);
}

export function formatLatency(value: number | null | undefined) {
  if (value === null || value === undefined || Number.isNaN(value)) return "0 ms";
  return `${Math.round(value).toLocaleString("vi-VN")} ms`;
}

export function AnalyticsStat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-border bg-white px-4 py-3 shadow-sm">
      <p className="font-mono text-[11px] uppercase tracking-[0.14em] text-muted-foreground">{label}</p>
      <p className="mt-1 font-display text-2xl text-foreground">{value}</p>
    </div>
  );
}

export function AnalyticsFilterInput({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="grid gap-1">
      <span className="font-mono text-[10px] uppercase tracking-[0.14em] text-muted-foreground">{label}</span>
      {children}
    </label>
  );
}

export function AnalyticsShell({ title, icon, meta, children }: { title: string; icon: React.ReactNode; meta?: React.ReactNode; children: React.ReactNode }) {
  return (
    <section className="overflow-hidden rounded-xl border border-border bg-white shadow-sm">
      <div className="flex flex-wrap items-start justify-between gap-4 border-b border-border bg-white px-5 py-4">
        <div className="min-w-0">
          <SectionLabel>Advanced analytics</SectionLabel>
          <h2 className="mt-3 font-display text-2xl text-foreground">{title}</h2>
          {meta ? <div className="mt-3 flex flex-wrap items-center gap-2">{meta}</div> : null}
        </div>
        <div className="grid h-11 w-11 place-items-center rounded-xl gradient-surface text-white">{icon}</div>
      </div>
      <div className="bg-gradient-to-b from-muted/40 to-white p-5">{children}</div>
    </section>
  );
}

export function BreakdownCard({ title, total, days, maxTotal, tone = "blue" }: { title: string; total: number; days: Array<{ date: string; count: number }>; maxTotal: number; tone?: "blue" | "red" }) {
  const peak = days.reduce<{ date: string; count: number } | null>((best, item) => (!best || item.count > best.count ? item : best), null);
  const ratio = Math.max(4, Math.min(100, (total / Math.max(maxTotal, 1)) * 100));
  const barClass = tone === "red" ? "bg-danger" : "gradient-surface";
  const sortedDays = [...days].sort((a, b) => b.count - a.count);

  return (
    <article className="group rounded-xl border border-border bg-white p-4 shadow-sm transition hover:-translate-y-0.5 hover:border-accent/35 hover:shadow-card">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate font-mono text-xs uppercase tracking-[0.08em] text-foreground">{displayKey(title)}</p>
          <p className="mt-1 text-xs text-muted-foreground">{peak ? `${formatAnalyticsDate(peak.date)} cao nhất` : `${days.length} ngày`}</p>
        </div>
        <Badge tone={tone} className="shrink-0">
          {formatNumber(total)}
        </Badge>
      </div>
      <div className="mt-4 h-2 overflow-hidden rounded-full bg-muted">
        <div className={`h-full rounded-full ${barClass}`} style={{ width: `${ratio}%` }} />
      </div>
      <div className="mt-4 flex flex-wrap gap-2">
        {sortedDays.slice(0, 5).map((day) => (
          <div key={`${title}-${day.date}`} className="inline-flex items-center gap-2 rounded-full border border-border bg-muted/60 px-3 py-1.5 text-xs">
            <CalendarDays className={tone === "red" ? "h-3.5 w-3.5 text-danger" : "h-3.5 w-3.5 text-accent"} />
            <span className="text-muted-foreground">{formatAnalyticsDate(day.date)}</span>
            <span className={tone === "red" ? "font-semibold text-danger" : "font-semibold text-accent"}>{formatNumber(day.count)}</span>
          </div>
        ))}
      </div>
    </article>
  );
}

export function AnalyticsFilterBar({
  from,
  to,
  limit,
  loading,
  onFromChange,
  onToChange,
  onLimitChange,
  onPreset,
  onApply,
}: {
  from: string;
  to: string;
  limit: number;
  loading: boolean;
  onFromChange: (value: string) => void;
  onToChange: (value: string) => void;
  onLimitChange: (value: number) => void;
  onPreset: (days: number) => void;
  onApply: () => void;
}) {
  const invalidRange = Boolean(from && to && from > to);

  return (
    <div className="rounded-xl border border-border bg-white p-4 shadow-sm">
      <div className="grid gap-3 lg:grid-cols-[1fr_auto] lg:items-end">
        <div className="grid gap-3 sm:grid-cols-3">
          <AnalyticsFilterInput label="Từ ngày">
            <input
              type="date"
              value={from}
              max={to || undefined}
              onChange={(event) => onFromChange(event.target.value)}
              className="focus-ring h-11 rounded-xl border border-border bg-white px-3 text-sm text-foreground shadow-sm invalid:border-danger"
            />
          </AnalyticsFilterInput>
          <AnalyticsFilterInput label="Đến ngày">
            <input
              type="date"
              value={to}
              min={from || undefined}
              onChange={(event) => onToChange(event.target.value)}
              className="focus-ring h-11 rounded-xl border border-border bg-white px-3 text-sm text-foreground shadow-sm invalid:border-danger"
            />
          </AnalyticsFilterInput>
          <AnalyticsFilterInput label="Hiển thị">
            <select value={limit} onChange={(event) => onLimitChange(Number(event.target.value))} className="focus-ring h-11 rounded-xl border border-border bg-white px-3 text-sm font-semibold text-foreground shadow-sm">
              {[5, 10, 20].map((value) => (
                <option key={value} value={value}>
                  Top {value}
                </option>
              ))}
            </select>
          </AnalyticsFilterInput>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {[7, 30].map((days) => (
            <Button key={days} type="button" size="sm" variant="secondary" onClick={() => onPreset(days)}>
              {days} ngày
            </Button>
          ))}
          <Button type="button" size="sm" variant="primary" onClick={onApply} disabled={loading || invalidRange}>
            {loading ? <Spinner /> : <ListFilter className="h-4 w-4" />}
            Áp dụng
          </Button>
        </div>
      </div>
    </div>
  );
}
