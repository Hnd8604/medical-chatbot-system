import { useEffect, useRef, useState } from "react";
import { CloudUpload, Database, DatabaseBackup, HardDriveDownload, RefreshCw } from "lucide-react";
import { apiGet, apiPost } from "../services/api";
import type { BackupHistoryItem, BackupStatus, PageResponse } from "../lib/types";
import { formatBytes, formatDateTime } from "../lib/formatters";
import { DashboardLayout } from "../components/dashboard/DashboardLayout";
import { EmptyState } from "../components/dashboard/EmptyState";
import { Badge } from "../components/ui/Badge";
import { Button } from "../components/ui/Button";
import { Spinner } from "../components/ui/Spinner";

const HISTORY_PATH = "/api/admin/backup/history";
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
  const [loading, setLoading] = useState(true);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const pollRef = useRef<number | null>(null);

  async function loadHistory(showSpinner = true) {
    if (showSpinner) {
      setLoading(true);
    }
    try {
      const result = await apiGet<PageResponse<BackupHistoryItem>>(`${HISTORY_PATH}?page=0&size=20`);
      setItems(result.content || []);
      setError(null);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : "Không thể tải lịch sử sao lưu.");
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
      await loadHistory(false);
    } catch (triggerError) {
      setError(triggerError instanceof Error ? triggerError.message : "Không thể bắt đầu sao lưu.");
    } finally {
      setRunning(false);
    }
  }

  useEffect(() => {
    void loadHistory();
    return () => {
      if (pollRef.current) {
        window.clearInterval(pollRef.current);
      }
    };
  }, []);

  // Poll trong khi còn bản ghi đang chạy để cập nhật trạng thái tự động.
  const hasRunning = items.some((item) => item.status === "RUNNING");
  useEffect(() => {
    if (hasRunning && pollRef.current == null) {
      pollRef.current = window.setInterval(() => void loadHistory(false), POLL_INTERVAL_MS);
    } else if (!hasRunning && pollRef.current != null) {
      window.clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, [hasRunning]);

  return (
    <DashboardLayout
      eyebrow="Admin Backup"
      title="Sao lưu & phục hồi"
      description="Sao lưu CSDL ứng dụng và HAPI FHIR lên Google Drive. Tự động lúc 02:00 hằng ngày hoặc chạy thủ công."
      actions={
        <div className="flex flex-wrap gap-3">
          <Button type="button" variant="secondary" onClick={() => void loadHistory()} disabled={loading}>
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
            <p className="text-sm text-muted-foreground">02:00 hằng ngày (Task Scheduler).</p>
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

                <div className="text-right text-sm text-muted-foreground lg:min-w-[8rem]">
                  <p className="font-semibold text-foreground">
                    {item.totalSizeBytes != null ? formatBytes(item.totalSizeBytes) : "—"}
                  </p>
                  <p className="text-xs">Thời lượng {durationText(item.startedAt, item.finishedAt)}</p>
                </div>
              </article>
            ))}
          </div>
        )}
      </section>
    </DashboardLayout>
  );
}
