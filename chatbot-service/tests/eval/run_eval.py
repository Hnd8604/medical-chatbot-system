"""Bộ đo cho ``POST /chat``: chạy bộ câu hỏi có nhãn và in bảng độ chính xác
route/tool/status/nội dung, số LLM call, token, cost, latency p50/p95, tỉ lệ cache hit,
và độ chính xác theo từng nhóm câu hỏi (tag).

Nhãn của một case (``questions.jsonl``):

- ``expected_route`` / ``expected_tools`` / ``expected_status`` / ``expected_http_status``.
- ``must_include``: dữ kiện PHẢI có trong câu trả lời (so không dấu, "7,8" = "7.8");
  một mục có thể liệt kê nhiều cách viết, ngăn bằng ``|`` ("2026-02-02|02/02/2026").
- ``must_not_include``: dữ kiện KHÔNG được có (vd mã bệnh nhân khác trong câu injection).
- ``tags``: nhóm để báo cáo riêng (time, trend, cohort, emergency, injection...).
- ``turns``: hội thoại nhiều lượt; mỗi lượt có nhãn riêng, ngữ cảnh (tin nhắn gần đây,
  bệnh nhân đang nói tới, resource vừa nhắc) được mang sang lượt sau như Spring làm.

Ngày tương đối ("3 tháng qua") phụ thuộc ngày chạy eval, nên case kiểm nội dung dùng
mốc tuyệt đối ("tháng 1/2026", "năm 2025") khớp dữ liệu seed.

So sánh trước–sau (vd khi đổi prompt): lưu một lần chạy bằng ``--json`` rồi truyền nó
vào ``--baseline`` ở lần chạy sau.

Cách dùng (cần chatbot-service đang chạy và HAPI FHIR có dữ liệu):

```powershell
cd chatbot-service
python -m tests.eval.run_eval --json before.json
python -m tests.eval.run_eval --baseline before.json --verbose
```

Lưu ý: script này gọi LLM thật qua gateway nên **tốn tiền**. Chạy khi cần số liệu
báo cáo hoặc khi vừa đổi prompt, không đưa vào CI mặc định.

``--offline``: không cần stack, không tốn tiền. Chạy qua chính FastAPI app trong tiến
trình, FHIR giả nạp từ file seed (``tests/eval/seed_fhir.py``), KHÔNG có LLM (router và
planner bằng luật, câu trả lời template), không cache. Dùng làm mốc "chỉ luật" để so với
lần chạy có LLM, và để kiểm nhãn eval còn khớp dữ liệu seed sau khi sửa code.

```powershell
python -m tests.eval.run_eval --offline --verbose
```
"""

from __future__ import annotations

import argparse
import asyncio
import json
import re
import time
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


QUESTIONS_PATH = Path(__file__).with_name("questions.jsonl")
CHAT_PATH = "/chat"


@dataclass
class CaseResult:
    case_id: str
    ok_route: bool | None
    ok_tools: bool | None
    ok_status: bool | None
    http_status: int
    latency_ms: float
    input_tokens: int
    output_tokens: int
    cost_usd: float
    llm_calls: int
    cache_hit: bool
    answer_source: str
    detail: str = ""
    ok_content: bool | None = None
    tags: list[str] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return all(value is not False for value in (self.ok_route, self.ok_tools, self.ok_status, self.ok_content))


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
            "status_accuracy": self._rate([r.ok_status for r in self.results]),
            "content_accuracy": self._rate([r.ok_content for r in self.results]),
            "case_pass_rate": self._rate([r.passed for r in self.results]),
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

    def by_tag(self) -> dict[str, str]:
        tags = sorted({tag for result in self.results for tag in result.tags})
        return {tag: self._rate([r.passed for r in self.results if tag in r.tags]) for tag in tags}


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
    if case.get("patient_id"):
        payload["patient_id"] = case["patient_id"]
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


