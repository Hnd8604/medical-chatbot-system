import { useState } from "react";
import { errorMessage } from "@/shared/api/client";
import { lastDays } from "@/shared/lib/dates";
import { Notice } from "@/shared/ui/Notice";
import { PageHeader } from "@/shared/ui/PageHeader";
import { RefreshButton } from "@/shared/ui/RefreshButton";
import { UsageDashboard } from "../components/UsageDashboard";
import { useUsageSummary } from "../hooks";

export function UsagePage() {
  const [range] = useState(() => lastDays(7));
  const { quota, cost, loading, error, reload } = useUsageSummary(range);

  return (
    <>
      <PageHeader eyebrow="Usage / Quota" title="Theo dõi mức sử dụng" actions={<RefreshButton loading={loading} onClick={reload} />} />
      {error ? <Notice className="mb-6">{errorMessage(error, "Không thể tải dữ liệu usage.")}</Notice> : null}
      <UsageDashboard quota={quota} cost={cost} loading={loading} />
    </>
  );
}
