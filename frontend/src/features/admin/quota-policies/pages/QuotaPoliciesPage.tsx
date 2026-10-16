import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Gauge, Pencil, Plus, Trash2 } from "lucide-react";
import { errorMessage } from "@/shared/api/client";
import { formatDateTime, formatNumber, formatUsd } from "@/shared/lib/format";
import { Button } from "@/shared/ui/Button";
import { EmptyState } from "@/shared/ui/EmptyState";
import { LoadingPanel } from "@/shared/ui/LoadingPanel";
import { Notice } from "@/shared/ui/Notice";
import { PageHeader } from "@/shared/ui/PageHeader";
import { RefreshButton } from "@/shared/ui/RefreshButton";
import { Spinner } from "@/shared/ui/Spinner";
import { quotaPolicyApi, quotaPolicyKeys } from "../api";
import { QuotaPolicyModal } from "../components/QuotaPolicyModal";
import type { QuotaPolicy } from "../types";

export function QuotaPoliciesPage() {
  const queryClient = useQueryClient();
  const [modal, setModal] = useState<{ open: boolean; policy: QuotaPolicy | null }>({ open: false, policy: null });

  const policies = useQuery({ queryKey: quotaPolicyKeys.list(), queryFn: quotaPolicyApi.list });
  const remove = useMutation({
    mutationFn: quotaPolicyApi.remove,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: quotaPolicyKeys.list() }),
  });

  function confirmRemove(policy: QuotaPolicy) {
    if (window.confirm(`Xóa chính sách quota "${policy.name}"?`)) {
      remove.mutate(policy.id);
    }
  }

  const items = policies.data ?? [];
  const error = policies.error ?? remove.error;

  return (
    <>
      <PageHeader
        eyebrow="Admin Config"
        title="Cấu hình hệ thống"
        actions={<RefreshButton loading={policies.isFetching} onClick={() => void policies.refetch()} />}
      />
      {error ? <Notice className="mb-6">{errorMessage(error, "Không thể tải cấu hình hệ thống.")}</Notice> : null}

      {policies.isPending ? (
        <LoadingPanel label="Đang tải cấu hình..." />
      ) : (
        <section className="rounded-lg border border-border bg-white">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-5 py-4">
            <div className="flex items-center gap-3">
              <Gauge className="h-5 w-5 text-accent" />
              <div>
                <h2 className="text-lg font-semibold text-foreground">Chính sách quota</h2>
                <p className="text-sm text-muted-foreground">Giới hạn request, token và chi phí theo ngày.</p>
              </div>
            </div>
            <Button type="button" variant="primary" onClick={() => setModal({ open: true, policy: null })}>
              <Plus className="h-4 w-4" />
              Thêm chính sách
            </Button>
          </div>

          {items.length === 0 ? (
            <div className="p-5">
              <EmptyState title="Chưa có chính sách quota nào." description="Tạo chính sách đầu tiên để giới hạn sử dụng." />
            </div>
          ) : (
            <div className="divide-y divide-border">
              {items.map((policy) => {
                const removing = remove.isPending && remove.variables === policy.id;
                return (
                  <article key={policy.id} className="flex flex-wrap items-center justify-between gap-4 px-5 py-4">
                    <div className="min-w-0">
                      <p className="font-semibold text-foreground">{policy.name}</p>
                      <p className="mt-1 text-sm text-muted-foreground">
                        {formatNumber(policy.dailyRequestLimit)} request · {formatNumber(policy.dailyTokenLimit)} token ·{" "}
                        {formatUsd(policy.dailyCostLimitUsd)} / ngày
                        {policy.rateLimitPerMinute != null ? ` · ${formatNumber(policy.rateLimitPerMinute)} req/phút` : ""}
                      </p>
                      <p className="mt-1 text-xs text-muted-foreground">Tạo lúc {formatDateTime(policy.createdAt)}</p>
                    </div>
                    <div className="flex items-center gap-2">
                      <Button type="button" variant="secondary" size="sm" onClick={() => setModal({ open: true, policy })}>
                        <Pencil className="h-3.5 w-3.5" />
                        Sửa
                      </Button>
                      <Button type="button" variant="danger" size="sm" disabled={removing} onClick={() => confirmRemove(policy)}>
                        {removing ? <Spinner /> : <Trash2 className="h-3.5 w-3.5" />}
                        Xóa
                      </Button>
                    </div>
                  </article>
                );
              })}
            </div>
          )}
        </section>
      )}

      <QuotaPolicyModal open={modal.open} policy={modal.policy} onClose={() => setModal((current) => ({ ...current, open: false }))} />
    </>
  );
}
