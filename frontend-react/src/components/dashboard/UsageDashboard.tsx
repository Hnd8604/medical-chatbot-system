import { AlertTriangle } from "lucide-react";
import type { CostSummaryResponse, QuotaStatusResponse } from "../../lib/types";
import { formatNumber, formatUsd, numericValue } from "../../lib/formatters";
import { Spinner } from "../ui/Spinner";
import { MetricCard } from "./MetricCard";
import { ProgressBar } from "./ProgressBar";
import { SevenDayUsageChart } from "./SevenDayUsageChart";

type UsageDay = {
  date?: string;
  day?: string;
  request_count?: number | string;
  input_tokens?: number | string;
  output_tokens?: number | string;
  total_tokens?: number | string;
  estimated_cost_usd?: number | string;
};

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

interface UsageDashboardProps {
  quota: QuotaStatusResponse | null;
  cost: CostSummaryResponse | null;
  loading: boolean;
}

export function UsageDashboard({ quota, cost, loading }: UsageDashboardProps) {
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

  if (loading && !quota) {
    return (
      <div className="grid min-h-[18rem] place-items-center rounded-lg border border-border bg-white">
        <div className="flex items-center gap-3 text-muted-foreground">
          <Spinner className="text-accent" />
          Đang tải dữ liệu usage...
        </div>
      </div>
    );
  }

  return (
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

      <section className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
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
      </section>

      <section className="grid items-stretch gap-5 lg:grid-cols-2">
        <div className="rounded-lg border border-border bg-white p-5 shadow-sm">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h2 className="font-display text-2xl text-foreground">Tổng quan quota ngày</h2>
              <p className="mt-1 text-sm text-muted-foreground">Mỗi thanh thể hiện phần đã sử dụng so với giới hạn.</p>
            </div>
          </div>
          <div className="mt-6 grid gap-5">
            <ProgressBar
              value={requestProgress}
              tone={requestProgress >= 90 ? "red" : "blue"}
              label="Request"
              hint={`${formatNumber(quota?.used_requests)}/${formatNumber(quota?.daily_request_limit)} · ${Math.round(requestProgress)}%`}
            />
            <ProgressBar
              value={tokenProgress}
              tone={tokenProgress >= 90 ? "red" : "blue"}
              label="Token"
              hint={`${formatNumber(quota?.used_tokens)}/${formatNumber(quota?.daily_token_limit)} · ${Math.round(tokenProgress)}%`}
            />
            <ProgressBar
              value={costProgress}
              tone={costProgress >= 90 ? "red" : "green"}
              label="Chi phí"
              hint={`${formatUsd(quota?.used_cost_usd)}/${formatUsd(quota?.daily_cost_limit_usd)} · ${Math.round(costProgress)}%`}
            />
          </div>
        </div>

        <SevenDayUsageChart days={days} />
      </section>
    </div>
  );
}
