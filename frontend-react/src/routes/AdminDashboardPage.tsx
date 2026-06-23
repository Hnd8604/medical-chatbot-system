import { useEffect, useState } from "react";
import { AlertTriangle, BarChart3, Bell, CalendarDays, Database, FileClock, Gauge, MessageSquare, RefreshCw, Users } from "lucide-react";
import { Link } from "react-router-dom";
import { apiJson } from "../lib/api";
import type {
  AdminAlertItem,
  AdminUserListResponse,
  AuditLogItem,
  CacheMetricsResponse,
  ErrorAnalytics,
  IntentAnalytics,
  NotificationListResponse,
  PageResponse,
  PerformanceAnalytics,
} from "../lib/types";
import { formatDateTime, formatNumber, formatPercent, formatUsd } from "../lib/formatters";
import { DashboardLayout } from "../components/dashboard/DashboardLayout";
import { EmptyState } from "../components/dashboard/EmptyState";
import { MetricCard } from "../components/dashboard/MetricCard";
import { Badge } from "../components/ui/Badge";
import { Button } from "../components/ui/Button";
import { SectionLabel } from "../components/ui/SectionLabel";
import { Spinner } from "../components/ui/Spinner";

const ANALYTICS_PAGE_SIZE = 7;

function pageTotal<T>(page: PageResponse<T> | null) {
  return page?.total_elements ?? page?.totalElements ?? 0;
}

function alertTime(alert: AdminAlertItem) {
  return alert.created_at || alert.createdAt || null;
}

