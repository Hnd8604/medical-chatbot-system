import { useQuery } from "@tanstack/react-query";
import { apiGet, toQuery } from "../services/api";
import type { CostSummaryResponse, QuotaStatusResponse } from "../lib/types";

// Query keys tập trung: dùng lại cho invalidate/prefetch ở nơi khác.
// Ví dụ sau khi gửi chat làm thay đổi quota:
//   queryClient.invalidateQueries({ queryKey: usageKeys.all });
export const usageKeys = {
  all: ["usage"] as const,
  quotaStatus: () => [...usageKeys.all, "quota-status"] as const,
  costSummary: (from: string, to: string) =>
    [...usageKeys.all, "cost-summary", from, to] as const,
};

export function useQuotaStatus() {
  return useQuery({
    queryKey: usageKeys.quotaStatus(),
    queryFn: () => apiGet<QuotaStatusResponse>("/api/quota/status"),
  });
}

export function useCostSummary(range: { from: string; to: string }) {
  return useQuery({
    queryKey: usageKeys.costSummary(range.from, range.to),
    queryFn: () =>
      apiGet<CostSummaryResponse>(`/api/usage/cost-summary${toQuery(range)}`),
  });
}
