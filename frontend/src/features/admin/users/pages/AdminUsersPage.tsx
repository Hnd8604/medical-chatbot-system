import { useMemo, useState } from "react";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Search, ShieldAlert } from "lucide-react";
import { useSignedInUser } from "@/features/auth/AuthProvider";
import { roleLabel, statusLabel, USER_ROLES, USER_STATUSES } from "@/features/auth/labels";
import type { UserRole, UserStatus } from "@/features/auth/types";
import { errorMessage } from "@/shared/api/client";
import { useDebouncedValue } from "@/shared/hooks/useDebouncedValue";
import { formatDateTime } from "@/shared/lib/format";
import { Badge } from "@/shared/ui/Badge";
import { EmptyState } from "@/shared/ui/EmptyState";
import { inputClass } from "@/shared/ui/Field";
import { LoadingPanel } from "@/shared/ui/LoadingPanel";
import { Notice } from "@/shared/ui/Notice";
import { PageHeader } from "@/shared/ui/PageHeader";
import { RefreshButton } from "@/shared/ui/RefreshButton";
import { adminUserApi, adminUserKeys } from "../api";
import type { AdminUser } from "../types";

const PAGE_SIZE = 100;

const ROLE_TONE: Record<UserRole, "blue" | "green" | "slate"> = { ADMIN: "blue", DOCTOR: "green", USER: "slate" };
const STATUS_TONE: Record<UserStatus, "green" | "amber" | "red"> = { ACTIVE: "green", LOCKED: "amber", DISABLED: "red" };

type UserChange = { userId: string } & ({ role: UserRole } | { status: UserStatus });

function PatientLinks({ user }: { user: AdminUser }) {
  if (user.patient_links.length === 0) {
    return <span className="text-sm text-muted-foreground">Chưa có dữ liệu liên kết</span>;
  }
  return (
    <div className="flex flex-wrap items-start gap-2">
      {user.patient_links.map((link) => (
        <Badge key={link.fhir_patient_id} tone={link.is_primary ? "blue" : "slate"}>
          {link.fhir_patient_id}
        </Badge>
      ))}
    </div>
  );
}

function UserIdentity({ user }: { user: AdminUser }) {
  return (
    <div className="min-w-0">
      <p className="line-clamp-1 font-semibold text-foreground">{user.display_name || user.username}</p>
      <p className="line-clamp-1 text-sm text-muted-foreground">{user.email}</p>
      <p className="mt-1 text-xs text-muted-foreground">Tạo lúc {formatDateTime(user.created_at)}</p>
    </div>
  );
}

