import { useEffect, useMemo, useState } from "react";
import { AlertTriangle, RefreshCw } from "lucide-react";
import { apiJson, toQuery, todayIso } from "../services/api";
import type { CostSummaryResponse, QuotaStatusResponse } from "../lib/types";
import { formatDate, formatNumber, formatUsd, numericValue } from "../lib/formatters";
import { Button } from "../components/ui/Button";
import { Spinner } from "../components/ui/Spinner";
import { DashboardLayout } from "../components/dashboard/DashboardLayout";
import { EmptyState } from "../components/dashboard/EmptyState";
import { MetricCard } from "../components/dashboard/MetricCard";
import { ProgressBar } from "../components/dashboard/ProgressBar";

type UsageDay = {
  date?: string;
  day?: string;
  request_count?: number | string;
  input_tokens?: number | string;
  output_tokens?: number | string;
  total_tokens?: number | string;
  estimated_cost_usd?: number | string;
};

function sevenDaysAgoIso() {
  const date = new Date();
  date.setDate(date.getDate() - 6);
  return date.toISOString().slice(0, 10);
}

function percentage(used: number | string | null | undefined, limit: number | string | null | undefined) {
  const limitValue = numericValue(limit);
  if (limitValue <= 0) {
    return 0;
  }
  return Math.min(100, (numericValue(used) / limitValue) * 100);
}

function remainingPercentage(remaining: number | string | null | undefined, limit: number | string | null | undefined) {
  const limitValue = numericValue(limit);
  if (limitValue <= 0) {
    return 100;
  }
  return Math.max(0, (numericValue(remaining) / limitValue) * 100);
}

