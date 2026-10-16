import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Search } from "lucide-react";
import { adminUserApi, adminUserKeys } from "@/features/admin/users/api";
import type { AdminUser } from "@/features/admin/users/types";
import { useDebouncedValue } from "@/shared/hooks/useDebouncedValue";
import { inputClass } from "@/shared/ui/Field";

const SUGGESTION_LIMIT = 8;

interface UserLookupProps {
  value: string;
  onChange: (value: string) => void;
  onPick: (user: AdminUser) => void;
  onSubmit: () => void;
}

/** Text input with as-you-type user suggestions. */
export function UserLookup({ value, onChange, onPick, onSubmit }: UserLookupProps) {
  const [open, setOpen] = useState(false);
  const term = useDebouncedValue(value.trim(), 250);
  const params = { page: 0, size: SUGGESTION_LIMIT, search: term };
  const suggestions = useQuery({
    queryKey: adminUserKeys.list(params),
    queryFn: () => adminUserApi.list(params),
    enabled: open && term.length > 0,
  });
  const items = open && term ? (suggestions.data?.items ?? []) : [];

  return (
    <div className="relative">
      <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
      <input
        className={inputClass("pl-10")}
        value={value}
        placeholder="Nhập tên đăng nhập, email hoặc tên hiển thị"
        autoComplete="off"
        onChange={(event) => {
          onChange(event.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        // Delayed so that a click on a suggestion still lands.
        onBlur={() => window.setTimeout(() => setOpen(false), 150)}
        onKeyDown={(event) => {
          if (event.key === "Enter") {
            setOpen(false);
            onSubmit();
          }
        }}
      />
      {items.length > 0 ? (
        <ul className="absolute z-20 mt-1 max-h-64 w-full overflow-auto rounded-lg border border-border bg-white py-1 shadow-lg">
          {items.map((user) => (
            <li key={user.id}>
              <button
                type="button"
                className="flex w-full flex-col items-start gap-0.5 px-3 py-2 text-left hover:bg-muted/60"
                onMouseDown={(event) => {
                  event.preventDefault();
                  setOpen(false);
                  onPick(user);
                }}
              >
                <span className="text-sm font-medium text-foreground">{user.username}</span>
                <span className="text-xs text-muted-foreground">
                  {user.displayName}
                  {user.email ? ` · ${user.email}` : ""}
                </span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

/** Best match for free text: an exact username, email or display name, else the first result. */
export async function findUser(term: string): Promise<AdminUser | null> {
  const query = term.trim();
  if (!query) {
    return null;
  }
  const { items } = await adminUserApi.list({ page: 0, size: SUGGESTION_LIMIT, search: query });
  const lower = query.toLowerCase();
  return (
    items.find((user) => [user.username, user.email, user.displayName].some((value) => value?.toLowerCase() === lower)) ??
    items[0] ??
    null
  );
}