function UserControls({
  user,
  isSelf,
  updating,
  onChange,
}: {
  user: AdminUser;
  isSelf: boolean;
  updating: boolean;
  onChange: (change: UserChange) => void;
}) {
  const selectClass = "focus-ring min-h-10 rounded-lg border border-border bg-white px-3 text-sm font-semibold text-foreground";
  return (
    <div className="grid gap-2">
      <select
        className={selectClass}
        value={user.role}
        disabled={isSelf || updating}
        aria-label={`Đổi vai trò cho ${user.username}`}
        onChange={(event) => onChange({ userId: user.id, role: event.target.value as UserRole })}
      >
        {USER_ROLES.map((role) => (
          <option key={role} value={role}>
            {roleLabel(role)}
          </option>
        ))}
      </select>
      <select
        className={selectClass}
        value={user.status}
        disabled={isSelf || updating}
        aria-label={`Đổi trạng thái cho ${user.username}`}
        onChange={(event) => onChange({ userId: user.id, status: event.target.value as UserStatus })}
      >
        {USER_STATUSES.map((status) => (
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

export function AdminUsersPage() {
  const currentUser = useSignedInUser();
  const queryClient = useQueryClient();
  const [search, setSearch] = useState("");
  const [roleFilter, setRoleFilter] = useState<UserRole | "ALL">("ALL");
  const [statusFilter, setStatusFilter] = useState<UserStatus | "ALL">("ALL");
  const params = { page: 0, size: PAGE_SIZE, search: useDebouncedValue(search.trim(), 300) };

  const users = useQuery({
    queryKey: adminUserKeys.list(params),
    queryFn: () => adminUserApi.list(params),
    placeholderData: keepPreviousData,
  });
  const update = useMutation({
    mutationFn: (change: UserChange) =>
      "role" in change
        ? adminUserApi.updateRole(change.userId, change.role)
        : adminUserApi.updateStatus(change.userId, change.status),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminUserKeys.all }),
  });

  const items = useMemo(() => users.data?.items ?? [], [users.data]);
  const visibleUsers = useMemo(
    () =>
      items.filter(
        (user) => (roleFilter === "ALL" || user.role === roleFilter) && (statusFilter === "ALL" || user.status === statusFilter),
      ),
    [items, roleFilter, statusFilter],
  );
  const error = users.error ?? update.error;

  function controls(user: AdminUser) {
    return (
      <UserControls
        user={user}
        isSelf={user.id === currentUser.id}
        updating={update.isPending && update.variables?.userId === user.id}
        onChange={(change) => update.mutate(change)}
      />
    );
  }

  return (
    <>
      <PageHeader
        eyebrow="Admin Users"
        title="Quản lý người dùng"
        actions={<RefreshButton loading={users.isFetching} onClick={() => void users.refetch()} />}
      />
      {error ? <Notice className="mb-6">{errorMessage(error, "Không thể tải danh sách người dùng.")}</Notice> : null}

      <section className="mb-6 grid gap-3 rounded-lg border border-border bg-white p-4 lg:grid-cols-[1fr_12rem_12rem_auto]">
        <label className="relative">
          <span className="sr-only">Tìm người dùng</span>
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            className={inputClass("pl-10")}
            value={search}
            placeholder="Tìm theo tên, email hoặc tên hiển thị"
            onChange={(event) => setSearch(event.target.value)}
          />
        </label>
        <label>
          <span className="sr-only">Lọc theo vai trò</span>
          <select className={inputClass()} value={roleFilter} onChange={(event) => setRoleFilter(event.target.value as UserRole | "ALL")}>
            <option value="ALL">Tất cả vai trò</option>
            {USER_ROLES.map((role) => (
              <option key={role} value={role}>
                {roleLabel(role)}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span className="sr-only">Lọc theo trạng thái</span>
          <select
            className={inputClass()}
            value={statusFilter}
            onChange={(event) => setStatusFilter(event.target.value as UserStatus | "ALL")}
          >
            <option value="ALL">Tất cả trạng thái</option>
            {USER_STATUSES.map((status) => (
              <option key={status} value={status}>
                {statusLabel(status)}
              </option>
            ))}
          </select>
        </label>
        <div className="flex items-center justify-end text-sm font-semibold text-muted-foreground">
          {visibleUsers.length}/{users.data?.total_elements ?? 0} user
        </div>
      </section>

      {users.isPending ? (
        <LoadingPanel label="Đang tải danh sách người dùng..." />
      ) : visibleUsers.length === 0 ? (
        <EmptyState title="Không tìm thấy người dùng phù hợp." description="Hãy đổi từ khóa hoặc bỏ bớt bộ lọc role/status." />
      ) : (
        <>
          <section className="hidden overflow-hidden rounded-lg border border-border bg-white lg:block">
            <div className="grid grid-cols-[1.4fr_0.9fr_0.9fr_1fr_1.2fr] gap-4 border-b border-border bg-muted/60 px-5 py-3 text-xs font-medium uppercase tracking-wide text-muted-foreground">
              <span>Người dùng</span>
              <span>Role</span>
              <span>Trạng thái</span>
              <span>Patient link</span>
              <span>Thao tác</span>
            </div>
            <div className="divide-y divide-border">
              {visibleUsers.map((user) => (
                <article key={user.id} className="grid grid-cols-[1.4fr_0.9fr_0.9fr_1fr_1.2fr] gap-4 px-5 py-4">
                  <UserIdentity user={user} />
                  <div>
                    <Badge tone={ROLE_TONE[user.role]}>{roleLabel(user.role)}</Badge>
                  </div>
                  <div>
                    <Badge tone={STATUS_TONE[user.status]}>{statusLabel(user.status)}</Badge>
                  </div>
                  <PatientLinks user={user} />
                  {controls(user)}
                </article>
              ))}
            </div>
          </section>

          <section className="grid gap-4 lg:hidden">
            {visibleUsers.map((user) => (
              <article key={user.id} className="rounded-lg border border-border bg-white p-4">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <UserIdentity user={user} />
                  <div className="flex flex-wrap gap-2">
                    <Badge tone={ROLE_TONE[user.role]}>{roleLabel(user.role)}</Badge>
                    <Badge tone={STATUS_TONE[user.status]}>{statusLabel(user.status)}</Badge>
                  </div>
                </div>
                <div className="mt-4 grid gap-3">
                  <div>
                    <p className="mb-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">Patient link</p>
                    <PatientLinks user={user} />
                  </div>
                  {controls(user)}
                </div>
              </article>
            ))}
          </section>
        </>
      )}
    </>
  );
}
