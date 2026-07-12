import { RefreshCw } from "lucide-react";
import { Button } from "../components/ui/Button";
import { Spinner } from "../components/ui/Spinner";
import { DashboardLayout } from "../components/dashboard/DashboardLayout";
import { UsageDashboard } from "../components/dashboard/UsageDashboard";
import { useUsageData } from "../hooks/useUsageData";

export function UsagePage() {
  const { quota, cost, loading, error, reload } = useUsageData();

  return (
    <DashboardLayout
      eyebrow="Usage / Quota"
      title="Theo dõi mức sử dụng"
      description="Tổng hợp số request, token, chi phí ước tính và giới hạn trong ngày của tài khoản hiện tại."
      actions={
        <Button type="button" variant="secondary" onClick={() => void reload()} disabled={loading}>
          {loading ? <Spinner /> : <RefreshCw className="h-4 w-4" />}
          Làm mới
        </Button>
      }
    >
      {error ? (
        <div className="mb-6 rounded-lg border border-danger/25 bg-danger/5 p-4 text-sm text-danger">{error}</div>
      ) : null}

      <UsageDashboard quota={quota} cost={cost} loading={loading} />
    </DashboardLayout>
  );
}
