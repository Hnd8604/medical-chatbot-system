/** yyyy-MM-dd in the browser's time zone (toISOString would use UTC and shift the day). */
export function toIsoDate(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

export function todayIso(): string {
  return toIsoDate(new Date());
}

export function daysAgoIso(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return toIsoDate(date);
}

export interface DateRange {
  from: string;
  to: string;
}

/** The last `days` days, today included. */
export function lastDays(days: number): DateRange {
  return { from: daysAgoIso(days - 1), to: todayIso() };
}

export function rangeLength({ from, to }: DateRange): number {
  const start = new Date(from);
  const end = new Date(to);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) {
    return 0;
  }
  return Math.max(1, Math.round((end.getTime() - start.getTime()) / 86_400_000) + 1);
}

/** Start of a local day as an ISO timestamp, for endpoints that filter by instant. */
export function startOfDayIso(date: string): string | null {
  return date ? new Date(`${date}T00:00:00`).toISOString() : null;
}

/** End of a local day as an ISO timestamp. */
export function endOfDayIso(date: string): string | null {
  return date ? new Date(`${date}T23:59:59.999`).toISOString() : null;
}
