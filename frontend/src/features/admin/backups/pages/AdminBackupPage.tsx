import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { CloudUpload, Database, DatabaseBackup, HardDriveDownload, RotateCcw } from "lucide-react";
import { errorMessage } from "@/shared/api/client";
import type { PageResponse } from "@/shared/api/types";
import { formatBytes, formatDateTime, formatDuration } from "@/shared/lib/format";
import { Badge } from "@/shared/ui/Badge";
import { Button } from "@/shared/ui/Button";
import { EmptyState } from "@/shared/ui/EmptyState";
import { LoadingPanel } from "@/shared/ui/LoadingPanel";
import { Notice } from "@/shared/ui/Notice";
import { PageHeader } from "@/shared/ui/PageHeader";
import { RefreshButton } from "@/shared/ui/RefreshButton";
import { Spinner } from "@/shared/ui/Spinner";
import { backupApi, backupKeys } from "../api";
import { RestoreBackupModal } from "../components/RestoreBackupModal";
import type { Backup, JobStatus, Restore } from "../types";

const BACKUP_HISTORY_SIZE = 20;
const RESTORE_HISTORY_SIZE = 10;
const POLL_INTERVAL_MS = 4000;

const STATUS_TONE: Record<JobStatus, "green" | "amber" | "red" | "blue"> = {
  SUCCESS: "green",
  PARTIAL: "amber",
  FAILED: "red",
  RUNNING: "blue",
};

const STATUS_LABEL: Record<JobStatus, string> = {
  SUCCESS: "Thành công",
  PARTIAL: "Một phần",
  FAILED: "Thất bại",
  RUNNING: "Đang chạy",
};

function hasRunningJob(jobs: Array<{ status: JobStatus }>): boolean {
  return jobs.some((job) => job.status === "RUNNING");
}

/** Both lists poll while either a backup or a restore is still running. */
function pollWhileRunning(queryClient: QueryClient): number | false {
  const backups = queryClient.getQueryData<PageResponse<Backup>>(backupKeys.backups());
  const restores = queryClient.getQueryData<PageResponse<Restore>>(backupKeys.restores());
  return hasRunningJob([...(backups?.items ?? []), ...(restores?.items ?? [])]) ? POLL_INTERVAL_MS : false;
}

function DatabaseChip({ name, children }: { name: string; children: React.ReactNode }) {
  return (
    <span className="inline-flex items-center gap-1.5 rounded-md border border-border bg-background px-2 py-1 text-xs text-muted-foreground">
      <Database className="h-3.5 w-3.5" />
      <span className="font-medium text-foreground">{name}</span>
      {children}
    </span>
  );
}

function InfoItem({ icon, title, text }: { icon: React.ReactNode; title: string; text: string }) {
  return (
    <div className="flex items-start gap-3">
      {icon}
      <div>
        <p className="text-sm font-semibold text-foreground">{title}</p>
        <p className="text-sm text-muted-foreground">{text}</p>
      </div>
    </div>
  );
}

