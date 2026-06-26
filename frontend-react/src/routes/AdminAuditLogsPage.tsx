import { useEffect, useMemo, useState } from "react";
import { ChevronDown, ChevronUp, Filter, RefreshCw, Search } from "lucide-react";
import { apiJson, toQuery } from "../lib/api";
import type { AdminUserItem, AdminUserListResponse, AuditLogItem, PageResponse } from "../lib/types";
import { formatDateTime, formatNumber, safeJson } from "../lib/formatters";
import { DashboardLayout } from "../components/dashboard/DashboardLayout";
import { EmptyState } from "../components/dashboard/EmptyState";
import { Badge } from "../components/ui/Badge";
import { Button } from "../components/ui/Button";
import { inputClass } from "../components/ui/Field";
import { SectionLabel } from "../components/ui/SectionLabel";
import { Spinner } from "../components/ui/Spinner";

const PAGE_SIZE = 15;

const actionOptions = [
  "CHAT_COMPLETED",
  "VIEW_OBSERVATIONS",
  "VIEW_CONDITIONS",
  "VIEW_MEDICATIONS",
  "VIEW_ENCOUNTERS",
  "VIEW_PATIENT_DETAIL",
  "SEARCH_PATIENTS",
  "VIEW_FROM_CACHE",
  "LOGIN_SUCCESS",
  "LOGIN_FAILURE",
  "LOGOUT",
  "EXPORT_CONVERSATION",
  "QUOTA_POLICY_CREATED",
  "QUOTA_POLICY_UPDATED",
  "MODEL_PRICING_CREATED",
  "MODEL_PRICING_UPDATED",
];

const resourceOptions = ["chat_session", "Patient", "Observation", "Condition", "MedicationRequest", "Encounter", "Cache", "app_user", "auth"];

function pageTotal<T>(page: PageResponse<T> | null) {
  return page?.totalElements ?? page?.total_elements ?? 0;
}

function pageCount<T>(page: PageResponse<T> | null) {
  return Math.max(1, page?.totalPages ?? page?.total_pages ?? 1);
}

function field(item: AuditLogItem, camel: keyof AuditLogItem, snake: keyof AuditLogItem) {
  return item[camel] ?? item[snake] ?? null;
}

function startOfDay(value: string) {
  return value ? `${value}T00:00:00+07:00` : null;
}

function endOfDay(value: string) {
  return value ? `${value}T23:59:59+07:00` : null;
}

function actionTone(action?: string | null) {
  if (!action) return "slate";
  if (action.includes("FAILURE") || action.includes("DELETED")) return "red";
  if (action.includes("CREATED") || action.includes("UPDATED")) return "green";
  if (action.includes("CACHE") || action.includes("EXPORT")) return "amber";
  return "blue";
}

function shortId(value?: string | null) {
  if (!value) return "-";
  if (value.length <= 16) return value;
  return `${value.slice(0, 8)}...${value.slice(-6)}`;
}

function metadataPreview(value: unknown) {
  if (!value || typeof value !== "object") {
    return "-";
  }
  const record = value as Record<string, unknown>;
  const keys = ["operation", "intent", "tool_name", "answer_source", "llm_model", "latency_ms", "patient_id"];
  const pairs = keys
    .map((key) => [key, record[key]] as const)
    .filter(([, item]) => item !== undefined && item !== null && item !== "");
  if (pairs.length === 0) {
    return safeJson(value);
  }
  return pairs.map(([key, item]) => `${key}: ${String(item)}`).join(" | ");
}

function metadataFull(value: unknown) {
  return safeJson(value);
}

function userLabel(users: AdminUserItem[], userId?: string | null) {
  if (!userId) return "-";
  const user = users.find((item) => item.id === userId);
  return user?.username || shortId(userId);
}

