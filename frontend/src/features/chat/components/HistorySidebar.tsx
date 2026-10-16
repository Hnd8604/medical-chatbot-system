import { useState } from "react";
import { Link } from "react-router-dom";
import {
  BarChart3,
  Check,
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
  SlidersHorizontal,
  Table2,
  Trash2,
  UserRound,
  X,
} from "lucide-react";
import { roleLabel } from "@/features/auth/labels";
import type { AuthUser } from "@/features/auth/types";
import { cn } from "@/shared/lib/cn";
import { Button } from "@/shared/ui/Button";
import { inputClass } from "@/shared/ui/Field";
import { UNTITLED_SESSION } from "../constants";
import type { ChatSession, ExportFormat } from "../types";

interface HistorySidebarProps {
  user: AuthUser;
  sessions: ChatSession[];
  activeSessionId: string | null;
  query: string;
  loading: boolean;
  collapsed: boolean;
  onToggleCollapsed: () => void;
  onQueryChange: (value: string) => void;
  onNewChat: () => void;
  onSelectSession: (session: ChatSession) => void;
  onRenameSession: (session: ChatSession, title: string) => void;
  onDeleteSession: (session: ChatSession) => void;
  onExportHistory: (format: ExportFormat) => void;
  onOpenUsage: () => void;
  onLogout: () => void;
  className?: string;
}

const navItemClass =
  "focus-ring flex min-h-9 w-full items-center gap-2.5 rounded-lg px-3 text-sm font-medium text-muted-foreground transition hover:bg-muted hover:text-foreground";

function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join("");
}

function CollapsedSidebar({
  user,
  displayName,
  shortName,
  onToggleCollapsed,
  onNewChat,
  onExportHistory,
  onOpenUsage,
  onLogout,
  className,
}: Pick<
  HistorySidebarProps,
  "user" | "onToggleCollapsed" | "onNewChat" | "onExportHistory" | "onOpenUsage" | "onLogout" | "className"
> & { displayName: string; shortName: string }) {
  const iconButton = (label: string, icon: React.ReactNode, onClick: () => void) => (
    <Button type="button" variant="ghost" size="icon" onClick={onClick} aria-label={label} title={label}>
      {icon}
    </Button>
  );

  return (
    <aside className={cn("flex min-h-0 flex-col border-r border-border bg-white p-3", className)}>
      <header className="flex flex-col items-center gap-3 border-b border-border pb-3">
        <span className="grid h-11 w-11 place-items-center rounded-2xl bg-foreground font-display text-sm text-white" title="Medical Chatbot">
          MC
        </span>
        {iconButton("Mở rộng thanh bên", <PanelLeftOpen className="h-5 w-5" />, onToggleCollapsed)}
      </header>

      <nav className="mt-4 grid gap-2" aria-label="Điều hướng nhanh">
        <Button type="button" variant="primary" size="icon" onClick={onNewChat} aria-label="Hội thoại mới" title="Hội thoại mới">
          <Plus className="h-5 w-5" />
        </Button>
        {iconButton("Tìm hội thoại", <Search className="h-5 w-5" />, onToggleCollapsed)}
        {iconButton("Xuất PDF", <FileText className="h-5 w-5" />, () => onExportHistory("pdf"))}
        {iconButton("Xuất CSV", <Table2 className="h-5 w-5" />, () => onExportHistory("csv"))}
        {iconButton("Usage / quota", <BarChart3 className="h-5 w-5" />, onOpenUsage)}
      </nav>

      <div className="min-h-0 flex-1" />

      <footer className="grid gap-2 border-t border-border pt-3">
        <span
          className="grid h-11 w-11 place-items-center rounded-full gradient-surface text-xs font-bold text-white"
          title={`${displayName} - ${roleLabel(user.role)}`}
        >
          {shortName}
        </span>
        <Link
          to="/profile"
          className="focus-ring grid h-9 w-9 place-items-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground"
          title="Hồ sơ của tôi"
          aria-label="Hồ sơ của tôi"
        >
          <UserRound className="h-5 w-5" />
        </Link>
        {iconButton("Đăng xuất", <LogOut className="h-5 w-5" />, onLogout)}
      </footer>
    </aside>
  );
}

function RenameForm({ session, onSubmit, onCancel }: { session: ChatSession; onSubmit: (title: string) => void; onCancel: () => void }) {
  const [title, setTitle] = useState(session.title || "");

  function submit() {
    const value = title.trim();
    if (value && value !== session.title) {
      onSubmit(value);
    }
    onCancel();
  }

  return (
    <form
      className="flex items-center gap-1 px-1 py-1"
      onSubmit={(event) => {
        event.preventDefault();
        submit();
      }}
    >
      <input
        autoFocus
        className={inputClass("h-9")}
        value={title}
        onChange={(event) => setTitle(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Escape") onCancel();
        }}
        onBlur={submit}
        maxLength={255}
        spellCheck={false}
      />
      <Button type="submit" variant="ghost" size="icon" className="h-9 w-9 shrink-0" aria-label="Lưu">
        <Check className="h-4 w-4" />
      </Button>
      <Button
        type="button"
        variant="ghost"
        size="icon"
        className="h-9 w-9 shrink-0"
        aria-label="Hủy"
        onMouseDown={(event) => event.preventDefault()}
        onClick={onCancel}
      >
        <X className="h-4 w-4" />
      </Button>
    </form>
  );
}

