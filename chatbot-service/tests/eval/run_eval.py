"""Bộ đo trước–sau cho hai đường chat (M-LG6).

Chạy cùng một bộ câu hỏi có nhãn qua ``/chat`` và ``/chat/langgraph`` rồi in bảng:
độ chính xác route/tool, số LLM call, token, cost, latency p50/p95, tỉ lệ cache hit.
Không có bảng này thì mọi phát biểu "agent rẻ hơn / chính xác hơn" đều là cảm tính.

Cách dùng (cần chatbot-service đang chạy và HAPI FHIR có dữ liệu):

```powershell
cd chatbot-service
python -m tests.eval.run_eval --base-url http://localhost:8000 --endpoint both
python -m tests.eval.run_eval --endpoint langgraph --json out.json
```

Lưu ý: script này gọi LLM thật qua gateway nên **tốn tiền**. Chạy khi cần số liệu
báo cáo hoặc khi vừa đổi prompt, không đưa vào CI mặc định.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


QUESTIONS_PATH = Path(__file__).with_name("questions.jsonl")
ENDPOINTS = {"legacy": "/chat", "langgraph": "/chat/langgraph"}


@dataclass
class CaseResult:
    case_id: str
    ok_route: bool | None
    ok_tools: bool | None
    http_status: int
    latency_ms: float
    input_tokens: int
    output_tokens: int
    cost_usd: float
    llm_calls: int
    cache_hit: bool
    answer_source: str
    detail: str = ""


@dataclass
class Report:
    endpoint: str
    results: list[CaseResult] = field(default_factory=list)

    def _rate(self, values: list[bool | None]) -> str:
        scored = [value for value in values if value is not None]
        if not scored:
            return "n/a"
        return f"{100 * sum(scored) / len(scored):.1f}% ({sum(scored)}/{len(scored)})"

    def summary(self) -> dict[str, Any]:
        latencies = sorted(result.latency_ms for result in self.results)
        total = len(self.results) or 1
        return {
            "endpoint": self.endpoint,
            "cases": len(self.results),
            "route_accuracy": self._rate([r.ok_route for r in self.results]),
            "tool_accuracy": self._rate([r.ok_tools for r in self.results]),
            "errors": sum(1 for r in self.results if r.http_status >= 500),
            "llm_calls_per_turn": round(sum(r.llm_calls for r in self.results) / total, 2),
            "input_tokens_per_turn": round(sum(r.input_tokens for r in self.results) / total, 1),
            "output_tokens_per_turn": round(sum(r.output_tokens for r in self.results) / total, 1),
            "cost_usd_per_turn": round(sum(r.cost_usd for r in self.results) / total, 6),
            "cost_usd_total": round(sum(r.cost_usd for r in self.results), 6),
            "latency_p50_ms": round(_percentile(latencies, 50), 1),
            "latency_p95_ms": round(_percentile(latencies, 95), 1),
            "cache_hit_rate": f"{100 * sum(1 for r in self.results if r.cache_hit) / total:.1f}%",
        }


def _percentile(sorted_values: list[float], pct: int) -> float:
    if not sorted_values:
        return 0.0
    index = min(len(sorted_values) - 1, int(round((pct / 100) * (len(sorted_values) - 1))))
    return sorted_values[index]


def load_cases(path: Path = QUESTIONS_PATH) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("//"):
            cases.append(json.loads(line))
    return cases


def build_request(case: dict[str, Any], user_id: str) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "user_id": user_id,
        "user_role": case.get("role", "DOCTOR"),
        "message": case["message"],
        "allowed_patient_ids": case.get("allowed_patient_ids", []),
    }
    context = case.get("context")
    if context:
        payload["conversation_context"] = {
            "active_patient_id": context.get("active_patient_id"),
            "last_resource_type": context.get("last_resource_type"),
            "last_resource_id": context.get("last_resource_id"),
            "memory_summary": context.get("memory_summary"),
            "recent_messages": context.get("recent_messages", []),
            "total_message_count": context.get("total_message_count", 0),
        }
    return payload


def score(case: dict[str, Any], payload: dict[str, Any], endpoint: str) -> tuple[bool | None, bool | None]:
    """So kết quả với nhãn.

    ``/chat`` không có khái niệm route nên route_accuracy chỉ chấm cho đường agent;
    tool thì cả hai đều chấm được (legacy qua ``tool_name``, agent qua ``plan_steps``).
    """
    expected_route = case.get("expected_route")
    ok_route: bool | None = None
    if expected_route and endpoint == "langgraph":
        ok_route = payload.get("agent_route") == expected_route

    expected_tools = case.get("expected_tools")
    ok_tools: bool | None = None
    if expected_tools is not None:
        if endpoint == "langgraph":
            actual = {step.get("tool") for step in payload.get("plan_steps") or []}
        else:
            actual = {payload.get("tool_name")} - {None}
        if not expected_tools:
            ok_tools = not actual
        else:
            # Đủ tool cần thiết là đạt; thừa step không bị phạt ở đây (đã có cột token).
            ok_tools = set(expected_tools).issubset(actual)
    return ok_route, ok_tools


def count_llm_calls(payload: dict[str, Any], endpoint: str) -> int:
    if endpoint == "langgraph":
        stage_usage = payload.get("stage_usage") or {}
        calls = sum(1 for usage in stage_usage.values() if (usage or {}).get("input_tokens"))
        if (payload.get("answer_source") or "").startswith("llm"):
            calls += 0  # answer đã nằm trong stage_usage
        return calls
    calls = 0
    if (payload.get("intent_source") or "").startswith("llm"):
        calls += 1
    if (payload.get("answer_source") or "") == "llm":
        calls += 1
    if (payload.get("routing_source") or "") == "llm_router":
        calls += 1
    if (payload.get("summary_usage") or {}).get("input_tokens"):
        calls += 1
    return calls


async def run_endpoint(
    *,
    base_url: str,
    endpoint: str,
    cases: list[dict[str, Any]],
    user_id: str,
    timeout: float,
) -> Report:
    import httpx

    report = Report(endpoint=endpoint)
    url = base_url.rstrip("/") + ENDPOINTS[endpoint]

    async with httpx.AsyncClient(timeout=timeout) as client:
        for case in cases:
            started = time.perf_counter()
            try:
                response = await client.post(url, json=build_request(case, user_id))
                latency_ms = (time.perf_counter() - started) * 1000
                status_code = response.status_code
                payload = response.json() if response.content else {}
            except Exception as exc:  # mạng hỏng cũng là một kết quả cần ghi lại
                report.results.append(
                    CaseResult(
                        case_id=case["id"],
                        ok_route=False,
                        ok_tools=False,
                        http_status=0,
                        latency_ms=(time.perf_counter() - started) * 1000,
                        input_tokens=0,
                        output_tokens=0,
                        cost_usd=0.0,
                        llm_calls=0,
                        cache_hit=False,
                        answer_source="error",
                        detail=str(exc)[:120],
                    )
                )
                continue

            expected_status = case.get("expected_http_status")
            if expected_status:
                # Case chính sách: đúng nghĩa là bị chặn đúng mã.
                ok = status_code == expected_status
                report.results.append(
                    CaseResult(
                        case_id=case["id"],
                        ok_route=ok,
                        ok_tools=ok,
                        http_status=status_code,
                        latency_ms=latency_ms,
                        input_tokens=0,
                        output_tokens=0,
                        cost_usd=0.0,
                        llm_calls=0,
                        cache_hit=False,
                        answer_source="policy",
                        detail="" if ok else f"mong đợi {expected_status}",
                    )
                )
                continue

            if not isinstance(payload, dict) or status_code >= 400:
                report.results.append(
                    CaseResult(
                        case_id=case["id"],
                        ok_route=False,
                        ok_tools=False,
                        http_status=status_code,
                        latency_ms=latency_ms,
                        input_tokens=0,
                        output_tokens=0,
                        cost_usd=0.0,
                        llm_calls=0,
                        cache_hit=False,
                        answer_source="error",
                        detail=str(payload)[:120],
                    )
                )
                continue

            usage = payload.get("usage") or {}
            ok_route, ok_tools = score(case, payload, endpoint)
            report.results.append(
                CaseResult(
                    case_id=case["id"],
                    ok_route=ok_route,
                    ok_tools=ok_tools,
                    http_status=status_code,
                    latency_ms=latency_ms,
                    input_tokens=int(usage.get("input_tokens") or 0),
                    output_tokens=int(usage.get("output_tokens") or 0),
                    cost_usd=float(usage.get("estimated_cost_usd") or 0),
                    llm_calls=count_llm_calls(payload, endpoint),
                    cache_hit="cache" in (payload.get("answer_source") or ""),
                    answer_source=payload.get("answer_source") or "",
                )
            )
    return report


def print_report(report: Report, *, verbose: bool) -> None:
    print(f"\n=== {report.endpoint} ({ENDPOINTS[report.endpoint]}) ===")
    if verbose:
        print(f"{'case':<12}{'route':<7}{'tool':<7}{'ms':>8}{'in':>7}{'out':>7}{'calls':>7}  source")
        for r in report.results:
            print(
                f"{r.case_id:<12}"
                f"{_mark(r.ok_route):<7}{_mark(r.ok_tools):<7}"
                f"{r.latency_ms:>8.0f}{r.input_tokens:>7}{r.output_tokens:>7}{r.llm_calls:>7}"
                f"  {r.answer_source}{(' | ' + r.detail) if r.detail else ''}"
            )
    print("-" * 72)
    for key, value in report.summary().items():
        print(f"{key:<26}{value}")


def _mark(value: bool | None) -> str:
    if value is None:
        return "-"
    return "OK" if value else "FAIL"


def main() -> None:
    # Console Windows mặc định là cp1252; nhãn và câu trả lời đều là tiếng Việt.
    try:
        import sys

        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    parser = argparse.ArgumentParser(description="Đo before/after cho agent (M-LG6).")
    parser.add_argument("--base-url", default="http://localhost:8000")
    parser.add_argument("--endpoint", choices=["legacy", "langgraph", "both"], default="both")
    parser.add_argument("--user-id", default="eval-user")
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--questions", type=Path, default=QUESTIONS_PATH)
    parser.add_argument("--json", type=Path, help="Ghi kết quả chi tiết ra file JSON.")
    parser.add_argument("--verbose", action="store_true", help="In từng case.")
    args = parser.parse_args()

    cases = load_cases(args.questions)
    targets = ["legacy", "langgraph"] if args.endpoint == "both" else [args.endpoint]

    reports: list[Report] = []
    for endpoint in targets:
        report = asyncio.run(
            run_endpoint(
                base_url=args.base_url,
                endpoint=endpoint,
                cases=cases,
                user_id=f"{args.user_id}-{endpoint}",
                timeout=args.timeout,
            )
        )
        reports.append(report)
        print_report(report, verbose=args.verbose)

    if len(reports) == 2:
        print("\n=== so sánh (langgraph so với legacy) ===")
        legacy, agent = reports[0].summary(), reports[1].summary()
        for key in ("llm_calls_per_turn", "input_tokens_per_turn", "cost_usd_per_turn", "latency_p50_ms", "latency_p95_ms"):
            before, after = legacy[key], agent[key]
            delta = f"{(after - before) / before * 100:+.1f}%" if before else "n/a"
            print(f"{key:<26}{before:>12} -> {after:>12}   {delta}")

    if args.json:
        args.json.write_text(
            json.dumps(
                {
                    "summaries": [r.summary() for r in reports],
                    "results": {r.endpoint: [vars(c) for c in r.results] for r in reports},
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        print(f"\nĐã ghi {args.json}")


if __name__ == "__main__":
    main()