export function AdminAuditLogsPage() {
  const [page, setPage] = useState<PageResponse<AuditLogItem> | null>(null);
  const [users, setUsers] = useState<AdminUserItem[]>([]);
  const [userId, setUserId] = useState("");
  const [action, setAction] = useState("");
  const [resourceType, setResourceType] = useState("");
  const [resourceId, setResourceId] = useState("");
  const [fromDate, setFromDate] = useState("");
  const [toDate, setToDate] = useState("");
  const [pageIndex, setPageIndex] = useState(0);
  const [expandedLogId, setExpandedLogId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const logs = page?.content || [];
  const total = pageTotal(page);
  const totalPages = pageCount(page);
  const currentPage = page?.number ?? pageIndex;

  const activeFilters = useMemo(
    () => [userId, action, resourceType, resourceId, fromDate, toDate].filter(Boolean).length,
    [userId, action, resourceType, resourceId, fromDate, toDate],
  );

  async function loadUsers() {
    const data = await apiJson<AdminUserListResponse>("/api/admin/users?page=0&size=100");
    setUsers(data.users || []);
  }

  async function loadLogs(nextPage = pageIndex) {
    setLoading(true);
    setError(null);
    try {
      const query = toQuery({
        userId,
        action,
        resourceType,
        resourceId,
        fromDate: startOfDay(fromDate),
        toDate: endOfDay(toDate),
        page: nextPage,
        size: PAGE_SIZE,
      });
      const data = await apiJson<PageResponse<AuditLogItem>>(`/api/audit-logs${query}`);
      setPage(data);
      setPageIndex(data.number ?? nextPage);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : "Không thể tải audit log.");
    } finally {
      setLoading(false);
    }
  }

  async function loadAll() {
    setLoading(true);
    setError(null);
    try {
      await Promise.all([loadUsers(), loadLogs(0)]);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : "Không thể tải audit log.");
      setLoading(false);
    }
  }

  function applyFilters() {
    setPageIndex(0);
    void loadLogs(0);
  }

  function clearFilters() {
    setUserId("");
    setAction("");
    setResourceType("");
    setResourceId("");
    setFromDate("");
    setToDate("");
    setPageIndex(0);
    setTimeout(() => void loadLogs(0), 0);
  }

  function goToPage(nextPage: number) {
    const bounded = Math.min(Math.max(nextPage, 0), totalPages - 1);
    setPageIndex(bounded);
    void loadLogs(bounded);
  }

  function logKey(item: AuditLogItem) {
    const createdAt = field(item, "createdAt", "created_at") as string | null;
    return item.id || `${item.action || "audit"}-${createdAt || "time"}`;
  }

  function toggleMetadata(key: string) {
    setExpandedLogId((current) => (current === key ? null : key));
  }

  useEffect(() => {
    void loadAll();
  }, []);

  return (
    <DashboardLayout
      eyebrow="Audit Log"
      title="Audit log"
      description="Theo dõi thao tác hệ thống, truy cập FHIR, đăng nhập, cache và thay đổi cấu hình."
      actions={
        <Button type="button" variant="secondary" onClick={() => void loadAll()} disabled={loading}>
          {loading ? <Spinner /> : <RefreshCw className="h-4 w-4" />}
          Làm mới
        </Button>
      }
    >
      {error ? <div className="mb-6 rounded-lg border border-danger/25 bg-danger/5 p-4 text-sm text-danger">{error}</div> : null}

      <div className="grid gap-6">
        <section className="rounded-xl border border-border bg-white p-4 shadow-sm">
          <div className="grid gap-3 xl:grid-cols-[1fr_1fr_1fr_1fr_11rem_11rem_auto] xl:items-end">
            <label className="grid gap-1">
              <span className="font-mono text-[10px] uppercase tracking-[0.14em] text-muted-foreground">User</span>
              <select className={inputClass()} value={userId} onChange={(event) => setUserId(event.target.value)}>
                <option value="">Tất cả user</option>
                {users.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.username}
                  </option>
                ))}
              </select>
            </label>
            <label className="grid gap-1">
              <span className="font-mono text-[10px] uppercase tracking-[0.14em] text-muted-foreground">Action</span>
              <select className={inputClass()} value={action} onChange={(event) => setAction(event.target.value)}>
                <option value="">Tất cả action</option>
                {actionOptions.map((item) => (
                  <option key={item} value={item}>
                    {item}
                  </option>
                ))}
              </select>
            </label>
            <label className="grid gap-1">
              <span className="font-mono text-[10px] uppercase tracking-[0.14em] text-muted-foreground">Resource</span>
              <select className={inputClass()} value={resourceType} onChange={(event) => setResourceType(event.target.value)}>
                <option value="">Tất cả resource</option>
                {resourceOptions.map((item) => (
                  <option key={item} value={item}>
                    {item}
                  </option>
                ))}
              </select>
            </label>
            <label className="grid gap-1">
              <span className="font-mono text-[10px] uppercase tracking-[0.14em] text-muted-foreground">Resource ID</span>
              <div className="relative">
                <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                <input
                  className={inputClass("pl-10")}
                  value={resourceId}
                  placeholder="ID chính xác"
                  onChange={(event) => setResourceId(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter") applyFilters();
                  }}
                />
              </div>
            </label>
            <label className="grid gap-1">
              <span className="font-mono text-[10px] uppercase tracking-[0.14em] text-muted-foreground">Từ ngày</span>
              <input className={inputClass()} type="date" value={fromDate} max={toDate || undefined} onChange={(event) => setFromDate(event.target.value)} />
            </label>
            <label className="grid gap-1">
              <span className="font-mono text-[10px] uppercase tracking-[0.14em] text-muted-foreground">Đến ngày</span>
              <input className={inputClass()} type="date" value={toDate} min={fromDate || undefined} onChange={(event) => setToDate(event.target.value)} />
            </label>
            <div className="flex flex-wrap gap-2">
              <Button type="button" variant="primary" onClick={applyFilters} disabled={loading || Boolean(fromDate && toDate && fromDate > toDate)}>
                {loading ? <Spinner /> : <Filter className="h-4 w-4" />}
                Lọc
              </Button>
              <Button type="button" variant="secondary" onClick={clearFilters} disabled={loading || activeFilters === 0}>
                Xóa
              </Button>
            </div>
          </div>
        </section>

        <section className="rounded-xl border border-border bg-white p-5 shadow-sm">
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
            <div>
              <SectionLabel>Nhật ký</SectionLabel>
              <p className="mt-2 text-sm text-muted-foreground">{formatNumber(total)} bản ghi</p>
            </div>
            <Badge tone={activeFilters ? "blue" : "slate"}>{activeFilters} filter</Badge>
          </div>

          {loading && logs.length === 0 ? (
            <div className="grid min-h-[18rem] place-items-center rounded-lg border border-border bg-white">
              <div className="flex items-center gap-3 text-muted-foreground">
                <Spinner className="text-accent" />
                Đang tải audit log...
              </div>
            </div>
          ) : logs.length === 0 ? (
            <EmptyState title="Chưa có audit log phù hợp." />
          ) : (
            <>
              <div className="hidden overflow-hidden rounded-lg border border-border lg:block">
                <div className="grid grid-cols-[10rem_12rem_10rem_1fr_8rem_1.2fr] gap-4 border-b border-border bg-muted/60 px-4 py-3 text-xs font-bold uppercase tracking-[0.12em] text-muted-foreground">
                  <span>Thời gian</span>
                  <span>Action</span>
                  <span>User</span>
                  <span>Resource</span>
                  <span>Session</span>
                  <span>Metadata</span>
                </div>
                <div className="divide-y divide-border">
                  {logs.map((item) => {
                    const key = logKey(item);
                    const itemAction = item.action || "-";
                    const itemUserId = field(item, "userId", "user_id") as string | null;
                    const itemSessionId = field(item, "sessionId", "session_id") as string | null;
                    const itemResourceType = field(item, "resourceType", "resource_type") as string | null;
                    const itemResourceId = field(item, "resourceId", "resource_id") as string | null;
                    const itemCreatedAt = field(item, "createdAt", "created_at") as string | null;
                    const itemMetadata = field(item, "metadataJson", "metadata_json");
                    const preview = metadataPreview(itemMetadata);
                    const fullMetadata = metadataFull(itemMetadata);
                    const expanded = expandedLogId === key;

                    return (
                      <div key={key}>
                        <article className="grid grid-cols-[10rem_12rem_10rem_1fr_8rem_1.4fr] gap-4 px-4 py-3 text-sm">
                          <span className="text-muted-foreground">{formatDateTime(itemCreatedAt)}</span>
                          <span>
                            <Badge tone={actionTone(itemAction)}>{itemAction}</Badge>
                          </span>
                          <span className="min-w-0 truncate font-mono text-xs text-foreground">{userLabel(users, itemUserId)}</span>
                          <span className="min-w-0">
                            <span className="block font-semibold text-foreground">{itemResourceType || "-"}</span>
                            <span className="block truncate font-mono text-xs text-muted-foreground" title={itemResourceId || undefined}>
                              {itemResourceId || "-"}
                            </span>
                          </span>
                          <span className="font-mono text-xs text-muted-foreground" title={itemSessionId || undefined}>{shortId(itemSessionId)}</span>
                          <span className="min-w-0">
                            <span className="line-clamp-2 font-mono text-xs text-muted-foreground" title={fullMetadata}>
                              {preview}
                            </span>
                            <button
                              type="button"
                              className="focus-ring mt-2 inline-flex items-center gap-1 rounded-full border border-border bg-white px-2 py-1 font-mono text-[10px] uppercase tracking-[0.12em] text-accent transition hover:border-accent/30 hover:bg-accent/5"
                              onClick={() => toggleMetadata(key)}
                            >
                              {expanded ? <ChevronUp className="h-3 w-3" /> : <ChevronDown className="h-3 w-3" />}
                              {expanded ? "Ẩn" : "Xem"}
                            </button>
                          </span>
                        </article>
                        {expanded ? (
                          <div className="border-t border-border bg-muted/30 px-4 py-3">
                            <pre className="max-h-80 overflow-auto whitespace-pre-wrap rounded-lg border border-border bg-white p-3 font-mono text-xs leading-5 text-foreground">
                              {fullMetadata}
                            </pre>
                          </div>
                        ) : null}
                      </div>
                    );
                  })}
                </div>
              </div>

              <div className="grid gap-3 lg:hidden">
                {logs.map((item) => {
                  const key = logKey(item);
                  const itemAction = item.action || "-";
                  const itemUserId = field(item, "userId", "user_id") as string | null;
                  const itemSessionId = field(item, "sessionId", "session_id") as string | null;
                  const itemResourceType = field(item, "resourceType", "resource_type") as string | null;
                  const itemResourceId = field(item, "resourceId", "resource_id") as string | null;
                  const itemCreatedAt = field(item, "createdAt", "created_at") as string | null;
                  const itemMetadata = field(item, "metadataJson", "metadata_json");
                  const fullMetadata = metadataFull(itemMetadata);
                  const expanded = expandedLogId === key;

                  return (
                    <article key={key} className="rounded-lg border border-border bg-white p-4 shadow-sm">
                      <div className="flex flex-wrap items-start justify-between gap-3">
                        <Badge tone={actionTone(itemAction)}>{itemAction}</Badge>
                        <span className="text-xs text-muted-foreground">{formatDateTime(itemCreatedAt)}</span>
                      </div>
                      <div className="mt-4 grid gap-2 text-sm">
                        <div className="flex justify-between gap-3">
                          <span className="text-muted-foreground">User</span>
                          <span className="font-mono text-xs text-foreground">{userLabel(users, itemUserId)}</span>
                        </div>
                        <div className="flex justify-between gap-3">
                          <span className="text-muted-foreground">Resource</span>
                          <span className="min-w-0 truncate text-right font-semibold text-foreground">
                            {itemResourceType || "-"} / {itemResourceId || "-"}
                          </span>
                        </div>
                        <div className="flex justify-between gap-3">
                          <span className="text-muted-foreground">Session</span>
                          <span className="font-mono text-xs text-muted-foreground">{shortId(itemSessionId)}</span>
                        </div>
                        <p className="mt-2 line-clamp-3 font-mono text-xs text-muted-foreground" title={fullMetadata}>
                          {metadataPreview(itemMetadata)}
                        </p>
                        <button
                          type="button"
                          className="focus-ring mt-2 inline-flex items-center gap-1 rounded-full border border-border bg-white px-2 py-1 font-mono text-[10px] uppercase tracking-[0.12em] text-accent"
                          onClick={() => toggleMetadata(key)}
                        >
                          {expanded ? <ChevronUp className="h-3 w-3" /> : <ChevronDown className="h-3 w-3" />}
                          {expanded ? "Ẩn metadata" : "Xem metadata"}
                        </button>
                        {expanded ? (
                          <pre className="mt-3 max-h-72 overflow-auto whitespace-pre-wrap rounded-lg border border-border bg-muted/30 p-3 font-mono text-xs leading-5 text-foreground">
                            {fullMetadata}
                          </pre>
                        ) : null}
                      </div>
                    </article>
                  );
                })}
              </div>
            </>
          )}

          <div className="mt-5 flex flex-wrap items-center justify-between gap-3">
            <p className="text-sm text-muted-foreground">
              Trang {formatNumber(currentPage + 1)} / {formatNumber(totalPages)}
            </p>
            <div className="flex gap-2">
              <Button type="button" variant="secondary" onClick={() => goToPage(currentPage - 1)} disabled={loading || currentPage <= 0}>
                Trước
              </Button>
              <Button type="button" variant="secondary" onClick={() => goToPage(currentPage + 1)} disabled={loading || currentPage >= totalPages - 1}>
                Sau
              </Button>
            </div>
          </div>
        </section>
      </div>
    </DashboardLayout>
  );
}
