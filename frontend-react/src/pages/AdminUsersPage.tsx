import { useEffect, useMemo, useState } from "react";
import { RefreshCw, Search, ShieldAlert } from "lucide-react";
import { apiGet, apiPatch } from "../services/api";
import { useAuth } from "../hooks/useAuth";
import type { AdminUserItem, AdminUserListResponse, UserRole, UserStatus } from "../lib/types";
import { formatDateTime, roleLabel, statusLabel } from "../lib/formatters";
import { DashboardLayout } from "../components/dashboard/DashboardLayout";
import { EmptyState } from "../components/dashboard/EmptyState";
import { Badge } from "../components/ui/Badge";
import { Button } from "../components/ui/Button";
import { inputClass } from "../components/ui/Field";
import { Spinner } from "../components/ui/Spinner";

const roles: UserRole[] = ["USER", "DOCTOR", "ADMIN"];
const statuses: UserStatus[] = ["ACTIVE", "LOCKED", "DISABLED"];

function roleTone(role: UserRole) {
  if (role === "ADMIN") {
    return "blue";
  }
  if (role === "DOCTOR") {
    return "green";
  }
  return "slate";
}

function statusTone(status: UserStatus) {
  if (status === "ACTIVE") {
    return "green";
  }
  if (status === "LOCKED") {
    return "amber";
  }
  return "red";
}

function matchesQuery(user: AdminUserItem, query: string) {
  const normalized = query.trim().toLowerCase();
  if (!normalized) {
    return true;
  }
  return [user.username, user.email, user.display_name].some((value) => value?.toLowerCase().includes(normalized));
}

function PatientLinkCell({ user }: { user: AdminUserItem }) {
  const links = user.patient_links || [];
  if (links.length === 0) {
    return <span className="text-sm text-muted-foreground">Chưa có dữ liệu liên kết</span>;
  }

  return (
    <div className="flex flex-wrap gap-2">
      {links.map((link, index) => (
        <Badge key={`${link.fhir_patient_id || index}`} tone={link.is_primary ? "blue" : "slate"}>
          {link.fhir_patient_id || "Patient"}
        </Badge>
      ))}
    </div>
  );
}

