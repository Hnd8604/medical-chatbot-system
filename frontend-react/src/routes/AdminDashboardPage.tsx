import { useEffect, useState } from "react";
import { AlertTriangle, BarChart3, Bell, CalendarDays, Database, Gauge, ListFilter, MessageSquare, RefreshCw, Users } from "lucide-react";
import { Link } from "react-router-dom";
import { apiJson, toQuery } from "../lib/api";
import { useAuth } from "../lib/auth";
import type { AdminAlertItem, AdminUserListResponse, CacheMetricsResponse, ErrorAnalytics, IntentAnalytics, NotificationListResponse, PageResponse, PerformanceAnalytics, RequestAnalyticsSummary } from "../lib/types";
import { formatDateTime, formatNumber, formatPercent, formatUsd } from "../lib/formatters";
import { DashboardLayout } from "../components/dashboard/DashboardLayout";
import { EmptyState } from "../components/dashboard/EmptyState";
import { MetricCard } from "../components/dashboard/MetricCard";
import { Badge } from "../components/ui/Badge";
import { Button } from "../components/ui/Button";
import { SectionLabel } from "../components/ui/SectionLabel";
import { Spinner } from "../components/ui/Spinner";

const DEFAULT_ANALYTICS_LIMIT = 10;
const DEFAULT_ANALYTICS_DAYS = 7;

type AnalyticsFilter = {
  from: string;
  to: string;
  limit: number;
};

function pageTotal<T>(page: PageResponse<T> | null) {
  return page?.total_elements ?? page?.totalElements ?? 0;
}

function alertTime(alert: AdminAlertItem) {
  return alert.created_at || alert.createdAt || null;
}

function alertType(alert: AdminAlertItem) {
  return alert.alert_type || alert.alertType || "SYSTEM";
}

function alertStatus(alert: AdminAlertItem) {
  return alert.status || "OPEN";
}

function alertSeverity(alert: AdminAlertItem) {
  return alert.severity || "INFO";
}

function alertPageLabel(page: PageResponse<AdminAlertItem> | null) {
  const total = pageTotal(page);
  if (!total) return "0 alert";
  return `${formatNumber(total)} alert`;
}

