import { useState } from "react";
import { AlertTriangle, RotateCcw } from "lucide-react";
import { formatDateTime } from "@/shared/lib/format";
import { Button } from "@/shared/ui/Button";
import { Modal } from "@/shared/ui/Modal";
import { Spinner } from "@/shared/ui/Spinner";
import type { Backup } from "../types";

interface RestoreBackupModalProps {
  /** Null while closed. */
  backup: Backup | null;
  submitting: boolean;
  onConfirm: (backup: Backup) => void;
  onClose: () => void;
}

/** Restoring overwrites live data, so it asks for an explicit acknowledgement first. */
export function RestoreBackupModal({ backup, submitting, onConfirm, onClose }: RestoreBackupModalProps) {
  const [acknowledged, setAcknowledged] = useState(false);

  function close() {
    if (!submitting) {
      setAcknowledged(false);
      onClose();
    }
  }

  return (
    <Modal
      open={backup !== null}
      title="Khôi phục dữ liệu"
      description="Thao tác này GHI ĐÈ toàn bộ dữ liệu hiện tại bằng bản sao lưu đã chọn."
      onClose={close}
    >
      {backup ? (
        <div className="grid gap-4">
          <div className="flex items-start gap-3 rounded-lg border border-warning/30 bg-warning/10 p-3 text-sm text-foreground">
            <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-warning" />
            <div>
              <p className="font-semibold">Không thể hoàn tác.</p>
              <p className="mt-1 text-muted-foreground">
                App DB và HAPI DB sẽ bị ghi đè bằng bản sao lưu lúc{" "}
                <span className="font-medium text-foreground">{formatDateTime(backup.started_at)}</span>. HAPI sẽ được khởi động
                lại trong quá trình này.
              </p>
            </div>
          </div>

          <label className="flex items-start gap-3 text-sm text-foreground">
            <input
              type="checkbox"
              className="mt-0.5 h-4 w-4"
              checked={acknowledged}
              onChange={(event) => setAcknowledged(event.target.checked)}
            />
            Tôi hiểu dữ liệu hiện tại sẽ bị ghi đè và muốn tiếp tục khôi phục.
          </label>

          <div className="flex justify-end gap-3">
            <Button type="button" variant="ghost" onClick={close} disabled={submitting}>
              Hủy
            </Button>
            <Button type="button" variant="danger" disabled={!acknowledged || submitting} onClick={() => onConfirm(backup)}>
              {submitting ? <Spinner /> : <RotateCcw className="h-4 w-4" />}
              Khôi phục ngay
            </Button>
          </div>
        </div>
      ) : null}
    </Modal>
  );
}
