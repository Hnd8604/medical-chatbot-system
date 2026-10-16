import { api } from "@/shared/api/client";
import type { PageResponse } from "@/shared/api/types";
import type { Alert, AlertFilter } from "./types";

export const alertKeys = {
  all: ["admin", "alerts"] as const,
  list: (filter: AlertFilter) => [...alertKeys.all, filter] as const,
};

export const alertApi = {
  list: (filter: AlertFilter) => api.get<PageResponse<Alert>>("/admin/alerts", { ...filter, sort: "createdAt,desc" }),
  resolve: (alertId: string) => api.put(`/admin/alerts/${encodeURIComponent(alertId)}/resolve`),
};
