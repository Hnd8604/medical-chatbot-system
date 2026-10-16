import { RefreshCw } from "lucide-react";
import { Button } from "./Button";
import { Spinner } from "./Spinner";

export function RefreshButton({ loading, onClick }: { loading: boolean; onClick: () => void }) {
  return (
    <Button type="button" variant="secondary" onClick={onClick} disabled={loading}>
      {loading ? <Spinner /> : <RefreshCw className="h-4 w-4" />}
      Làm mới
    </Button>
  );
}