export function HistorySidebar(props: HistorySidebarProps) {
  const {
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
    onLogout,
    className,
  } = props;
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [searchOpen, setSearchOpen] = useState(false);
  const [exportOpen, setExportOpen] = useState(false);
  const displayName = user.display_name || user.username;
  const shortName = initials(displayName) || "U";

  if (collapsed) {
    return <CollapsedSidebar {...props} displayName={displayName} shortName={shortName} />;
  }

  return (
    <aside className={cn("flex min-h-0 flex-col border-r border-border bg-white", className)}>
      <header className="flex items-center justify-between gap-2 px-4 pb-1 pt-4">
        <h1 className="truncate font-display text-lg font-bold">Medical Chatbot</h1>
        <Button type="button" variant="ghost" size="icon" onClick={onToggleCollapsed} aria-label="Thu gọn thanh bên">
          <PanelLeftClose className="h-5 w-5" />
        </Button>
      </header>

      <nav className="space-y-0.5 px-2 pb-1 pt-2" aria-label="Điều hướng">
        <button
          type="button"
          onClick={onNewChat}
          className="focus-ring flex min-h-9 w-full items-center gap-2.5 rounded-lg bg-muted px-3 text-sm font-semibold text-foreground transition hover:bg-muted/70"
        >
          <MessageSquarePlus className="h-4 w-4 shrink-0" />
          Hội thoại mới
        </button>
        <button type="button" onClick={() => setSearchOpen((value) => !value)} className={navItemClass} aria-expanded={searchOpen}>
          <Search className="h-4 w-4 shrink-0" />
          Tìm hội thoại
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
              placeholder="Tìm hội thoại"
              spellCheck={false}
            />
          </div>
        ) : null}
        <button type="button" onClick={onOpenUsage} className={navItemClass}>
          <SlidersHorizontal className="h-4 w-4 shrink-0" />
          Sử dụng / Hạn mức
        </button>
        <button type="button" onClick={() => setExportOpen((value) => !value)} className={navItemClass} aria-expanded={exportOpen}>
          <Download className="h-4 w-4 shrink-0" />
          Xuất
        </button>
        {exportOpen ? (
          <div className="space-y-0.5 border-l border-border pl-2">
            <button type="button" onClick={() => onExportHistory("pdf")} className={navItemClass}>
              <FileText className="h-4 w-4 shrink-0" />
              Xuất PDF
            </button>
            <button type="button" onClick={() => onExportHistory("csv")} className={navItemClass}>
              <Table2 className="h-4 w-4 shrink-0" />
              Xuất CSV
            </button>
          </div>
        ) : null}
      </nav>

      <section
        className="mt-1 min-h-0 flex-1 overflow-y-auto overflow-x-hidden border-t border-border px-3 py-3"
        aria-label="Danh sách đoạn chat"
      >
        <h2 className="mb-1 px-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">Đoạn chat</h2>
        {loading ? <p className="px-2 py-3 text-sm text-muted-foreground">Đang tải hội thoại...</p> : null}
        {!loading && sessions.length === 0 ? (
          <p className="px-2 py-3 text-sm text-muted-foreground">
            {query.trim() ? "Không tìm thấy hội thoại phù hợp." : "Chưa có hội thoại."}
          </p>
        ) : null}
        <div className="grid gap-1">
          {sessions.map((session) =>
            renamingId === session.id ? (
              <RenameForm
                key={session.id}
                session={session}
                onSubmit={(title) => onRenameSession(session, title)}
                onCancel={() => setRenamingId(null)}
              />
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
                  title={session.title || UNTITLED_SESSION}
                  onClick={() => onSelectSession(session)}
                >
                  <span className="block truncate">{session.title || UNTITLED_SESSION}</span>
                </button>
                <div className="flex shrink-0 items-center gap-0.5 pr-1 opacity-0 transition focus-within:opacity-100 group-hover:opacity-100">
                  <button
                    type="button"
                    className="focus-ring grid h-7 w-7 place-items-center rounded-lg text-muted-foreground transition hover:bg-white hover:text-foreground"
                    aria-label="Đổi tên"
                    title="Đổi tên"
                    onClick={() => setRenamingId(session.id)}
                  >
                    <Pencil className="h-3.5 w-3.5" />
                  </button>
                  <button
                    type="button"
                    className="focus-ring grid h-7 w-7 place-items-center rounded-lg text-muted-foreground transition hover:bg-white hover:text-danger"
                    aria-label="Xóa"
                    title="Xóa"
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
            title="Hồ sơ của tôi"
            aria-label="Hồ sơ của tôi"
          >
            <Settings className="h-4 w-4" />
          </Link>
          <Button type="button" variant="ghost" size="icon" className="h-8 w-8" onClick={onLogout} aria-label="Đăng xuất">
            <LogOut className="h-4 w-4" />
          </Button>
        </div>
      </footer>
    </aside>
  );
}