def score(case: dict[str, Any], payload: dict[str, Any]) -> tuple[bool | None, bool | None, bool | None]:
    """So kết quả với nhãn: route, tool (đủ tool cần thiết là đạt) và response_status."""
    expected_route = case.get("expected_route")
    ok_route = payload.get("agent_route") == expected_route if expected_route else None

    expected_tools = case.get("expected_tools")
    ok_tools: bool | None = None
    if expected_tools is not None:
        actual = {step.get("tool") for step in payload.get("plan_steps") or []}
        # Thừa step không bị phạt ở đây (đã có cột token).
        ok_tools = not actual if not expected_tools else set(expected_tools).issubset(actual)

    expected_status = case.get("expected_status")
    ok_status = payload.get("response_status") == expected_status if expected_status else None
    return ok_route, ok_tools, ok_status


def score_content(case: dict[str, Any], answer: str) -> tuple[bool | None, str]:
    """Kiểm dữ kiện trong câu trả lời. Trả (đạt?, mô tả dữ kiện thiếu/thừa)."""
    must = case.get("must_include") or []
    must_not = case.get("must_not_include") or []
    if not must and not must_not:
        return None, ""
    text = normalize_answer(answer)
    missing = [item for item in must if not any(normalize_answer(alt) in text for alt in item.split("|"))]
    leaked = [item for item in must_not if normalize_answer(item) in text]
    problems = [f"thiếu {item!r}" for item in missing] + [f"không được có {item!r}" for item in leaked]
    return not problems, "; ".join(problems)


def normalize_answer(text: str) -> str:
    """Không dấu, chữ thường, "7,8" → "7.8" (dấu thập phân kiểu Việt), gộp khoảng trắng."""
    value = unicodedata.normalize("NFD", text or "")
    value = "".join(char for char in value if unicodedata.category(char) != "Mn")
    value = value.replace("đ", "d").replace("Đ", "d").lower()
    value = re.sub(r"(?<=\d),(?=\d)", ".", value)
    return re.sub(r"\s+", " ", value)


def next_context(previous: dict[str, Any] | None, message: str, payload: dict[str, Any]) -> dict[str, Any]:
    """Ngữ cảnh cho lượt sau, theo cách Spring dựng ``conversation_context``."""
    context = dict(previous or {})
    messages = list(context.get("recent_messages") or [])
    messages += [{"role": "user", "content": message}, {"role": "assistant", "content": payload.get("answer") or ""}]
    context["recent_messages"] = messages[-8:]
    context["total_message_count"] = (context.get("total_message_count") or 0) + 2
    memory = payload.get("memory_update") or {}
    for key in ("active_patient_id", "last_resource_type", "last_resource_id"):
        if memory.get(key):
            context[key] = memory[key]
    if memory.get("summary"):
        context["memory_summary"] = memory["summary"]
    return context


