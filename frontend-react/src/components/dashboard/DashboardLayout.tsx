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
} from "lucide-react";
import { Link, NavLink } from "react-router-dom";
import { useAuth } from "../../hooks/useAuth";
import { cn } from "../../lib/cn";
import { TEXT } from "../../lib/constants";
import { roleLabel } from "../../lib/formatters";
import { Badge } from "../ui/Badge";
import { Button } from "../ui/Button";

interface DashboardLayoutProps {
  eyebrow: string;
  title: string;
  description?: string;
  children: React.ReactNode;
  actions?: React.ReactNode;
}

function navClass({ isActive }: { isActive: boolean }) {
  return cn(
    "focus-ring group flex min-h-11 w-full items-center gap-3 rounded-lg px-3 text-sm font-medium transition",
    isActive ? "bg-white/20 text-white" : "text-white/80 hover:bg-white/10 hover:text-white",
  );
}

export function DashboardLayout({ eyebrow, title, description, actions, children }: DashboardLayoutProps) {
  const { user, logout } = useAuth();
  const isAdmin = user?.role === "ADMIN";
  const initials = (user?.display_name || user?.username || "?").slice(0, 1).toUpperCase();

  return (
    <div className="min-h-screen bg-background lg:flex">
      <aside className="sticky top-0 z-20 flex h-auto shrink-0 flex-col gap-6 bg-gradient-to-b from-accent to-accent-secondary px-4 py-6 text-white lg:h-screen lg:w-64 lg:overflow-y-auto">
        <Link to={isAdmin ? "/admin" : "/chat"} className="group flex items-center gap-3">
          <span className="grid h-10 w-10 place-items-center rounded-xl bg-white/20 font-display text-sm text-white">
            MC
          </span>
          <span className="font-display text-xl leading-none text-white">Medical Chatbot</span>
        </Link>

        {user ? (
          <div className="flex items-center gap-3 rounded-xl bg-white/10 p-3">
            <span className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-white/25 text-sm font-semibold text-white">
              {initials}
            </span>
            <div className="min-w-0">
              <p className="truncate text-sm font-semibold text-white">{user.display_name || user.username}</p>
              <p className="text-xs text-white/70">{roleLabel(user.role)}</p>
            </div>
          </div>
        ) : null}

        <nav className="flex flex-col gap-1">
          <p className="px-3 pb-1 text-xs font-semibold uppercase tracking-[0.16em] text-white/50">Main Menu</p>
          {!isAdmin ? (
            <>
              <NavLink to="/chat" className={navClass}>
                <MessageSquareText className="h-4 w-4" />
                <span className="flex-1">Chat</span>
                <ChevronRight className="h-4 w-4 opacity-50" />
              </NavLink>
              <NavLink to="/usage" className={navClass}>
                <BarChart3 className="h-4 w-4" />
                <span className="flex-1">Usage</span>
                <ChevronRight className="h-4 w-4 opacity-50" />
              </NavLink>
            </>
          ) : null}
          {isAdmin ? (
            <>
              <NavLink to="/admin" className={navClass} end>
                <Shield className="h-4 w-4" />
                <span className="flex-1">Admin</span>
                <ChevronRight className="h-4 w-4 opacity-50" />
              </NavLink>
              <NavLink to="/admin/analytics" className={navClass}>
                <Gauge className="h-4 w-4" />
                <span className="flex-1">Analytics</span>
                <ChevronRight className="h-4 w-4 opacity-50" />
              </NavLink>
              <NavLink to="/admin/alerts" className={navClass}>
                <BellRing className="h-4 w-4" />
                <span className="flex-1">Alerts</span>
                <ChevronRight className="h-4 w-4 opacity-50" />
              </NavLink>
              <NavLink to="/admin/users" className={navClass}>
                <Users className="h-4 w-4" />
                <span className="flex-1">Users</span>
                <ChevronRight className="h-4 w-4 opacity-50" />
              </NavLink>
              <NavLink to="/admin/config" className={navClass}>
                <Settings className="h-4 w-4" />
                <span className="flex-1">Cấu hình</span>
                <ChevronRight className="h-4 w-4 opacity-50" />
              </NavLink>
              <NavLink to="/admin/usage-cost" className={navClass}>
                <CircleDollarSign className="h-4 w-4" />
                <span className="flex-1">Quota/Cost</span>
                <ChevronRight className="h-4 w-4 opacity-50" />
              </NavLink>
              <NavLink to="/admin/audit-logs" className={navClass}>
                <ClipboardList className="h-4 w-4" />
                <span className="flex-1">Audit</span>
                <ChevronRight className="h-4 w-4 opacity-50" />
              </NavLink>
              <NavLink to="/admin/backup" className={navClass}>
                <DatabaseBackup className="h-4 w-4" />
                <span className="flex-1">Backup</span>
                <ChevronRight className="h-4 w-4 opacity-50" />
              </NavLink>
            </>
          ) : null}
        </nav>

        <Link to="/change-password" className={cn(navClass({ isActive: false }), "mt-auto")}>
          <KeyRound className="h-5 w-5" />
          {TEXT.changePassword}
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
        <div className="mx-auto w-full px-4 py-5 sm:px-6 lg:px-8">
          <section className="py-8 sm:py-10">
            <div className="flex flex-wrap items-end justify-between gap-5">
              <div className="max-w-3xl">
                <Badge tone="blue" pulse>
                  {eyebrow}
                </Badge>
                <h1 className="mt-4 font-display text-4xl leading-tight text-foreground sm:text-5xl">{title}</h1>
                {description ? <p className="mt-3 max-w-2xl text-base leading-7 text-muted-foreground">{description}</p> : null}
              </div>
              {actions ? <div className="flex flex-wrap gap-3">{actions}</div> : null}
            </div>
          </section>

          <div className="pb-10">{children}</div>
        </div>
      </main>
    </div>
  );
}
