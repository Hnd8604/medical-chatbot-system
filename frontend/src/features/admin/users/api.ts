import { api } from "@/shared/api/client";
import type { PageResponse } from "@/shared/api/types";
import type { UserRole, UserStatus } from "@/features/auth/types";
import type { AdminUser } from "./types";

export interface UserListParams {
  page?: number;
  size?: number;
  /** Matches username, email or display name. */
  search?: string;
}

export const adminUserKeys = {
  all: ["admin", "users"] as const,
  list: (params: UserListParams) => [...adminUserKeys.all, params] as const,
};

export const adminUserApi = {
  list: (params: UserListParams) => api.get<PageResponse<AdminUser>>("/api/admin/users", { ...params }),
  updateRole: (userId: string, role: UserRole) =>
    api.patch<AdminUser>(`/api/admin/users/${encodeURIComponent(userId)}/role`, { role }),
  updateStatus: (userId: string, status: UserStatus) =>
    api.patch<AdminUser>(`/api/admin/users/${encodeURIComponent(userId)}/status`, { status }),
};