def expand_cases(cases: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Số case được chấm: mỗi lượt của hội thoại tính là một case."""
    return [turn for case in cases for turn in (case.get("turns") or [case])]


def count_llm_calls(payload: dict[str, Any]) -> int:
    calls = payload.get("llm_calls")
    if isinstance(calls, list):
        return len(calls)
    stage_usage = payload.get("stage_usage") or {}
    return sum(1 for usage in stage_usage.values() if (usage or {}).get("input_tokens"))


async def run_cases(
    *,
    base_url: str,
    cases: list[dict[str, Any]],
    user_id: str,
    timeout: float,
    transport: Any = None,
) -> Report:
    import httpx

    report = Report(endpoint=CHAT_PATH)
    url = base_url.rstrip("/") + CHAT_PATH

    async with httpx.AsyncClient(timeout=timeout, transport=transport) as client:
        for case in cases:
            if case.get("turns"):
                await _run_conversation(client, url, case, user_id, report)
                continue
            await _run_case(client, url, case, user_id, report)
    return report


async def _run_conversation(client: Any, url: str, conversation: dict[str, Any], user_id: str, report: Report) -> None:
    context: dict[str, Any] | None = None
    for index, turn in enumerate(conversation["turns"], start=1):
        case = {
            "role": conversation.get("role", "DOCTOR"),
            "allowed_patient_ids": conversation.get("allowed_patient_ids", []),
            "patient_id": conversation.get("patient_id"),
            **turn,
            "id": f"{conversation['id']}#{index}",
            "tags": sorted(set(conversation.get("tags", [])) | set(turn.get("tags", [])) | {"multi_turn"}),
            "context": context,
        }
        payload = await _run_case(client, url, case, f"{user_id}-{conversation['id']}", report)
        context = next_context(context, turn["message"], payload or {})


async def _run_case(client: Any, url: str, case: dict[str, Any], user_id: str, report: Report) -> dict[str, Any] | None:
    """Chạy một case, ghi kết quả vào report; trả payload (để hội thoại mang ngữ cảnh sang lượt sau)."""
    tags = list(case.get("tags") or [])
    started = time.perf_counter()
    try:
        response = await client.post(url, json=build_request(case, user_id))
        latency_ms = (time.perf_counter() - started) * 1000
        status_code = response.status_code
        payload = response.json() if response.content else {}
    except Exception as exc:  # mạng hỏng cũng là một kết quả cần ghi lại
        report.results.append(_failed(case, 0, (time.perf_counter() - started) * 1000, "error", str(exc)[:120], tags))
        return None

    expected_http = case.get("expected_http_status")
    if expected_http:
        # Case chính sách: đúng nghĩa là bị chặn đúng mã.
        ok = status_code == expected_http
        result = _failed(case, status_code, latency_ms, "policy", "" if ok else f"mong đợi {expected_http}", tags)
        result.ok_route = result.ok_tools = result.ok_status = ok
        report.results.append(result)
        return payload if isinstance(payload, dict) else None

    if not isinstance(payload, dict) or status_code >= 400:
        report.results.append(_failed(case, status_code, latency_ms, "error", str(payload)[:120], tags))
        return None

    usage = payload.get("usage") or {}
    ok_route, ok_tools, ok_status = score(case, payload)
    ok_content, content_detail = score_content(case, payload.get("answer") or "")
    report.results.append(
        CaseResult(
            case_id=case["id"],
            ok_route=ok_route,
            ok_tools=ok_tools,
            ok_status=ok_status,
            http_status=status_code,
            latency_ms=latency_ms,
            input_tokens=int(usage.get("input_tokens") or 0),
            output_tokens=int(usage.get("output_tokens") or 0),
            cost_usd=float(usage.get("cost_usd") or 0),
            llm_calls=count_llm_calls(payload),
            cache_hit="cache" in (payload.get("answer_source") or ""),
            answer_source=payload.get("answer_source") or "",
            detail=content_detail,
            ok_content=ok_content,
            tags=tags,
        )
    )
    return payload


def _failed(case: dict[str, Any], status: int, latency_ms: float, source: str, detail: str, tags: list[str]) -> CaseResult:
    return CaseResult(
        case_id=case["id"],
        ok_route=False,
        ok_tools=False,
        ok_status=False,
        http_status=status,
        latency_ms=latency_ms,
        input_tokens=0,
        output_tokens=0,
        cost_usd=0.0,
        llm_calls=0,
        cache_hit=False,
        answer_source=source,
        detail=detail,
        ok_content=False if (case.get("must_include") or case.get("must_not_include")) else None,
        tags=tags,
    )


def print_report(report: Report, *, verbose: bool) -> None:
    print(f"\n=== {report.endpoint} ===")
    if verbose:
        print(
            f"{'case':<16}{'route':<7}{'tool':<7}{'status':<7}{'content':<8}"
            f"{'ms':>8}{'in':>7}{'out':>7}{'calls':>7}  source"
        )
        for r in report.results:
            print(
                f"{r.case_id:<16}"
                f"{_mark(r.ok_route):<7}{_mark(r.ok_tools):<7}{_mark(r.ok_status):<7}{_mark(r.ok_content):<8}"
                f"{r.latency_ms:>8.0f}{r.input_tokens:>7}{r.output_tokens:>7}{r.llm_calls:>7}"
                f"  {r.answer_source}{(' | ' + r.detail) if r.detail else ''}"
            )
    print("-" * 72)
    for key, value in report.summary().items():
        print(f"{key:<26}{value}")
    tag_rates = report.by_tag()
    if tag_rates:
        print("\n--- tỉ lệ case đạt theo nhóm ---")
        for tag, rate in tag_rates.items():
            print(f"{tag:<26}{rate}")


def _mark(value: bool | None) -> str:
    if value is None:
        return "-"
    return "OK" if value else "FAIL"


def offline_transport() -> Any:
    """ASGI transport tới FastAPI app với FHIR seed, không LLM, không Qdrant."""
    import httpx

    from agent.answer.generator import TemplateAnswerGenerator, get_answer_generator
    from agent.memory.summary import SummaryResult, get_summary_generator
    from app.config import get_settings
    from app.main import app
    from cache.semantic_cache import get_semantic_cache
    from fhir.client import get_fhir_client
    from tests.eval.seed_fhir import SeedFhirClient

    settings = get_settings()
    settings.litellm_master_key = None  # không gọi gateway: router/planner luật, câu template
    settings.enable_plan_cache = False  # không chạm Qdrant

    class _NoCache:
        def __init__(self) -> None:
            self.settings = settings

        async def get_cached_answer(self, **kwargs: Any) -> None:
            return None

        async def save_to_cache(self, **kwargs: Any) -> None:
            return None

    class _NoSummary:
        async def summarize(self, **kwargs: Any) -> SummaryResult:
            return SummaryResult()

    seed = SeedFhirClient()
    app.dependency_overrides[get_fhir_client] = lambda: seed
    app.dependency_overrides[get_answer_generator] = lambda: TemplateAnswerGenerator()
    app.dependency_overrides[get_semantic_cache] = lambda: _NoCache()
    app.dependency_overrides[get_summary_generator] = lambda: _NoSummary()
    return httpx.ASGITransport(app=app)


def main() -> None:
    # Console Windows mặc định là cp1252; nhãn và câu trả lời đều là tiếng Việt.
    try:
        import sys

        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    parser = argparse.ArgumentParser(description="Đo chất lượng và chi phí của POST /chat.")
    parser.add_argument("--base-url", default="http://localhost:8000")
    parser.add_argument("--user-id", default="eval-user")
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--questions", type=Path, default=QUESTIONS_PATH)
    parser.add_argument("--json", type=Path, help="Ghi kết quả chi tiết ra file JSON.")
    parser.add_argument("--baseline", type=Path, help="File JSON của một lần chạy trước để so sánh.")
    parser.add_argument("--verbose", action="store_true", help="In từng case.")
    parser.add_argument("--tag", action="append", help="Chỉ chạy case có tag này (lặp được).")
    parser.add_argument("--offline", action="store_true", help="Chạy trong tiến trình: FHIR từ seed, không LLM, không cache.")
    args = parser.parse_args()

    cases = load_cases(args.questions)
    if args.tag:
        wanted = set(args.tag)
        cases = [
            case for case in cases
            if wanted & (set(case.get("tags", [])) | {t for turn in case.get("turns", []) for t in turn.get("tags", [])})
        ]
    transport = offline_transport() if args.offline else None
    report = asyncio.run(
        run_cases(
            base_url="http://offline" if args.offline else args.base_url,
            cases=cases,
            user_id=args.user_id,
            timeout=args.timeout,
            transport=transport,
        )
    )
    if args.offline:
        report.endpoint = f"{CHAT_PATH} (offline: seed FHIR, không LLM)"
    print_report(report, verbose=args.verbose)
    summary = report.summary()

    if args.baseline:
        baseline = json.loads(args.baseline.read_text(encoding="utf-8"))["summary"]
        print(f"\n=== so sánh với {args.baseline.name} ===")
        for key in ("llm_calls_per_turn", "input_tokens_per_turn", "cost_usd_per_turn", "latency_p50_ms", "latency_p95_ms"):
            before, after = baseline.get(key), summary[key]
            delta = f"{(after - before) / before * 100:+.1f}%" if before else "n/a"
            print(f"{key:<26}{before!s:>12} -> {after!s:>12}   {delta}")

    if args.json:
        args.json.write_text(
            json.dumps(
                {"summary": summary, "by_tag": report.by_tag(), "results": [vars(c) for c in report.results]},
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )


if __name__ == "__main__":
    main()
