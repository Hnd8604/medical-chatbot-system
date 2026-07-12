import { ArrowLeft, RefreshCw } from "lucide-react";
import { cn } from "../../lib/cn";
import { useUsageData } from "../../hooks/useUsageData";
import { Badge } from "../ui/Badge";
import { Button } from "../ui/Button";
import { Spinner } from "../ui/Spinner";
import { UsageDashboard } from "../dashboard/UsageDashboard";

interface UsagePanelProps {
  onClose: () => void;
  className?: string;
}

export function UsagePanel({ onClose, className }: UsagePanelProps) {
  const { quota, cost, loading, error, reload } = useUsageData();

  return (
    <section className={cn("flex h-full min-h-0 flex-col bg-background", className)}>
      <header className="sticky top-0 z-10 border-b border-border bg-white/95 px-4 py-4 backdrop-blur sm:px-6">
        <div className="mx-auto flex w-full max-w-5xl flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <Button type="button" variant="ghost" size="icon" onClick={onClose} aria-label="Quay lại chat" title="Quay lại chat">
              <ArrowLeft className="h-5 w-5" />
            </Button>
            <div>
              <Badge tone="blue">Usage / Quota</Badge>
              <h1 className="mt-1.5 font-display text-2xl text-foreground">Theo dõi mức sử dụng</h1>
            </div>
          </div>
          <Button type="button" variant="secondary" onClick={() => void reload()} disabled={loading}>
            {loading ? <Spinner /> : <RefreshCw className="h-4 w-4" />}
            Làm mới
          </Button>
        </div>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto px-4 py-6 sm:px-6">
        <div className="mx-auto w-full max-w-5xl">
          <p className="mb-6 max-w-2xl text-sm leading-7 text-muted-foreground">
            Tổng hợp số request, token, chi phí ước tính và giới hạn trong ngày của tài khoản hiện tại.
          </p>
          {error ? (
            <div className="mb-6 rounded-lg border border-danger/25 bg-danger/5 p-4 text-sm text-danger">{error}</div>
          ) : null}
          <UsageDashboard quota={quota} cost={cost} loading={loading} />
        </div>
      </div>
    </section>
  );
}
