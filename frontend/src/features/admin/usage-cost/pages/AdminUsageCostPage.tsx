import { useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { CircleDollarSign, Gauge, ShieldCheck, WalletCards } from "lucide-react";
import type { AdminUser } from "@/features/admin/users/types";
import { errorMessage } from "@/shared/api/client";
import { lastDays, type DateRange } from "@/shared/lib/dates";
import { formatDate, formatNumber, formatUsd, numericValue, usagePercent } from "@/shared/lib/format";
import { Badge } from "@/shared/ui/Badge";
import { Button } from "@/shared/ui/Button";
import { EmptyState } from "@/shared/ui/EmptyState";
import { inputClass } from "@/shared/ui/Field";
import { MetricCard } from "@/shared/ui/MetricCard";
import { Notice } from "@/shared/ui/Notice";
import { PageHeader } from "@/shared/ui/PageHeader";
import { ProgressBar, usageTone } from "@/shared/ui/ProgressBar";
import { RefreshButton } from "@/shared/ui/RefreshButton";
import { SectionLabel } from "@/shared/ui/SectionLabel";
import { Spinner } from "@/shared/ui/Spinner";
import { userUsageApi, userUsageKeys } from "../api";
import { findUser, UserLookup } from "../components/UserLookup";

const WARN_PERCENT = 75;

function maxOf(values: number[]): number {
  return Math.max(1, ...values);
}

export function AdminUsageCostPage() {
  const [term, setTerm] = useState("");
  const [range, setRange] = useState<DateRange>(() => lastDays(7));
  const [selection, setSelection] = useState<{ user: AdminUser; range: DateRange } | null>(null);
  const userId = selection?.user.id ?? "";

  const quota = useQuery({
    queryKey: userUsageKeys.quota(userId),
    queryFn: () => userUsageApi.quota(userId),
    enabled: selection !== null,
  });
  const cost = useQuery({
    queryKey: userUsageKeys.costs(userId, selection?.range ?? range),
    queryFn: () => userUsageApi.costs(userId, selection?.range ?? range),
    enabled: selection !== null,
  });

  const [notFound, setNotFound] = useState(false);

  function show(user: AdminUser) {
    setNotFound(false);
    setTerm(user.username);
    setSelection({ user, range });
  }

  const lookup = useMutation({
    mutationFn: () => findUser(term),
    onSuccess: (user) => (user ? show(user) : setNotFound(true)),
  });

  const quotaData = quota.data;
  const costData = cost.data;
  const requestPercent = usagePercent(quotaData?.used_requests, quotaData?.daily_request_limit);
  const tokenPercent = usagePercent(quotaData?.used_tokens, quotaData?.daily_token_limit);
  const costPercent = usagePercent(quotaData?.used_cost_usd, quotaData?.daily_cost_limit_usd);
  const models = useMemo(
    () => [...(costData?.models ?? [])].sort((left, right) => numericValue(right.cost_usd) - numericValue(left.cost_usd)),
    [costData],
  );
  const days = useMemo(() => [...(costData?.days ?? [])].sort((left, right) => right.date.localeCompare(left.date)), [costData]);
  const maxModelCost = maxOf(models.map((model) => numericValue(model.cost_usd)));
  const maxDayRequests = maxOf(days.map((day) => day.request_count));

  const loading = quota.isFetching || cost.isFetching;
  const failure = lookup.error ?? quota.error ?? cost.error;
  const error = notFound
    ? "Không tìm thấy người dùng phù hợp với từ khóa."
    : failure
      ? errorMessage(failure, "Không thể tải quota/cost.")
      : null;

  return (
    <>
      <PageHeader
        eyebrow="Quota & Cost"
        title="Quota, usage, cost"
        actions={
          <RefreshButton
            loading={loading}
            onClick={() => {
              if (selection) {
                void quota.refetch();
                void cost.refetch();
              }
            }}
          />
        }
      />
      {error ? <Notice className="mb-6">{error}</Notice> : null}

      <div className="grid gap-7">
        <section className="rounded-lg border border-border bg-white p-4">
          <div className="grid gap-3 lg:grid-cols-[1fr_12rem_12rem_auto] lg:items-end">
            <label className="grid gap-1">
              <span className="text-xs font-medium text-muted-foreground">Người dùng</span>
              <UserLookup value={term} onChange={setTerm} onPick={show} onSubmit={() => lookup.mutate()} />
            </label>
            <label className="grid gap-1">
              <span className="text-xs font-medium text-muted-foreground">Từ ngày</span>
              <input
                className={inputClass()}
                type="date"
                value={range.from}
                max={range.to}
                onChange={(event) => setRange((current) => ({ ...current, from: event.target.value }))}
              />
            </label>
            <label className="grid gap-1">
              <span className="text-xs font-medium text-muted-foreground">Đến ngày</span>
              <input
                className={inputClass()}
                type="date"
                value={range.to}
                min={range.from}
                onChange={(event) => setRange((current) => ({ ...current, to: event.target.value }))}
              />
            </label>
            <Button
              type="button"
              variant="primary"
              onClick={() => lookup.mutate()}
              disabled={lookup.isPending || loading || !term.trim() || range.from > range.to}
            >
              {lookup.isPending || loading ? <Spinner /> : <Gauge className="h-4 w-4" />}
              Áp dụng
            </Button>
          </div>
        </section>

        <section className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
          <MetricCard
            label="Request/ngày"
            value={`${formatNumber(quotaData?.used_requests)}/${formatNumber(quotaData?.daily_request_limit)}`}
            progress={requestPercent}
            progressTone={usageTone(requestPercent, "blue", WARN_PERCENT)}
          />
          <MetricCard
            label="Token/ngày"
            value={`${formatNumber(quotaData?.used_tokens)}/${formatNumber(quotaData?.daily_token_limit)}`}
            progress={tokenPercent}
            progressTone={usageTone(tokenPercent, "blue", WARN_PERCENT)}
          />
          <MetricCard
            label="Cost/ngày"
            value={formatUsd(quotaData?.used_cost_usd)}
            progress={costPercent}
            progressTone={usageTone(costPercent, "blue", WARN_PERCENT)}
          />
          <MetricCard label="Cost range" value={formatUsd(costData?.cost_usd)} description={`${formatNumber(costData?.request_count)} request`} />
        </section>

        <section className="grid gap-6 xl:grid-cols-[0.9fr_1.1fr]">
          <div className="rounded-lg border border-border bg-white p-5">
            <div className="flex items-center justify-between gap-3">
              <div>
                <SectionLabel>Quota</SectionLabel>
                <h2 className="mt-3 text-lg font-semibold text-foreground">{quotaData?.policy || "-"}</h2>
              </div>
              <ShieldCheck className={quotaData?.allowed ? "h-6 w-6 text-success" : "h-6 w-6 text-danger"} />
            </div>
            <div className="mt-6 grid gap-5">
              <ProgressBar
                label="Request"
                value={requestPercent}
                tone={usageTone(requestPercent, "blue", WARN_PERCENT)}
                hint={`${formatNumber(quotaData?.used_requests)}/${formatNumber(quotaData?.daily_request_limit)} · ${Math.round(requestPercent)}%`}
              />
              <ProgressBar
                label="Token"
                value={tokenPercent}
                tone={usageTone(tokenPercent, "blue", WARN_PERCENT)}
                hint={`${formatNumber(quotaData?.used_tokens)}/${formatNumber(quotaData?.daily_token_limit)} · ${Math.round(tokenPercent)}%`}
              />
              <ProgressBar
                label="Cost"
                value={costPercent}
                tone={usageTone(costPercent, "blue", WARN_PERCENT)}
                hint={`${formatUsd(quotaData?.used_cost_usd)}/${formatUsd(quotaData?.daily_cost_limit_usd)} · ${Math.round(costPercent)}%`}
              />
            </div>
          </div>

          <div className="rounded-lg border border-border bg-white p-5">
            <div className="mb-4 flex items-center justify-between gap-3">
              <SectionLabel>Model cost</SectionLabel>
              <WalletCards className="h-5 w-5 text-accent" />
            </div>
            {models.length === 0 ? (
              <EmptyState title="Chưa có cost theo model." />
            ) : (
              <div className="grid gap-3">
                {models.slice(0, 5).map((model) => (
                  <div key={`${model.llm_provider}-${model.llm_model}`} className="rounded-lg border border-border bg-muted/40 p-3">
                    <div className="flex items-center justify-between gap-3">
                      <div className="min-w-0">
                        <p className="truncate text-sm font-medium text-foreground">{model.llm_model || "unknown"}</p>
                        <p className="text-xs text-muted-foreground">
                          {model.llm_provider || "unknown"} · {formatNumber(model.call_count)} call
                        </p>
                      </div>
                      <span className="font-semibold text-foreground">{formatUsd(model.cost_usd)}</span>
                    </div>
                    <ProgressBar value={(numericValue(model.cost_usd) / maxModelCost) * 100} tone="green" />
                  </div>
                ))}
              </div>
            )}
          </div>
        </section>

        <section className="rounded-lg border border-border bg-white p-5">
          <div className="mb-4 flex items-center justify-between gap-3">
            <SectionLabel>Usage days</SectionLabel>
            <CircleDollarSign className="h-5 w-5 text-accent" />
          </div>
          {days.length === 0 ? (
            <EmptyState title="Chưa có usage theo ngày." />
          ) : (
            <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
              {days.slice(0, 8).map((day) => (
                <article key={day.date} className="rounded-lg border border-border bg-white p-4">
                  <div className="flex items-center justify-between gap-3">
                    <span className="font-semibold text-foreground">{formatDate(day.date)}</span>
                    <span className="font-mono text-sm text-accent">{formatNumber(day.request_count)} req</span>
                  </div>
                  <p className="mt-2 text-lg font-semibold text-foreground">{formatUsd(day.cost_usd)}</p>
                  <ProgressBar value={(day.request_count / maxDayRequests) * 100} />
                  <dl className="mt-3 grid grid-cols-2 gap-x-3 gap-y-2 border-t border-border pt-3 text-xs">
                    <div>
                      <dt className="text-muted-foreground">Token</dt>
                      <dd className="font-mono font-semibold tabular-nums text-foreground">{formatNumber(day.total_tokens)}</dd>
                    </div>
                    <div>
                      <dt className="text-muted-foreground">In / Out</dt>
                      <dd className="font-mono font-semibold tabular-nums text-foreground">
                        {formatNumber(day.input_tokens)}/{formatNumber(day.output_tokens)}
                      </dd>
                    </div>
                  </dl>
                </article>
              ))}
            </div>
          )}
        </section>

        {costData?.cost_unavailable_models.length ? (
          <section className="rounded-lg border border-warning/30 bg-warning/5 p-5">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone="amber">Gateway không báo cost</Badge>
              {costData.cost_unavailable_models.map((model) => (
                <span key={`${model.llm_provider}-${model.llm_model}`} className="font-mono text-sm text-foreground">
                  {model.llm_provider}/{model.llm_model} ({formatNumber(model.call_count)} call)
                </span>
              ))}
            </div>
            <p className="mt-2 text-xs text-muted-foreground">
              Các call này được ghi chi phí 0 nên tổng chi phí trong kỳ đang thiếu phần này. Kiểm tra cấu hình/version LiteLLM.
            </p>
          </section>
        ) : null}
      </div>
    </>
  );
}
