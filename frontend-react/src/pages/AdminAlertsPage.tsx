import { useEffect, useState } from "react";
import { ListFilter, RefreshCw } from "lucide-react";
import { apiGet, apiPatch, toQuery } from "../services/api";
import type { AdminAlertItem, PageResponse } from "../lib/types";
import { formatDateTime, formatNumber } from "../lib/formatters";
import { DashboardLayout } from "../components/dashboard/DashboardLayout";
import { EmptyState } from "../components/dashboard/EmptyState";
import { Badge } from "../components/ui/Badge";
import { Button } from "../components/ui/Button";
import { Spinner } from "../components/ui/Spinner";
import { AnalyticsFilterInput } from "../components/dashboard/analytics";

const PAGE_SIZE = 12;

function pageTotal<T>(page: PageResponse<T> | null) {
  return page?.total_elements ?? page?.totalElements ?? 0;
}

function pageCount<T>(page: PageResponse<T> | null) {
  return Math.max(1, page?.totalPages ?? page?.total_pages ?? 1);
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

export function AdminAlertsPage() {
  const [alertPage, setAlertPage] = useState<PageResponse<AdminAlertItem> | null>(null);
  const [alerts, setAlerts] = useState<AdminAlertItem[]>([]);
  const [statusFilter, setStatusFilter] = useState("OPEN");
  const [severityFilter, setSeverityFilter] = useState("ALL");
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [resolvingAlertId, setResolvingAlertId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function loadAlerts(status = statusFilter, severity = severityFilter, nextPage = page) {
    setLoading(true);
    setError(null);
    const query = toQuery({
      status: status === "ALL" ? null : status,
      severity: severity === "ALL" ? null : severity,
      page: nextPage,
      size: PAGE_SIZE,
      sort: "createdAt,desc",
    });

    try {
      const result = await apiGet<PageResponse<AdminAlertItem>>(`/api/admin/alerts${query}`);
      setAlertPage(result);
      setAlerts(result.content || []);
      setPage(nextPage);
    } catch {
      setError("Không thể tải danh sách alert.");
    } finally {
      setLoading(false);
    }
  }

  async function resolveAlert(alertId: string) {
    setResolvingAlertId(alertId);
    try {
      await apiPatch<void>(`/api/admin/alerts/${alertId}/resolve`);
      await loadAlerts();
    } catch {
      setError("Không thể đánh dấu alert đã xử lý.");
    } finally {
      setResolvingAlertId(null);
    }
  }

  useEffect(() => {
    void loadAlerts();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const totalPages = pageCount(alertPage);

  return (
    <DashboardLayout
      eyebrow="Admin Alerts"
      title="Alert vận hành"
      actions={
        <Button type="button" variant="secondary" onClick={() => void loadAlerts()} disabled={loading}>
          {loading ? <Spinner /> : <RefreshCw className="h-4 w-4" />}
          Làm mới
        </Button>
      }
    >
      {error ? (
        <div className="mb-6 rounded-lg border border-warning/30 bg-warning/10 p-4 text-sm text-muted-foreground">
          <p className="font-semibold text-foreground">{error}</p>
        </div>
      ) : null}

      <div className="grid gap-4 rounded-lg border border-border bg-white p-5 shadow-sm">
        <div className="grid gap-3 lg:grid-cols-[1fr_auto] lg:items-end">
          <div className="grid gap-3 sm:grid-cols-2">
            <AnalyticsFilterInput label="Severity">
              <select value={severityFilter} onChange={(event) => setSeverityFilter(event.target.value)} className="focus-ring h-11 rounded-xl border border-border bg-white px-3 text-sm font-semibold text-foreground shadow-sm">
                <option value="ALL">Tất cả severity</option>
                <option value="CRITICAL">Critical</option>
                <option value="WARNING">Warning</option>
                <option value="INFO">Info</option>
              </select>
            </AnalyticsFilterInput>
            <AnalyticsFilterInput label="Status">
              <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)} className="focus-ring h-11 rounded-xl border border-border bg-white px-3 text-sm font-semibold text-foreground shadow-sm">
                <option value="ALL">Tất cả status</option>
                <option value="OPEN">Open</option>
                <option value="RESOLVED">Resolved</option>
              </select>
            </AnalyticsFilterInput>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Badge tone="blue">{formatNumber(pageTotal(alertPage))} alert</Badge>
            <Button type="button" size="sm" variant="primary" onClick={() => void loadAlerts(statusFilter, severityFilter, 0)} disabled={loading}>
              {loading ? <Spinner /> : <ListFilter className="h-4 w-4" />}
              Áp dụng
            </Button>
          </div>
        </div>

        {alerts.length === 0 ? (
          <EmptyState title={loading ? "Đang tải alert..." : "Chưa có alert phù hợp."} />
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

        {totalPages > 1 ? (
          <div className="flex items-center justify-between gap-3">
            <Button type="button" size="sm" variant="secondary" disabled={loading || page <= 0} onClick={() => void loadAlerts(statusFilter, severityFilter, page - 1)}>
              Trước
            </Button>
            <span className="text-sm text-muted-foreground">
              Trang {page + 1} / {totalPages}
            </span>
            <Button type="button" size="sm" variant="secondary" disabled={loading || page >= totalPages - 1} onClick={() => void loadAlerts(statusFilter, severityFilter, page + 1)}>
              Sau
            </Button>
          </div>
        ) : null}
      </div>
    </DashboardLayout>
  );
}