export function UsagePage() {
  const [quota, setQuota] = useState<QuotaStatusResponse | null>(null);
  const [cost, setCost] = useState<CostSummaryResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const range = useMemo(() => ({ from: sevenDaysAgoIso(), to: todayIso() }), []);

  async function loadUsage() {
    setLoading(true);
    setError(null);
    try {
      const [quotaData, costData] = await Promise.all([
        apiJson<QuotaStatusResponse>("/api/quota/status"),
        apiJson<CostSummaryResponse>(`/api/usage/cost-summary${toQuery(range)}`),
      ]);
      setQuota(quotaData);
      setCost(costData);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : "Không thể tải dữ liệu usage.");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadUsage();
  }, []);

  const requestProgress = percentage(quota?.used_requests, quota?.daily_request_limit);
  const tokenProgress = percentage(quota?.used_tokens, quota?.daily_token_limit);
  const costProgress = percentage(quota?.used_cost_usd, quota?.daily_cost_limit_usd);
  const lowQuota =
    quota &&
    (!quota.allowed ||
      Math.min(
        remainingPercentage(quota.remaining_requests, quota.daily_request_limit),
        remainingPercentage(quota.remaining_tokens, quota.daily_token_limit),
        remainingPercentage(quota.remaining_cost_usd, quota.daily_cost_limit_usd),
      ) <= 20);
  const days = (Array.isArray(cost?.days) ? cost.days : []) as UsageDay[];
  const sortedDays = [...days].sort((left, right) => {
    const leftTime = new Date(left.date || left.day || "").getTime();
    const rightTime = new Date(right.date || right.day || "").getTime();
    return (Number.isNaN(rightTime) ? 0 : rightTime) - (Number.isNaN(leftTime) ? 0 : leftTime);
  });

  return (
    <DashboardLayout
      eyebrow="Usage / Quota"
      title="Theo dõi mức sử dụng"
      description="Tổng hợp số request, token, chi phí ước tính và giới hạn trong ngày của tài khoản hiện tại."
      actions={
        <Button type="button" variant="secondary" onClick={() => void loadUsage()} disabled={loading}>
          {loading ? <Spinner /> : <RefreshCw className="h-4 w-4" />}
          Làm mới
        </Button>
      }
    >
      {error ? (
        <div className="mb-6 rounded-lg border border-danger/25 bg-danger/5 p-4 text-sm text-danger">{error}</div>
      ) : null}

      {loading && !quota ? (
        <div className="grid min-h-[18rem] place-items-center rounded-lg border border-border bg-white">
          <div className="flex items-center gap-3 text-muted-foreground">
            <Spinner className="text-accent" />
            Đang tải dữ liệu usage...
          </div>
        </div>
      ) : (
        <div className="grid gap-7">
          {lowQuota ? (
            <section className="rounded-lg border border-warning/30 bg-warning/10 p-5">
              <div className="flex items-start gap-3">
                <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-warning" />
                <div>
                  <h2 className="font-semibold text-foreground">Cảnh báo quota</h2>
                  <p className="mt-1 text-sm leading-6 text-muted-foreground">
                    {quota?.allowed
                      ? "Tài khoản đang gần hết giới hạn trong ngày. Hãy cân nhắc rút gọn câu hỏi hoặc chờ quota được làm mới."
                      : quota?.blocked_reason || "Tài khoản đã vượt giới hạn sử dụng trong ngày."}
                  </p>
                </div>
              </div>
            </section>
          ) : null}

          <section className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
            <MetricCard
              label="Request hôm nay"
              value={`${formatNumber(quota?.used_requests)}/${formatNumber(quota?.daily_request_limit)}`}
              description={`${formatNumber(quota?.remaining_requests)} request còn lại trong ngày.`}
              progress={requestProgress}
              progressTone={requestProgress >= 90 ? "red" : requestProgress >= 80 ? "amber" : "blue"}
            />
            <MetricCard
              label="Token hôm nay"
              value={`${formatNumber(quota?.used_tokens)}/${formatNumber(quota?.daily_token_limit)}`}
              description={`${formatNumber(quota?.used_input_tokens)} input, ${formatNumber(quota?.used_output_tokens)} output.`}
              progress={tokenProgress}
              progressTone={tokenProgress >= 90 ? "red" : tokenProgress >= 80 ? "amber" : "blue"}
            />
            <MetricCard
              label="Chi phí hôm nay"
              value={formatUsd(quota?.used_cost_usd)}
              description={`Giới hạn ngày: ${formatUsd(quota?.daily_cost_limit_usd)}.`}
              progress={costProgress}
              progressTone={costProgress >= 90 ? "red" : costProgress >= 80 ? "amber" : "green"}
            />
            <article className="rounded-lg border border-border bg-white p-5 shadow-sm">
              <p className="font-mono text-xs uppercase tracking-[0.14em] text-muted-foreground">Request 7 ngày</p>
              <div className="mt-4 flex items-baseline gap-2">
                <span className="font-display text-3xl text-foreground">{formatNumber(cost?.request_count)}</span>
                <span className="text-sm font-semibold text-muted-foreground">request</span>
              </div>
              <p className="mt-2 text-sm leading-6 text-muted-foreground">
                {formatNumber(cost?.total_tokens)} token, {formatUsd(cost?.estimated_cost_usd)} ước tính.
              </p>
              <div className="mt-5 rounded-lg border border-border bg-muted/60 px-3 py-2 text-xs font-semibold text-muted-foreground">
                {formatDate(range.from)} - {formatDate(range.to)}
              </div>
            </article>
          </section>

          <section className="grid gap-5 lg:grid-cols-[1fr_0.85fr]">
            <div className="rounded-lg border border-border bg-white p-5 shadow-sm">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <h2 className="font-display text-2xl text-foreground">Tổng quan quota ngày</h2>
                  <p className="mt-1 text-sm text-muted-foreground">Mỗi thanh thể hiện phần đã sử dụng so với giới hạn.</p>
                </div>
              </div>
              <div className="mt-6 grid gap-5">
                <ProgressBar value={requestProgress} tone={requestProgress >= 90 ? "red" : "blue"} label="Request" />
                <ProgressBar value={tokenProgress} tone={tokenProgress >= 90 ? "red" : "blue"} label="Token" />
                <ProgressBar value={costProgress} tone={costProgress >= 90 ? "red" : "green"} label="Chi phí" />
              </div>
            </div>

            <div className="rounded-lg border border-border bg-foreground p-5 text-white shadow-card">
              <p className="font-mono text-xs uppercase tracking-[0.14em] text-white/60">Trạng thái</p>
              <h2 className="mt-4 font-display text-3xl">{quota?.allowed ? "Được phép sử dụng" : "Đã bị giới hạn"}</h2>
              <p className="mt-3 text-sm leading-6 text-white/70">
                {quota?.allowed
                  ? "Quota hiện tại vẫn đủ để tiếp tục sử dụng chatbot. Các giới hạn sẽ được làm mới theo chính sách hệ thống."
                  : quota?.blocked_reason || "Tài khoản không thể gửi thêm request ở thời điểm này."}
              </p>
            </div>
          </section>

          <section>
            <div className="mb-4 flex items-end justify-between gap-3">
              <div>
                <h2 className="font-display text-2xl text-foreground">Lịch sử sử dụng gần đây</h2>
                <p className="mt-1 text-sm text-muted-foreground">Dữ liệu tổng hợp theo ngày nếu backend có trả về.</p>
              </div>
            </div>

            {sortedDays.length === 0 ? (
              <EmptyState title="Chưa có dữ liệu lịch sử." description="Backend chưa trả về thống kê theo ngày cho khoảng thời gian này." />
            ) : (
              <div className="overflow-hidden rounded-lg border border-border bg-white shadow-sm">
                <div className="hidden grid-cols-[1fr_1fr_1fr_1fr] gap-4 border-b border-border bg-muted/60 px-5 py-3 text-xs font-bold uppercase tracking-[0.12em] text-muted-foreground md:grid">
                  <span>Ngày</span>
                  <span>Request</span>
                  <span>Token</span>
                  <span>Chi phí</span>
                </div>
                <div className="divide-y divide-border">
                  {sortedDays.map((day, index) => (
                    <article key={`${day.date || day.day || index}`} className="grid gap-2 px-5 py-4 text-sm md:grid-cols-[1fr_1fr_1fr_1fr] md:gap-4">
                      <span className="font-semibold text-foreground">{formatDate(day.date || day.day)}</span>
                      <span>{formatNumber(day.request_count)}</span>
                      <span>{formatNumber(day.total_tokens)}</span>
                      <span>{formatUsd(day.estimated_cost_usd)}</span>
                    </article>
                  ))}
                </div>
              </div>
            )}
          </section>
        </div>
      )}
    </DashboardLayout>
  );
}
