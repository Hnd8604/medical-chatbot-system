import { formatNumber, formatUsd, numericValue } from "../../lib/formatters";

type UsageDay = {
  date?: string;
  day?: string;
  request_count?: number | string;
  total_tokens?: number | string;
  estimated_cost_usd?: number | string;
};

function shortDate(value?: string): string {
  if (!value) {
    return "-";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return `${String(date.getDate()).padStart(2, "0")}-${String(date.getMonth() + 1).padStart(2, "0")}`;
}

function barHeight(value: number, max: number): string {
  if (max <= 0 || value <= 0) {
    return "0%";
  }
  // Giữ tối thiểu 4% để cột có giá trị nhỏ vẫn nhìn thấy.
  return `${Math.max(4, (value / max) * 100)}%`;
}

interface SevenDayUsageChartProps {
  days: UsageDay[];
}

export function SevenDayUsageChart({ days }: SevenDayUsageChartProps) {
  const chartDays = [...days]
    .sort((left, right) => {
      const leftTime = new Date(left.date || left.day || "").getTime();
      const rightTime = new Date(right.date || right.day || "").getTime();
      return (Number.isNaN(leftTime) ? 0 : leftTime) - (Number.isNaN(rightTime) ? 0 : rightTime);
    })
    .slice(-7);

  const maxTokens = Math.max(0, ...chartDays.map((day) => numericValue(day.total_tokens)));
  const maxRequests = Math.max(0, ...chartDays.map((day) => numericValue(day.request_count)));

  const rangeLabel =
    chartDays.length > 0 ? `${shortDate(chartDays[0].date || chartDays[0].day)} - ${shortDate(chartDays[chartDays.length - 1].date || chartDays[chartDays.length - 1].day)}` : null;

  return (
    <div className="flex h-full flex-col rounded-lg border border-border bg-white p-5 shadow-sm">
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="font-mono text-xs uppercase tracking-[0.14em] text-muted-foreground">Lịch sử</p>
          <h2 className="mt-2 font-display text-2xl text-foreground">Thống kê 7 ngày gần nhất</h2>
        </div>
        {rangeLabel ? <span className="mt-1 shrink-0 text-xs font-semibold text-muted-foreground">{rangeLabel}</span> : null}
      </div>

      {chartDays.length === 0 ? (
        <div className="mt-6 grid flex-1 place-items-center rounded-lg border border-dashed border-border py-10 text-center text-sm text-muted-foreground">
          Chưa có dữ liệu thống kê theo ngày.
        </div>
      ) : (
        <>
          <div className="mt-6 flex flex-1 items-end justify-between gap-2">
            {chartDays.map((day, index) => {
              const tokens = numericValue(day.total_tokens);
              const requests = numericValue(day.request_count);
              return (
                <div key={`${day.date || day.day || index}`} className="group relative flex flex-1 flex-col items-center gap-2">
                  <div className="flex h-32 w-full items-end justify-center gap-1">
                    <div
                      className="w-2 rounded-t bg-accent transition-opacity group-hover:opacity-80"
                      style={{ height: barHeight(tokens, maxTokens) }}
                      aria-hidden
                    />
                    <div
                      className="w-2 rounded-t bg-accent-secondary transition-opacity group-hover:opacity-80"
                      style={{ height: barHeight(requests, maxRequests) }}
                      aria-hidden
                    />
                  </div>
                  <div className="text-center">
                    <p className="text-xs font-semibold text-foreground">{shortDate(day.date || day.day)}</p>
                    <p className="text-[10px] tabular-nums text-muted-foreground">{formatUsd(day.estimated_cost_usd)}</p>
                  </div>

                  <div className="pointer-events-none absolute bottom-full left-1/2 z-10 mb-2 hidden w-max -translate-x-1/2 rounded-lg border border-border bg-foreground px-3 py-2 text-xs text-white shadow-card group-hover:block">
                    <p className="font-semibold">{shortDate(day.date || day.day)}</p>
                    <p className="mt-1 text-white/80">Tokens: {formatNumber(tokens)}</p>
                    <p className="text-white/80">Requests: {formatNumber(requests)}</p>
                    <p className="text-white/80">Chi phí: {formatUsd(day.estimated_cost_usd)}</p>
                  </div>
                </div>
              );
            })}
          </div>

          <div className="mt-5 flex items-center gap-5 text-xs font-semibold text-muted-foreground">
            <span className="flex items-center gap-2">
              <span className="h-2.5 w-2.5 rounded-full bg-accent" aria-hidden />
              Tokens
            </span>
            <span className="flex items-center gap-2">
              <span className="h-2.5 w-2.5 rounded-full bg-accent-secondary" aria-hidden />
              Requests
            </span>
          </div>
        </>
      )}
    </div>
  );
}
