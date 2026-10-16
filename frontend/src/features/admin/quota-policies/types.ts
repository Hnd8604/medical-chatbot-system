export interface QuotaPolicy {
  id: string;
  name: string;
  daily_request_limit: number;
  daily_token_limit: number;
  daily_cost_limit_usd: number | string;
  /** Null means no per-minute limit. */
  rate_limit_per_minute: number | null;
  created_at: string;
}

export interface QuotaPolicyRequest {
  name: string;
  daily_request_limit: number;
  daily_token_limit: number;
  daily_cost_limit_usd: number;
  rate_limit_per_minute: number | null;
}
