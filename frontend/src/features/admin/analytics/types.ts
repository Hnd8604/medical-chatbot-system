import type { DateRange } from "@/shared/lib/dates";

export interface AnalyticsFilter extends DateRange {
  limit: number;
}

export interface IntentStat {
  date: string;
  intent: string;
  count: number;
}

export interface ErrorStat {
  date: string;
  service: string;
  errorType: string;
  count: number;
}

export interface PerformanceStat {
  model: string;
  avgLatency: number;
  p95Latency: number;
  p99Latency: number;
}

export interface RequestCount {
  from: string;
  to: string;
  requestCount: number;
}

export interface CacheMetrics {
  totalRequests: number;
  totalCacheHits: number;
  totalSavedTokens: number;
  totalSavedCostUsd: number | string;
  hitRatePercentage: number;
}
