import { formatNumber } from "@/shared/lib/format";
import { Button } from "./Button";

interface PaginationProps {
  /** Zero-based, as in the API. */
  page: number;
  totalPages: number;
  disabled?: boolean;
  onPageChange: (page: number) => void;
}

export function Pagination({ page, totalPages, disabled = false, onPageChange }: PaginationProps) {
  const pageCount = Math.max(1, totalPages);

  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <p className="text-sm text-muted-foreground">
        Trang {formatNumber(page + 1)} / {formatNumber(pageCount)}
      </p>
      <div className="flex gap-2">
        <Button type="button" size="sm" variant="secondary" disabled={disabled || page <= 0} onClick={() => onPageChange(page - 1)}>
          Trước
        </Button>
        <Button
          type="button"
          size="sm"
          variant="secondary"
          disabled={disabled || page >= pageCount - 1}
          onClick={() => onPageChange(page + 1)}
        >
          Sau
        </Button>
      </div>
    </div>
  );
}
