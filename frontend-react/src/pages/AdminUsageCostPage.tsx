import { useEffect, useMemo, useState } from "react";
import { CircleDollarSign, Gauge, RefreshCw, Search, ShieldCheck, WalletCards } from "lucide-react";
import { apiGet, todayIso, toQuery } from "../services/api";
import { useAuth } from "../hooks/useAuth";
import type {
  AdminUserItem,
  AdminUserListResponse,
  CostByDay,
  CostByModel,
  CostSummaryResponse,
  ModelPricingInfo,
  ModelPricingListResponse,
  QuotaPolicy,
  QuotaStatusResponse,
} from "../lib/types";
import { formatDate, formatNumber, formatUsd, numericValue, roleLabel } from "../lib/formatters";
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
  const { user } = useAuth();
  const [username, setUsername] = useState(user?.username || "admin_demo");
  const [from, setFrom] = useState(sevenDaysAgoIso);
  const [to, setTo] = useState(todayIso);
  const [quota, setQuota] = useState<QuotaStatusResponse | null>(null);
  const [cost, setCost] = useState<CostSummaryResponse | null>(null);
  const [users, setUsers] = useState<AdminUserItem[]>([]);
  const [userQuery, setUserQuery] = useState("");
  const [policies, setPolicies] = useState<QuotaPolicy[]>([]);
  const [pricing, setPricing] = useState<ModelPricingInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [lookupLoading, setLookupLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

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
  const visibleUsers = useMemo(() => {
    const normalized = userQuery.trim().toLowerCase();
    const source = normalized
      ? users.filter((item) =>
          [item.username, item.email, item.display_name].some((value) => value?.toLowerCase().includes(normalized)),
        )
      : users;
    return source.slice(0, 8);
  }, [users, userQuery]);

  async function loadReferenceData() {
    const [policyData, pricingData, userData] = await Promise.all([
      apiGet<QuotaPolicy[]>("/api/admin/quotas/policies"),
      apiGet<ModelPricingListResponse>("/api/admin/costs/pricing"),
      apiGet<AdminUserListResponse>("/api/admin/users?page=0&size=100"),
    ]);
    setPolicies(policyData || []);
    setPricing(pricingData.pricing || []);
    setUsers(userData.users || []);
  }

  function selectUser(nextUsername: string) {
    setUsername(nextUsername);
    void loadUserDashboard(nextUsername);
  }

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
      await loadReferenceData();
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

  return (
    <DashboardLayout
      eyebrow="Quota & Cost"
      title="Quota, usage, cost"
      description="Dashboard admin theo user, policy và bảng giá model."
      actions={
        <Button type="button" variant="secondary" onClick={() => void loadAll()} disabled={loading || lookupLoading}>
          {loading ? <Spinner /> : <RefreshCw className="h-4 w-4" />}
          Làm mới
        </Button>
      }
    >
      {error ? <div className="mb-6 rounded-lg border border-danger/25 bg-danger/5 p-4 text-sm text-danger">{error}</div> : null}

      <div className="grid gap-7">
        <section className="rounded-xl border border-border bg-white p-4 shadow-sm">
          <div className="grid gap-3 lg:grid-cols-[1fr_12rem_12rem_auto] lg:items-end">
            <label className="grid gap-1">
              <span className="font-mono text-[10px] uppercase tracking-[0.14em] text-muted-foreground">Username</span>
              <div className="relative">
                <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                <input
                  className={inputClass("pl-10")}
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter") void loadUserDashboard();
                  }}
                />
              </div>
            </label>
            <label className="grid gap-1">
              <span className="font-mono text-[10px] uppercase tracking-[0.14em] text-muted-foreground">Từ ngày</span>
              <input className={inputClass()} type="date" value={from} max={to} onChange={(event) => setFrom(event.target.value)} />
            </label>
            <label className="grid gap-1">
              <span className="font-mono text-[10px] uppercase tracking-[0.14em] text-muted-foreground">Đến ngày</span>
              <input className={inputClass()} type="date" value={to} min={from} onChange={(event) => setTo(event.target.value)} />
            </label>
            <Button type="button" variant="primary" onClick={() => void loadUserDashboard()} disabled={lookupLoading || !username.trim() || from > to}>
              {lookupLoading ? <Spinner /> : <Gauge className="h-4 w-4" />}
              Áp dụng
            </Button>
          </div>
        </section>

        <section className="rounded-xl border border-border bg-white p-4 shadow-sm">
          <div className="grid gap-3 lg:grid-cols-[16rem_1fr]">
            <label className="relative">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <input
                className={inputClass("pl-10")}
                value={userQuery}
                placeholder="Lọc user"
                onChange={(event) => setUserQuery(event.target.value)}
              />
            </label>
            <div className="grid gap-2 md:grid-cols-2 xl:grid-cols-4">
              {visibleUsers.map((item) => {
                const active = item.username === username;
                return (
                  <button
                    key={item.id}
                    type="button"
                    className={
                      active
                        ? "focus-ring rounded-lg border border-accent/30 bg-accent/10 p-3 text-left shadow-sm"
                        : "focus-ring rounded-lg border border-border bg-white p-3 text-left shadow-sm transition hover:-translate-y-0.5 hover:border-accent/35 hover:shadow-card"
                    }
                    onClick={() => selectUser(item.username)}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="line-clamp-1 font-semibold text-foreground">{item.username}</span>
                      <Badge tone={item.status === "ACTIVE" ? "green" : "amber"}>{item.status}</Badge>
                    </div>
                    <p className="mt-1 line-clamp-1 text-xs text-muted-foreground">{roleLabel(item.role)}</p>
                  </button>
                );
              })}
            </div>
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
            trend={quota?.allowed ? "OK" : "BLOCK"}
            trendTone={quota?.allowed ? "up" : "down"}
            progress={costPct}
            progressTone={progressTone(costPct)}
          />
          <MetricCard label="Cost range" value={formatUsd(cost?.estimated_cost_usd)} description={`${formatNumber(cost?.request_count)} request`} />
        </section>

        <section className="grid gap-6 xl:grid-cols-[0.9fr_1.1fr]">
          <div className="rounded-xl border border-border bg-white p-5 shadow-sm">
            <div className="flex items-center justify-between gap-3">
              <div>
                <SectionLabel>Quota</SectionLabel>
                <h2 className="mt-3 font-display text-2xl text-foreground">{quota?.policy || "-"}</h2>
              </div>
              <ShieldCheck className={quota?.allowed ? "h-6 w-6 text-success" : "h-6 w-6 text-danger"} />
            </div>
            <div className="mt-6 grid gap-5">
              <ProgressBar label="Request" value={requestPct} tone={progressTone(requestPct)} />
              <ProgressBar label="Token" value={tokenPct} tone={progressTone(tokenPct)} />
              <ProgressBar label="Cost" value={costPct} tone={progressTone(costPct)} />
            </div>
          </div>

          <div className="rounded-xl border border-border bg-white p-5 shadow-sm">
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
                        <p className="truncate font-mono text-xs uppercase tracking-[0.08em] text-foreground">{modelName(item)}</p>
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

        <section className="grid gap-6 xl:grid-cols-2">
          <div className="rounded-xl border border-border bg-white p-5 shadow-sm">
            <div className="mb-4 flex items-center justify-between gap-3">
              <SectionLabel>Policies</SectionLabel>
              <Badge tone="blue">{formatNumber(policies.length)}</Badge>
            </div>
            <div className="grid gap-3">
              {policies.map((policy) => (
                <div key={policy.id} className="grid gap-3 rounded-lg border border-border bg-white p-3 md:grid-cols-[1fr_1fr_1fr_1fr]">
                  <strong className="text-foreground">{policy.name}</strong>
                  <span className="font-mono text-sm">{formatNumber(policy.dailyRequestLimit)} req</span>
                  <span className="font-mono text-sm">{formatNumber(policy.dailyTokenLimit)} tok</span>
                  <span className="font-mono text-sm">{formatUsd(policy.dailyCostLimitUsd)}</span>
                </div>
              ))}
            </div>
          </div>

          <div className="rounded-xl border border-border bg-white p-5 shadow-sm">
            <div className="mb-4 flex items-center justify-between gap-3">
              <SectionLabel>Pricing</SectionLabel>
              <Badge tone="blue">{formatNumber(pricing.length)}</Badge>
            </div>
            <div className="grid gap-3">
              {pricing.slice(0, 6).map((item) => (
                <div key={`${item.provider}-${item.model}`} className="grid gap-3 rounded-lg border border-border bg-muted/40 p-3 md:grid-cols-[1fr_auto_auto] md:items-center">
                  <div className="min-w-0">
                    <p className="truncate font-mono text-xs uppercase tracking-[0.08em] text-foreground">{item.model}</p>
                    <p className="text-xs text-muted-foreground">{item.provider} · {item.currency}</p>
                  </div>
                  <Badge tone="slate">In {formatUsd(item.input_price_per_1m_tokens)}</Badge>
                  <Badge tone="slate">Out {formatUsd(item.output_price_per_1m_tokens)}</Badge>
                </div>
              ))}
            </div>
          </div>
        </section>

        <section className="rounded-xl border border-border bg-white p-5 shadow-sm">
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
                    <span className="font-mono text-sm text-accent">{formatNumber(day.request_count)}</span>
                  </div>
                  <p className="mt-2 font-display text-2xl text-foreground">{formatUsd(day.estimated_cost_usd)}</p>
                  <MiniBar value={numericValue(day.request_count)} max={dayMaxRequests} />
                </article>
              ))}
            </div>
          )}
        </section>

        {cost?.missing_pricing_models?.length ? (
          <section className="rounded-xl border border-warning/30 bg-warning/10 p-5">
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
