import { api } from "@/shared/api/client";
import type { DateRange } from "@/shared/lib/dates";
import type { CostSummary, QuotaStatus } from "./types";

export const usageKeys = {
  all: ["usage"] as const,
  quota: () => [...usageKeys.all, "quota"] as const,
  costs: (range: DateRange) => [...usageKeys.all, "costs", range.from, range.to] as const,
};

export const usageApi = {
  quota: () => api.get<QuotaStatus>("/api/usage/quota"),
  costs: (range: DateRange) => api.get<CostSummary>("/api/usage/costs", { ...range }),
};
