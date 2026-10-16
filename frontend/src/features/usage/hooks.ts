import { useQuery } from "@tanstack/react-query";
import type { DateRange } from "@/shared/lib/dates";
import { usageApi, usageKeys } from "./api";

/** Today's quota of the signed-in user plus their cost breakdown for the range. */
export function useUsageSummary(range: DateRange) {
  const quota = useQuery({ queryKey: usageKeys.quota(), queryFn: usageApi.quota });
  const cost = useQuery({ queryKey: usageKeys.costs(range), queryFn: () => usageApi.costs(range) });

  return {
    quota: quota.data ?? null,
    cost: cost.data ?? null,
    loading: quota.isFetching || cost.isFetching,
    error: quota.error ?? cost.error,
    reload: () => void Promise.all([quota.refetch(), cost.refetch()]),
  };
}