export function AdminUsersPage() {
  const { user: currentUser } = useAuth();
  const [users, setUsers] = useState<AdminUserItem[]>([]);
  const [query, setQuery] = useState("");
  const [roleFilter, setRoleFilter] = useState<UserRole | "ALL">("ALL");
  const [statusFilter, setStatusFilter] = useState<UserStatus | "ALL">("ALL");
  const [loading, setLoading] = useState(true);
  const [updatingId, setUpdatingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function loadUsers() {
    setLoading(true);
    setError(null);
    try {
      const data = await apiGet<AdminUserListResponse>("/api/admin/users?page=0&size=100");
      setUsers(data.users || []);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : "Không thể tải danh sách người dùng.");
    } finally {
      setLoading(false);
    }
  }

  async function patchUser(id: string, path: "role" | "status", value: UserRole | UserStatus) {
    setUpdatingId(id);
    setError(null);
    try {
      await apiPatch(`/api/admin/users/${encodeURIComponent(id)}/${path}`, { [path]: value });
      await loadUsers();
    } catch (updateError) {
      setError(updateError instanceof Error ? updateError.message : "Không thể cập nhật người dùng.");
    } finally {
      setUpdatingId(null);
    }
  }

  useEffect(() => {
    void loadUsers();
  }, []);

  const filteredUsers = useMemo(
    () =>
      users.filter((item) => {
        const roleMatch = roleFilter === "ALL" || item.role === roleFilter;
        const statusMatch = statusFilter === "ALL" || item.status === statusFilter;
        return roleMatch && statusMatch && matchesQuery(item, query);
      }),
    [users, query, roleFilter, statusFilter],
  );

  function UserControls({ item }: { item: AdminUserItem }) {
    const isSelf = currentUser?.id === item.id;
    const isUpdating = updatingId === item.id;

    return (
      <div className="grid gap-2">
        <select
          className="focus-ring min-h-10 rounded-lg border border-border bg-white px-3 text-sm font-semibold text-foreground"
          value={item.role}
          disabled={isSelf || isUpdating}
          aria-label={`Đổi vai trò cho ${item.username}`}
          onChange={(event) => void patchUser(item.id, "role", event.target.value as UserRole)}
        >
          {roles.map((role) => (
            <option key={role} value={role}>
              {roleLabel(role)}
            </option>
          ))}
        </select>
        <select
          className="focus-ring min-h-10 rounded-lg border border-border bg-white px-3 text-sm font-semibold text-foreground"
          value={item.status}
          disabled={isSelf || isUpdating}
          aria-label={`Đổi trạng thái cho ${item.username}`}
          onChange={(event) => void patchUser(item.id, "status", event.target.value as UserStatus)}
        >
          {statuses.map((status) => (
            <option key={status} value={status}>
              {statusLabel(status)}
            </option>
          ))}
        </select>
        {isSelf ? (
          <p className="flex items-start gap-2 text-xs leading-5 text-muted-foreground">
            <ShieldAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            Không thể tự khóa hoặc tự hạ quyền.
          </p>
        ) : null}
      </div>
    );
  }

  return (
    <DashboardLayout
      eyebrow="Admin Users"
      title="Quản lý người dùng"
      description="Tìm kiếm, lọc theo role/status, đổi quyền và khóa hoặc mở khóa tài khoản. Các thao tác tự khóa hoặc tự hạ quyền admin hiện tại sẽ bị vô hiệu hóa."
      actions={
        <Button type="button" variant="secondary" onClick={() => void loadUsers()} disabled={loading}>
          {loading ? <Spinner /> : <RefreshCw className="h-4 w-4" />}
          Làm mới
        </Button>
      }
    >
      {error ? (
        <div className="mb-6 rounded-lg border border-danger/25 bg-danger/5 p-4 text-sm text-danger">{error}</div>
      ) : null}

      <section className="mb-6 grid gap-3 rounded-lg border border-border bg-white p-4 shadow-sm lg:grid-cols-[1fr_12rem_12rem_auto]">
        <label className="relative">
          <span className="sr-only">Tìm người dùng</span>
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            className={inputClass("pl-10")}
            value={query}
            placeholder="Tìm theo tên, email hoặc tên hiển thị"
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>
        <label>
          <span className="sr-only">Lọc theo vai trò</span>
          <select className={inputClass()} value={roleFilter} onChange={(event) => setRoleFilter(event.target.value as UserRole | "ALL")}>
            <option value="ALL">Tất cả vai trò</option>
            {roles.map((role) => (
              <option key={role} value={role}>
                {roleLabel(role)}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span className="sr-only">Lọc theo trạng thái</span>
          <select className={inputClass()} value={statusFilter} onChange={(event) => setStatusFilter(event.target.value as UserStatus | "ALL")}>
            <option value="ALL">Tất cả trạng thái</option>
            {statuses.map((status) => (
              <option key={status} value={status}>
                {statusLabel(status)}
              </option>
            ))}
          </select>
        </label>
        <div className="flex items-center justify-end text-sm font-semibold text-muted-foreground">
          {filteredUsers.length}/{users.length} user
        </div>
      </section>

      {loading && users.length === 0 ? (
        <div className="grid min-h-[18rem] place-items-center rounded-lg border border-border bg-white">
          <div className="flex items-center gap-3 text-muted-foreground">
            <Spinner className="text-accent" />
            Đang tải danh sách người dùng...
          </div>
        </div>
      ) : filteredUsers.length === 0 ? (
        <EmptyState title="Không tìm thấy người dùng phù hợp." description="Hãy đổi từ khóa hoặc bỏ bớt bộ lọc role/status." />
      ) : (
        <>
          <section className="hidden overflow-hidden rounded-lg border border-border bg-white shadow-sm lg:block">
            <div className="grid grid-cols-[1.4fr_0.9fr_0.9fr_1fr_1.2fr] gap-4 border-b border-border bg-muted/60 px-5 py-3 text-xs font-bold uppercase tracking-[0.12em] text-muted-foreground">
              <span>Người dùng</span>
              <span>Role</span>
              <span>Trạng thái</span>
              <span>Patient link</span>
              <span>Thao tác</span>
            </div>
            <div className="divide-y divide-border">
              {filteredUsers.map((item) => (
                <article key={item.id} className="grid grid-cols-[1.4fr_0.9fr_0.9fr_1fr_1.2fr] gap-4 px-5 py-4">
                  <div className="min-w-0">
                    <p className="line-clamp-1 font-semibold text-foreground">{item.display_name || item.username}</p>
                    <p className="line-clamp-1 text-sm text-muted-foreground">{item.email}</p>
                    <p className="mt-1 text-xs text-muted-foreground">Tạo lúc {formatDateTime(item.created_at)}</p>
                  </div>
                  <div>
                    <Badge tone={roleTone(item.role)}>{roleLabel(item.role)}</Badge>
                  </div>
                  <div>
                    <Badge tone={statusTone(item.status)}>{statusLabel(item.status)}</Badge>
                  </div>
                  <PatientLinkCell user={item} />
                  <UserControls item={item} />
                </article>
              ))}
            </div>
          </section>

          <section className="grid gap-4 lg:hidden">
            {filteredUsers.map((item) => (
              <article key={item.id} className="rounded-lg border border-border bg-white p-4 shadow-sm">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="line-clamp-1 font-semibold text-foreground">{item.display_name || item.username}</p>
                    <p className="line-clamp-1 text-sm text-muted-foreground">{item.email}</p>
                    <p className="mt-1 text-xs text-muted-foreground">Tạo lúc {formatDateTime(item.created_at)}</p>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    <Badge tone={roleTone(item.role)}>{roleLabel(item.role)}</Badge>
                    <Badge tone={statusTone(item.status)}>{statusLabel(item.status)}</Badge>
                  </div>
                </div>
                <div className="mt-4 grid gap-3">
                  <div>
                    <p className="mb-2 text-xs font-bold uppercase tracking-[0.12em] text-muted-foreground">Patient link</p>
                    <PatientLinkCell user={item} />
                  </div>
                  <UserControls item={item} />
                </div>
              </article>
            ))}
          </section>
        </>
      )}
    </DashboardLayout>
  );
}
