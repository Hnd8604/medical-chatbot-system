type Numeric = number | string | null | undefined;

/** BigDecimal values may arrive as strings; anything unparsable counts as 0. */
export function numericValue(value: Numeric): number {
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : 0;
  }
  if (typeof value === "string" && value.trim()) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }
  return 0;
}

export function formatNumber(value: Numeric): string {
  return new Intl.NumberFormat("vi-VN").format(numericValue(value));
}

export function formatUsd(value: Numeric): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 2,
    maximumFractionDigits: 4,
  }).format(numericValue(value));
}

export function formatPercent(value: Numeric): string {
  return `${numericValue(value).toFixed(1)}%`;
}

/** Share of the limit used, capped at 100; 0 when there is no limit. */
export function usagePercent(used: Numeric, limit: Numeric): number {
  const limitValue = numericValue(limit);
  if (limitValue <= 0) {
    return 0;
  }
  return Math.min(100, (numericValue(used) / limitValue) * 100);
}

export function formatBytes(value: Numeric): string {
  const bytes = numericValue(value);
  if (bytes <= 0) {
    return "0 B";
  }
  const units = ["B", "KB", "MB", "GB", "TB"];
  const exponent = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  const size = bytes / Math.pow(1024, exponent);
  return `${size.toFixed(exponent === 0 ? 0 : 1)} ${units[exponent]}`;
}

export function formatLatency(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return "0 ms";
  }
  return `${Math.round(value).toLocaleString("vi-VN")} ms`;
}

function parseDate(value?: string | null): Date | null {
  if (!value) {
    return null;
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

export function formatDateTime(value?: string | null): string {
  const date = parseDate(value);
  if (!date) {
    return value || "-";
  }
  return new Intl.DateTimeFormat("vi-VN", { dateStyle: "short", timeStyle: "short" }).format(date);
}

export function formatDate(value?: string | null): string {
  const date = parseDate(value);
  if (!date) {
    return value || "-";
  }
  return new Intl.DateTimeFormat("vi-VN", { dateStyle: "medium" }).format(date);
}

export function formatWeekday(value: string): string {
  const date = parseDate(value);
  if (!date) {
    return value;
  }
  return new Intl.DateTimeFormat("vi-VN", {
    weekday: "short",
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
  }).format(date);
}

/** Duration between two timestamps as "42s" or "3m 5s"; an em dash while still running. */
export function formatDuration(startedAt: string, finishedAt: string | null): string {
  const start = parseDate(startedAt);
  const end = parseDate(finishedAt);
  if (!start || !end || end < start) {
    return "—";
  }
  const seconds = Math.round((end.getTime() - start.getTime()) / 1000);
  if (seconds < 60) {
    return `${seconds}s`;
  }
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

export function shortId(value?: string | null): string {
  if (!value) {
    return "-";
  }
  return value.length <= 16 ? value : `${value.slice(0, 8)}...${value.slice(-6)}`;
}

export function prettyJson(value: unknown): string {
  if (value === undefined || value === null) {
    return "-";
  }
  return JSON.stringify(value, null, 2);
}
