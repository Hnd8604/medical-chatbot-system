import { useEffect, useRef, useState } from "react";
import {
  AlertTriangle,
  CloudUpload,
  Database,
  DatabaseBackup,
  HardDriveDownload,
  RefreshCw,
  RotateCcw,
} from "lucide-react";
import { apiGet, apiPost } from "../services/api";
import type { BackupHistoryItem, BackupStatus, PageResponse, RestoreHistoryItem } from "../lib/types";
import { formatBytes, formatDateTime } from "../lib/formatters";
import { DashboardLayout } from "../components/dashboard/DashboardLayout";
import { EmptyState } from "../components/dashboard/EmptyState";
import { Badge } from "../components/ui/Badge";
import { Button } from "../components/ui/Button";
import { Modal } from "../components/ui/Modal";
import { Spinner } from "../components/ui/Spinner";

const HISTORY_PATH = "/api/admin/backup/history";
const RESTORE_HISTORY_PATH = "/api/admin/backup/restore-history";
const BACKUP_PATH = "/api/admin/backup";
const POLL_INTERVAL_MS = 4000;

function statusTone(status: BackupStatus): "green" | "amber" | "red" | "blue" {
  switch (status) {
    case "SUCCESS":
      return "green";
    case "PARTIAL":
      return "amber";
    case "FAILED":
      return "red";
    default:
      return "blue";
  }
}

function statusLabel(status: BackupStatus): string {
  switch (status) {
    case "SUCCESS":
      return "Thành công";
    case "PARTIAL":
      return "Một phần";
    case "FAILED":
      return "Thất bại";
    default:
      return "Đang chạy";
  }
}

function triggerLabel(trigger: string): string {
  return trigger === "MANUAL" ? "Thủ công" : "Tự động";
}

function durationText(startedAt: string, finishedAt: string | null): string {
  if (!finishedAt) {
    return "—";
  }
  const ms = new Date(finishedAt).getTime() - new Date(startedAt).getTime();
  if (!Number.isFinite(ms) || ms < 0) {
    return "—";
  }
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) {
    return `${seconds}s`;
  }
  const minutes = Math.floor(seconds / 60);
  return `${minutes}m ${seconds % 60}s`;
}