function formatAnalyticsDate(value: string) {
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

function toIsoDate(date: Date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function analyticsRange(days: number) {
  const to = new Date();
  const from = new Date(to);
  from.setDate(to.getDate() - days + 1);
  return {
    from: toIsoDate(from),
    to: toIsoDate(to),
  };
}

function defaultAnalyticsFilter(): AnalyticsFilter {
  return {
    ...analyticsRange(DEFAULT_ANALYTICS_DAYS),
    limit: DEFAULT_ANALYTICS_LIMIT,
  };
}

function analyticsParams(filter: AnalyticsFilter) {
  return `from=${filter.from}&to=${filter.to}&limit=${filter.limit}`;
}

function analyticsWindowDays(from: string, to: string) {
  const start = new Date(from);
  const end = new Date(to);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return 0;
  return Math.max(1, Math.round((end.getTime() - start.getTime()) / 86_400_000) + 1);
}

function displayKey(value: string) {
  return value.replace(/_/g, " ");
}

function summarizeByKey<T extends { date: string; count: number }>(items: T[], keyOf: (item: T) => string) {
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

function totalCount(items: Array<{ count: number }>) {
  return items.reduce((sum, item) => sum + item.count, 0);
}

function formatLatency(value: number | null | undefined) {
  if (value === null || value === undefined || Number.isNaN(value)) return "0 ms";
  return `${Math.round(value).toLocaleString("vi-VN")} ms`;
}

function AnalyticsStat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-border bg-white px-4 py-3 shadow-sm">
      <p className="font-mono text-[11px] uppercase tracking-[0.14em] text-muted-foreground">{label}</p>
      <p className="mt-1 font-display text-2xl text-foreground">{value}</p>
    </div>
  );
}

function AnalyticsFilterInput({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="grid gap-1">
      <span className="font-mono text-[10px] uppercase tracking-[0.14em] text-muted-foreground">{label}</span>
      {children}
    </label>
  );
}

function QuickLink({ to, title, description }: { to: string; title: string; description: string }) {
  return (
    <Link to={to} className="focus-ring group rounded-lg border border-border bg-white p-5 shadow-sm transition hover:-translate-y-0.5 hover:border-accent/35 hover:shadow-card">
      <p className="font-semibold text-foreground">{title}</p>
      <p className="mt-1 text-sm leading-6 text-muted-foreground">{description}</p>
    </Link>
  );
}

function AnalyticsShell({ title, icon, meta, children }: { title: string; icon: React.ReactNode; meta?: React.ReactNode; children: React.ReactNode }) {
  return (
    <section className="overflow-hidden rounded-xl border border-border bg-white shadow-sm">
      <div className="flex flex-wrap items-start justify-between gap-4 border-b border-border bg-white px-5 py-4">
        <div className="min-w-0">
          <SectionLabel>Advanced analytics</SectionLabel>
          <h2 className="mt-3 font-display text-2xl text-foreground">{title}</h2>
          {meta ? <div className="mt-3 flex flex-wrap items-center gap-2">{meta}</div> : null}
        </div>
        <div className="grid h-11 w-11 place-items-center rounded-xl gradient-surface text-white shadow-accent">{icon}</div>
      </div>
      <div className="bg-gradient-to-b from-muted/40 to-white p-5">{children}</div>
    </section>
  );
}

function BreakdownCard({ title, total, days, maxTotal, tone = "blue" }: { title: string; total: number; days: Array<{ date: string; count: number }>; maxTotal: number; tone?: "blue" | "red" }) {
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

function AnalyticsFilterBar({
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

export function AdminDashboardPage() {
  const [usersTotal, setUsersTotal] = useState<number | null>(null);
  const [requestSummary, setRequestSummary] = useState<RequestAnalyticsSummary | null>(null);
  const [cacheMetrics, setCacheMetrics] = useState<CacheMetricsResponse | null>(null);
  const [alertPage, setAlertPage] = useState<PageResponse<AdminAlertItem> | null>(null);
  const [alerts, setAlerts] = useState<AdminAlertItem[]>([]);
  const [alertStatusFilter, setAlertStatusFilter] = useState("OPEN");
  const [alertSeverityFilter, setAlertSeverityFilter] = useState("ALL");
  const [alertsLoading, setAlertsLoading] = useState(true);
  const [resolvingAlertId, setResolvingAlertId] = useState<string | null>(null);
  const [notifications, setNotifications] = useState<NotificationListResponse | null>(null);
  const [intentData, setIntentData] = useState<IntentAnalytics[]>([]);
  const [errorData, setErrorData] = useState<ErrorAnalytics[]>([]);
  const [performanceData, setPerformanceData] = useState<PerformanceAnalytics[]>([]);
  const [intentFilter, setIntentFilter] = useState<AnalyticsFilter>(() => defaultAnalyticsFilter());
  const [errorFilter, setErrorFilter] = useState<AnalyticsFilter>(() => defaultAnalyticsFilter());
  const [performanceFilter, setPerformanceFilter] = useState<AnalyticsFilter>(() => defaultAnalyticsFilter());
  const [loading, setLoading] = useState(true);
  const [intentLoading, setIntentLoading] = useState(true);
  const [errorLoading, setErrorLoading] = useState(true);
  const [performanceLoading, setPerformanceLoading] = useState(true);
  const [errors, setErrors] = useState<string[]>([]);

  const { user } = useAuth();

  async function loadIntentAnalytics(filter = intentFilter) {
    setIntentLoading(true);
    try {
      setIntentData(await apiJson<IntentAnalytics[]>(`/api/admin/analytics/intents?${analyticsParams(filter)}`));
    } catch {
      setErrors((current) => [...current, "Không thể tải question analytics."]);
    } finally {
      setIntentLoading(false);
    }
  }

  async function loadErrorAnalytics(filter = errorFilter) {
    setErrorLoading(true);
    try {
      setErrorData(await apiJson<ErrorAnalytics[]>(`/api/admin/analytics/errors?${analyticsParams(filter)}`));
    } catch {
      setErrors((current) => [...current, "Không thể tải error analytics."]);
    } finally {
      setErrorLoading(false);
    }
  }

  async function loadPerformanceAnalytics(filter = performanceFilter) {
    setPerformanceLoading(true);
    try {
      setPerformanceData(await apiJson<PerformanceAnalytics[]>(`/api/admin/analytics/performance?${analyticsParams(filter)}`));
    } catch {
      setErrors((current) => [...current, "Không thể tải performance analytics."]);
    } finally {
      setPerformanceLoading(false);
    }
  }

  async function loadAnalytics() {
    await Promise.all([loadIntentAnalytics(), loadErrorAnalytics(), loadPerformanceAnalytics()]);
  }

  function applyAnalyticsPreset(days: number, currentFilter: AnalyticsFilter, setFilter: React.Dispatch<React.SetStateAction<AnalyticsFilter>>, loadFiltered: (filter: AnalyticsFilter) => Promise<void>) {
    const nextFilter = {
      ...analyticsRange(days),
      limit: currentFilter.limit,
    };
    setFilter(nextFilter);
    void loadFiltered(nextFilter);
  }

  async function loadAlerts(status = alertStatusFilter, severity = alertSeverityFilter, page = 0) {
    setAlertsLoading(true);
    const query = toQuery({
      status: status === "ALL" ? null : status,
      severity: severity === "ALL" ? null : severity,
      page,
      size: 8,
      sort: "createdAt,desc",
    });

    try {
      const result = await apiJson<PageResponse<AdminAlertItem>>(`/api/admin/alerts${query}`);
      setAlertPage(result);
      setAlerts(result.content || []);
    } catch {
      setErrors((current) => [...current, "Không thể tải alert gần đây."]);
    } finally {
      setAlertsLoading(false);
    }
  }

  async function resolveAlert(alertId: string) {
    setResolvingAlertId(alertId);
    try {
      const resolvedBy = encodeURIComponent(user?.username || "admin");
      await apiJson<void>(`/api/admin/alerts/${alertId}/resolve?resolvedBy=${resolvedBy}`, {
        method: "PATCH",
      });
      await loadAlerts();
    } catch {
      setErrors((current) => [...current, "Không thể đánh dấu alert đã xử lý."]);
    } finally {
      setResolvingAlertId(null);
    }
  }

  async function loadDashboard() {
    setLoading(true);
    setErrors([]);

    const summaryRange = analyticsRange(DEFAULT_ANALYTICS_DAYS);
    const [usersResult, requestResult, cacheResult, notificationsResult] = await Promise.allSettled([
      apiJson<AdminUserListResponse>("/api/admin/users?page=0&size=1"),
      apiJson<RequestAnalyticsSummary>(`/api/admin/analytics/requests?from=${summaryRange.from}&to=${summaryRange.to}`),
      apiJson<CacheMetricsResponse>("/api/metrics/cache"),
      apiJson<NotificationListResponse>("/api/notifications"),
    ]);

    const nextErrors: string[] = [];

    if (usersResult.status === "fulfilled") {
      setUsersTotal(usersResult.value.total_elements);
    } else {
      nextErrors.push("Không thể tải số user.");
    }

    if (requestResult.status === "fulfilled") {
      setRequestSummary(requestResult.value);
    } else {
      setRequestSummary(null);
    }

    if (cacheResult.status === "fulfilled") {
      setCacheMetrics(cacheResult.value);
    } else {
      nextErrors.push("Không thể tải cache metrics.");
    }

    if (notificationsResult.status === "fulfilled") {
      setNotifications(notificationsResult.value);
    } else {
      nextErrors.push("Không thể tải thông báo.");
    }

    setErrors(nextErrors);
    setLoading(false);
    void loadAlerts();
    void loadAnalytics();
  }

  useEffect(() => {
    void loadDashboard();
  }, []);

  const intentSummaries = summarizeByKey(intentData, (item) => item.intent || "unknown");
  const errorSummaries = summarizeByKey(errorData, (item) => `${item.service || "unknown"} · ${item.errorType || "SYSTEM"}`);
  const intentTotal = totalCount(intentData);
  const errorTotal = totalCount(errorData);
  const maxIntentTotal = Math.max(1, ...intentSummaries.map((summary) => summary.total));
  const maxErrorTotal = Math.max(1, ...errorSummaries.map((summary) => summary.total));
  const maxLatency = Math.max(1, ...performanceData.map((item) => item.p99Latency || item.p95Latency || item.avgLatency || 0));
  const intentWindow = analyticsWindowDays(intentFilter.from, intentFilter.to);
  const errorWindow = analyticsWindowDays(errorFilter.from, errorFilter.to);
  const performanceWindow = analyticsWindowDays(performanceFilter.from, performanceFilter.to);

  return (
    <DashboardLayout
      eyebrow="Admin Dashboard"
      title="Tổng quan hệ thống"
      description="Theo dõi nhanh người dùng, request, cache, alert, notification và các phân tích hệ thống"
      actions={
        <Button type="button" variant="secondary" onClick={() => void loadDashboard()} disabled={loading}>
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

      {loading && usersTotal === null ? (
        <div className="grid min-h-[18rem] place-items-center rounded-lg border border-border bg-white">
          <div className="flex items-center gap-3 text-muted-foreground">
            <Spinner className="text-accent" />
            Đang tải dashboard...
          </div>
        </div>
      ) : (
        <div className="grid gap-7">
          <section className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
            <MetricCard label="Số user" value={formatNumber(usersTotal)} description="Tổng tài khoản trong hệ thống." />
            <MetricCard label="Request 7 ngày" value={requestSummary ? formatNumber(requestSummary.request_count) : "-"} description="Tổng request trong usage logs." />
            <MetricCard
              label="Cache hit rate"
              value={formatPercent(cacheMetrics?.hit_rate_percentage)}
              description={`${formatNumber(cacheMetrics?.total_cache_hits)} hit trên ${formatNumber(cacheMetrics?.total_requests)} request cache.`}
              progress={cacheMetrics?.hit_rate_percentage ?? 0}
              progressTone="green"
            />
            <MetricCard label="Tiết kiệm ước tính" value={formatUsd(cacheMetrics?.total_saved_cost_usd)} description={`${formatNumber(cacheMetrics?.total_saved_tokens)} token đã tiết kiệm từ cache.`} />
          </section>

          <section className="grid gap-4 md:grid-cols-3">
            <EmptyState title="Số hội thoại: Chưa có dữ liệu." description="Backend hiện chưa có endpoint tổng hợp số hội thoại toàn hệ thống cho admin dashboard." />
            <QuickLink to="/admin/usage-cost" title="Quota / Cost" description="Policy, bảng giá model và cost theo username." />
            <QuickLink to="/admin/audit-logs" title="Audit log" description="Timeline thao tác, FHIR access, auth và cấu hình." />
          </section>

          <section className="grid gap-6">
            <div className="grid gap-6">
              <AnalyticsShell
                title="Question analytics"
                icon={<MessageSquare className="h-5 w-5" />}
                meta={
                  <>
                    <Badge tone="blue">{formatAnalyticsDate(intentFilter.from)}</Badge>
                    <span className="font-mono text-xs text-muted-foreground">→</span>
                    <Badge tone="blue">{formatAnalyticsDate(intentFilter.to)}</Badge>
                  </>
                }
              >
                <div className="grid gap-4">
                  <AnalyticsFilterBar
                    from={intentFilter.from}
                    to={intentFilter.to}
                    limit={intentFilter.limit}
                    loading={intentLoading}
                    onFromChange={(from) => setIntentFilter((current) => ({ ...current, from }))}
                    onToChange={(to) => setIntentFilter((current) => ({ ...current, to }))}
                    onLimitChange={(limit) => setIntentFilter((current) => ({ ...current, limit }))}
                    onPreset={(days) => applyAnalyticsPreset(days, intentFilter, setIntentFilter, loadIntentAnalytics)}
                    onApply={() => void loadIntentAnalytics()}
                  />
                  {intentSummaries.length === 0 ? (
                    <EmptyState title="Chưa có dữ liệu câu hỏi." />
                  ) : (
                    <>
                      <div className="grid gap-3 md:grid-cols-3">
                        <AnalyticsStat label="Tổng câu hỏi" value={formatNumber(intentTotal)} />
                        <AnalyticsStat label="Nhóm intent" value={formatNumber(intentSummaries.length)} />
                        <AnalyticsStat label="Cửa sổ" value={`${intentWindow} ngày`} />
                      </div>
                      <div className="grid gap-4 xl:grid-cols-2">
                        {intentSummaries.map((summary) => (
                          <BreakdownCard key={summary.key} title={summary.key} total={summary.total} days={summary.days} maxTotal={maxIntentTotal} />
                        ))}
                      </div>
                    </>
                  )}
                </div>
              </AnalyticsShell>

              <AnalyticsShell
                title="Error analytics"
                icon={<AlertTriangle className="h-5 w-5" />}
                meta={
                  <>
                    <Badge tone="red">{formatAnalyticsDate(errorFilter.from)}</Badge>
                    <span className="font-mono text-xs text-muted-foreground">→</span>
                    <Badge tone="red">{formatAnalyticsDate(errorFilter.to)}</Badge>
                  </>
                }
              >
                <div className="grid gap-4">
                  <AnalyticsFilterBar
                    from={errorFilter.from}
                    to={errorFilter.to}
                    limit={errorFilter.limit}
                    loading={errorLoading}
                    onFromChange={(from) => setErrorFilter((current) => ({ ...current, from }))}
                    onToChange={(to) => setErrorFilter((current) => ({ ...current, to }))}
                    onLimitChange={(limit) => setErrorFilter((current) => ({ ...current, limit }))}
                    onPreset={(days) => applyAnalyticsPreset(days, errorFilter, setErrorFilter, loadErrorAnalytics)}
                    onApply={() => void loadErrorAnalytics()}
                  />
                  {errorSummaries.length === 0 ? (
                    <EmptyState title="Chưa có lỗi nổi bật." />
                  ) : (
                    <>
                      <div className="grid gap-3 md:grid-cols-3">
                        <AnalyticsStat label="Tổng lỗi" value={formatNumber(errorTotal)} />
                        <AnalyticsStat label="Nhóm service" value={formatNumber(errorSummaries.length)} />
                        <AnalyticsStat label="Cửa sổ" value={`${errorWindow} ngày`} />
                      </div>
                      <div className="grid gap-4 xl:grid-cols-2">
                        {errorSummaries.map((summary) => (
                          <BreakdownCard key={summary.key} title={summary.key} total={summary.total} days={summary.days} maxTotal={maxErrorTotal} tone="red" />
                        ))}
                      </div>
                    </>
                  )}
                </div>
              </AnalyticsShell>
            </div>

            <AnalyticsShell
              title="Performance analytics"
              icon={<Gauge className="h-5 w-5" />}
              meta={
                <>
                  <Badge tone="blue">{formatAnalyticsDate(performanceFilter.from)}</Badge>
                  <span className="font-mono text-xs text-muted-foreground">→</span>
                  <Badge tone="blue">{formatAnalyticsDate(performanceFilter.to)}</Badge>
                </>
              }
            >
              <div className="grid gap-4">
                <AnalyticsFilterBar
                  from={performanceFilter.from}
                  to={performanceFilter.to}
                  limit={performanceFilter.limit}
                  loading={performanceLoading}
                  onFromChange={(from) => setPerformanceFilter((current) => ({ ...current, from }))}
                  onToChange={(to) => setPerformanceFilter((current) => ({ ...current, to }))}
                  onLimitChange={(limit) => setPerformanceFilter((current) => ({ ...current, limit }))}
                  onPreset={(days) => applyAnalyticsPreset(days, performanceFilter, setPerformanceFilter, loadPerformanceAnalytics)}
                  onApply={() => void loadPerformanceAnalytics()}
                />
                <div className="grid gap-3 md:grid-cols-3">
                  <AnalyticsStat label="Model/tool" value={formatNumber(performanceData.length)} />
                  <AnalyticsStat label="Cửa sổ" value={`${performanceWindow} ngày`} />
                  <AnalyticsStat label="Top chậm nhất" value={formatNumber(performanceFilter.limit)} />
                </div>
                {performanceData.length === 0 ? (
                  <EmptyState title="Chưa có dữ liệu latency." />
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
          </section>

          <section className="rounded-lg border border-border bg-white shadow-sm">
            <div className="flex flex-wrap items-start justify-between gap-3 border-b border-border px-5 py-4">
              <div>
                <SectionLabel>Alert dashboard</SectionLabel>
                <h2 className="mt-3 font-display text-2xl text-foreground">Alert vận hành</h2>
              </div>
              <div className="grid h-11 w-11 place-items-center rounded-xl gradient-surface text-white shadow-accent">
                <Database className="h-5 w-5" />
              </div>
            </div>
            <div className="grid gap-4 p-5">
              <div className="grid gap-3 lg:grid-cols-[1fr_auto] lg:items-end">
                <div className="grid gap-3 sm:grid-cols-2">
                  <AnalyticsFilterInput label="Severity">
                    <select value={alertSeverityFilter} onChange={(event) => setAlertSeverityFilter(event.target.value)} className="focus-ring h-11 rounded-xl border border-border bg-white px-3 text-sm font-semibold text-foreground shadow-sm">
                      <option value="ALL">Tất cả severity</option>
                      <option value="CRITICAL">Critical</option>
                      <option value="WARNING">Warning</option>
                      <option value="INFO">Info</option>
                    </select>
                  </AnalyticsFilterInput>
                  <AnalyticsFilterInput label="Status">
                    <select value={alertStatusFilter} onChange={(event) => setAlertStatusFilter(event.target.value)} className="focus-ring h-11 rounded-xl border border-border bg-white px-3 text-sm font-semibold text-foreground shadow-sm">
                      <option value="ALL">Tất cả status</option>
                      <option value="OPEN">Open</option>
                      <option value="RESOLVED">Resolved</option>
                    </select>
                  </AnalyticsFilterInput>
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone="blue">{alertPageLabel(alertPage)}</Badge>
                  <Button type="button" size="sm" variant="primary" onClick={() => void loadAlerts(alertStatusFilter, alertSeverityFilter)} disabled={alertsLoading}>
                    {alertsLoading ? <Spinner /> : <ListFilter className="h-4 w-4" />}
                    Áp dụng
                  </Button>
                </div>
              </div>

              {alerts.length === 0 ? (
                <EmptyState title={alertsLoading ? "Đang tải alert..." : "Chưa có alert phù hợp."} />
              ) : (
                <div className="divide-y divide-border overflow-hidden rounded-xl border border-border bg-white">
                  {alerts.map((alert, index) => {
                    const status = alertStatus(alert);
                    const severity = alertSeverity(alert);
                    const canResolve = status === "OPEN" && Boolean(alert.id);
                    return (
                      <article key={alert.id || index} className="grid gap-4 px-5 py-4 lg:grid-cols-[1fr_auto] lg:items-center">
                        <div className="min-w-0">
                          <div className="flex flex-wrap items-center gap-2">
                            <Badge tone={severity === "CRITICAL" ? "red" : severity === "WARNING" ? "amber" : "blue"}>{severity}</Badge>
                            <Badge tone={status === "RESOLVED" ? "slate" : "blue"}>{status}</Badge>
                            <span className="font-mono text-xs uppercase tracking-[0.12em] text-muted-foreground">{alertType(alert)}</span>
                          </div>
                          <p className="mt-2 text-sm font-semibold text-foreground">{alert.message || "Không có nội dung alert."}</p>
                          <p className="mt-1 text-xs text-muted-foreground">
                            {alert.source || "SYSTEM"} · {formatDateTime(alertTime(alert))}
                          </p>
                        </div>
                        <Button type="button" size="sm" variant={canResolve ? "secondary" : "ghost"} disabled={!canResolve || resolvingAlertId === alert.id} onClick={() => alert.id && void resolveAlert(alert.id)}>
                          {resolvingAlertId === alert.id ? <Spinner /> : null}
                          {status === "RESOLVED" ? "Đã xử lý" : "Mark resolved"}
                        </Button>
                      </article>
                    );
                  })}
                </div>
              )}
            </div>
          </section>

          <section>
            <div className="rounded-lg border border-border bg-white shadow-sm">
              <div className="flex items-center justify-between gap-3 border-b border-border px-5 py-4">
                <div>
                  <h2 className="font-display text-2xl text-foreground">Thông báo</h2>
                  <p className="text-sm text-muted-foreground">Thông báo của tài khoản admin hiện tại.</p>
                </div>
                <Bell className="h-5 w-5 text-accent" />
              </div>
              {!notifications || notifications.notifications.length === 0 ? (
                <div className="p-5">
                  <EmptyState title="Chưa có thông báo." />
                </div>
              ) : (
                <div className="divide-y divide-border">
                  {notifications.notifications.slice(0, 5).map((notification) => (
                    <article key={notification.id} className="px-5 py-4">
                      <div className="flex items-center gap-2">
                        <Badge tone={notification.is_read ? "slate" : "blue"}>{notification.type}</Badge>
                        {!notification.is_read ? <span className="h-2 w-2 rounded-full bg-accent" /> : null}
                      </div>
                      <p className="mt-2 text-sm font-semibold text-foreground">{notification.title}</p>
                      <p className="mt-1 line-clamp-2 text-sm text-muted-foreground">{notification.content}</p>
                      <p className="mt-2 text-xs text-muted-foreground">{formatDateTime(notification.created_at)}</p>
                    </article>
                  ))}
                </div>
              )}
            </div>
          </section>

          <section>
            <h2 className="mb-4 font-display text-2xl text-foreground">Liên kết nhanh</h2>
            <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
              <QuickLink to="/admin/users" title="Quản lý user" description="Tìm kiếm, đổi role và khóa/mở khóa tài khoản." />
              <QuickLink to="/admin/usage-cost" title="Quota / Cost" description="Tra quota, usage và chi phí theo user." />
              <QuickLink to="/admin/audit-logs" title="Audit log" description="Xem log theo thời gian và lọc theo user/action/resource." />
              <QuickLink to="/usage" title="Usage cá nhân" description="Xem quota, token và chi phí của tài khoản hiện tại." />
              <QuickLink to="/chat" title="Quay về chat" description="Mở giao diện chatbot chính." />
            </div>
          </section>
        </div>
      )}
    </DashboardLayout>
  );
}
