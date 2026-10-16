import { api } from "@/shared/api/client";
import type { PageResponse } from "@/shared/api/types";
import type { Alert, AlertFilter } from "./types";

export const alertKeys = {
  all: ["admin", "alerts"] as const,
  list: (filter: AlertFilter) => [...alertKeys.all, filter] as const,
};

export const alertApi = {
  list: (filter: AlertFilter) => api.get<PageResponse<Alert>>("/api/admin/alerts", { ...filter, sort: "createdAt,desc" }),
  resolve: (alertId: string) => api.patch(`/api/admin/alerts/${encodeURIComponent(alertId)}/resolve`),
};
