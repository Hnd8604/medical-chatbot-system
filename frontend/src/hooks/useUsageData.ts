import { useCallback, useEffect, useMemo, useState } from "react";
import { apiGet, toQuery, todayIso } from "../services/api";
import type { CostSummaryResponse, QuotaStatusResponse } from "../lib/types";

function sevenDaysAgoIso() {
  const date = new Date();
  date.setDate(date.getDate() - 6);
  return date.toISOString().slice(0, 10);
}

export type UsageRange = {
  from: string;
  to: string;
};

export function useUsageData() {
  const [quota, setQuota] = useState<QuotaStatusResponse | null>(null);
  const [cost, setCost] = useState<CostSummaryResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const range = useMemo<UsageRange>(() => ({ from: sevenDaysAgoIso(), to: todayIso() }), []);

  const reload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [quotaData, costData] = await Promise.all([
        apiGet<QuotaStatusResponse>("/api/quota/status"),
        apiGet<CostSummaryResponse>(`/api/usage/cost-summary${toQuery(range)}`),
      ]);
      setQuota(quotaData);
      setCost(costData);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : "Không thể tải dữ liệu usage.");
    } finally {
      setLoading(false);
    }
  }, [range]);

  useEffect(() => {
    void reload();
  }, [reload]);

  return { quota, cost, loading, error, range, reload };
}
