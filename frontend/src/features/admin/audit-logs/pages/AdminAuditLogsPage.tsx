import { useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Filter, Search } from "lucide-react";
import { adminUserApi, adminUserKeys } from "@/features/admin/users/api";
import { errorMessage } from "@/shared/api/client";
import { formatNumber, shortId } from "@/shared/lib/format";
import { Badge } from "@/shared/ui/Badge";
import { Button } from "@/shared/ui/Button";
import { EmptyState } from "@/shared/ui/EmptyState";
import { inputClass } from "@/shared/ui/Field";
import { LoadingPanel } from "@/shared/ui/LoadingPanel";
import { Notice } from "@/shared/ui/Notice";
import { PageHeader } from "@/shared/ui/PageHeader";
import { Pagination } from "@/shared/ui/Pagination";
import { RefreshButton } from "@/shared/ui/RefreshButton";
import { SectionLabel } from "@/shared/ui/SectionLabel";
import { Spinner } from "@/shared/ui/Spinner";
import { auditLogApi, auditLogKeys } from "../api";
import { AuditLogList } from "../components/AuditLogList";
import type { AuditLogFilter } from "../types";

const PAGE_SIZE = 15;
const USER_OPTIONS = { page: 0, size: 100 };
const NO_FILTER: AuditLogFilter = { user_id: "", action: "", resource_type: "", resource_id: "", from: "", to: "" };

function SelectFilter({
  label,
  value,
  allLabel,
  options,
  onChange,
}: {
  label: string;
  value: string;
  allLabel: string;
  options: Array<{ value: string; label: string }>;
  onChange: (value: string) => void;
}) {
  return (
    <label className="grid gap-1">
      <span className="text-xs font-medium text-muted-foreground">{label}</span>
      <select className={inputClass()} value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="">{allLabel}</option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </label>
  );
}

export function AdminAuditLogsPage() {
  const [draft, setDraft] = useState<AuditLogFilter>(NO_FILTER);
  const [filter, setFilter] = useState<AuditLogFilter>(NO_FILTER);
  const [page, setPage] = useState(0);

  const logs = useQuery({
    queryKey: auditLogKeys.list(filter, page, PAGE_SIZE),
    queryFn: () => auditLogApi.list(filter, page, PAGE_SIZE),
    placeholderData: keepPreviousData,
  });
  const options = useQuery({ queryKey: auditLogKeys.options(), queryFn: auditLogApi.filterOptions, staleTime: Infinity });
  const users = useQuery({ queryKey: adminUserKeys.list(USER_OPTIONS), queryFn: () => adminUserApi.list(USER_OPTIONS) });

  const userItems = users.data?.items ?? [];
  const usernameOf = (userId: string | null) =>
    userId ? (userItems.find((user) => user.id === userId)?.username ?? shortId(userId)) : "-";
  const activeFilters = Object.values(draft).filter(Boolean).length;
  const invalidRange = Boolean(draft.from && draft.to && draft.from > draft.to);
  const items = logs.data?.items ?? [];

  function update(key: keyof AuditLogFilter, value: string) {
    setDraft((current) => ({ ...current, [key]: value }));
  }

  function apply(next: AuditLogFilter) {
    setPage(0);
    setFilter(next);
  }

  function clear() {
    setDraft(NO_FILTER);
    apply(NO_FILTER);
  }

  return (
    <>
      <PageHeader
        eyebrow="Audit Log"
        title="Audit log"
        actions={
          <RefreshButton
            loading={logs.isFetching}
            onClick={() => {
              void logs.refetch();
              void users.refetch();
            }}
          />
        }
      />
      {logs.error ? <Notice className="mb-6">{errorMessage(logs.error, "Không thể tải audit log.")}</Notice> : null}

      <div className="grid gap-6">
        <section className="rounded-lg border border-border bg-white p-4">
          <div className="grid gap-3 xl:grid-cols-[1fr_1fr_1fr_1fr_11rem_11rem_auto] xl:items-end">
            <SelectFilter
              label="User"
              value={draft.user_id}
              allLabel="Tất cả user"
              options={userItems.map((user) => ({ value: user.id, label: user.username }))}
              onChange={(value) => update("user_id", value)}
            />
            <SelectFilter
              label="Action"
              value={draft.action}
              allLabel="Tất cả action"
              options={(options.data?.actions ?? []).map((action) => ({ value: action, label: action }))}
              onChange={(value) => update("action", value)}
            />
            <SelectFilter
              label="Resource"
              value={draft.resource_type}
              allLabel="Tất cả resource"
              options={(options.data?.resource_types ?? []).map((type) => ({ value: type, label: type }))}
              onChange={(value) => update("resource_type", value)}
            />
            <label className="grid gap-1">
              <span className="text-xs font-medium text-muted-foreground">Resource ID</span>
              <div className="relative">
                <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                <input
                  className={inputClass("pl-10")}
                  value={draft.resource_id}
                  placeholder="ID chính xác"
                  onChange={(event) => update("resource_id", event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" && !invalidRange) apply(draft);
                  }}
                />
              </div>
            </label>
            <label className="grid gap-1">
              <span className="text-xs font-medium text-muted-foreground">Từ ngày</span>
              <input
                className={inputClass()}
                type="date"
                value={draft.from}
                max={draft.to || undefined}
                onChange={(event) => update("from", event.target.value)}
              />
            </label>
            <label className="grid gap-1">
              <span className="text-xs font-medium text-muted-foreground">Đến ngày</span>
              <input
                className={inputClass()}
                type="date"
                value={draft.to}
                min={draft.from || undefined}
                onChange={(event) => update("to", event.target.value)}
              />
            </label>
            <div className="flex flex-wrap gap-2">
              <Button type="button" variant="primary" onClick={() => apply(draft)} disabled={logs.isFetching || invalidRange}>
                {logs.isFetching ? <Spinner /> : <Filter className="h-4 w-4" />}
                Lọc
              </Button>
              <Button type="button" variant="secondary" onClick={clear} disabled={logs.isFetching || activeFilters === 0}>
                Xóa
              </Button>
            </div>
          </div>
        </section>

        <section className="rounded-lg border border-border bg-white p-5">
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
            <div>
              <SectionLabel>Nhật ký</SectionLabel>
              <p className="mt-2 text-sm text-muted-foreground">{formatNumber(logs.data?.total_elements)} bản ghi</p>
            </div>
            <Badge tone={activeFilters ? "blue" : "slate"}>{activeFilters} filter</Badge>
          </div>

          {logs.isPending ? (
            <LoadingPanel label="Đang tải audit log..." />
          ) : items.length === 0 ? (
            <EmptyState title="Chưa có audit log phù hợp." />
          ) : (
            <AuditLogList logs={items} usernameOf={usernameOf} />
          )}

          <div className="mt-5">
            <Pagination page={page} totalPages={logs.data?.total_pages ?? 1} disabled={logs.isFetching} onPageChange={setPage} />
          </div>
        </section>
      </div>
    </>
  );
}
