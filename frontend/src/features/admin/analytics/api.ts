import { api } from "@/shared/api/client";
import type { DateRange } from "@/shared/lib/dates";
import type { AnalyticsFilter, CacheMetrics, ErrorStat, IntentStat, PerformanceStat, RequestCount } from "./types";

const BASE = "/admin/analytics";

export const analyticsKeys = {
  all: ["admin", "analytics"] as const,
  intents: (filter: AnalyticsFilter) => [...analyticsKeys.all, "intents", filter] as const,
  errors: (filter: AnalyticsFilter) => [...analyticsKeys.all, "errors", filter] as const,
  performance: (filter: AnalyticsFilter) => [...analyticsKeys.all, "performance", filter] as const,
  requests: (range: DateRange) => [...analyticsKeys.all, "requests", range] as const,
  cache: () => [...analyticsKeys.all, "cache"] as const,
};

export const analyticsApi = {
  intents: (filter: AnalyticsFilter) => api.get<IntentStat[]>(`${BASE}/intents`, { ...filter }),
  errors: (filter: AnalyticsFilter) => api.get<ErrorStat[]>(`${BASE}/errors`, { ...filter }),
  performance: (filter: AnalyticsFilter) => api.get<PerformanceStat[]>(`${BASE}/performance`, { ...filter }),
  requests: (range: DateRange) => api.get<RequestCount>(`${BASE}/requests`, { ...range }),
  /** Semantic-cache hit rate and savings over all recorded requests. */
  cache: () => api.get<CacheMetrics>(`${BASE}/cache`),
};
