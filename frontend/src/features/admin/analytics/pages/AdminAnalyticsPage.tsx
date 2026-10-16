import { useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { AlertTriangle, BarChart3, Gauge, MessageSquare } from "lucide-react";
import { lastDays, rangeLength } from "@/shared/lib/dates";
import { formatLatency, formatNumber } from "@/shared/lib/format";
import { Button } from "@/shared/ui/Button";
import { EmptyState } from "@/shared/ui/EmptyState";
import { Notice } from "@/shared/ui/Notice";
import { PageHeader } from "@/shared/ui/PageHeader";
import { RefreshButton } from "@/shared/ui/RefreshButton";
import { analyticsApi, analyticsKeys } from "../api";
import {
  AnalyticsFilterBar,
  AnalyticsShell,
  AnalyticsStat,
  BreakdownCard,
  summarizeByKey,
  type KeySummary,
} from "../components/AnalyticsWidgets";
import type { AnalyticsFilter, PerformanceStat } from "../types";

type Tab = "intent" | "error" | "performance";

const TABS: Array<{ key: Tab; label: string; icon: React.ReactNode }> = [
  { key: "intent", label: "Câu hỏi", icon: <MessageSquare className="h-4 w-4" /> },
  { key: "error", label: "Lỗi", icon: <AlertTriangle className="h-4 w-4" /> },
  { key: "performance", label: "Hiệu năng", icon: <Gauge className="h-4 w-4" /> },
];

const DEFAULT_DAYS = 7;
const DEFAULT_LIMIT = 10;

function Breakdown({
  summaries,
  loading,
  emptyText,
  totalLabel,
  groupLabel,
  windowDays,
  tone,
}: {
  summaries: KeySummary[];
  loading: boolean;
  emptyText: string;
  totalLabel: string;
  groupLabel: string;
  windowDays: number;
  tone?: "blue" | "red";
}) {
  if (summaries.length === 0) {
    return <EmptyState title={loading ? "Đang tải..." : emptyText} />;
  }
  const total = summaries.reduce((sum, summary) => sum + summary.total, 0);
  const maxTotal = Math.max(1, ...summaries.map((summary) => summary.total));
  return (
    <div className="grid gap-4">
      <div className="grid gap-3 md:grid-cols-3">
        <AnalyticsStat label={totalLabel} value={formatNumber(total)} />
        <AnalyticsStat label={groupLabel} value={formatNumber(summaries.length)} />
        <AnalyticsStat label="Cửa sổ" value={`${windowDays} ngày`} />
      </div>
      <div className="grid gap-4 xl:grid-cols-2">
        {summaries.map((summary) => (
          <BreakdownCard key={summary.key} summary={summary} maxTotal={maxTotal} tone={tone} />
        ))}
      </div>
    </div>
  );
}

function LatencyCard({ stat, maxLatency }: { stat: PerformanceStat; maxLatency: number }) {
  const bars = [
    { label: "Average", value: stat.avgLatency, color: "bg-accent" },
    { label: "P95", value: stat.p95Latency, color: "bg-warning" },
    { label: "P99", value: stat.p99Latency, color: "bg-danger" },
  ];
  return (
    <article className="rounded-lg border border-border bg-white p-5 shadow-sm">
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="font-mono text-xs uppercase tracking-[0.14em] text-accent">{stat.model || "unknown"}</p>
          <p className="mt-1 text-sm text-muted-foreground">Latency theo model/tool</p>
        </div>
        <BarChart3 className="h-5 w-5 text-accent" />
      </div>
      <div className="mt-5 space-y-4">
        {bars.map((bar) => (
          <div key={bar.label}>
            <div className="mb-1 flex items-center justify-between gap-3 text-sm">
              <span className="text-muted-foreground">{bar.label}</span>
              <span className="font-mono font-semibold text-foreground">{formatLatency(bar.value)}</span>
            </div>
            <div className="h-2 overflow-hidden rounded-full bg-muted">
              <div className={`h-full rounded-full ${bar.color}`} style={{ width: `${Math.min(100, ((bar.value || 0) / maxLatency) * 100)}%` }} />
            </div>
          </div>
        ))}
      </div>
    </article>
  );
}

export function AdminAnalyticsPage() {
  const [draft, setDraft] = useState<AnalyticsFilter>(() => ({ ...lastDays(DEFAULT_DAYS), limit: DEFAULT_LIMIT }));
  const [filter, setFilter] = useState(draft);
  const [tab, setTab] = useState<Tab>("intent");

  const options = { placeholderData: keepPreviousData };
  const intents = useQuery({ queryKey: analyticsKeys.intents(filter), queryFn: () => analyticsApi.intents(filter), ...options });
  const errors = useQuery({ queryKey: analyticsKeys.errors(filter), queryFn: () => analyticsApi.errors(filter), ...options });
  const performance = useQuery({
    queryKey: analyticsKeys.performance(filter),
    queryFn: () => analyticsApi.performance(filter),
    ...options,
  });
  const queries = [intents, errors, performance];
  const loading = queries.some((query) => query.isFetching);
  const failures = [
    intents.error ? "Không thể tải question analytics." : null,
    errors.error ? "Không thể tải error analytics." : null,
    performance.error ? "Không thể tải performance analytics." : null,
  ].filter(Boolean);

  function applyPreset(days: number) {
    const next = { ...lastDays(days), limit: draft.limit };
    setDraft(next);
    setFilter(next);
  }

  const windowDays = rangeLength(filter);
  const performanceStats = performance.data ?? [];
  const maxLatency = Math.max(1, ...performanceStats.map((stat) => stat.p99Latency || stat.p95Latency || stat.avgLatency || 0));

  return (
    <>
      <PageHeader
        eyebrow="Admin Analytics"
        title="Phân tích hệ thống"
        actions={<RefreshButton loading={loading} onClick={() => queries.forEach((query) => void query.refetch())} />}
      />
      {failures.length > 0 ? (
        <Notice tone="warning" className="mb-6" title="Một số dữ liệu chưa tải được">
          {failures.join(" ")}
        </Notice>
      ) : null}

      <div className="grid gap-6">
        <AnalyticsFilterBar filter={draft} loading={loading} onChange={setDraft} onPreset={applyPreset} onApply={() => setFilter(draft)} />

        <div className="flex flex-wrap gap-2">
          {TABS.map((item) => (
            <Button key={item.key} type="button" size="sm" variant={tab === item.key ? "primary" : "secondary"} onClick={() => setTab(item.key)}>
              {item.icon}
              {item.label}
            </Button>
          ))}
        </div>

        {tab === "intent" ? (
          <AnalyticsShell title="Question analytics" icon={<MessageSquare className="h-5 w-5" />} filter={filter}>
            <Breakdown
              summaries={summarizeByKey(intents.data ?? [], (stat) => stat.intent || "unknown")}
              loading={intents.isFetching}
              emptyText="Chưa có dữ liệu câu hỏi."
              totalLabel="Tổng câu hỏi"
              groupLabel="Nhóm intent"
              windowDays={windowDays}
            />
          </AnalyticsShell>
        ) : null}

        {tab === "error" ? (
          <AnalyticsShell title="Error analytics" icon={<AlertTriangle className="h-5 w-5" />} filter={filter} tone="red">
            <Breakdown
              summaries={summarizeByKey(errors.data ?? [], (stat) => `${stat.service || "unknown"} · ${stat.errorType || "SYSTEM"}`)}
              loading={errors.isFetching}
              emptyText="Chưa có lỗi nổi bật."
              totalLabel="Tổng lỗi"
              groupLabel="Nhóm service"
              windowDays={windowDays}
              tone="red"
            />
          </AnalyticsShell>
        ) : null}

        {tab === "performance" ? (
          <AnalyticsShell title="Performance analytics" icon={<Gauge className="h-5 w-5" />} filter={filter}>
            <div className="grid gap-4">
              <div className="grid gap-3 md:grid-cols-3">
                <AnalyticsStat label="Model/tool" value={formatNumber(performanceStats.length)} />
                <AnalyticsStat label="Cửa sổ" value={`${windowDays} ngày`} />
                <AnalyticsStat label="Top chậm nhất" value={formatNumber(filter.limit)} />
              </div>
              {performanceStats.length === 0 ? (
                <EmptyState title={performance.isFetching ? "Đang tải..." : "Chưa có dữ liệu latency."} />
              ) : (
                <div className="grid gap-4 lg:grid-cols-3">
                  {performanceStats.map((stat) => (
                    <LatencyCard key={stat.model} stat={stat} maxLatency={maxLatency} />
                  ))}
                </div>
              )}
            </div>
          </AnalyticsShell>
        ) : null}
      </div>
    </>
  );
}