export function AdminBackupPage() {
  const queryClient = useQueryClient();
  const [notice, setNotice] = useState<string | null>(null);
  const [restoreTarget, setRestoreTarget] = useState<Backup | null>(null);
  const refetchInterval = () => pollWhileRunning(queryClient);
  const backups = useQuery({
    queryKey: backupKeys.backups(),
    queryFn: () => backupApi.backups(BACKUP_HISTORY_SIZE),
    refetchInterval,
  });
  const restores = useQuery({
    queryKey: backupKeys.restores(),
    queryFn: () => backupApi.restores(RESTORE_HISTORY_SIZE),
    refetchInterval,
  });
  const backupItems = backups.data?.items ?? [];
  const restoreItems = restores.data?.items ?? [];
  const hasRunning = hasRunningJob([...backupItems, ...restoreItems]);

  // Hide the "started" banner as soon as the background job finishes.
  const wasRunning = useRef(false);
  useEffect(() => {
    if (wasRunning.current && !hasRunning) {
      setNotice(null);
    }
    wasRunning.current = hasRunning;
  }, [hasRunning]);

  const refreshAll = () => queryClient.invalidateQueries({ queryKey: backupKeys.all });
  const start = useMutation({
    mutationFn: backupApi.start,
    onMutate: () => setNotice(null),
    onSuccess: () => {
      setNotice("Đã bắt đầu sao lưu. Quá trình chạy nền, trạng thái sẽ tự cập nhật bên dưới.");
      void refreshAll();
    },
  });
  const restore = useMutation({
    mutationFn: (backup: Backup) => backupApi.restore(backup.id),
    onMutate: () => setNotice(null),
    onSuccess: () => {
      setNotice("Đã bắt đầu khôi phục. Quá trình chạy nền, xem trạng thái ở mục Lịch sử khôi phục.");
      setRestoreTarget(null);
      void refreshAll();
    },
  });

  const failure = start.error ?? restore.error ?? backups.error ?? restores.error;

  return (
    <>
      <PageHeader
        eyebrow="Admin Backup"
        title="Backup & Restore"
        description="Backup application database and HAPI FHIR to Google Drive. Auto-runs at 02:00 daily or trigger manually."
        actions={
          <>
            <RefreshButton loading={backups.isFetching || restores.isFetching} onClick={() => void refreshAll()} />
            <Button type="button" variant="primary" onClick={() => start.mutate()} disabled={start.isPending || hasRunning}>
              {start.isPending || hasRunning ? <Spinner /> : <DatabaseBackup className="h-4 w-4" />}
              Backup ngay
            </Button>
          </>
        }
      />
      {failure ? <Notice className="mb-6">{errorMessage(failure, "Không thể tải dữ liệu sao lưu.")}</Notice> : null}
      {notice ? (
        <Notice tone="info" className="mb-6">
          {notice}
        </Notice>
      ) : null}

      <section className="mb-6 grid gap-4 rounded-lg border border-border bg-white p-5 sm:grid-cols-3">
        <InfoItem
          icon={<HardDriveDownload className="mt-0.5 h-5 w-5 text-accent" />}
          title="Lịch tự động"
          text="02:00 hằng ngày (backend tự xử lý)."
        />
        <InfoItem icon={<Database className="mt-0.5 h-5 w-5 text-accent" />} title="Phạm vi" text="App DB + HAPI FHIR DB (pg_dump, nén gzip)." />
        <InfoItem icon={<CloudUpload className="mt-0.5 h-5 w-5 text-accent" />} title="Đích lưu" text="Google Drive qua rclone, giữ 7 ngày." />
      </section>

      <section className="rounded-lg border border-border bg-white">
        <div className="border-b border-border px-5 py-4">
          <h2 className="text-lg font-semibold text-foreground">Lịch sử sao lưu</h2>
          <p className="text-sm text-muted-foreground">20 lần gần nhất, tự động cập nhật khi đang chạy.</p>
        </div>

        {backups.isPending ? (
          <LoadingPanel label="Đang tải lịch sử..." className="min-h-[14rem] rounded-none border-0" />
        ) : backupItems.length === 0 ? (
          <div className="p-5">
            <EmptyState title="Chưa có lần sao lưu nào." description="Bấm “Backup ngay” để tạo bản sao lưu đầu tiên." />
          </div>
        ) : (
          <div className="divide-y divide-border">
            {backupItems.map((backup) => (
              <article key={backup.id} className="grid gap-3 px-5 py-4 lg:grid-cols-[1fr_auto] lg:items-start">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={STATUS_TONE[backup.status]}>{STATUS_LABEL[backup.status]}</Badge>
                    <Badge tone="slate">{backup.trigger_type === "MANUAL" ? "Thủ công" : "Tự động"}</Badge>
                    <span className="text-sm font-semibold text-foreground">{formatDateTime(backup.started_at)}</span>
                    {backup.triggered_by ? <span className="text-xs text-muted-foreground">bởi {backup.triggered_by}</span> : null}
                  </div>
                  <div className="mt-2 flex flex-wrap gap-2">
                    {(backup.items ?? []).map((item) => (
                      <DatabaseChip key={item.db} name={item.db}>
                        <span>· {formatBytes(item.size_bytes)}</span>
                        {item.uploaded ? <CloudUpload className="h-3.5 w-3.5 text-success" /> : null}
                        {!item.ok ? <span className="text-danger">lỗi</span> : null}
                      </DatabaseChip>
                    ))}
                  </div>
                  {backup.error_message ? <p className="mt-2 text-xs text-danger">{backup.error_message}</p> : null}
                  {backup.upload_target ? <p className="mt-1 text-xs text-muted-foreground">Đích: {backup.upload_target}</p> : null}
                </div>

                <div className="flex flex-col items-end gap-2 text-right text-sm text-muted-foreground lg:min-w-[8rem]">
                  <div>
                    <p className="font-semibold text-foreground">
                      {backup.total_size_bytes != null ? formatBytes(backup.total_size_bytes) : "—"}
                    </p>
                    <p className="text-xs">Thời lượng {formatDuration(backup.started_at, backup.finished_at)}</p>
                  </div>
                  {backup.status === "SUCCESS" || backup.status === "PARTIAL" ? (
                    <Button type="button" size="sm" variant="secondary" disabled={hasRunning} onClick={() => setRestoreTarget(backup)}>
                      <RotateCcw className="h-3.5 w-3.5" />
                      Khôi phục
                    </Button>
                  ) : null}
                </div>
              </article>
            ))}
          </div>
        )}
      </section>

      {restoreItems.length > 0 ? (
        <section className="mt-8 rounded-lg border border-border bg-white">
          <div className="border-b border-border px-5 py-4">
            <h2 className="text-lg font-semibold text-foreground">Lịch sử khôi phục</h2>
            <p className="text-sm text-muted-foreground">10 lần gần nhất.</p>
          </div>
          <div className="divide-y divide-border">
            {restoreItems.map((job) => (
              <article key={job.id} className="grid gap-3 px-5 py-4 lg:grid-cols-[1fr_auto] lg:items-start">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={STATUS_TONE[job.status]}>{STATUS_LABEL[job.status]}</Badge>
                    <span className="text-sm font-semibold text-foreground">{formatDateTime(job.started_at)}</span>
                    {job.triggered_by ? <span className="text-xs text-muted-foreground">bởi {job.triggered_by}</span> : null}
                  </div>
                  <div className="mt-2 flex flex-wrap gap-2">
                    {(job.items ?? []).map((item) => (
                      <DatabaseChip key={item.db} name={item.db}>
                        {item.ok ? <RotateCcw className="h-3.5 w-3.5 text-success" /> : <span className="text-danger">lỗi</span>}
                      </DatabaseChip>
                    ))}
                  </div>
                  {job.error_message ? <p className="mt-2 text-xs text-danger">{job.error_message}</p> : null}
                </div>
                <div className="text-right text-xs text-muted-foreground lg:min-w-[8rem]">
                  Thời lượng {formatDuration(job.started_at, job.finished_at)}
                </div>
              </article>
            ))}
          </div>
        </section>
      ) : null}

      <RestoreBackupModal
        key={restoreTarget?.id ?? "closed"}
        backup={restoreTarget}
        submitting={restore.isPending}
        onConfirm={(backup) => restore.mutate(backup)}
        onClose={() => setRestoreTarget(null)}
      />
    </>
  );
}
