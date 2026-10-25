"""Agent graph — điều phối một lượt chat.

```text
prepare ─ triage ──cấp cứu (luật) ────────────────────────────────────> END   (0 LLM call)
            │
            └─ cache_lookup ──hit─────────────────────────────────────> END   (0 LLM call)
                 │miss
                 └─ route ──┬─ general_chat ──────┐
                            ├─ conversation_meta ─┼──────────────────> END
                            ├─ unsupported ───────┘
                            └─ plan ──┬─ (không nhận ra) ─ clarify ──> END
                                      └─ validate ──┬─ (plan hỏng) ─ clarify > END
                                                    └─ execute ─ respond > END

(song song từ đầu lượt: summary_task — rolling summary, await ở bước finalize;
 chỉ chạy khi số tin chưa tóm tắt đạt ngưỡng)
```

- Triage cấp cứu (luật) đặt trước cả cache ⇒ hướng dẫn gọi 115 không bao giờ bị cache che.
- Semantic cache đặt trước router ⇒ cache hit tốn 0 LLM call.
- Rolling summary chạy song song cả lượt, không chặn response.
- Các step FHIR độc lập chạy song song.
- Mọi LLM call đi qua LiteLLM gateway (không trạng thái; cost từng call về Spring ghi sổ).
"""

from __future__ import annotations

import asyncio
import uuid
from typing import Any

from agent.answer.generator import AnswerGenerator
from agent.memory.context import patient_id_hint
from agent.memory.summary import SummaryGenerator, summary_context_for_turn
from agent.nodes.cache_lookup import cache_lookup
from agent.nodes.execute import execute
from agent.nodes.plan import has_plan, plan
from agent.nodes.prepare import prepare
from agent.nodes.respond import respond
from agent.nodes.route import route
from agent.nodes.simple import clarify, conversation_meta, general_chat, unsupported
from agent.nodes.triage import triage
from agent.nodes.validate import validate
from agent.prompts import (
    ANSWER_PROMPT_VERSION,
    CHAT_PROMPT_VERSION,
    PLANNER_PROMPT_VERSION,
    ROUTER_PROMPT_VERSION,
)
from agent.state import (
    ROUTE_CACHE,
    ROUTE_CONVERSATION_META,
    ROUTE_FHIR,
    ROUTE_GENERAL_CHAT,
    ROUTE_UNSUPPORTED,
    AgentDeps,
    AgentState,
)
from api.schemas import ChatRequest
from app.config import get_settings
from cache.semantic_cache import SemanticCacheService
from fhir.client import FhirClient
from llm.client import llm_unavailable_reason, recorded_calls, set_gateway_context, start_call_log


_compiled_graph = None


def build_graph():
    from langgraph.graph import END, StateGraph

    graph = StateGraph(AgentState)
    graph.add_node("prepare", prepare)
    graph.add_node("triage", triage)
    graph.add_node("cache_lookup", cache_lookup)
    graph.add_node("route", route)
    graph.add_node("general_chat", general_chat)
    graph.add_node("conversation_meta", conversation_meta)
    graph.add_node("unsupported", unsupported)
    graph.add_node("plan", plan)
    graph.add_node("clarify", clarify)
    graph.add_node("validate", validate)
    graph.add_node("execute", execute)
    graph.add_node("respond", respond)

    graph.set_entry_point("prepare")
    graph.add_edge("prepare", "triage")
    graph.add_conditional_edges(
        "triage",
        lambda state: "emergency" if state.get("emergency_kind") else "continue",
        {"emergency": END, "continue": "cache_lookup"},
    )
    graph.add_conditional_edges(
        "cache_lookup",
        lambda state: "hit" if state.get("cache_hit") else "miss",
        {"hit": END, "miss": "route"},
    )
    graph.add_conditional_edges(
        "route",
        lambda state: state.get("route") or ROUTE_FHIR,
        {
            ROUTE_GENERAL_CHAT: "general_chat",
            ROUTE_CONVERSATION_META: "conversation_meta",
            ROUTE_UNSUPPORTED: "unsupported",
            ROUTE_FHIR: "plan",
        },
    )
    graph.add_conditional_edges(
        "plan",
        lambda state: "plan" if has_plan(state) else "clarify",
        {"plan": "validate", "clarify": "clarify"},
    )
    for terminal in ("general_chat", "conversation_meta", "unsupported", "clarify"):
        graph.add_edge(terminal, END)
    graph.add_conditional_edges(
        "validate",
        lambda state: "clarify" if state.get("plan_error") else "execute",
        {"clarify": "clarify", "execute": "execute"},
    )
    graph.add_edge("execute", "respond")
    graph.add_edge("respond", END)
    return graph.compile()


def get_compiled_graph():
    global _compiled_graph
    if _compiled_graph is None:
        _compiled_graph = build_graph()
    return _compiled_graph


async def run_agent(
    request: ChatRequest,
    *,
    client: FhirClient,
    answer_generator: AnswerGenerator,
    cache_service: SemanticCacheService,
    summary_generator: SummaryGenerator,
) -> dict[str, Any]:
    """Chạy một lượt chat qua agent graph và trả payload cho Spring."""
    set_gateway_context(request.user_id)
    # Mở sổ call trước khi tạo summary task/graph để mọi call (kể cả task con) ghi chung.
    start_call_log()
    trace_id = uuid.uuid4().hex

    # Rolling summary chạy song song cả lượt; các nhánh finalize mới await. Chọn tin cần
    # tóm tắt theo mốc Spring gửi (xem summary_context_for_turn).
    summary_context = summary_context_for_turn(
        request.conversation_context,
        low_cost=get_settings().is_low_cost(request.quota_used_ratio),
    )
    total_message_count = request.conversation_context.total_message_count if request.conversation_context else None
    summary_task = None
    if summary_context is not None:
        summary_task = asyncio.create_task(
            summary_generator.summarize(
                conversation_context=summary_context,
                question=request.message,
                patient_id=patient_id_hint(request),
                total_message_count=total_message_count,
            )
        )
    deps = AgentDeps(
        client=client,
        answer_generator=answer_generator,
        cache_service=cache_service,
        summary_task=summary_task,
    )

    try:
        state = await get_compiled_graph().ainvoke(
            {"request": request, "trace_id": trace_id},
            config={"configurable": {"deps": deps, "thread_id": request.session_id or request.user_id}},
        )
    finally:
        deps.cancel_summary()

    payload = state.get("payload")
    if not isinstance(payload, dict):
        raise RuntimeError(f"Agent graph kết thúc mà không có payload (trace_id={trace_id}).")

    payload.setdefault("agent_route", state.get("route") or ROUTE_CACHE)
    payload["trace_id"] = trace_id
    payload["stage_usage"] = deps.stage_usage
    # Từng LLM call (stage, model, token, cost thật của gateway, call_id) — Spring lưu
    # thành llm_call_usage để báo cáo theo model/stage đúng.
    payload["llm_calls"] = recorded_calls()
    unavailable = llm_unavailable_reason()
    if unavailable:
        payload["llm_unavailable_reason"] = unavailable
    payload["prompt_versions"] = {
        "router": ROUTER_PROMPT_VERSION,
        "planner": PLANNER_PROMPT_VERSION,
        "chat": CHAT_PROMPT_VERSION,
        "answer": ANSWER_PROMPT_VERSION,
    }
    return payload
