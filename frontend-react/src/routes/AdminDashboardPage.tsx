import { useEffect, useState } from "react";
import { Bell, Database, FileClock, RefreshCw, Users } from "lucide-react";
import { Link } from "react-router-dom";
import { apiJson } from "../lib/api";
import type {
  AdminAlertItem,
  AdminUserListResponse,
  AuditLogItem,
  CacheMetricsResponse,
  NotificationListResponse,
  PageResponse,
} from "../lib/types";
import { formatDateTime, formatNumber, formatPercent, formatUsd } from "../lib/formatters";
import { DashboardLayout } from "../components/dashboard/DashboardLayout";
import { EmptyState } from "../components/dashboard/EmptyState";
import { MetricCard } from "../components/dashboard/MetricCard";
import { Badge } from "../components/ui/Badge";
import { Button } from "../components/ui/Button";
import { Spinner } from "../components/ui/Spinner";

function pageTotal<T>(page: PageResponse<T> | null) {
  return page?.total_elements ?? page?.totalElements ?? 0;
}

function alertTime(alert: AdminAlertItem) {
  return alert.created_at || alert.createdAt || null;
}

function alertType(alert: AdminAlertItem) {
  return alert.alert_type || alert.alertType || "SYSTEM";
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

export function AdminDashboardPage() {
  const [usersTotal, setUsersTotal] = useState<number | null>(null);
  const [auditPage, setAuditPage] = useState<PageResponse<AuditLogItem> | null>(null);
  const [cacheMetrics, setCacheMetrics] = useState<CacheMetricsResponse | null>(null);
  const [alerts, setAlerts] = useState<AdminAlertItem[]>([]);
  const [notifications, setNotifications] = useState<NotificationListResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [errors, setErrors] = useState<string[]>([]);

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
  }

  useEffect(() => {
    void loadDashboard();
  }, []);

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
