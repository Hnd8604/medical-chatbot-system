/** Money values are BigDecimal on the server and may arrive as numbers or strings. */
export type Money = number | string;

export interface QuotaStatus {
  user: string;
  policy: string;
  dailyRequestLimit: number;
  dailyTokenLimit: number;
  dailyCostLimitUsd: Money;
  usedRequests: number;
  usedInputTokens: number;
  usedOutputTokens: number;
  usedTokens: number;
  usedCostUsd: Money;
  remainingRequests: number;
  remainingTokens: number;
  remainingCostUsd: Money;
  allowed: boolean;
  blockedReason: string | null;
}

export interface ModelCost {
  llmProvider: string | null;
  llmModel: string | null;
  /** LLM calls of the model; one chat request can make several calls. */
  callCount: number;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  costUsd: Money;
}

export interface DailyCost {
  date: string;
  requestCount: number;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  costUsd: Money;
}

/** Models whose gateway reported no cost; their calls were recorded at 0 USD. */
export interface UnpricedModel {
  llmProvider: string | null;
  llmModel: string | null;
  callCount: number;
}

export interface CostSummary {
  from: string;
  to: string;
  requestCount: number;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  costUsd: Money;
  models: ModelCost[];
  days: DailyCost[];
  costUnavailableModels: UnpricedModel[];
}
