import {
  BarChart3,
  CircleDollarSign,
  FileText,
  LogOut,
  PanelLeftClose,
  PanelLeftOpen,
  Plus,
  RefreshCcw,
  Search,
  Shield,
  Table2,
  UsersRound,
} from "lucide-react";
import { Link } from "react-router-dom";
import { ChatSessionSummary, AuthUser } from "../../lib/types";
import { roleLabel } from "../../lib/formatters";
import { TEXT } from "../../lib/constants";
import { Button } from "../ui/Button";
import { Badge } from "../ui/Badge";
import { inputClass } from "../ui/Field";
import { cn } from "../../lib/cn";

interface HistorySidebarProps {
  user: AuthUser;
  sessions: ChatSessionSummary[];
  activeSessionId: string | null;
  query: string;
  loading: boolean;
  collapsed: boolean;
  onToggleCollapsed: () => void;
  onQueryChange: (value: string) => void;
  onRefresh: () => void;
  onNewChat: () => void;
  onSelectSession: (session: ChatSessionSummary) => void;
  onExportHistory: (format: "pdf" | "csv") => void;
  onOpenAdmin: () => void;
  onLogout: () => void;
  className?: string;
}

function initials(name: string) {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join("");
}

export function HistorySidebar({
  user,
  sessions,
  activeSessionId,
  query,
  loading,
  collapsed,
  onToggleCollapsed,
  onQueryChange,
  onRefresh,
  onNewChat,
  onSelectSession,
  onExportHistory,
  onOpenAdmin,
  onLogout,
  className,
}: HistorySidebarProps) {
  const emptyText = query.trim() ? TEXT.emptySessionSearch : TEXT.emptySessions;
  const displayName = user.display_name || user.username;
  const shortName = initials(displayName) || "U";

  if (collapsed) {
    return (
      <aside className={cn("flex min-h-0 flex-col border-r border-border bg-white/90 p-3 backdrop-blur", className)}>
        <header className="flex flex-col items-center gap-3 border-b border-border pb-3">
          <button
            type="button"
            className="focus-ring grid h-11 w-11 place-items-center rounded-2xl bg-foreground font-display text-sm text-white"
            title="Medical Chatbot"
            aria-label="Medical Chatbot"
          >
            MC
          </button>
          <Button type="button" variant="ghost" size="icon" onClick={onToggleCollapsed} aria-label="Mở rộng thanh bên">
            <PanelLeftOpen className="h-5 w-5" />
          </Button>
        </header>

        <nav className="mt-4 grid gap-2" aria-label="Điều hướng nhanh">
          <Button type="button" variant="primary" size="icon" onClick={onNewChat} aria-label={TEXT.newChat} title={TEXT.newChat}>
            <Plus className="h-5 w-5" />
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            onClick={onToggleCollapsed}
            aria-label={TEXT.searchSessions}
            title={TEXT.searchSessions}
          >
            <Search className="h-5 w-5" />
          </Button>
          <Button type="button" variant="ghost" size="icon" onClick={onRefresh} aria-label={TEXT.refresh} title={TEXT.refresh}>
            <RefreshCcw className="h-5 w-5" />
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            onClick={() => onExportHistory("pdf")}
            aria-label={TEXT.exportPdf}
            title={TEXT.exportPdf}
          >
            <FileText className="h-5 w-5" />
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            onClick={() => onExportHistory("csv")}
            aria-label={TEXT.exportCsv}
            title={TEXT.exportCsv}
          >
            <Table2 className="h-5 w-5" />
          </Button>
          <Link
            to="/usage"
            className="focus-ring inline-flex h-11 w-11 items-center justify-center rounded-xl bg-transparent text-muted-foreground transition hover:bg-muted hover:text-foreground"
            aria-label="Usage / quota"
            title="Usage / quota"
          >
            <BarChart3 className="h-5 w-5" />
          </Link>
          {user.role === "ADMIN" ? (
            <Link
              to="/admin"
              className="focus-ring inline-flex h-11 w-11 items-center justify-center rounded-xl bg-transparent text-muted-foreground transition hover:bg-muted hover:text-foreground"
              aria-label="Admin dashboard"
              title="Admin dashboard"
            >
              <Shield className="h-5 w-5" />
            </Link>
          ) : null}
          {user.role === "ADMIN" ? (
            <Link
              to="/admin/usage-cost"
              className="focus-ring inline-flex h-11 w-11 items-center justify-center rounded-xl bg-transparent text-muted-foreground transition hover:bg-muted hover:text-foreground"
              aria-label="Quota / Cost"
              title="Quota / Cost"
            >
              <CircleDollarSign className="h-5 w-5" />
            </Link>
          ) : null}
          {user.role === "ADMIN" ? (
            <Button
              type="button"
              variant="ghost"
              size="icon"
              onClick={onOpenAdmin}
              aria-label="Quản lý người dùng"
              title="Quản lý người dùng"
            >
              <UsersRound className="h-5 w-5" />
            </Button>
          ) : null}
        </nav>

        <div className="min-h-0 flex-1" />

        <footer className="grid gap-2 border-t border-border pt-3">
          <button
            type="button"
            className="focus-ring grid h-11 w-11 place-items-center rounded-full gradient-surface text-xs font-bold text-white"
            title={`${displayName} - ${roleLabel(user.role)}`}
            aria-label={`${displayName} - ${roleLabel(user.role)}`}
          >
            {shortName}
          </button>
          <Button type="button" variant="ghost" size="icon" onClick={onLogout} aria-label={TEXT.logoutButton} title={TEXT.logoutButton}>
            <LogOut className="h-5 w-5" />
          </Button>
        </footer>
      </aside>
    );
  }

  return (
    <aside className={cn("flex min-h-0 flex-col border-r border-border bg-white/90 backdrop-blur", className)}>
      <header className="space-y-4 border-b border-border p-4">
        <div className="flex items-center justify-between gap-3">
          <div>
            <h1 className="font-display text-2xl">Medical Chatbot</h1>
            <p className="mt-1 text-sm text-muted-foreground">{roleLabel(user.role)}</p>
          </div>
          <Button type="button" variant="ghost" size="icon" onClick={onToggleCollapsed} aria-label="Thu gọn thanh bên">
            <PanelLeftClose className="h-5 w-5" />
          </Button>
        </div>

        <Button type="button" variant="primary" className="w-full justify-start" onClick={onNewChat}>
          <Plus className="h-4 w-4" />
          {TEXT.newChat}
        </Button>
      </header>

      <div className="space-y-3 border-b border-border p-4">
        <label className="grid gap-2 text-sm font-semibold">
          {TEXT.searchSessions}
          <div className="relative">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <input
              className={inputClass("pl-9")}
              value={query}
              onChange={(event) => onQueryChange(event.target.value)}
              maxLength={100}
              placeholder="Tiêu đề hội thoại"
              spellCheck={false}
            />
          </div>
        </label>
        <div className="grid gap-2">
          <Button type="button" variant="secondary" size="sm" className="w-full justify-start" onClick={onRefresh}>
            <RefreshCcw className="h-4 w-4" />
            {TEXT.refresh}
          </Button>
          <Button
            type="button"
            variant="secondary"
            size="sm"
            className="w-full justify-start"
            onClick={() => onExportHistory("pdf")}
          >
            <FileText className="h-4 w-4" />
            {TEXT.exportPdf}
          </Button>
          <Button
            type="button"
            variant="secondary"
            size="sm"
            className="w-full justify-start"
            onClick={() => onExportHistory("csv")}
          >
            <Table2 className="h-4 w-4" />
            {TEXT.exportCsv}
          </Button>
        </div>
        <Link
          to="/usage"
          className="focus-ring inline-flex min-h-9 w-full items-center justify-start gap-2 rounded-lg border border-border bg-white px-3 text-xs font-semibold text-foreground shadow-sm transition hover:-translate-y-0.5 hover:border-accent/40 hover:shadow-card"
        >
          <BarChart3 className="h-4 w-4" />
          Usage / quota
        </Link>
        {user.role === "ADMIN" ? (
          <Link
            to="/admin"
            className="focus-ring inline-flex min-h-9 w-full items-center justify-start gap-2 rounded-lg border border-border bg-white px-3 text-xs font-semibold text-foreground shadow-sm transition hover:-translate-y-0.5 hover:border-accent/40 hover:shadow-card"
          >
            <Shield className="h-4 w-4" />
            Admin dashboard
          </Link>
        ) : null}
        {user.role === "ADMIN" ? (
          <Link
            to="/admin/usage-cost"
            className="focus-ring inline-flex min-h-9 w-full items-center justify-start gap-2 rounded-lg border border-border bg-white px-3 text-xs font-semibold text-foreground shadow-sm transition hover:-translate-y-0.5 hover:border-accent/40 hover:shadow-card"
          >
            <CircleDollarSign className="h-4 w-4" />
            Quota / Cost
          </Link>
        ) : null}
        {user.role === "ADMIN" ? (
          <Button type="button" variant="ghost" size="sm" className="w-full justify-start" onClick={onOpenAdmin}>
            <UsersRound className="h-4 w-4" />
            Quản lý người dùng
          </Button>
        ) : null}
      </div>

      <section className="min-h-0 flex-1 overflow-auto px-3 py-4" aria-label="Danh sách hội thoại">
        <h2 className="mb-2 px-2 text-xs font-bold uppercase tracking-[0.14em] text-muted-foreground">Gần đây</h2>
        {loading ? <p className="px-2 py-3 text-sm text-muted-foreground">Đang tải hội thoại...</p> : null}
        {!loading && sessions.length === 0 ? <p className="px-2 py-3 text-sm text-muted-foreground">{emptyText}</p> : null}
        <div className="grid gap-1">
          {sessions.map((session) => (
            <button
              key={session.id}
              type="button"
              className={cn(
                "focus-ring rounded-xl px-3 py-2 text-left text-sm font-medium transition",
                activeSessionId === session.id ? "bg-accent/10 text-accent" : "text-foreground hover:bg-muted",
              )}
              title={session.title || "Hội thoại chưa đặt tên"}
              onClick={() => onSelectSession(session)}
            >
              <span className="line-clamp-1">{session.title || "Hội thoại chưa đặt tên"}</span>
            </button>
          ))}
        </div>
      </section>

      <footer className="border-t border-border p-3">
        <div className="flex items-center gap-3 rounded-2xl p-2 transition hover:bg-muted">
          <div className="grid h-10 w-10 shrink-0 place-items-center rounded-full gradient-surface text-xs font-bold text-white">
            {shortName}
          </div>
          <div className="min-w-0 flex-1">
            <strong className="line-clamp-1 text-sm">{displayName}</strong>
            <p className="line-clamp-1 text-xs text-muted-foreground">{user.email}</p>
          </div>
          <Badge tone={user.status === "ACTIVE" ? "green" : "amber"} className="hidden px-2 py-0.5 text-[9px] xl:inline-flex">
            {user.status}
          </Badge>
          <Button type="button" variant="ghost" size="icon" onClick={onLogout} aria-label={TEXT.logoutButton}>
            <LogOut className="h-4 w-4" />
          </Button>
        </div>
      </footer>
    </aside>
  );
}
