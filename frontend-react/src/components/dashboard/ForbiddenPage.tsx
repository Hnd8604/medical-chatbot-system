import { ShieldAlert } from "lucide-react";
import { Link } from "react-router-dom";
import { Badge } from "../ui/Badge";

export function ForbiddenPage() {
  return (
    <main className="grid min-h-screen place-items-center bg-background px-4">
      <section className="w-full max-w-xl rounded-lg border border-border bg-white p-8 text-center shadow-card">
        <div className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-danger/10 text-danger">
          <ShieldAlert className="h-7 w-7" />
        </div>
        <Badge tone="red" className="mt-5">
          Không có quyền
        </Badge>
        <h1 className="mt-4 font-display text-4xl text-foreground">Bạn không thể truy cập trang này</h1>
        <p className="mt-3 text-muted-foreground">
          Khu vực này chỉ dành cho quản trị viên. Hãy quay lại trang chat hoặc đăng nhập bằng tài khoản phù hợp.
        </p>
        <div className="mt-7 flex flex-wrap justify-center gap-3">
          <Link
            to="/chat"
            className="focus-ring gradient-surface inline-flex min-h-11 items-center justify-center rounded-xl px-4 py-2 text-sm font-semibold text-white shadow-accent transition hover:-translate-y-0.5 hover:brightness-110"
          >
            Quay về chat
          </Link>
          <Link
            to="/usage"
            className="focus-ring inline-flex min-h-11 items-center justify-center rounded-xl border border-border bg-white px-4 py-2 text-sm font-semibold text-foreground shadow-sm transition hover:-translate-y-0.5 hover:border-accent/40 hover:shadow-card"
          >
            Xem usage
          </Link>
        </div>
      </section>
    </main>
  );
}
