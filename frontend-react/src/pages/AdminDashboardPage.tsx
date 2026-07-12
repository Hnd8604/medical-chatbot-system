import { useEffect, useState } from "react";
import { ArrowRight, Gauge, RefreshCw } from "lucide-react";
import { Link } from "react-router-dom";
import { apiGet } from "../services/api";
import type { AdminAlertItem, AdminUserListResponse, CacheMetricsResponse, PageResponse, RequestAnalyticsSummary } from "../lib/types";
import { formatDateTime, formatNumber, formatPercent, formatUsd } from "../lib/formatters";
import { DashboardLayout } from "../components/dashboard/DashboardLayout";
import { EmptyState } from "../components/dashboard/EmptyState";
import { MetricCard } from "../components/dashboard/MetricCard";
import { Badge } from "../components/ui/Badge";
import { Button, buttonVariants } from "../components/ui/Button";
import { SectionLabel } from "../components/ui/SectionLabel";
import { Spinner } from "../components/ui/Spinner";
import { analyticsRange } from "../components/dashboard/analytics";

const OPEN_ALERT_PREVIEW = 3;

function alertTime(alert: AdminAlertItem) {
  return alert.created_at || alert.createdAt || null;
}

function alertType(alert: AdminAlertItem) {
  return alert.alert_type || alert.alertType || "SYSTEM";
}

function alertSeverity(alert: AdminAlertItem) {
  return alert.severity || "INFO";
}

export function AdminDashboardPage() {
  const [usersTotal, setUsersTotal] = useState<number | null>(null);
  const [requestSummary, setRequestSummary] = useState<RequestAnalyticsSummary | null>(null);
  const [cacheMetrics, setCacheMetrics] = useState<CacheMetricsResponse | null>(null);
  const [openAlerts, setOpenAlerts] = useState<AdminAlertItem[]>([]);
  const [openAlertsTotal, setOpenAlertsTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [errors, setErrors] = useState<string[]>([]);

  async function loadDashboard() {
    setLoading(true);
    setErrors([]);

    const summaryRange = analyticsRange(1);
    const [usersResult, requestResult, cacheResult, alertsResult] = await Promise.allSettled([
      apiGet<AdminUserListResponse>("/api/admin/users?page=0&size=1"),
      apiGet<RequestAnalyticsSummary>(`/api/admin/analytics/requests?from=${summaryRange.from}&to=${summaryRange.to}`),
      apiGet<CacheMetricsResponse>("/api/metrics/cache"),
      apiGet<PageResponse<AdminAlertItem>>(`/api/admin/alerts?status=OPEN&page=0&size=${OPEN_ALERT_PREVIEW}&sort=createdAt,desc`),
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

    if (alertsResult.status === "fulfilled") {
      setOpenAlerts(alertsResult.value.content || []);
      setOpenAlertsTotal(alertsResult.value.total_elements ?? alertsResult.value.totalElements ?? 0);
    } else {
      nextErrors.push("Không thể tải alert.");
    }

    setErrors(nextErrors);
    setLoading(false);
  }

  useEffect(() => {
    void loadDashboard();
  }, []);

  return (
    <DashboardLayout
      eyebrow="Admin Dashboard"
      title="Tổng quan hệ thống"
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
          <section className="grid gap-4 md:grid-cols-2 xl:grid-cols-5">
            <MetricCard label="Số user" value={formatNumber(usersTotal)} description="Tổng tài khoản trong hệ thống." />
            <MetricCard label="Request hôm nay" value={requestSummary ? formatNumber(requestSummary.request_count) : "-"} description="Tổng request hôm nay." />
            <MetricCard
              label="Cảnh báo chờ xem xét"
              value={formatNumber(openAlertsTotal)}
              description="Cảnh báo hệ thống đang mở, chưa được xử lý."
              progressTone={openAlertsTotal > 0 ? "amber" : "green"}
            />
            <MetricCard
              label="Cache hit rate"
              value={formatPercent(cacheMetrics?.hit_rate_percentage)}
              description={`${formatNumber(cacheMetrics?.total_cache_hits)} hit trên ${formatNumber(cacheMetrics?.total_requests)} request cache.`}
              progress={cacheMetrics?.hit_rate_percentage ?? 0}
              progressTone="green"
            />
            <MetricCard label="Tiết kiệm ước tính" value={formatUsd(cacheMetrics?.total_saved_cost_usd)} description={`${formatNumber(cacheMetrics?.total_saved_tokens)} token đã tiết kiệm từ cache.`} />
          </section>

          <section className="rounded-xl border border-accent/25 bg-gradient-to-br from-accent/5 to-white p-5 shadow-sm">
            <div className="flex flex-wrap items-center justify-between gap-4">
              <div className="flex items-center gap-3">
                <div className="grid h-11 w-11 place-items-center rounded-xl gradient-surface text-white">
                  <Gauge className="h-5 w-5" />
                </div>
                <div>
                  <h2 className="font-display text-xl text-foreground">Phân tích chuyên sâu</h2>
                  <p className="text-sm text-muted-foreground">Câu hỏi, lỗi và hiệu năng theo khoảng thời gian tùy chọn.</p>
                </div>
              </div>
              <Link to="/admin/analytics" className={buttonVariants({ variant: "primary" })}>
                Mở Analytics
                <ArrowRight className="h-4 w-4" />
              </Link>
            </div>
          </section>

          <section className="rounded-lg border border-border bg-white shadow-sm">
            <div className="flex flex-wrap items-start justify-between gap-3 border-b border-border px-5 py-4">
              <div>
                <SectionLabel>Alert dashboard</SectionLabel>
                <h2 className="mt-3 font-display text-2xl text-foreground">Alert đang mở</h2>
              </div>
              <div className="flex items-center gap-3">
                <Badge tone={openAlertsTotal > 0 ? "amber" : "slate"}>{formatNumber(openAlertsTotal)} open</Badge>
                <Link to="/admin/alerts" className={buttonVariants({ variant: "secondary", size: "sm" })}>
                  Xem tất cả
                  <ArrowRight className="h-4 w-4" />
                </Link>
              </div>
            </div>
            {openAlerts.length === 0 ? (
              <div className="p-5">
                <EmptyState title="Không có alert đang mở." />
              </div>
            ) : (
              <div className="divide-y divide-border">
                {openAlerts.slice(0, OPEN_ALERT_PREVIEW).map((alert, index) => {
                  const severity = alertSeverity(alert);
                  return (
                    <article key={alert.id || index} className="px-5 py-4">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone={severity === "CRITICAL" ? "red" : severity === "WARNING" ? "amber" : "blue"}>{severity}</Badge>
                        <span className="font-mono text-xs uppercase tracking-[0.12em] text-muted-foreground">{alertType(alert)}</span>
                      </div>
                      <p className="mt-2 text-sm font-semibold text-foreground">{alert.message || "Không có nội dung alert."}</p>
                      <p className="mt-1 text-xs text-muted-foreground">
                        {alert.source || "SYSTEM"} · {formatDateTime(alertTime(alert))}
                      </p>
                    </article>
                  );
                })}
              </div>
            )}
          </section>
        </div>
      )}
    </DashboardLayout>
  );
}
