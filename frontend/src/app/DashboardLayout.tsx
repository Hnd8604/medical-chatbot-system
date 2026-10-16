import { Suspense } from "react";
import { Link, NavLink, Outlet } from "react-router-dom";
import {
  BarChart3,
  BellRing,
  ChevronRight,
  CircleDollarSign,
  ClipboardList,
  DatabaseBackup,
  Gauge,
  KeyRound,
  LogOut,
  MessageSquareText,
  Settings,
  Shield,
  Users,
  type LucideIcon,
} from "lucide-react";
import { useAuth, useSignedInUser } from "@/features/auth/AuthProvider";
import { roleLabel } from "@/features/auth/labels";
import { cn } from "@/shared/lib/cn";
import { Button } from "@/shared/ui/Button";
import { LoadingPanel } from "@/shared/ui/LoadingPanel";

interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
  /** Only active on an exact match (the dashboard root). */
  end?: boolean;
}

const USER_NAV: NavItem[] = [
  { to: "/chat", label: "Chat", icon: MessageSquareText },
  { to: "/usage", label: "Usage", icon: BarChart3 },
];

const ADMIN_NAV: NavItem[] = [
  { to: "/admin", label: "Admin", icon: Shield, end: true },
  { to: "/admin/analytics", label: "Analytics", icon: Gauge },
  { to: "/admin/alerts", label: "Alerts", icon: BellRing },
  { to: "/admin/users", label: "Users", icon: Users },
  { to: "/admin/config", label: "Cấu hình", icon: Settings },
  { to: "/admin/usage-cost", label: "Quota/Cost", icon: CircleDollarSign },
  { to: "/admin/audit-logs", label: "Audit", icon: ClipboardList },
  { to: "/admin/backup", label: "Backup", icon: DatabaseBackup },
];

function navClass({ isActive }: { isActive: boolean }) {
  return cn(
    "focus-ring group flex min-h-11 w-full items-center gap-3 rounded-lg px-3 text-sm font-medium transition",
    isActive ? "bg-white/20 text-white" : "text-white/80 hover:bg-white/10 hover:text-white",
  );
}

/** Sidebar shell for the usage page and every admin page; pages render inside the outlet. */
export function DashboardLayout() {
  const { logout } = useAuth();
  const user = useSignedInUser();
  const isAdmin = user.role === "ADMIN";
  const displayName = user.display_name || user.username;

  return (
    <div className="min-h-screen bg-background lg:flex">
      <aside className="sticky top-0 z-20 flex h-auto shrink-0 flex-col gap-6 bg-gradient-to-b from-accent to-accent-secondary px-4 py-6 text-white lg:h-screen lg:w-64 lg:overflow-y-auto">
        <Link to={isAdmin ? "/admin" : "/chat"} className="group flex items-center gap-3">
          <span className="grid h-10 w-10 place-items-center rounded-xl bg-white/20 font-display text-sm text-white">MC</span>
          <span className="font-display text-xl leading-none text-white">Medical Chatbot</span>
        </Link>

        <div className="flex items-center gap-3 rounded-xl bg-white/10 p-3">
          <span className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-white/25 text-sm font-semibold text-white">
            {displayName.slice(0, 1).toUpperCase()}
          </span>
          <div className="min-w-0">
            <p className="truncate text-sm font-semibold text-white">{displayName}</p>
            <p className="text-xs text-white/70">{roleLabel(user.role)}</p>
          </div>
        </div>

        <nav className="flex flex-col gap-1">
          <p className="px-3 pb-1 text-xs font-semibold uppercase tracking-[0.16em] text-white/50">Main Menu</p>
          {(isAdmin ? ADMIN_NAV : USER_NAV).map(({ to, label, icon: Icon, end }) => (
            <NavLink key={to} to={to} className={navClass} end={end}>
              <Icon className="h-4 w-4" />
              <span className="flex-1">{label}</span>
              <ChevronRight className="h-4 w-4 opacity-50" />
            </NavLink>
          ))}
        </nav>

        <Link to="/change-password" className={cn(navClass({ isActive: false }), "mt-auto")}>
          <KeyRound className="h-5 w-5" />
          Đổi mật khẩu
        </Link>
        <Button
          type="button"
          variant="ghost"
          onClick={() => void logout()}
          className="w-full justify-start gap-3 text-white/80 hover:bg-white/10 hover:text-white"
        >
          <LogOut className="h-5 w-5" />
          Đăng xuất
        </Button>
      </aside>

      <main className="min-w-0 flex-1">
        <div className="mx-auto w-full px-4 pb-10 pt-5 sm:px-6 lg:px-8">
          <Suspense fallback={<LoadingPanel label="Đang tải trang..." className="mt-10" />}>
            <Outlet />
          </Suspense>
        </div>
      </main>
    </div>
  );
}
