import { useState } from "react";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ListFilter } from "lucide-react";
import { errorMessage } from "@/shared/api/client";
import { formatNumber } from "@/shared/lib/format";
import { Badge } from "@/shared/ui/Badge";
import { Button } from "@/shared/ui/Button";
import { EmptyState } from "@/shared/ui/EmptyState";
import { FilterField, filterInputClass } from "@/shared/ui/Field";
import { Notice } from "@/shared/ui/Notice";
import { PageHeader } from "@/shared/ui/PageHeader";
import { Pagination } from "@/shared/ui/Pagination";
import { RefreshButton } from "@/shared/ui/RefreshButton";
import { Spinner } from "@/shared/ui/Spinner";
import { alertApi, alertKeys } from "../api";
import { AlertSummary } from "../components/AlertSummary";
import type { AlertFilter, AlertSeverity, AlertStatus } from "../types";

const PAGE_SIZE = 12;

export function AdminAlertsPage() {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<AlertStatus | "">("OPEN");
  const [severity, setSeverity] = useState<AlertSeverity | "">("");
  const [filter, setFilter] = useState<AlertFilter>({ status: "OPEN", page: 0, size: PAGE_SIZE });

  const alerts = useQuery({
    queryKey: alertKeys.list(filter),
    queryFn: () => alertApi.list(filter),
    placeholderData: keepPreviousData,
  });
  const resolve = useMutation({
    mutationFn: alertApi.resolve,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: alertKeys.all }),
  });

  function applyFilter() {
    setFilter({ status: status || undefined, severity: severity || undefined, page: 0, size: PAGE_SIZE });
  }

  const items = alerts.data?.items ?? [];
  const error = alerts.error ?? resolve.error;

  return (
    <>
      <PageHeader
        eyebrow="Admin Alerts"
        title="Alert vận hành"
        actions={<RefreshButton loading={alerts.isFetching} onClick={() => void alerts.refetch()} />}
      />
      {error ? <Notice tone="warning" className="mb-6" title={errorMessage(error, "Không thể tải danh sách alert.")} /> : null}

      <div className="grid gap-4 rounded-lg border border-border bg-white p-5 shadow-sm">
        <div className="grid gap-3 lg:grid-cols-[1fr_auto] lg:items-end">
          <div className="grid gap-3 sm:grid-cols-2">
            <FilterField label="Severity">
              <select value={severity} onChange={(event) => setSeverity(event.target.value as AlertSeverity | "")} className={filterInputClass()}>
                <option value="">Tất cả severity</option>
                <option value="CRITICAL">Critical</option>
                <option value="WARNING">Warning</option>
                <option value="INFO">Info</option>
              </select>
            </FilterField>
            <FilterField label="Status">
              <select value={status} onChange={(event) => setStatus(event.target.value as AlertStatus | "")} className={filterInputClass()}>
                <option value="">Tất cả status</option>
                <option value="OPEN">Open</option>
                <option value="RESOLVED">Resolved</option>
              </select>
            </FilterField>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Badge tone="blue">{formatNumber(alerts.data?.totalElements)} alert</Badge>
            <Button type="button" size="sm" variant="primary" onClick={applyFilter} disabled={alerts.isFetching}>
              {alerts.isFetching ? <Spinner /> : <ListFilter className="h-4 w-4" />}
              Áp dụng
            </Button>
          </div>
        </div>

        {items.length === 0 ? (
          <EmptyState title={alerts.isPending ? "Đang tải alert..." : "Chưa có alert phù hợp."} />
        ) : (
          <div className="divide-y divide-border overflow-hidden rounded-xl border border-border bg-white">
            {items.map((alert) => {
              const resolving = resolve.isPending && resolve.variables === alert.id;
              const open = alert.status === "OPEN";
              return (
                <article key={alert.id} className="grid gap-4 px-5 py-4 lg:grid-cols-[1fr_auto] lg:items-center">
                  <AlertSummary alert={alert} extra={<Badge tone={open ? "blue" : "slate"}>{alert.status}</Badge>} />
                  <Button
                    type="button"
                    size="sm"
                    variant={open ? "secondary" : "ghost"}
                    disabled={!open || resolving}
                    onClick={() => resolve.mutate(alert.id)}
                  >
                    {resolving ? <Spinner /> : null}
                    {open ? "Mark resolved" : "Đã xử lý"}
                  </Button>
                </article>
              );
            })}
          </div>
        )}

        {(alerts.data?.totalPages ?? 0) > 1 ? (
          <Pagination
            page={filter.page}
            totalPages={alerts.data?.totalPages ?? 1}
            disabled={alerts.isFetching}
            onPageChange={(page) => setFilter((current) => ({ ...current, page }))}
          />
        ) : null}
      </div>
    </>
  );
}