function alertType(alert: AdminAlertItem) {
  return alert.alert_type || alert.alertType || "SYSTEM";
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

function groupByDate<T extends { date: string; count: number }>(items: T[]) {
  const groups = new Map<string, T[]>();
  for (const item of items) {
    const bucket = groups.get(item.date) ?? [];
    bucket.push(item);
    groups.set(item.date, bucket);
  }

  return Array.from(groups.entries())
    .sort(([a], [b]) => b.localeCompare(a))
    .map(([date, rows]) => ({
      date,
      total: rows.reduce((sum, row) => sum + row.count, 0),
      rows: [...rows].sort((a, b) => b.count - a.count),
    }));
}

function formatLatency(value: number | null | undefined) {
  if (value === null || value === undefined || Number.isNaN(value)) return "0 ms";
  return `${Math.round(value).toLocaleString("vi-VN")} ms`;
}

function QuickLink({ to, title, description }: { to: string; title: string; description: string }) {
  return (
    <Link
      to={to}
      className="focus-ring group rounded-lg border border-border bg-white p-5 shadow-sm transition hover:-translate-y-0.5 hover:border-accent/35 hover:shadow-card"
    >
      <p className="font-semibold text-foreground">{title}</p>
      <p className="mt-1 text-sm leading-6 text-muted-foreground">{description}</p>
    </Link>
  );
}

function AnalyticsShell({
  title,
  icon,
  children,
}: {
  title: string;
  icon: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-lg border border-border bg-white shadow-sm">
      <div className="flex flex-wrap items-start justify-between gap-4 border-b border-border px-5 py-4">
        <div>
          <SectionLabel>Advanced analytics</SectionLabel>
          <h2 className="mt-3 font-display text-2xl text-foreground">{title}</h2>
        </div>
        <div className="grid h-11 w-11 place-items-center rounded-xl gradient-surface text-white shadow-accent">{icon}</div>
      </div>
      <div className="p-5">{children}</div>
    </section>
  );
}

function DateGroupCard({
  date,
  total,
  children,
}: {
  date: string;
  total: number;
  children: React.ReactNode;
}) {
  return (
    <article className="rounded-lg border border-border bg-white p-4 shadow-sm transition hover:-translate-y-0.5 hover:border-accent/35 hover:shadow-card">
      <div className="flex items-start justify-between gap-3">
        <div className="flex min-w-0 items-center gap-2">
          <CalendarDays className="h-4 w-4 shrink-0 text-accent" />
          <p className="font-semibold text-foreground">{formatAnalyticsDate(date)}</p>
        </div>
        <Badge tone="blue" className="shrink-0">
          {formatNumber(total)}
        </Badge>
      </div>
      <div className="mt-4 space-y-2">{children}</div>
    </article>
  );
}

function AnalyticsPager({
  page,
  hasNext,
  onPrevious,
  onNext,
}: {
  page: number;
  hasNext: boolean;
  onPrevious: () => void;
  onNext: () => void;
}) {
  return (
    <div className="mt-4 flex flex-wrap items-center justify-end gap-2">
      <Button type="button" variant="secondary" size="sm" disabled={page === 0} onClick={onPrevious}>
        Trước
      </Button>
      <span className="rounded-lg border border-border bg-muted px-3 py-2 font-mono text-xs text-muted-foreground">
        Trang {page + 1}
      </span>
      <Button type="button" variant="secondary" size="sm" disabled={!hasNext} onClick={onNext}>
        Sau
      </Button>
    </div>
  );
}

export function AdminDashboardPage() {
  const [usersTotal, setUsersTotal] = useState<number | null>(null);
  const [auditPage, setAuditPage] = useState<PageResponse<AuditLogItem> | null>(null);
  const [cacheMetrics, setCacheMetrics] = useState<CacheMetricsResponse | null>(null);
  const [alerts, setAlerts] = useState<AdminAlertItem[]>([]);
  const [notifications, setNotifications] = useState<NotificationListResponse | null>(null);
  const [intentData, setIntentData] = useState<IntentAnalytics[]>([]);
  const [errorData, setErrorData] = useState<ErrorAnalytics[]>([]);
  const [performanceData, setPerformanceData] = useState<PerformanceAnalytics[]>([]);
  const [intentPage, setIntentPage] = useState(0);
  const [errorPage, setErrorPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [analyticsLoading, setAnalyticsLoading] = useState(true);
  const [errors, setErrors] = useState<string[]>([]);

  async function loadAnalytics(nextIntentPage = intentPage, nextErrorPage = errorPage) {
    setAnalyticsLoading(true);

    const [intentResult, errorResult, performanceResult] = await Promise.allSettled([
      apiJson<IntentAnalytics[]>(`/api/admin/analytics/intents?page=${nextIntentPage}&size=${ANALYTICS_PAGE_SIZE}`),
      apiJson<ErrorAnalytics[]>(`/api/admin/analytics/errors?page=${nextErrorPage}&size=${ANALYTICS_PAGE_SIZE}`),
      apiJson<PerformanceAnalytics[]>("/api/admin/analytics/performance"),
    ]);

    const nextErrors: string[] = [];

    if (intentResult.status === "fulfilled") {
      setIntentData(intentResult.value || []);
    } else {
      nextErrors.push("Không thể tải question analytics.");
    }

    if (errorResult.status === "fulfilled") {
      setErrorData(errorResult.value || []);
    } else {
      nextErrors.push("Không thể tải error analytics.");
    }

    if (performanceResult.status === "fulfilled") {
      setPerformanceData(performanceResult.value || []);
    } else {
      nextErrors.push("Không thể tải performance analytics.");
    }

    setErrors((current) => [...current, ...nextErrors]);
    setAnalyticsLoading(false);
  }

  async function loadDashboard() {
    setLoading(true);
    setErrors([]);

    const [usersResult, auditResult, cacheResult, alertsResult, notificationsResult] = await Promise.allSettled([
      apiJson<AdminUserListResponse>("/api/admin/users?page=0&size=1"),
      apiJson<PageResponse<AuditLogItem>>("/api/audit-logs?action=CHAT_COMPLETED&page=0&size=1"),
      apiJson<CacheMetricsResponse>("/api/metrics/cache"),
      apiJson<PageResponse<AdminAlertItem>>("/api/admin/alerts?page=0&size=5"),
      apiJson<NotificationListResponse>("/api/notifications"),
    ]);

    const nextErrors: string[] = [];

    if (usersResult.status === "fulfilled") {
      setUsersTotal(usersResult.value.total_elements);
    } else {
      nextErrors.push("Không thể tải số user.");
    }

    if (auditResult.status === "fulfilled") {
      setAuditPage(auditResult.value);
    } else {
      nextErrors.push("Không thể tải thống kê request.");
    }

    if (cacheResult.status === "fulfilled") {
      setCacheMetrics(cacheResult.value);
    } else {
      nextErrors.push("Không thể tải cache metrics.");
    }

    if (alertsResult.status === "fulfilled") {
      setAlerts(alertsResult.value.content || []);
    } else {
      nextErrors.push("Không thể tải alert gần đây.");
    }

    if (notificationsResult.status === "fulfilled") {
      setNotifications(notificationsResult.value);
    } else {
      nextErrors.push("Không thể tải thông báo.");
    }

    setErrors(nextErrors);
    setLoading(false);
    void loadAnalytics();
  }

  useEffect(() => {
    void loadDashboard();
  }, []);

  const intentGroups = groupByDate(intentData);
  const errorGroups = groupByDate(errorData);
  const maxLatency = Math.max(1, ...performanceData.map((item) => item.p99Latency || item.p95Latency || item.avgLatency || 0));

  return (
    <DashboardLayout
      eyebrow="Admin Dashboard"
      title="Tổng quan hệ thống"
      description="Theo dõi nhanh người dùng, request, cache và alert gần đây. Các số liệu chưa có endpoint riêng sẽ hiển thị trạng thái trống."
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
            <MetricCard label="Số request" value={formatNumber(pageTotal(auditPage))} description="Tổng audit log CHAT_COMPLETED." />
            <MetricCard
              label="Cache hit rate"
              value={formatPercent(cacheMetrics?.hit_rate_percentage)}
              description={`${formatNumber(cacheMetrics?.total_cache_hits)} hit trên ${formatNumber(cacheMetrics?.total_requests)} request cache.`}
              progress={cacheMetrics?.hit_rate_percentage ?? 0}
              progressTone="green"
            />
            <MetricCard
              label="Tiết kiệm ước tính"
              value={formatUsd(cacheMetrics?.total_saved_cost_usd)}
              description={`${formatNumber(cacheMetrics?.total_saved_tokens)} token đã tiết kiệm từ cache.`}
            />
          </section>

          <section className="grid gap-4 md:grid-cols-2">
            <EmptyState
              title="Số hội thoại: Chưa có dữ liệu."
              description="Backend hiện chưa có endpoint tổng hợp số hội thoại toàn hệ thống cho admin dashboard."
            />
            <EmptyState
              title="Cost estimate toàn hệ thống: Chưa có dữ liệu."
              description="Trang usage hiện mới có cost summary theo user đăng nhập, chưa có API tổng hợp toàn hệ thống."
            />
          </section>

          <section className="grid gap-6">
            <div className="grid gap-6 xl:grid-cols-2">
              <AnalyticsShell
                title="Question analytics"
                icon={<MessageSquare className="h-5 w-5" />}
              >
                {intentGroups.length === 0 ? (
                  <EmptyState title="Chưa có dữ liệu câu hỏi." />
                ) : (
                  <>
                    <div className="grid gap-4 md:grid-cols-2">
                      {intentGroups.map((group) => (
                        <DateGroupCard key={group.date} date={group.date} total={group.total}>
                          {group.rows.map((item) => (
                            <div key={`${item.date}-${item.intent}`} className="flex items-center justify-between gap-3 rounded-lg bg-muted/70 px-3 py-2">
                              <span className="min-w-0 truncate font-mono text-xs text-foreground">{item.intent}</span>
                              <span className="font-semibold text-accent">{formatNumber(item.count)}</span>
                            </div>
                          ))}
                        </DateGroupCard>
                      ))}
                    </div>
                    <AnalyticsPager
                      page={intentPage}
                      hasNext={intentData.length >= ANALYTICS_PAGE_SIZE}
                      onPrevious={() => {
                        const nextPage = Math.max(0, intentPage - 1);
                        setIntentPage(nextPage);
                        void loadAnalytics(nextPage, errorPage);
                      }}
                      onNext={() => {
                        const nextPage = intentPage + 1;
                        setIntentPage(nextPage);
                        void loadAnalytics(nextPage, errorPage);
                      }}
                    />
                  </>
                )}
              </AnalyticsShell>

              <AnalyticsShell
                title="Error analytics"
                icon={<AlertTriangle className="h-5 w-5" />}
              >
                {errorGroups.length === 0 ? (
                  <EmptyState title="Chưa có lỗi nổi bật." />
                ) : (
                  <>
                    <div className="grid gap-4 md:grid-cols-2">
                      {errorGroups.map((group) => (
                        <DateGroupCard key={group.date} date={group.date} total={group.total}>
                          {group.rows.map((item) => (
                            <div key={`${item.date}-${item.service}-${item.errorType}`} className="rounded-lg bg-muted/70 px-3 py-2">
                              <div className="flex items-center justify-between gap-3">
                                <span className="min-w-0 truncate text-sm font-semibold text-foreground">{item.service || "unknown"}</span>
                                <span className="font-semibold text-danger">{formatNumber(item.count)}</span>
                              </div>
                              <p className="mt-1 truncate font-mono text-xs text-muted-foreground">{item.errorType || "SYSTEM"}</p>
                            </div>
                          ))}
                        </DateGroupCard>
                      ))}
                    </div>
                    <AnalyticsPager
                      page={errorPage}
                      hasNext={errorData.length >= ANALYTICS_PAGE_SIZE}
                      onPrevious={() => {
                        const nextPage = Math.max(0, errorPage - 1);
                        setErrorPage(nextPage);
                        void loadAnalytics(intentPage, nextPage);
                      }}
                      onNext={() => {
                        const nextPage = errorPage + 1;
                        setErrorPage(nextPage);
                        void loadAnalytics(intentPage, nextPage);
                      }}
                    />
                  </>
                )}
              </AnalyticsShell>
            </div>

            <AnalyticsShell
              title="Performance analytics"
              icon={<Gauge className="h-5 w-5" />}
            >
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
            </AnalyticsShell>
          </section>

          <section className="grid gap-6 xl:grid-cols-[1fr_0.85fr]">
            <div className="rounded-lg border border-border bg-white shadow-sm">
              <div className="flex items-center justify-between gap-3 border-b border-border px-5 py-4">
                <div>
                  <h2 className="font-display text-2xl text-foreground">Alert gần đây</h2>
                  <p className="text-sm text-muted-foreground">Notification/alert phục vụ theo dõi vận hành.</p>
                </div>
                <Database className="h-5 w-5 text-accent" />
              </div>
              {alerts.length === 0 ? (
                <div className="p-5">
                  <EmptyState title="Chưa có alert gần đây." />
                </div>
              ) : (
                <div className="divide-y divide-border">
                  {alerts.map((alert, index) => (
                    <article key={alert.id || index} className="px-5 py-4">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone={alert.severity === "CRITICAL" ? "red" : alert.severity === "WARNING" ? "amber" : "blue"}>
                          {alert.severity || "INFO"}
                        </Badge>
                        <span className="font-mono text-xs uppercase tracking-[0.12em] text-muted-foreground">{alertType(alert)}</span>
                      </div>
                      <p className="mt-2 text-sm font-semibold text-foreground">{alert.message || "Không có nội dung alert."}</p>
                      <p className="mt-1 text-xs text-muted-foreground">{formatDateTime(alertTime(alert))}</p>
                    </article>
                  ))}
                </div>
              )}
            </div>

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
              <QuickLink to="/usage" title="Usage cá nhân" description="Xem quota, token và chi phí của tài khoản hiện tại." />
              <QuickLink to="/chat" title="Quay về chat" description="Mở giao diện chatbot chính." />
              <div className="rounded-lg border border-dashed border-border bg-white/70 p-5">
                <div className="flex items-center gap-2 text-muted-foreground">
                  <FileClock className="h-4 w-4" />
                  <p className="font-semibold text-foreground">Audit log / metrics</p>
                </div>
                <p className="mt-1 text-sm leading-6 text-muted-foreground">Placeholder cho trang chi tiết khi cần mở rộng M13.</p>
              </div>
            </div>
          </section>
        </div>
      )}
    </DashboardLayout>
  );
}
