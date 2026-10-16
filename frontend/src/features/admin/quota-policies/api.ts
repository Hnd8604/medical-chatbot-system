import { api } from "@/shared/api/client";
import type { QuotaPolicy, QuotaPolicyRequest } from "./types";

const BASE = "/api/admin/quota-policies";

export const quotaPolicyKeys = {
  list: () => ["admin", "quota-policies"] as const,
};

export const quotaPolicyApi = {
  list: () => api.get<QuotaPolicy[]>(BASE),
  create: (body: QuotaPolicyRequest) => api.post<QuotaPolicy>(BASE, body),
  update: (id: string, body: QuotaPolicyRequest) => api.put<QuotaPolicy>(`${BASE}/${encodeURIComponent(id)}`, body),
  remove: (id: string) => api.delete(`${BASE}/${encodeURIComponent(id)}`),
};
