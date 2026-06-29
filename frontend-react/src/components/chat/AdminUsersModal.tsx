import { useEffect, useState } from "react";
import { apiJson } from "../../services/api";
import { AdminUserItem, AdminUserListResponse, UserRole, UserStatus } from "../../lib/types";
import { formatDateTime, roleLabel, statusLabel } from "../../lib/formatters";
import { Button } from "../ui/Button";
import { Badge } from "../ui/Badge";
import { Modal } from "../ui/Modal";
import { Spinner } from "../ui/Spinner";

interface AdminUsersModalProps {
  open: boolean;
  onClose: () => void;
  onError: (message: string) => void;
}

const roles: UserRole[] = ["USER", "DOCTOR", "ADMIN"];
const statuses: UserStatus[] = ["ACTIVE", "LOCKED", "DISABLED"];

export function AdminUsersModal({ open, onClose, onError }: AdminUsersModalProps) {
  const [users, setUsers] = useState<AdminUserItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [updatingId, setUpdatingId] = useState<string | null>(null);

  async function loadUsers() {
    setLoading(true);
    try {
      const data = await apiJson<AdminUserListResponse>("/api/admin/users?page=0&size=50");
      setUsers(data.users);
    } catch (error) {
      onError(error instanceof Error ? error.message : "Không thể tải danh sách người dùng.");
    } finally {
      setLoading(false);
    }
  }

  async function patchUser(id: string, path: "role" | "status", value: UserRole | UserStatus) {
    setUpdatingId(id);
    try {
      await apiJson(`/api/admin/users/${encodeURIComponent(id)}/${path}`, {
        method: "PATCH",
        body: JSON.stringify({ [path]: value }),
      });
      await loadUsers();
    } catch (error) {
      onError(error instanceof Error ? error.message : "Không thể cập nhật người dùng.");
    } finally {
      setUpdatingId(null);
    }
  }

  useEffect(() => {
    if (open) {
      void loadUsers();
    }
  }, [open]);

  return (
    <Modal open={open} title="Quản lý người dùng" description="Khóa, mở khóa tài khoản và thay đổi vai trò." onClose={onClose} size="lg">
      <div className="mb-4 flex justify-end">
        <Button type="button" variant="secondary" onClick={() => void loadUsers()} disabled={loading}>
          {loading ? <Spinner /> : null}
          Làm mới
        </Button>
      </div>
      <div className="grid gap-3">
        {users.map((user) => (
          <article key={user.id} className="rounded-2xl border border-border bg-white p-4">
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div className="min-w-0">
                <strong className="line-clamp-1">{user.display_name || user.username}</strong>
                <p className="line-clamp-1 text-sm text-muted-foreground">{user.email}</p>
                <p className="mt-1 text-xs text-muted-foreground">Tạo lúc {formatDateTime(user.created_at)}</p>
              </div>
              <div className="flex flex-wrap gap-2">
                <Badge tone="blue">{roleLabel(user.role)}</Badge>
                <Badge tone={user.status === "ACTIVE" ? "green" : "amber"}>{statusLabel(user.status)}</Badge>
              </div>
            </div>
            <div className="mt-4 grid gap-3 sm:grid-cols-2">
              <label className="grid gap-2 text-sm font-semibold">
                Vai trò
                <select
                  className="focus-ring min-h-11 rounded-xl border border-border bg-white px-3"
                  value={user.role}
                  disabled={updatingId === user.id}
                  onChange={(event) => void patchUser(user.id, "role", event.target.value as UserRole)}
                >
                  {roles.map((role) => (
                    <option key={role} value={role}>
                      {roleLabel(role)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="grid gap-2 text-sm font-semibold">
                Trạng thái
                <select
                  className="focus-ring min-h-11 rounded-xl border border-border bg-white px-3"
                  value={user.status}
                  disabled={updatingId === user.id}
                  onChange={(event) => void patchUser(user.id, "status", event.target.value as UserStatus)}
                >
                  {statuses.map((status) => (
                    <option key={status} value={status}>
                      {statusLabel(status)}
                    </option>
                  ))}
                </select>
              </label>
            </div>
          </article>
        ))}
      </div>
    </Modal>
  );
}
