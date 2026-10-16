import type { CostSummary, QuotaStatus } from "@/features/usage/types";
import { api } from "@/shared/api/client";
import type { DateRange } from "@/shared/lib/dates";

function userPath(userId: string) {
  return `/api/admin/users/${encodeURIComponent(userId)}`;
}

export const userUsageKeys = {
  all: ["admin", "user-usage"] as const,
  quota: (userId: string) => [...userUsageKeys.all, userId, "quota"] as const,
  costs: (userId: string, range: DateRange) => [...userUsageKeys.all, userId, "costs", range.from, range.to] as const,
};

export const userUsageApi = {
  quota: (userId: string) => api.get<QuotaStatus>(`${userPath(userId)}/quota`),
  costs: (userId: string, range: DateRange) => api.get<CostSummary>(`${userPath(userId)}/costs`, { ...range }),
};
