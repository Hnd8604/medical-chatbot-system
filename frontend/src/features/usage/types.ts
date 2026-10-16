/** Money values are BigDecimal on the server and may arrive as numbers or strings. */
export type Money = number | string;

export interface QuotaStatus {
  user: string;
  policy: string;
  daily_request_limit: number;
  daily_token_limit: number;
  daily_cost_limit_usd: Money;
  used_requests: number;
  used_input_tokens: number;
  used_output_tokens: number;
  used_tokens: number;
  used_cost_usd: Money;
  remaining_requests: number;
  remaining_tokens: number;
  remaining_cost_usd: Money;
  allowed: boolean;
  blocked_reason: string | null;
}

export interface ModelCost {
  llm_provider: string | null;
  llm_model: string | null;
  /** LLM calls of the model; one chat request can make several calls. */
  call_count: number;
  input_tokens: number;
  output_tokens: number;
  total_tokens: number;
  cost_usd: Money;
}

export interface DailyCost {
  date: string;
  request_count: number;
  input_tokens: number;
  output_tokens: number;
  total_tokens: number;
  cost_usd: Money;
}

/** Models whose gateway reported no cost; their calls were recorded at 0 USD. */
export interface UnpricedModel {
  llm_provider: string | null;
  llm_model: string | null;
  call_count: number;
}

export interface CostSummary {
  from: string;
  to: string;
  request_count: number;
  input_tokens: number;
  output_tokens: number;
  total_tokens: number;
  cost_usd: Money;
  models: ModelCost[];
  days: DailyCost[];
  cost_unavailable_models: UnpricedModel[];
}
