import { useEffect, useState } from "react";
import { AlertTriangle, BarChart3, Gauge, MessageSquare, RefreshCw } from "lucide-react";
import { apiGet } from "../services/api";
import type { ErrorAnalytics, IntentAnalytics, PerformanceAnalytics } from "../lib/types";
import { formatNumber } from "../lib/formatters";
import { DashboardLayout } from "../components/dashboard/DashboardLayout";
import { EmptyState } from "../components/dashboard/EmptyState";
import { Badge } from "../components/ui/Badge";
import { Button } from "../components/ui/Button";
import { Spinner } from "../components/ui/Spinner";
import {
  AnalyticsFilter,
  AnalyticsFilterBar,
  AnalyticsShell,
  AnalyticsStat,
  BreakdownCard,
  analyticsParams,
  analyticsRange,
  analyticsWindowDays,
  defaultAnalyticsFilter,
  formatAnalyticsDate,
  formatLatency,
  summarizeByKey,
  totalCount,
} from "../components/dashboard/analytics";

type AnalyticsTab = "intent" | "error" | "performance";

const TABS: Array<{ key: AnalyticsTab; label: string; icon: React.ReactNode }> = [
  { key: "intent", label: "Câu hỏi", icon: <MessageSquare className="h-4 w-4" /> },
  { key: "error", label: "Lỗi", icon: <AlertTriangle className="h-4 w-4" /> },
  { key: "performance", label: "Hiệu năng", icon: <Gauge className="h-4 w-4" /> },
];

