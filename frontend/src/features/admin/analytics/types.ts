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
  error_type: string;
  count: number;
}

export interface PerformanceStat {
  model: string;
  avg_latency: number;
  p95_latency: number;
  p99_latency: number;
}

export interface RequestCount {
  from: string;
  to: string;
  request_count: number;
}

export interface CacheMetrics {
  total_requests: number;
  total_cache_hits: number;
  total_saved_tokens: number;
  total_saved_cost_usd: number | string;
  hit_rate_percentage: number;
}
