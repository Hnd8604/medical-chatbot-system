import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { ArrowRight, Gauge } from "lucide-react";
import { alertApi, alertKeys } from "@/features/admin/alerts/api";
import { AlertSummary } from "@/features/admin/alerts/components/AlertSummary";
import { analyticsApi, analyticsKeys } from "@/features/admin/analytics/api";
import { adminUserApi, adminUserKeys } from "@/features/admin/users/api";
import { lastDays } from "@/shared/lib/dates";
import { formatNumber, formatPercent, formatUsd } from "@/shared/lib/format";
import { Badge } from "@/shared/ui/Badge";
import { buttonVariants } from "@/shared/ui/Button";
import { EmptyState } from "@/shared/ui/EmptyState";
import { LoadingPanel } from "@/shared/ui/LoadingPanel";
import { MetricCard } from "@/shared/ui/MetricCard";
import { Notice } from "@/shared/ui/Notice";
import { PageHeader } from "@/shared/ui/PageHeader";
import { RefreshButton } from "@/shared/ui/RefreshButton";
import { SectionLabel } from "@/shared/ui/SectionLabel";

const OPEN_ALERT_PREVIEW = 3;
const USER_COUNT_PARAMS = { page: 0, size: 1 };
const OPEN_ALERTS = { status: "OPEN" as const, page: 0, size: OPEN_ALERT_PREVIEW };

export function AdminDashboardPage() {
  const [today] = useState(() => lastDays(1));
  const users = useQuery({ queryKey: adminUserKeys.list(USER_COUNT_PARAMS), queryFn: () => adminUserApi.list(USER_COUNT_PARAMS) });
  const requests = useQuery({ queryKey: analyticsKeys.requests(today), queryFn: () => analyticsApi.requests(today) });
  const cache = useQuery({ queryKey: analyticsKeys.cache(), queryFn: analyticsApi.cache });
  const alerts = useQuery({ queryKey: alertKeys.list(OPEN_ALERTS), queryFn: () => alertApi.list(OPEN_ALERTS) });

  const queries = [users, requests, cache, alerts];
  const loading = queries.some((query) => query.isFetching);
  const failures = [
    users.error ? "Không thể tải số user." : null,
    cache.error ? "Không thể tải cache metrics." : null,
    alerts.error ? "Không thể tải alert." : null,
  ].filter(Boolean);
  const openAlerts = alerts.data?.items ?? [];
  const openAlertsTotal = alerts.data?.totalElements ?? 0;
  const cacheMetrics = cache.data;

  return (
    <>
      <PageHeader
        eyebrow="Admin Dashboard"
        title="Tổng quan hệ thống"
        actions={<RefreshButton loading={loading} onClick={() => queries.forEach((query) => void query.refetch())} />}
      />
      {failures.length > 0 ? (
        <Notice tone="warning" className="mb-6" title="Một số dữ liệu chưa tải được">
          {failures.join(" ")}
        </Notice>
      ) : null}

      {users.isPending && cache.isPending ? (
        <LoadingPanel label="Đang tải dashboard..." />
      ) : (
        <div className="grid gap-7">
          <section className="grid gap-4 md:grid-cols-2 xl:grid-cols-5">
            <MetricCard label="Số user" value={formatNumber(users.data?.totalElements)} description="Tổng tài khoản trong hệ thống." />
            <MetricCard
              label="Request hôm nay"
              value={requests.data ? formatNumber(requests.data.requestCount) : "-"}
              description="Tổng request hôm nay."
            />
            <MetricCard
              label="Cảnh báo chờ xem xét"
              value={formatNumber(openAlertsTotal)}
              description="Cảnh báo hệ thống đang mở, chưa được xử lý."
            />
            <MetricCard
              label="Cache hit rate"
              value={formatPercent(cacheMetrics?.hitRatePercentage)}
              description={`${formatNumber(cacheMetrics?.totalCacheHits)} hit trên ${formatNumber(cacheMetrics?.totalRequests)} request cache.`}
              progress={cacheMetrics?.hitRatePercentage ?? 0}
              progressTone="green"
            />
            <MetricCard
              label="Tiết kiệm ước tính"
              value={formatUsd(cacheMetrics?.totalSavedCostUsd)}
              description={`${formatNumber(cacheMetrics?.totalSavedTokens)} token đã tiết kiệm từ cache.`}
            />
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
                {openAlerts.map((alert) => (
                  <article key={alert.id} className="px-5 py-4">
                    <AlertSummary alert={alert} />
                  </article>
                ))}
              </div>
            )}
          </section>
        </div>
      )}
    </>
  );
}
