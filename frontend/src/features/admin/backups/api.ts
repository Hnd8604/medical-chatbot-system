import { api } from "@/shared/api/client";
import type { PageResponse } from "@/shared/api/types";
import type { Backup, Restore } from "./types";

export const backupKeys = {
  all: ["admin", "backups"] as const,
  backups: () => [...backupKeys.all, "backups"] as const,
  restores: () => [...backupKeys.all, "restores"] as const,
};

export const backupApi = {
  backups: (size: number) => api.get<PageResponse<Backup>>("/api/admin/backups", { page: 0, size }),
  restores: (size: number) => api.get<PageResponse<Restore>>("/api/admin/restores", { page: 0, size }),
  /** Starts a backup in the background; poll the list for its outcome. */
  start: () => api.post<Backup>("/api/admin/backups"),
  /** Overwrites both databases with the backup, in the background. */
  restore: (backupId: string) => api.post<Restore>(`/api/admin/backups/${encodeURIComponent(backupId)}/restore`),
};