export function AdminAnalyticsPage() {
  const [filter, setFilter] = useState<AnalyticsFilter>(() => defaultAnalyticsFilter());
  const [activeTab, setActiveTab] = useState<AnalyticsTab>("intent");
  const [intentData, setIntentData] = useState<IntentAnalytics[]>([]);
  const [errorData, setErrorData] = useState<ErrorAnalytics[]>([]);
  const [performanceData, setPerformanceData] = useState<PerformanceAnalytics[]>([]);
  const [loading, setLoading] = useState(true);
  const [errors, setErrors] = useState<string[]>([]);

  async function loadAnalytics(current = filter) {
    setLoading(true);
    setErrors([]);
    const params = analyticsParams(current);

    const [intentResult, errorResult, performanceResult] = await Promise.allSettled([
      apiGet<IntentAnalytics[]>(`/api/admin/analytics/intents?${params}`),
      apiGet<ErrorAnalytics[]>(`/api/admin/analytics/errors?${params}`),
      apiGet<PerformanceAnalytics[]>(`/api/admin/analytics/performance?${params}`),
    ]);

    const nextErrors: string[] = [];
    if (intentResult.status === "fulfilled") setIntentData(intentResult.value);
    else nextErrors.push("Không thể tải question analytics.");
    if (errorResult.status === "fulfilled") setErrorData(errorResult.value);
    else nextErrors.push("Không thể tải error analytics.");
    if (performanceResult.status === "fulfilled") setPerformanceData(performanceResult.value);
    else nextErrors.push("Không thể tải performance analytics.");

    setErrors(nextErrors);
    setLoading(false);
  }

  useEffect(() => {
    void loadAnalytics();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function applyPreset(days: number) {
    const nextFilter = { ...analyticsRange(days), limit: filter.limit };
    setFilter(nextFilter);
    void loadAnalytics(nextFilter);
  }

  const intentSummaries = summarizeByKey(intentData, (item) => item.intent || "unknown");
  const errorSummaries = summarizeByKey(errorData, (item) => `${item.service || "unknown"} · ${item.errorType || "SYSTEM"}`);
  const intentTotal = totalCount(intentData);
  const errorTotal = totalCount(errorData);
  const maxIntentTotal = Math.max(1, ...intentSummaries.map((summary) => summary.total));
  const maxErrorTotal = Math.max(1, ...errorSummaries.map((summary) => summary.total));
  const maxLatency = Math.max(1, ...performanceData.map((item) => item.p99Latency || item.p95Latency || item.avgLatency || 0));
  const windowDays = analyticsWindowDays(filter.from, filter.to);

  return (
    <DashboardLayout
      eyebrow="Admin Analytics"
      title="Phân tích hệ thống"
      actions={
        <Button type="button" variant="secondary" onClick={() => void loadAnalytics()} disabled={loading}>
          {loading ? <Spinner /> : <RefreshCw className="h-4 w-4" />}
          Làm mới
        </Button>
      }
    >
      {errors.length > 0 ? (
        <div className="mb-6 rounded-lg border border-warning/30 bg-warning/10 p-4 text-sm text-muted-foreground">
          <p className="font-semibold text-foreground">Một số dữ liệu chưa tải được</p>
          <p className="mt-1">{errors.join(" ")}</p>
        </div>
      ) : null}

      <div className="grid gap-6">
        <AnalyticsFilterBar
          from={filter.from}
          to={filter.to}
          limit={filter.limit}
          loading={loading}
          onFromChange={(from) => setFilter((current) => ({ ...current, from }))}
          onToChange={(to) => setFilter((current) => ({ ...current, to }))}
          onLimitChange={(limit) => setFilter((current) => ({ ...current, limit }))}
          onPreset={applyPreset}
          onApply={() => void loadAnalytics()}
        />

        <div className="flex flex-wrap gap-2">
          {TABS.map((tab) => (
            <Button
              key={tab.key}
              type="button"
              size="sm"
              variant={activeTab === tab.key ? "primary" : "secondary"}
              onClick={() => setActiveTab(tab.key)}
            >
              {tab.icon}
              {tab.label}
            </Button>
          ))}
        </div>

        {activeTab === "intent" ? (
          <AnalyticsShell
            title="Question analytics"
            icon={<MessageSquare className="h-5 w-5" />}
            meta={
              <>
                <Badge tone="blue">{formatAnalyticsDate(filter.from)}</Badge>
                <span className="font-mono text-xs text-muted-foreground">→</span>
                <Badge tone="blue">{formatAnalyticsDate(filter.to)}</Badge>
              </>
            }
          >
            {intentSummaries.length === 0 ? (
              <EmptyState title={loading ? "Đang tải..." : "Chưa có dữ liệu câu hỏi."} />
            ) : (
              <div className="grid gap-4">
                <div className="grid gap-3 md:grid-cols-3">
                  <AnalyticsStat label="Tổng câu hỏi" value={formatNumber(intentTotal)} />
                  <AnalyticsStat label="Nhóm intent" value={formatNumber(intentSummaries.length)} />
                  <AnalyticsStat label="Cửa sổ" value={`${windowDays} ngày`} />
                </div>
                <div className="grid gap-4 xl:grid-cols-2">
                  {intentSummaries.map((summary) => (
                    <BreakdownCard key={summary.key} title={summary.key} total={summary.total} days={summary.days} maxTotal={maxIntentTotal} />
                  ))}
                </div>
              </div>
            )}
          </AnalyticsShell>
        ) : null}

        {activeTab === "error" ? (
          <AnalyticsShell
            title="Error analytics"
            icon={<AlertTriangle className="h-5 w-5" />}
            meta={
              <>
                <Badge tone="red">{formatAnalyticsDate(filter.from)}</Badge>
                <span className="font-mono text-xs text-muted-foreground">→</span>
                <Badge tone="red">{formatAnalyticsDate(filter.to)}</Badge>
              </>
            }
          >
            {errorSummaries.length === 0 ? (
              <EmptyState title={loading ? "Đang tải..." : "Chưa có lỗi nổi bật."} />
            ) : (
              <div className="grid gap-4">
                <div className="grid gap-3 md:grid-cols-3">
                  <AnalyticsStat label="Tổng lỗi" value={formatNumber(errorTotal)} />
                  <AnalyticsStat label="Nhóm service" value={formatNumber(errorSummaries.length)} />
                  <AnalyticsStat label="Cửa sổ" value={`${windowDays} ngày`} />
                </div>
                <div className="grid gap-4 xl:grid-cols-2">
                  {errorSummaries.map((summary) => (
                    <BreakdownCard key={summary.key} title={summary.key} total={summary.total} days={summary.days} maxTotal={maxErrorTotal} tone="red" />
                  ))}
                </div>
              </div>
            )}
          </AnalyticsShell>
        ) : null}

        {activeTab === "performance" ? (
          <AnalyticsShell
            title="Performance analytics"
            icon={<Gauge className="h-5 w-5" />}
            meta={
              <>
                <Badge tone="blue">{formatAnalyticsDate(filter.from)}</Badge>
                <span className="font-mono text-xs text-muted-foreground">→</span>
                <Badge tone="blue">{formatAnalyticsDate(filter.to)}</Badge>
              </>
            }
          >
            <div className="grid gap-4">
              <div className="grid gap-3 md:grid-cols-3">
                <AnalyticsStat label="Model/tool" value={formatNumber(performanceData.length)} />
                <AnalyticsStat label="Cửa sổ" value={`${windowDays} ngày`} />
                <AnalyticsStat label="Top chậm nhất" value={formatNumber(filter.limit)} />
              </div>
              {performanceData.length === 0 ? (
                <EmptyState title={loading ? "Đang tải..." : "Chưa có dữ liệu latency."} />
              ) : (
                <div className="grid gap-4 lg:grid-cols-3">
                  {performanceData.map((item) => {
                    const avgWidth = Math.min(100, ((item.avgLatency || 0) / maxLatency) * 100);
                    const p95Width = Math.min(100, ((item.p95Latency || 0) / maxLatency) * 100);
                    const p99Width = Math.min(100, ((item.p99Latency || 0) / maxLatency) * 100);

                    return (
                      <article key={item.model} className="rounded-lg border border-border bg-white p-5 shadow-sm">
                        <div className="flex items-start justify-between gap-3">
                          <div>
                            <p className="font-mono text-xs uppercase tracking-[0.14em] text-accent">{item.model || "unknown"}</p>
                            <p className="mt-1 text-sm text-muted-foreground">Latency theo model/tool</p>
                          </div>
                          <BarChart3 className="h-5 w-5 text-accent" />
                        </div>
                        <div className="mt-5 space-y-4">
                          {[
                            ["Average", item.avgLatency, avgWidth, "bg-accent"],
                            ["P95", item.p95Latency, p95Width, "bg-warning"],
                            ["P99", item.p99Latency, p99Width, "bg-danger"],
                          ].map(([label, value, width, color]) => (
                            <div key={label as string}>
                              <div className="mb-1 flex items-center justify-between gap-3 text-sm">
                                <span className="text-muted-foreground">{label}</span>
                                <span className="font-mono font-semibold text-foreground">{formatLatency(value as number)}</span>
                              </div>
                              <div className="h-2 overflow-hidden rounded-full bg-muted">
                                <div className={`h-full rounded-full ${color}`} style={{ width: `${width}%` }} />
                              </div>
                            </div>
                          ))}
                        </div>
                      </article>
                    );
                  })}
                </div>
              )}
            </div>
          </AnalyticsShell>
        ) : null}
      </div>
    </DashboardLayout>
  );
}
