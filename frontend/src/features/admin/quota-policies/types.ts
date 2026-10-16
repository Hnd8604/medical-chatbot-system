export interface QuotaPolicy {
  id: string;
  name: string;
  dailyRequestLimit: number;
  dailyTokenLimit: number;
  dailyCostLimitUsd: number | string;
  /** Null means no per-minute limit. */
  rateLimitPerMinute: number | null;
  createdAt: string;
}

export interface QuotaPolicyRequest {
  name: string;
  dailyRequestLimit: number;
  dailyTokenLimit: number;
  dailyCostLimitUsd: number;
  rateLimitPerMinute: number | null;
}
