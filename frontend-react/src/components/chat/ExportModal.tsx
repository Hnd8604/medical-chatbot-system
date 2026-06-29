import { FormEvent, useMemo, useState } from "react";
import { Download } from "lucide-react";
import { apiDownload } from "../../services/api";
import { TEXT } from "../../lib/constants";
import { Button } from "../ui/Button";
import { Field, inputClass } from "../ui/Field";
import { Modal } from "../ui/Modal";
import { Spinner } from "../ui/Spinner";

interface ExportModalProps {
  open: boolean;
  format: "pdf" | "csv";
  onClose: () => void;
  onError: (message: string) => void;
}

function defaultDate(offsetDays: number) {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  return date.toISOString().slice(0, 10);
}

export function ExportModal({ open, format, onClose, onError }: ExportModalProps) {
  const [from, setFrom] = useState(defaultDate(-7));
  const [to, setTo] = useState(defaultDate(0));
  const [downloading, setDownloading] = useState(false);
  const title = useMemo(() => `Xuất lịch sử ${format.toUpperCase()}`, [format]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setDownloading(true);
    try {
      await apiDownload(
        `/api/chat/export?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&format=${format}`,
        `lich_su_hoi_thoai_${from}_${to}.${format}`,
      );
      onClose();
    } catch (error) {
      onError(error instanceof Error ? error.message : "Không thể xuất lịch sử hội thoại.");
    } finally {
      setDownloading(false);
    }
  }

  return (
    <Modal open={open} title={title} description="Chọn khoảng ngày cần tải xuống." onClose={onClose}>
      <form className="grid gap-4" onSubmit={handleSubmit}>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Từ ngày">
            <input className={inputClass()} type="date" value={from} onChange={(event) => setFrom(event.target.value)} required />
          </Field>
          <Field label="Đến ngày">
            <input className={inputClass()} type="date" value={to} onChange={(event) => setTo(event.target.value)} required />
          </Field>
        </div>
        <div className="flex justify-end gap-3">
          <Button type="button" variant="ghost" onClick={onClose}>
            Hủy
          </Button>
          <Button type="submit" variant="primary" disabled={downloading}>
            {downloading ? <Spinner /> : <Download className="h-4 w-4" />}
            {format === "pdf" ? TEXT.exportPdf : TEXT.exportCsv}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
