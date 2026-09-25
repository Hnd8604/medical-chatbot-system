import { useEffect, useMemo, useState } from "react";
import { CircleDollarSign, Gauge, RefreshCw, Search, ShieldCheck, WalletCards } from "lucide-react";
import { apiGet, todayIso, toQuery } from "../services/api";
import type {
  AdminUserListResponse,
  CostByDay,
  CostByModel,
  CostSummaryResponse,
  ModelPricingInfo,
  QuotaStatusResponse,
} from "../lib/types";
import { formatDate, formatNumber, formatUsd, numericValue } from "../lib/formatters";
import { DashboardLayout } from "../components/dashboard/DashboardLayout";
import { EmptyState } from "../components/dashboard/EmptyState";
import { MetricCard } from "../components/dashboard/MetricCard";
import { ProgressBar } from "../components/dashboard/ProgressBar";
import { Badge } from "../components/ui/Badge";
import { Button } from "../components/ui/Button";
import { inputClass } from "../components/ui/Field";
import { SectionLabel } from "../components/ui/SectionLabel";
import { Spinner } from "../components/ui/Spinner";

function sevenDaysAgoIso() {
  const date = new Date();
  date.setDate(date.getDate() - 6);
  return date.toISOString().slice(0, 10);
}

function percent(used: number | string | null | undefined, limit: number | string | null | undefined) {
  const limitValue = numericValue(limit);
  if (limitValue <= 0) return 0;
  return Math.min(100, (numericValue(used) / limitValue) * 100);
}

function progressTone(value: number) {
  if (value >= 90) return "red";
  if (value >= 75) return "amber";
  return "blue";
}

function modelName(item: CostByModel | ModelPricingInfo) {
  if ("model" in item) return item.model || "unknown";
  return item.llm_model || "unknown";
}

function providerName(item: CostByModel | ModelPricingInfo) {
  if ("provider" in item) return item.provider || "unknown";
  return item.llm_provider || "unknown";
}

function maxBy<T>(items: T[], valueOf: (item: T) => number) {
  return Math.max(1, ...items.map(valueOf));
}

function MiniBar({ value, max, tone = "blue" }: { value: number; max: number; tone?: "blue" | "green" | "amber" | "red" }) {
  return <ProgressBar value={(value / Math.max(max, 1)) * 100} tone={tone} />;
}

