import { FormEvent, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { Download } from "lucide-react";
import { errorMessage } from "@/shared/api/client";
import { daysAgoIso, todayIso } from "@/shared/lib/dates";
import { Button } from "@/shared/ui/Button";
import { Field, inputClass } from "@/shared/ui/Field";
import { Modal } from "@/shared/ui/Modal";
import { Notice } from "@/shared/ui/Notice";
import { Spinner } from "@/shared/ui/Spinner";
import { chatApi } from "../api";
import type { ExportFormat } from "../types";

interface ExportHistoryModalProps {
  /** Null while closed. */
  format: ExportFormat | null;
  onClose: () => void;
}

export function ExportHistoryModal({ format, onClose }: ExportHistoryModalProps) {
  const [from, setFrom] = useState(() => daysAgoIso(7));
  const [to, setTo] = useState(todayIso);
  const exportHistory = useMutation({
    mutationFn: (selected: ExportFormat) => chatApi.exportHistory({ from, to }, selected),
    onSuccess: onClose,
  });

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (format) {
      exportHistory.mutate(format);
    }
  }

  function close() {
    exportHistory.reset();
    onClose();
  }

  return (
    <Modal
      open={format !== null}
      title={`Xuất lịch sử ${format?.toUpperCase() ?? ""}`}
      description="Chọn khoảng ngày cần tải xuống."
      onClose={close}
    >
      <form className="grid gap-4" onSubmit={handleSubmit}>
        {exportHistory.isError ? <Notice>{errorMessage(exportHistory.error, "Không thể xuất lịch sử hội thoại.")}</Notice> : null}
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Từ ngày">
            <input className={inputClass()} type="date" value={from} max={to} onChange={(event) => setFrom(event.target.value)} required />
          </Field>
          <Field label="Đến ngày">
            <input className={inputClass()} type="date" value={to} min={from} onChange={(event) => setTo(event.target.value)} required />
          </Field>
        </div>
        <div className="flex justify-end gap-3">
          <Button type="button" variant="ghost" onClick={close}>
            Hủy
          </Button>
          <Button type="submit" variant="primary" disabled={exportHistory.isPending || from > to}>
            {exportHistory.isPending ? <Spinner /> : <Download className="h-4 w-4" />}
            {format === "csv" ? "Xuất CSV" : "Xuất PDF"}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
