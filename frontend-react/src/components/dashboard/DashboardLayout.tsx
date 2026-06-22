import { BarChart3, LogOut, MessageSquareText, Shield, Users } from "lucide-react";
import { Link, NavLink } from "react-router-dom";
import { useAuth } from "../../lib/auth";
import { cn } from "../../lib/cn";
import { roleLabel } from "../../lib/formatters";
import { Badge } from "../ui/Badge";
import { Button } from "../ui/Button";

interface DashboardLayoutProps {
  eyebrow: string;
  title: string;
  description: string;
  children: React.ReactNode;
  actions?: React.ReactNode;
}

function navClass({ isActive }: { isActive: boolean }) {
  return cn(
    "focus-ring inline-flex min-h-10 items-center gap-2 rounded-lg border px-3 text-sm font-semibold transition",
    isActive
      ? "border-accent/30 bg-accent/10 text-accent"
      : "border-transparent text-muted-foreground hover:border-border hover:bg-white hover:text-foreground",
  );
}

export function DashboardLayout({ eyebrow, title, description, actions, children }: DashboardLayoutProps) {
  const { user, logout } = useAuth();
  const isAdmin = user?.role === "ADMIN";

  return (
    <main className="min-h-screen bg-background">
      <div className="mx-auto flex min-h-screen w-full max-w-7xl flex-col px-4 py-5 sm:px-6 lg:px-8">
        <header className="sticky top-0 z-20 -mx-4 border-b border-border/70 bg-background/90 px-4 py-4 backdrop-blur sm:-mx-6 sm:px-6 lg:-mx-8 lg:px-8">
          <div className="flex flex-wrap items-center justify-between gap-4">
            <Link to="/chat" className="group flex items-center gap-3">
              <span className="grid h-11 w-11 place-items-center rounded-2xl bg-foreground font-display text-sm text-white shadow-card transition group-hover:-translate-y-0.5">
                MC
              </span>
              <span>
                <span className="block font-display text-2xl leading-none text-foreground">Medical Chatbot</span>
                <span className="text-sm text-muted-foreground">Bảng điều khiển</span>
              </span>
            </Link>

            <nav className="flex flex-wrap items-center gap-2">
              <NavLink to="/chat" className={navClass}>
                <MessageSquareText className="h-4 w-4" />
                Chat
              </NavLink>
              <NavLink to="/usage" className={navClass}>
                <BarChart3 className="h-4 w-4" />
                Usage
              </NavLink>
              {isAdmin ? (
                <>
                  <NavLink to="/admin" className={navClass} end>
                    <Shield className="h-4 w-4" />
                    Admin
                  </NavLink>
                  <NavLink to="/admin/users" className={navClass}>
                    <Users className="h-4 w-4" />
                    Users
                  </NavLink>
                </>
              ) : null}
            </nav>

            <div className="flex items-center gap-3">
              {user ? (
                <div className="hidden text-right sm:block">
                  <p className="text-sm font-semibold text-foreground">{user.display_name || user.username}</p>
                  <p className="text-xs text-muted-foreground">{roleLabel(user.role)}</p>
                </div>
              ) : null}
              {user ? <Badge tone={isAdmin ? "blue" : "slate"}>{user.role}</Badge> : null}
              <Button type="button" variant="ghost" size="icon" aria-label="Đăng xuất" onClick={() => void logout()}>
                <LogOut className="h-5 w-5" />
              </Button>
            </div>
          </div>
        </header>

        <section className="py-8 sm:py-10">
          <div className="flex flex-wrap items-end justify-between gap-5">
            <div className="max-w-3xl">
              <Badge tone="blue" pulse>
                {eyebrow}
              </Badge>
              <h1 className="mt-4 font-display text-4xl leading-tight text-foreground sm:text-5xl">{title}</h1>
              <p className="mt-3 max-w-2xl text-base leading-7 text-muted-foreground">{description}</p>
            </div>
            {actions ? <div className="flex flex-wrap gap-3">{actions}</div> : null}
          </div>
        </section>

        <div className="pb-10">{children}</div>
      </div>
    </main>
  );
}
