import { api } from "@/shared/api/client";
import type { PageResponse } from "@/shared/api/types";
import { endOfDayIso, startOfDayIso } from "@/shared/lib/dates";
import type { AuditLog, AuditLogFilter, AuditLogFilterOptions } from "./types";

const BASE = "/admin/audit-logs";

export const auditLogKeys = {
  all: ["admin", "audit-logs"] as const,
  list: (filter: AuditLogFilter, page: number, size: number) => [...auditLogKeys.all, filter, page, size] as const,
  options: () => [...auditLogKeys.all, "filter-options"] as const,
};

export const auditLogApi = {
  list: (filter: AuditLogFilter, page: number, size: number) =>
    api.get<PageResponse<AuditLog>>(BASE, {
      ...filter,
      from: startOfDayIso(filter.from),
      to: endOfDayIso(filter.to),
      page,
      size,
    }),
  filterOptions: () => api.get<AuditLogFilterOptions>(`${BASE}/filters`),
};
