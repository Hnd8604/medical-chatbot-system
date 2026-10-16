import { useState } from "react";
import { ArrowLeft } from "lucide-react";
import { errorMessage } from "@/shared/api/client";
import { cn } from "@/shared/lib/cn";
import { lastDays } from "@/shared/lib/dates";
import { Badge } from "@/shared/ui/Badge";
import { Button } from "@/shared/ui/Button";
import { Notice } from "@/shared/ui/Notice";
import { RefreshButton } from "@/shared/ui/RefreshButton";
import { useUsageSummary } from "../hooks";
import { UsageDashboard } from "./UsageDashboard";

/** The usage dashboard shown in place of the conversation on the chat screen. */
export function UsagePanel({ onClose, className }: { onClose: () => void; className?: string }) {
  const [range] = useState(() => lastDays(7));
  const { quota, cost, loading, error, reload } = useUsageSummary(range);

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
          <RefreshButton loading={loading} onClick={reload} />
        </div>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto px-4 py-6 sm:px-6">
        <div className="mx-auto w-full max-w-5xl">
          <p className="mb-6 max-w-2xl text-sm leading-7 text-muted-foreground">
            Tổng hợp số request, token, chi phí ước tính và giới hạn trong ngày của tài khoản hiện tại.
          </p>
          {error ? <Notice className="mb-6">{errorMessage(error, "Không thể tải dữ liệu usage.")}</Notice> : null}
          <UsageDashboard quota={quota} cost={cost} loading={loading} />
        </div>
      </div>
    </section>
  );
}
