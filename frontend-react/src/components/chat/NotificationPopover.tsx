import { Bell, CheckCheck } from "lucide-react";
import { NotificationItem } from "../../lib/types";
import { formatDateTime } from "../../lib/formatters";
import { Button } from "../ui/Button";
import { TEXT } from "../../lib/constants";

interface NotificationPopoverProps {
  open: boolean;
  notifications: NotificationItem[];
  unreadCount: number;
  onToggle: () => void;
  onMarkRead: (id: string) => void;
  onMarkAllRead: () => void;
}

export function NotificationPopover({
  open,
  notifications,
  unreadCount,
  onToggle,
  onMarkRead,
  onMarkAllRead,
}: NotificationPopoverProps) {
  return (
    <div className="relative">
      <Button type="button" variant="secondary" size="icon" onClick={onToggle} aria-label={TEXT.notifications}>
        <Bell className="h-5 w-5" />
        {unreadCount > 0 ? (
          <span className="absolute -right-1 -top-1 grid h-5 min-w-5 place-items-center rounded-full bg-danger px-1 text-[10px] font-bold text-white">
            {unreadCount}
          </span>
        ) : null}
      </Button>

      {open ? (
        <section className="absolute right-0 top-14 z-30 w-[min(360px,calc(100vw-2rem))] rounded-2xl border border-border bg-white shadow-lift">
          <header className="flex items-center justify-between gap-3 border-b border-border p-4">
            <div>
              <h3 className="font-semibold">{TEXT.notifications}</h3>
              <p className="text-xs text-muted-foreground">{unreadCount} chưa đọc</p>
            </div>
            <Button type="button" variant="ghost" size="sm" onClick={onMarkAllRead}>
              <CheckCheck className="h-4 w-4" />
              {TEXT.markAllRead}
            </Button>
          </header>
          <div className="max-h-96 overflow-auto p-2">
            {notifications.length === 0 ? (
              <p className="p-4 text-sm text-muted-foreground">Chưa có thông báo.</p>
            ) : (
              notifications.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  className="focus-ring w-full rounded-xl p-3 text-left transition hover:bg-muted"
                  onClick={() => onMarkRead(item.id)}
                >
                  <div className="flex items-start justify-between gap-3">
                    <strong className="text-sm text-foreground">{item.title}</strong>
                    {!item.is_read ? <span className="mt-1 h-2 w-2 rounded-full bg-accent" /> : null}
                  </div>
                  <p className="mt-1 text-sm leading-6 text-muted-foreground">{item.content}</p>
                  <p className="mt-2 text-xs text-muted-foreground">{formatDateTime(item.created_at)}</p>
                </button>
              ))
            )}
          </div>
        </section>
      ) : null}
    </div>
  );
}
