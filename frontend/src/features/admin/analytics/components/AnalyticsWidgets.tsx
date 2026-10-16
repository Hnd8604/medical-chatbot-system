import { CalendarDays, ListFilter } from "lucide-react";
import { formatNumber, formatWeekday } from "@/shared/lib/format";
import { Badge } from "@/shared/ui/Badge";
import { Button } from "@/shared/ui/Button";
import { FilterField, filterInputClass } from "@/shared/ui/Field";
import { SectionLabel } from "@/shared/ui/SectionLabel";
import { Spinner } from "@/shared/ui/Spinner";
import type { AnalyticsFilter } from "../types";

export interface DailyCount {
  date: string;
  count: number;
}

export interface KeySummary {
  key: string;
  total: number;
  days: DailyCount[];
}

/** Groups daily rows by key, largest total first. */
export function summarizeByKey<T extends DailyCount>(items: T[], keyOf: (item: T) => string): KeySummary[] {
  const groups = new Map<string, T[]>();
  for (const item of items) {
    const key = keyOf(item);
    groups.set(key, [...(groups.get(key) ?? []), item]);
  }
  return Array.from(groups, ([key, rows]) => ({
    key,
    total: rows.reduce((sum, row) => sum + row.count, 0),
    days: rows,
  })).sort((left, right) => right.total - left.total);
}

export function AnalyticsStat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-border bg-white px-4 py-3 shadow-sm">
      <p className="font-mono text-[11px] uppercase tracking-[0.14em] text-muted-foreground">{label}</p>
      <p className="mt-1 font-display text-2xl text-foreground">{value}</p>
    </div>
  );
}

export function AnalyticsShell({
  title,
  icon,
  filter,
  tone = "blue",
  children,
}: {
  title: string;
  icon: React.ReactNode;
  filter: AnalyticsFilter;
  tone?: "blue" | "red";
  children: React.ReactNode;
}) {
  return (
    <section className="overflow-hidden rounded-xl border border-border bg-white shadow-sm">
      <div className="flex flex-wrap items-start justify-between gap-4 border-b border-border bg-white px-5 py-4">
        <div className="min-w-0">
          <SectionLabel>Advanced analytics</SectionLabel>
          <h2 className="mt-3 font-display text-2xl text-foreground">{title}</h2>
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <Badge tone={tone}>{formatWeekday(filter.from)}</Badge>
            <span className="font-mono text-xs text-muted-foreground">→</span>
            <Badge tone={tone}>{formatWeekday(filter.to)}</Badge>
          </div>
        </div>
        <div className="grid h-11 w-11 place-items-center rounded-xl gradient-surface text-white">{icon}</div>
      </div>
      <div className="bg-gradient-to-b from-muted/40 to-white p-5">{children}</div>
    </section>
  );
}

export function BreakdownCard({ summary, maxTotal, tone = "blue" }: { summary: KeySummary; maxTotal: number; tone?: "blue" | "red" }) {
  const peak = summary.days.reduce<DailyCount | null>((best, day) => (!best || day.count > best.count ? day : best), null);
  const ratio = Math.max(4, Math.min(100, (summary.total / Math.max(maxTotal, 1)) * 100));
  const busiestDays = [...summary.days].sort((left, right) => right.count - left.count).slice(0, 5);
  const accentText = tone === "red" ? "text-danger" : "text-accent";

  return (
    <article className="group rounded-xl border border-border bg-white p-4 shadow-sm transition hover:-translate-y-0.5 hover:border-accent/35 hover:shadow-card">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate font-mono text-xs uppercase tracking-[0.08em] text-foreground">{summary.key.replace(/_/g, " ")}</p>
          <p className="mt-1 text-xs text-muted-foreground">
            {peak ? `${formatWeekday(peak.date)} cao nhất` : `${summary.days.length} ngày`}
          </p>
        </div>
        <Badge tone={tone} className="shrink-0">
          {formatNumber(summary.total)}
        </Badge>
      </div>
      <div className="mt-4 h-2 overflow-hidden rounded-full bg-muted">
        <div className={`h-full rounded-full ${tone === "red" ? "bg-danger" : "gradient-surface"}`} style={{ width: `${ratio}%` }} />
      </div>
      <div className="mt-4 flex flex-wrap gap-2">
        {busiestDays.map((day) => (
          <div key={day.date} className="inline-flex items-center gap-2 rounded-full border border-border bg-muted/60 px-3 py-1.5 text-xs">
            <CalendarDays className={`h-3.5 w-3.5 ${accentText}`} />
            <span className="text-muted-foreground">{formatWeekday(day.date)}</span>
            <span className={`font-semibold ${accentText}`}>{formatNumber(day.count)}</span>
          </div>
        ))}
      </div>
    </article>
  );
}

interface AnalyticsFilterBarProps {
  filter: AnalyticsFilter;
  loading: boolean;
  onChange: (filter: AnalyticsFilter) => void;
  onPreset: (days: number) => void;
  onApply: () => void;
}

export function AnalyticsFilterBar({ filter, loading, onChange, onPreset, onApply }: AnalyticsFilterBarProps) {
  const invalidRange = Boolean(filter.from && filter.to && filter.from > filter.to);

  return (
    <div className="rounded-xl border border-border bg-white p-4 shadow-sm">
      <div className="grid gap-3 lg:grid-cols-[1fr_auto] lg:items-end">
        <div className="grid gap-3 sm:grid-cols-3">
          <FilterField label="Từ ngày">
            <input
              type="date"
              value={filter.from}
              max={filter.to || undefined}
              onChange={(event) => onChange({ ...filter, from: event.target.value })}
              className={filterInputClass("font-normal")}
            />
          </FilterField>
          <FilterField label="Đến ngày">
            <input
              type="date"
              value={filter.to}
              min={filter.from || undefined}
              onChange={(event) => onChange({ ...filter, to: event.target.value })}
              className={filterInputClass("font-normal")}
            />
          </FilterField>
          <FilterField label="Hiển thị">
            <select
              value={filter.limit}
              onChange={(event) => onChange({ ...filter, limit: Number(event.target.value) })}
              className={filterInputClass()}
            >
              {[5, 10, 20].map((value) => (
                <option key={value} value={value}>
                  Top {value}
                </option>
              ))}
            </select>
          </FilterField>
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