export function AdminBackupPage() {
  const [items, setItems] = useState<BackupHistoryItem[]>([]);
  const [restores, setRestores] = useState<RestoreHistoryItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const pollRef = useRef<number | null>(null);
  const wasRunningRef = useRef(false);

  // Modal xác nhận khôi phục.
  const [restoreTarget, setRestoreTarget] = useState<BackupHistoryItem | null>(null);
  const [restoreConfirmed, setRestoreConfirmed] = useState(false);
  const [restoreSubmitting, setRestoreSubmitting] = useState(false);

  async function loadAll(showSpinner = true) {
    if (showSpinner) {
      setLoading(true);
    }
    try {
      const [backupPage, restorePage] = await Promise.all([
        apiGet<PageResponse<BackupHistoryItem>>(`${HISTORY_PATH}?page=0&size=20`),
        apiGet<PageResponse<RestoreHistoryItem>>(`${RESTORE_HISTORY_PATH}?page=0&size=10`),
      ]);
      setItems(backupPage.content || []);
      setRestores(restorePage.content || []);
      setError(null);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : "Không thể tải dữ liệu sao lưu.");
    } finally {
      if (showSpinner) {
        setLoading(false);
      }
    }
  }

  async function triggerBackup() {
    setRunning(true);
    setError(null);
    setNotice(null);
    try {
      await apiPost<BackupHistoryItem>(BACKUP_PATH);
      setNotice("Đã bắt đầu sao lưu. Quá trình chạy nền, trạng thái sẽ tự cập nhật bên dưới.");
      await loadAll(false);
    } catch (triggerError) {
      setError(triggerError instanceof Error ? triggerError.message : "Không thể bắt đầu sao lưu.");
    } finally {
      setRunning(false);
    }
  }

  function openRestore(item: BackupHistoryItem) {
    setRestoreConfirmed(false);
    setRestoreTarget(item);
  }

  function closeRestore() {
    if (restoreSubmitting) {
      return;
    }
    setRestoreTarget(null);
  }

  async function confirmRestore() {
    if (!restoreTarget) {
      return;
    }
    setRestoreSubmitting(true);
    setError(null);
    setNotice(null);
    try {
      await apiPost<RestoreHistoryItem>(`${BACKUP_PATH}/${encodeURIComponent(restoreTarget.id)}/restore`);
      setNotice("Đã bắt đầu khôi phục. Quá trình chạy nền, xem trạng thái ở mục Lịch sử khôi phục.");
      setRestoreTarget(null);
      await loadAll(false);
    } catch (restoreError) {
      setError(restoreError instanceof Error ? restoreError.message : "Không thể bắt đầu khôi phục.");
    } finally {
      setRestoreSubmitting(false);
    }
  }

  useEffect(() => {
    void loadAll();
    return () => {
      if (pollRef.current) {
        window.clearInterval(pollRef.current);
      }
    };
  }, []);

  // Poll trong khi còn backup hoặc restore đang chạy.
  const hasRunning =
    items.some((item) => item.status === "RUNNING") || restores.some((item) => item.status === "RUNNING");
  useEffect(() => {
    if (hasRunning && pollRef.current == null) {
      pollRef.current = window.setInterval(() => void loadAll(false), POLL_INTERVAL_MS);
    } else if (!hasRunning && pollRef.current != null) {
      window.clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, [hasRunning]);

  // Tắt banner "Đã bắt đầu ..." ngay khi tiến trình chạy nền vừa kết thúc
  // (hasRunning chuyển true -> false). Dùng ref để chỉ tắt đúng lúc chuyển trạng
  // thái, không tắt nhầm ở lần render đầu hay ngay sau khi vừa set banner.
  useEffect(() => {
    if (wasRunningRef.current && !hasRunning) {
      setNotice(null);
    }
    wasRunningRef.current = hasRunning;
  }, [hasRunning]);

  return (
    <DashboardLayout
      eyebrow="Admin Backup"
      title="Backup & Restore"
      description="Backup application database and HAPI FHIR to Google Drive. Auto-runs at 02:00 daily or trigger manually."
      actions={
        <div className="flex flex-wrap gap-3">
          <Button type="button" variant="secondary" onClick={() => void loadAll()} disabled={loading}>
            {loading ? <Spinner /> : <RefreshCw className="h-4 w-4" />}
            Làm mới
          </Button>
          <Button type="button" variant="primary" onClick={() => void triggerBackup()} disabled={running || hasRunning}>
            {running || hasRunning ? <Spinner /> : <DatabaseBackup className="h-4 w-4" />}
            Backup ngay
          </Button>
        </div>
      }
    >
      {error ? (
        <div className="mb-6 rounded-lg border border-danger/25 bg-danger/5 p-4 text-sm text-danger">{error}</div>
      ) : null}
      {notice ? (
        <div className="mb-6 rounded-lg border border-accent/25 bg-accent/5 p-4 text-sm text-foreground">{notice}</div>
      ) : null}

      <section className="mb-6 grid gap-4 rounded-lg border border-border bg-white p-5 sm:grid-cols-3">
        <div className="flex items-start gap-3">
          <HardDriveDownload className="mt-0.5 h-5 w-5 text-accent" />
          <div>
            <p className="text-sm font-semibold text-foreground">Lịch tự động</p>
            <p className="text-sm text-muted-foreground">02:00 hằng ngày (backend tự xử lý).</p>
          </div>
        </div>
        <div className="flex items-start gap-3">
          <Database className="mt-0.5 h-5 w-5 text-accent" />
          <div>
            <p className="text-sm font-semibold text-foreground">Phạm vi</p>
            <p className="text-sm text-muted-foreground">App DB + HAPI FHIR DB (pg_dump, nén gzip).</p>
          </div>
        </div>
        <div className="flex items-start gap-3">
          <CloudUpload className="mt-0.5 h-5 w-5 text-accent" />
          <div>
            <p className="text-sm font-semibold text-foreground">Đích lưu</p>
            <p className="text-sm text-muted-foreground">Google Drive qua rclone, giữ 7 ngày.</p>
          </div>
        </div>
      </section>

      <section className="rounded-lg border border-border bg-white">
        <div className="border-b border-border px-5 py-4">
          <h2 className="text-lg font-semibold text-foreground">Lịch sử sao lưu</h2>
          <p className="text-sm text-muted-foreground">20 lần gần nhất, tự động cập nhật khi đang chạy.</p>
        </div>

        {loading && items.length === 0 ? (
          <div className="grid min-h-[14rem] place-items-center">
            <div className="flex items-center gap-3 text-muted-foreground">
              <Spinner className="text-accent" />
              Đang tải lịch sử...
            </div>
          </div>
        ) : items.length === 0 ? (
          <div className="p-5">
            <EmptyState title="Chưa có lần sao lưu nào." description="Bấm “Backup ngay” để tạo bản sao lưu đầu tiên." />
          </div>
        ) : (
          <div className="divide-y divide-border">
            {items.map((item) => (
              <article key={item.id} className="grid gap-3 px-5 py-4 lg:grid-cols-[1fr_auto] lg:items-start">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={statusTone(item.status)}>{statusLabel(item.status)}</Badge>
                    <Badge tone="slate">{triggerLabel(item.triggerType)}</Badge>
                    <span className="text-sm font-semibold text-foreground">{formatDateTime(item.startedAt)}</span>
                    {item.triggeredBy ? (
                      <span className="text-xs text-muted-foreground">bởi {item.triggeredBy}</span>
                    ) : null}
                  </div>

                  <div className="mt-2 flex flex-wrap gap-2">
                    {(item.itemsJson || []).map((dbItem) => (
                      <span
                        key={dbItem.db}
                        className="inline-flex items-center gap-1.5 rounded-md border border-border bg-background px-2 py-1 text-xs text-muted-foreground"
                      >
                        <Database className="h-3.5 w-3.5" />
                        <span className="font-medium text-foreground">{dbItem.db}</span>
                        <span>· {formatBytes(dbItem.size_bytes)}</span>
                        {dbItem.uploaded ? <CloudUpload className="h-3.5 w-3.5 text-success" /> : null}
                        {!dbItem.ok ? <span className="text-danger">lỗi</span> : null}
                      </span>
                    ))}
                  </div>

                  {item.errorMessage ? (
                    <p className="mt-2 text-xs text-danger">{item.errorMessage}</p>
                  ) : null}
                  {item.uploadTarget ? (
                    <p className="mt-1 text-xs text-muted-foreground">Đích: {item.uploadTarget}</p>
                  ) : null}
                </div>

                <div className="flex flex-col items-end gap-2 text-right text-sm text-muted-foreground lg:min-w-[8rem]">
                  <div>
                    <p className="font-semibold text-foreground">
                      {item.totalSizeBytes != null ? formatBytes(item.totalSizeBytes) : "—"}
                    </p>
                    <p className="text-xs">Thời lượng {durationText(item.startedAt, item.finishedAt)}</p>
                  </div>
                  {item.status === "SUCCESS" || item.status === "PARTIAL" ? (
                    <Button
                      type="button"
                      size="sm"
                      variant="secondary"
                      disabled={hasRunning}
                      onClick={() => openRestore(item)}
                    >
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

      {restores.length > 0 ? (
        <section className="mt-8 rounded-lg border border-border bg-white">
          <div className="border-b border-border px-5 py-4">
            <h2 className="text-lg font-semibold text-foreground">Lịch sử khôi phục</h2>
            <p className="text-sm text-muted-foreground">10 lần gần nhất.</p>
          </div>
          <div className="divide-y divide-border">
            {restores.map((item) => (
              <article key={item.id} className="grid gap-3 px-5 py-4 lg:grid-cols-[1fr_auto] lg:items-start">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={statusTone(item.status)}>{statusLabel(item.status)}</Badge>
                    <span className="text-sm font-semibold text-foreground">{formatDateTime(item.startedAt)}</span>
                    {item.triggeredBy ? (
                      <span className="text-xs text-muted-foreground">bởi {item.triggeredBy}</span>
                    ) : null}
                  </div>
                  <div className="mt-2 flex flex-wrap gap-2">
                    {(item.itemsJson || []).map((dbItem) => (
                      <span
                        key={dbItem.db}
                        className="inline-flex items-center gap-1.5 rounded-md border border-border bg-background px-2 py-1 text-xs text-muted-foreground"
                      >
                        <Database className="h-3.5 w-3.5" />
                        <span className="font-medium text-foreground">{dbItem.db}</span>
                        {dbItem.ok ? (
                          <RotateCcw className="h-3.5 w-3.5 text-success" />
                        ) : (
                          <span className="text-danger">lỗi</span>
                        )}
                      </span>
                    ))}
                  </div>
                  {item.errorMessage ? <p className="mt-2 text-xs text-danger">{item.errorMessage}</p> : null}
                </div>
                <div className="text-right text-xs text-muted-foreground lg:min-w-[8rem]">
                  Thời lượng {durationText(item.startedAt, item.finishedAt)}
                </div>
              </article>
            ))}
          </div>
        </section>
      ) : null}

      <Modal
        open={restoreTarget != null}
        title="Khôi phục dữ liệu"
        description="Thao tác này GHI ĐÈ toàn bộ dữ liệu hiện tại bằng bản sao lưu đã chọn."
        onClose={closeRestore}
      >
        {restoreTarget ? (
          <div className="grid gap-4">
            <div className="flex items-start gap-3 rounded-lg border border-warning/30 bg-warning/10 p-3 text-sm text-foreground">
              <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-warning" />
              <div>
                <p className="font-semibold">Không thể hoàn tác.</p>
                <p className="mt-1 text-muted-foreground">
                  App DB và HAPI DB sẽ bị ghi đè bằng bản sao lưu lúc{" "}
                  <span className="font-medium text-foreground">{formatDateTime(restoreTarget.startedAt)}</span>. HAPI sẽ
                  được khởi động lại trong quá trình này.
                </p>
              </div>
            </div>

            <label className="flex items-start gap-3 text-sm text-foreground">
              <input
                type="checkbox"
                className="mt-0.5 h-4 w-4"
                checked={restoreConfirmed}
                onChange={(event) => setRestoreConfirmed(event.target.checked)}
              />
              Tôi hiểu dữ liệu hiện tại sẽ bị ghi đè và muốn tiếp tục khôi phục.
            </label>

            <div className="flex justify-end gap-3">
              <Button type="button" variant="ghost" onClick={closeRestore} disabled={restoreSubmitting}>
                Hủy
              </Button>
              <Button
                type="button"
                variant="danger"
                disabled={!restoreConfirmed || restoreSubmitting}
                onClick={() => void confirmRestore()}
              >
                {restoreSubmitting ? <Spinner /> : <RotateCcw className="h-4 w-4" />}
                Khôi phục ngay
              </Button>
            </div>
          </div>
        ) : null}
      </Modal>
    </DashboardLayout>
  );
}
