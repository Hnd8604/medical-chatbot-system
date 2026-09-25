import {
  BarChart3,
  Check,
  CircleDollarSign,
  ClipboardList,
  Download,
  FileText,
  LogOut,
  MessageSquarePlus,
  PanelLeftClose,
  PanelLeftOpen,
  Pencil,
  Plus,
  Search,
  Settings,
  Shield,
  SlidersHorizontal,
  Table2,
  Trash2,
  UserRound,
  UsersRound,
  X,
} from "lucide-react";
import { useState } from "react";
import { Link } from "react-router-dom";
import { ChatSessionSummary, AuthUser } from "../../lib/types";
import { roleLabel } from "../../lib/formatters";
import { TEXT } from "../../lib/constants";
import { Button } from "../ui/Button";
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
  onNewChat: () => void;
  onSelectSession: (session: ChatSessionSummary) => void;
  onRenameSession: (session: ChatSessionSummary, title: string) => void;
  onDeleteSession: (session: ChatSessionSummary) => void;
  onExportHistory: (format: "pdf" | "csv") => void;
  onOpenUsage: () => void;
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
  onNewChat,
  onSelectSession,
  onRenameSession,
  onDeleteSession,
  onExportHistory,
  onOpenUsage,
  onOpenAdmin,
  onLogout,
  className,
}: HistorySidebarProps) {
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState("");
  const [searchOpen, setSearchOpen] = useState(false);
  const [moreOpen, setMoreOpen] = useState(false);
  const emptyText = query.trim() ? TEXT.emptySessionSearch : TEXT.emptySessions;

  function startRename(session: ChatSessionSummary) {
    setRenamingId(session.id);
    setRenameValue(session.title || "");
  }

  function cancelRename() {
    setRenamingId(null);
    setRenameValue("");
  }

  function submitRename(session: ChatSessionSummary) {
    const value = renameValue.trim();
    if (value && value !== session.title) {
      onRenameSession(session, value);
    }
    cancelRename();
  }
  const displayName = user.display_name || user.username;
  const shortName = initials(displayName) || "U";

  if (collapsed) {
    return (
      <aside className={cn("flex min-h-0 flex-col border-r border-border bg-white p-3", className)}>
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
          <Button
            type="button"
            variant="ghost"
            size="icon"
            onClick={onOpenUsage}
            aria-label="Usage / quota"
            title="Usage / quota"
          >
            <BarChart3 className="h-5 w-5" />
          </Button>
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
            <Link
              to="/admin/audit-logs"
              className="focus-ring inline-flex h-11 w-11 items-center justify-center rounded-xl bg-transparent text-muted-foreground transition hover:bg-muted hover:text-foreground"
              aria-label="Audit log"
              title="Audit log"
            >
              <ClipboardList className="h-5 w-5" />
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
          <Link
            to="/profile"
            className="focus-ring grid h-9 w-9 place-items-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground"
            title={TEXT.myProfile}
            aria-label={TEXT.myProfile}
          >
            <UserRound className="h-5 w-5" />
          </Link>
          <Button type="button" variant="ghost" size="icon" onClick={onLogout} aria-label={TEXT.logoutButton} title={TEXT.logoutButton}>
            <LogOut className="h-5 w-5" />
          </Button>
        </footer>
      </aside>
    );
  }

  const navItemClass =
    "focus-ring flex min-h-9 w-full items-center gap-2.5 rounded-lg px-3 text-sm font-medium text-muted-foreground transition hover:bg-muted hover:text-foreground";
  const navActiveClass =
    "focus-ring flex min-h-9 w-full items-center gap-2.5 rounded-lg bg-accent/10 px-3 text-sm font-semibold text-accent transition";
  const newChatClass =
    "focus-ring flex min-h-9 w-full items-center gap-2.5 rounded-lg bg-muted px-3 text-sm font-semibold text-foreground transition hover:bg-muted/70";
  const isAdmin = user.role === "ADMIN";

  return (
    <aside className={cn("flex min-h-0 flex-col border-r border-border bg-white", className)}>
      <header className="flex items-center justify-between gap-2 px-4 pb-1 pt-4">
        <h1 className="truncate font-display text-lg font-bold">Medical Chatbot</h1>
        <Button type="button" variant="ghost" size="icon" onClick={onToggleCollapsed} aria-label="Thu gọn thanh bên">
          <PanelLeftClose className="h-5 w-5" />
        </Button>
      </header>

      <nav className="space-y-0.5 px-2 pb-1 pt-2" aria-label="Điều hướng">
        <button type="button" onClick={onNewChat} className={newChatClass}>
          <MessageSquarePlus className="h-4 w-4 shrink-0" />
          {TEXT.newChat}
        </button>
        <button
          type="button"
          onClick={() => setSearchOpen((value) => !value)}
          className={navItemClass}
          aria-expanded={searchOpen}
        >
          <Search className="h-4 w-4 shrink-0" />
          {TEXT.searchSessions}
        </button>
        {searchOpen ? (
          <div className="px-1 pb-1 pt-0.5">
            <input
              autoFocus
              className={inputClass("h-9")}
              value={query}
              onChange={(event) => onQueryChange(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Escape") setSearchOpen(false);
              }}
              maxLength={100}
              placeholder={TEXT.searchSessions}
              spellCheck={false}
            />
          </div>
        ) : null}
        <button type="button" onClick={onOpenUsage} className={navItemClass}>
          <SlidersHorizontal className="h-4 w-4 shrink-0" />
          Sử dụng / Hạn mức
        </button>
        <button
          type="button"
          onClick={() => setMoreOpen((value) => !value)}
          className={navItemClass}
          aria-expanded={moreOpen}
        >
          <Download className="h-4 w-4 shrink-0" />
          Xuất
        </button>
        {moreOpen ? (
          <div className="space-y-0.5 border-l border-border pl-2">
            <button type="button" onClick={() => onExportHistory("pdf")} className={navItemClass}>
              <FileText className="h-4 w-4 shrink-0" />
              {TEXT.exportPdf}
            </button>
            <button type="button" onClick={() => onExportHistory("csv")} className={navItemClass}>
              <Table2 className="h-4 w-4 shrink-0" />
              {TEXT.exportCsv}
            </button>
            {isAdmin ? (
              <Link to="/admin" className={navItemClass}>
                <Shield className="h-4 w-4 shrink-0" />
                Admin dashboard
              </Link>
            ) : null}
            {isAdmin ? (
              <Link to="/admin/usage-cost" className={navItemClass}>
                <CircleDollarSign className="h-4 w-4 shrink-0" />
                Quota / Cost
              </Link>
            ) : null}
            {isAdmin ? (
              <Link to="/admin/audit-logs" className={navItemClass}>
                <ClipboardList className="h-4 w-4 shrink-0" />
                Audit log
              </Link>
            ) : null}
            {isAdmin ? (
              <button type="button" onClick={onOpenAdmin} className={navItemClass}>
                <UsersRound className="h-4 w-4 shrink-0" />
                Quản lý người dùng
              </button>
            ) : null}
          </div>
        ) : null}
      </nav>

      <section className="mt-1 min-h-0 flex-1 overflow-y-auto overflow-x-hidden border-t border-border px-3 py-3" aria-label="Danh sách đoạn chat">
        <h2 className="mb-1 px-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">Đoạn chat</h2>
        {loading ? <p className="px-2 py-3 text-sm text-muted-foreground">Đang tải hội thoại...</p> : null}
        {!loading && sessions.length === 0 ? <p className="px-2 py-3 text-sm text-muted-foreground">{emptyText}</p> : null}
        <div className="grid gap-1">
          {sessions.map((session) =>
            renamingId === session.id ? (
              <form
                key={session.id}
                className="flex items-center gap-1 px-1 py-1"
                onSubmit={(event) => {
                  event.preventDefault();
                  submitRename(session);
                }}
              >
                <input
                  autoFocus
                  className={inputClass("h-9")}
                  value={renameValue}
                  onChange={(event) => setRenameValue(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === "Escape") {
                      cancelRename();
                    }
                  }}
                  onBlur={() => submitRename(session)}
                  maxLength={255}
                  spellCheck={false}
                />
                <Button type="submit" variant="ghost" size="icon" className="h-9 w-9 shrink-0" aria-label={TEXT.save}>
                  <Check className="h-4 w-4" />
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="h-9 w-9 shrink-0"
                  aria-label={TEXT.cancel}
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={cancelRename}
                >
                  <X className="h-4 w-4" />
                </Button>
              </form>
            ) : (
              <div
                key={session.id}
                className={cn(
                  "group relative flex min-w-0 items-center rounded-xl transition",
                  activeSessionId === session.id ? "bg-accent/10" : "hover:bg-muted",
                )}
              >
                <button
                  type="button"
                  className={cn(
                    "focus-ring min-w-0 flex-1 rounded-xl px-3 py-2 text-left text-sm font-medium",
                    activeSessionId === session.id ? "text-accent" : "text-foreground",
                  )}
                  title={session.title || "Hội thoại chưa đặt tên"}
                  onClick={() => onSelectSession(session)}
                >
                  <span className="block truncate">{session.title || "Hội thoại chưa đặt tên"}</span>
                </button>
                <div className="flex shrink-0 items-center gap-0.5 pr-1 opacity-0 transition focus-within:opacity-100 group-hover:opacity-100">
                  <button
                    type="button"
                    className="focus-ring grid h-7 w-7 place-items-center rounded-lg text-muted-foreground transition hover:bg-white hover:text-foreground"
                    aria-label={TEXT.renameSession}
                    title={TEXT.renameSession}
                    onClick={() => startRename(session)}
                  >
                    <Pencil className="h-3.5 w-3.5" />
                  </button>
                  <button
                    type="button"
                    className="focus-ring grid h-7 w-7 place-items-center rounded-lg text-muted-foreground transition hover:bg-white hover:text-danger"
                    aria-label={TEXT.deleteSession}
                    title={TEXT.deleteSession}
                    onClick={() => onDeleteSession(session)}
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </button>
                </div>
              </div>
            ),
          )}
        </div>
      </section>

      <footer className="border-t border-border p-2">
        <div className="flex items-center gap-2.5 rounded-xl px-2 py-1.5 transition hover:bg-muted">
          <div className="grid h-9 w-9 shrink-0 place-items-center rounded-full gradient-surface text-xs font-bold text-white">
            {shortName}
          </div>
          <div className="min-w-0 flex-1">
            <strong className="line-clamp-1 text-sm">{displayName}</strong>
            <p className="line-clamp-1 text-[11px] uppercase tracking-wide text-muted-foreground">{roleLabel(user.role)}</p>
          </div>
          <Link
            to="/profile"
            className="focus-ring grid h-8 w-8 shrink-0 place-items-center rounded-lg text-muted-foreground hover:bg-white hover:text-foreground"
            title={TEXT.myProfile}
            aria-label={TEXT.myProfile}
          >
            <Settings className="h-4 w-4" />
          </Link>
          <Button type="button" variant="ghost" size="icon" className="h-8 w-8" onClick={onLogout} aria-label={TEXT.logoutButton}>
            <LogOut className="h-4 w-4" />
          </Button>
        </div>
      </footer>
    </aside>
  );
}