export function AdminUsageCostPage() {
  const [username, setUsername] = useState("");
  const [from, setFrom] = useState(sevenDaysAgoIso);
  const [to, setTo] = useState(todayIso);
  const [quota, setQuota] = useState<QuotaStatusResponse | null>(null);
  const [cost, setCost] = useState<CostSummaryResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [lookupLoading, setLookupLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [suggestions, setSuggestions] = useState<AdminUserListResponse["users"]>([]);
  const [showSuggestions, setShowSuggestions] = useState(false);

  const requestPct = percent(quota?.used_requests, quota?.daily_request_limit);
  const tokenPct = percent(quota?.used_tokens, quota?.daily_token_limit);
  const costPct = percent(quota?.used_cost_usd, quota?.daily_cost_limit_usd);
  const modelMaxCost = maxBy(cost?.models || [], (item) => numericValue(item.estimated_cost_usd));
  const dayMaxRequests = maxBy(cost?.days || [], (item) => numericValue(item.request_count));

  const sortedModels = useMemo(
    () => [...(cost?.models || [])].sort((left, right) => numericValue(right.estimated_cost_usd) - numericValue(left.estimated_cost_usd)),
    [cost?.models],
  );
  const sortedDays = useMemo(
    () => [...(cost?.days || [])].sort((left, right) => right.date.localeCompare(left.date)),
    [cost?.days],
  );
  async function loadUserDashboard(targetUsername = username) {
    if (!targetUsername.trim()) return;
    setLookupLoading(true);
    setError(null);
    try {
      const safeUsername = encodeURIComponent(targetUsername.trim());
      const query = toQuery({ from, to });
      const [quotaData, costData] = await Promise.all([
        apiGet<QuotaStatusResponse>(`/api/admin/quotas/users/${safeUsername}`),
        apiGet<CostSummaryResponse>(`/api/admin/costs/users/${safeUsername}${query}`),
      ]);
      setQuota(quotaData);
      setCost(costData);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : "Không thể tải quota/cost.");
    } finally {
      setLookupLoading(false);
    }
  }

  async function loadAll() {
    setLoading(true);
    setError(null);
    try {
      await loadUserDashboard(username);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : "Không thể tải dashboard.");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadAll();
  }, []);

  useEffect(() => {
    const term = username.trim();
    if (!showSuggestions || term.length < 1) {
      setSuggestions([]);
      return;
    }
    let cancelled = false;
    const timer = window.setTimeout(async () => {
      try {
        const data = await apiGet<AdminUserListResponse>(
          `/api/admin/users${toQuery({ search: term, size: 8 })}`,
        );
        if (!cancelled) setSuggestions(data.users);
      } catch {
        if (!cancelled) setSuggestions([]);
      }
    }, 250);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [username, showSuggestions]);

  function selectUser(picked: string) {
    setUsername(picked);
    setShowSuggestions(false);
    setSuggestions([]);
    void loadUserDashboard(picked);
  }

  async function resolveUsername(term: string): Promise<string | null> {
    const trimmed = term.trim();
    if (!trimmed) return null;
    try {
      const data = await apiGet<AdminUserListResponse>(
        `/api/admin/users${toQuery({ search: trimmed, size: 8 })}`,
      );
      const list = data.users || [];
      if (list.length === 0) return null;
      const lower = trimmed.toLowerCase();
      const exact = list.find(
        (item) =>
          item.username.toLowerCase() === lower ||
          item.email?.toLowerCase() === lower ||
          item.display_name?.toLowerCase() === lower,
      );
      return (exact || list[0]).username;
    } catch {
      return null;
    }
  }

  async function applyLookup() {
    setShowSuggestions(false);
    if (!username.trim()) return;
    const resolved = await resolveUsername(username);
    if (!resolved) {
      setError("Không tìm thấy người dùng phù hợp với từ khóa.");
      return;
    }
    if (resolved !== username) setUsername(resolved);
    await loadUserDashboard(resolved);
  }

  return (
    <DashboardLayout
      eyebrow="Quota & Cost"
      title="Quota, usage, cost"
      actions={
        <Button type="button" variant="secondary" onClick={() => void loadAll()} disabled={loading || lookupLoading}>
          {loading ? <Spinner /> : <RefreshCw className="h-4 w-4" />}
          Làm mới
        </Button>
      }
    >
      {error ? <div className="mb-6 rounded-lg border border-danger/25 bg-danger/5 p-4 text-sm text-danger">{error}</div> : null}

      <div className="grid gap-7">
        <section className="rounded-lg border border-border bg-white p-4">
          <div className="grid gap-3 lg:grid-cols-[1fr_12rem_12rem_auto] lg:items-end">
            <label className="grid gap-1">
              <span className="text-xs font-medium text-muted-foreground">Người dùng</span>
              <div className="relative">
                <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                <input
                  className={inputClass("pl-10")}
                  value={username}
                  placeholder="Nhập tên đăng nhập, email hoặc tên hiển thị"
                  autoComplete="off"
                  onChange={(event) => {
                    setUsername(event.target.value);
                    setShowSuggestions(true);
                  }}
                  onFocus={() => setShowSuggestions(true)}
                  onBlur={() => window.setTimeout(() => setShowSuggestions(false), 150)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter") {
                      void applyLookup();
                    }
                  }}
                />
                {showSuggestions && suggestions.length > 0 ? (
                  <ul className="absolute z-20 mt-1 max-h-64 w-full overflow-auto rounded-lg border border-border bg-white py-1 shadow-lg">
                    {suggestions.map((item) => (
                      <li key={item.id}>
                        <button
                          type="button"
                          className="flex w-full flex-col items-start gap-0.5 px-3 py-2 text-left hover:bg-muted/60"
                          onMouseDown={(event) => {
                            event.preventDefault();
                            selectUser(item.username);
                          }}
                        >
                          <span className="text-sm font-medium text-foreground">{item.username}</span>
                          <span className="text-xs text-muted-foreground">
                            {item.display_name}
                            {item.email ? ` · ${item.email}` : ""}
                          </span>
                        </button>
                      </li>
                    ))}
                  </ul>
                ) : null}
              </div>
            </label>
            <label className="grid gap-1">
              <span className="text-xs font-medium text-muted-foreground">Từ ngày</span>
              <input className={inputClass()} type="date" value={from} max={to} onChange={(event) => setFrom(event.target.value)} />
            </label>
            <label className="grid gap-1">
              <span className="text-xs font-medium text-muted-foreground">Đến ngày</span>
              <input className={inputClass()} type="date" value={to} min={from} onChange={(event) => setTo(event.target.value)} />
            </label>
            <Button type="button" variant="primary" onClick={() => void applyLookup()} disabled={lookupLoading || !username.trim() || from > to}>
              {lookupLoading ? <Spinner /> : <Gauge className="h-4 w-4" />}
              Áp dụng
            </Button>
          </div>
        </section>

        <section className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
          <MetricCard
            label="Request/ngày"
            value={`${formatNumber(quota?.used_requests)}/${formatNumber(quota?.daily_request_limit)}`}
            progress={requestPct}
            progressTone={progressTone(requestPct)}
          />
          <MetricCard
            label="Token/ngày"
            value={`${formatNumber(quota?.used_tokens)}/${formatNumber(quota?.daily_token_limit)}`}
            progress={tokenPct}
            progressTone={progressTone(tokenPct)}
          />
          <MetricCard
            label="Cost/ngày"
            value={formatUsd(quota?.used_cost_usd)}
            progress={costPct}
            progressTone={progressTone(costPct)}
          />
          <MetricCard label="Cost range" value={formatUsd(cost?.estimated_cost_usd)} description={`${formatNumber(cost?.request_count)} request`} />
        </section>

        <section className="grid gap-6 xl:grid-cols-[0.9fr_1.1fr]">
          <div className="rounded-lg border border-border bg-white p-5">
            <div className="flex items-center justify-between gap-3">
              <div>
                <SectionLabel>Quota</SectionLabel>
                <h2 className="mt-3 text-lg font-semibold text-foreground">{quota?.policy || "-"}</h2>
              </div>
              <ShieldCheck className={quota?.allowed ? "h-6 w-6 text-success" : "h-6 w-6 text-danger"} />
            </div>
            <div className="mt-6 grid gap-5">
              <ProgressBar
                label="Request"
                value={requestPct}
                tone={progressTone(requestPct)}
                hint={`${formatNumber(quota?.used_requests)}/${formatNumber(quota?.daily_request_limit)} · ${Math.round(requestPct)}%`}
              />
              <ProgressBar
                label="Token"
                value={tokenPct}
                tone={progressTone(tokenPct)}
                hint={`${formatNumber(quota?.used_tokens)}/${formatNumber(quota?.daily_token_limit)} · ${Math.round(tokenPct)}%`}
              />
              <ProgressBar
                label="Cost"
                value={costPct}
                tone={progressTone(costPct)}
                hint={`${formatUsd(quota?.used_cost_usd)}/${formatUsd(quota?.daily_cost_limit_usd)} · ${Math.round(costPct)}%`}
              />
            </div>
          </div>

          <div className="rounded-lg border border-border bg-white p-5">
            <div className="mb-4 flex items-center justify-between gap-3">
              <SectionLabel>Model cost</SectionLabel>
              <WalletCards className="h-5 w-5 text-accent" />
            </div>
            {sortedModels.length === 0 ? (
              <EmptyState title="Chưa có cost theo model." />
            ) : (
              <div className="grid gap-3">
                {sortedModels.slice(0, 5).map((item) => (
                  <div key={`${providerName(item)}-${modelName(item)}`} className="rounded-lg border border-border bg-muted/40 p-3">
                    <div className="flex items-center justify-between gap-3">
                      <div className="min-w-0">
                        <p className="truncate text-sm font-medium text-foreground">{modelName(item)}</p>
                        <p className="text-xs text-muted-foreground">{providerName(item)} · {formatNumber(item.request_count)} request</p>
                      </div>
                      <span className="font-semibold text-foreground">{formatUsd(item.estimated_cost_usd)}</span>
                    </div>
                    <MiniBar value={numericValue(item.estimated_cost_usd)} max={modelMaxCost} tone="green" />
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
          {sortedDays.length === 0 ? (
            <EmptyState title="Chưa có usage theo ngày." />
          ) : (
            <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
              {sortedDays.slice(0, 8).map((day: CostByDay) => (
                <article key={day.date} className="rounded-lg border border-border bg-white p-4">
                  <div className="flex items-center justify-between gap-3">
                    <span className="font-semibold text-foreground">{formatDate(day.date)}</span>
                    <span className="font-mono text-sm text-accent">{formatNumber(day.request_count)} req</span>
                  </div>
                  <p className="mt-2 text-lg font-semibold text-foreground">{formatUsd(day.estimated_cost_usd)}</p>
                  <MiniBar value={numericValue(day.request_count)} max={dayMaxRequests} />
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

        {cost?.missing_pricing_models?.length ? (
          <section className="rounded-lg border border-warning/30 bg-warning/5 p-5">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone="amber">Missing pricing</Badge>
              {cost.missing_pricing_models.map((item) => (
                <span key={`${item.llm_provider}-${item.llm_model}`} className="font-mono text-sm text-foreground">
                  {item.llm_provider}/{item.llm_model} ({formatNumber(item.request_count)})
                </span>
              ))}
            </div>
          </section>
        ) : null}
      </div>
    </DashboardLayout>
  );
}
