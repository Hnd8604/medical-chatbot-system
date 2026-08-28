"""Agent graph — điều phối cả lượt chat.

```text
prepare ─ cache_lookup ──hit──> END                (0 LLM call)
             │miss
             └─ route ──┬─ general_chat ──┐
                        ├─ conversation_meta ─┤
                        ├─ unsupported ──┤
                        └─ plan ─ validate ─ execute ─ finalize_fhir
                                                       │
                        finalize_simple ◄──────────────┘(nhánh trái)
                                   └─ END

(chạy song song từ prepare: summary_task — rolling summary, await ở bước finalize)
```

Bốn khác biệt cốt lõi so với ``Medical-Chatbot-develop``:

1. Semantic cache **được nối thật** và đặt trước router → cache hit tốn 0 LLM call.
2. Rolling summary chạy **song song** cả lượt, không chặn response.
3. Các step FHIR độc lập chạy **song song**.
4. Mọi LLM call đi qua **LiteLLM gateway** (virtual key + budget + spend log theo stage).
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from typing import Any

from langchain_core.runnables import RunnableConfig

from agents.answer_generator import AnswerGenerator, combine_usage
from agents.context_payload import compact_conversation_for_llm
from agents.gateway_context import GatewayBudgetExceededError, set_gateway_context
from agents.intent.models import IntentPlan
from agents.intent.patient_utils import normalize_patient_id, resolve_explicit_patient_id
from agents.intent_extractor import IntentExtractor
from agents.model_router import ModelRouter
from agents.summary_generator import SummaryGenerator, SummaryResult
from api.chat_schemas import ChatRequest
from app.config import get_settings
from chat.cache_flow import get_cached_chat_payload
from chat.context_memory import _patient_id_hint
from chat.response_builder import current_user_context
from fhir.client import FhirClient
from langgraph_agent.errors import AgentError, AgentLlmError, PlanError
from langgraph_agent.finalize import finalize_fhir_payload, finalize_simple_payload
from langgraph_agent.llm import STAGE_CHAT, STAGE_PLANNER, STAGE_ROUTER, zero_usage
from langgraph_agent.plan_cache import get_plan_cache
from langgraph_agent.plan_executor import execute_plan
from langgraph_agent.plan_validator import validate_plan
from langgraph_agent.planner import Plan, create_plan, from_intent_plan
from langgraph_agent.prompts import (
    CHAT_PROMPT_VERSION,
    PLANNER_PROMPT_VERSION,
    ROUTER_PROMPT_VERSION,
)
from langgraph_agent.request_router import FALLBACK_DECISION, RouteDecision, decide_route
from langgraph_agent.simple_answers import (
    build_conversation_meta_payload,
    build_general_chat_payload,
    build_unsupported_payload,
)
from langgraph_agent.state import (
    ANSWER_MODE_DATA_ONLY,
    ROUTE_CONVERSATION_META,
    ROUTE_FHIR,
    ROUTE_GENERAL_CHAT,
    ROUTE_UNSUPPORTED,
    AgentState,
)
from services.semantic_cache import SemanticCacheService


log = logging.getLogger(__name__)

STAGE_ANSWER = "agent_answer"
STAGE_SUMMARY = "agent_summary"
# Model router của M16 (chọn model cho answer) — khác với request router của agent.
STAGE_MODEL_ROUTER = "agent_model_router"


@dataclass
class AgentDeps:
    """Phụ thuộc theo request. Ở ngoài state để state luôn serialize được (M-LG4)."""

    client: FhirClient
    answer_generator: AnswerGenerator
    cache_service: SemanticCacheService
    model_router: ModelRouter
    summary_generator: SummaryGenerator
    intent_extractor: IntentExtractor
    summary_task: "asyncio.Task[SummaryResult] | None" = None
    stage_usage: dict[str, dict[str, int | float]] = field(default_factory=dict)

    def record(self, stage: str, usage: dict[str, int | float] | None) -> None:
        if not usage:
            return
        current = self.stage_usage.get(stage) or zero_usage()
        self.stage_usage[stage] = combine_usage(current, usage)


def _deps(config: RunnableConfig) -> AgentDeps:
    return config["configurable"]["deps"]


class AgentGraph:
    def __init__(self) -> None:
        self._compiled = None

    def compiled(self):
        if self._compiled is None:
            self._compiled = self._build()
        return self._compiled

    def _build(self):
        from langgraph.graph import END, StateGraph

        graph = StateGraph(AgentState)
        graph.add_node("prepare", self._prepare)
        graph.add_node("cache_lookup", self._cache_lookup)
        graph.add_node("route", self._route)
        graph.add_node("general_chat", self._general_chat)
        graph.add_node("conversation_meta", self._conversation_meta)
        graph.add_node("unsupported", self._unsupported)
        graph.add_node("plan", self._plan)
        graph.add_node("validate", self._validate)
        graph.add_node("execute", self._execute)

        graph.set_entry_point("prepare")
        graph.add_edge("prepare", "cache_lookup")
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
        graph.add_edge("general_chat", END)
        graph.add_edge("conversation_meta", END)
        graph.add_edge("unsupported", END)
        graph.add_edge("plan", "validate")
        graph.add_edge("validate", "execute")
        graph.add_edge("execute", END)
        return graph.compile()

    # ------------------------------------------------------------------ nodes

    async def _prepare(self, state: AgentState, config: RunnableConfig) -> dict[str, Any]:
        request: ChatRequest = state["request"]
        settings = get_settings()

        allowed: list[str] = []
        for patient_id in request.allowed_patient_ids:
            value = normalize_patient_id(patient_id)
            if value and value not in allowed:
                allowed.append(value)

        conversation_context = compact_conversation_for_llm(request.conversation_context)
        provided = (
            normalize_patient_id(request.patient_id)
            or resolve_explicit_patient_id(request.message)
            or _patient_id_hint(request)
            or (allowed[0] if request.user_role == "USER" and allowed else None)
        )
        return {
            "allowed_patient_ids": allowed,
            "provided_patient_id": provided,
            "conversation_context": conversation_context,
            "low_cost_mode": request.quota_used_ratio >= settings.agent_low_cost_mode_ratio,
            "stage_usage": {},
        }

    async def _cache_lookup(self, state: AgentState, config: RunnableConfig) -> dict[str, Any]:
        deps = _deps(config)
        try:
            cached = await get_cached_chat_payload(state["request"], deps.cache_service)
        except Exception:
            log.exception("Agent cache lookup lỗi; coi như miss")
            return {"cache_hit": False}
        if not cached:
            return {"cache_hit": False}

        # Cache hit thì rolling summary cũng không cần chạy: mục tiêu của hit là ~0 chi phí.
        if deps.summary_task and not deps.summary_task.done():
            deps.summary_task.cancel()
        cached["response_status"] = "answered"
        cached["agent_route"] = "cache"
        return {"cache_hit": True, "payload": cached, "response_status": "answered"}

    async def _route(self, state: AgentState, config: RunnableConfig) -> dict[str, Any]:
        deps = _deps(config)
        request: ChatRequest = state["request"]
        settings = get_settings()

        # 1) Plan cache: hit ở đây bỏ được CẢ router lẫn planner.
        cached_plan: Plan | None = None
        decision: RouteDecision | None = None
        cached = await get_plan_cache().lookup(
            question=request.message,
            user_role=request.user_role,
            conversation_context=state.get("conversation_context"),
        )
        if cached is not None:
            decision, cached_plan = cached

        # 2) Low-cost mode: quota gần cạn thì không tiêu LLM cho việc định tuyến.
        if decision is None and state.get("low_cost_mode"):
            decision = RouteDecision(
                route=ROUTE_FHIR,
                reason="Low-cost mode: bỏ router LLM.",
                source="low_cost",
            )

        usage = zero_usage()
        if decision is None:
            try:
                decision, usage = await decide_route(
                    message=request.message,
                    user_role=request.user_role,
                    provided_patient_id=state.get("provided_patient_id"),
                    conversation_context=state.get("conversation_context"),
                    model=settings.agent_route_model,
                )
            except AgentLlmError as exc:
                log.info("Router LLM lỗi, dùng route mặc định: %s", exc)
                decision = FALLBACK_DECISION

        deps.record(STAGE_ROUTER, usage)
        update: dict[str, Any] = {
            "route": decision.route,
            "route_meta_kind": decision.meta_kind,
            "route_resource_hint": decision.resource_hint,
            "route_safety_flag": decision.safety_flag,
            "route_reason": decision.reason,
            "route_source": decision.source,
        }
        if cached_plan is not None:
            update["plan_steps"] = [step.as_dict() for step in cached_plan.steps]
            update["plan_answer_mode"] = cached_plan.answer_mode
            update["plan_source"] = "plan_cache"
        return update

    async def _plan(self, state: AgentState, config: RunnableConfig) -> dict[str, Any]:
        deps = _deps(config)
        request: ChatRequest = state["request"]
        settings = get_settings()

        # Plan cache đã cấp plan ở node route -> không gọi planner LLM.
        if state.get("plan_source") == "plan_cache" and state.get("plan_steps"):
            return {}

        if state.get("low_cost_mode"):
            return await self._rule_fallback_plan_async(state, deps, reason="low_cost_mode")

        try:
            plan, usage = await create_plan(
                message=request.message,
                user_role=request.user_role,
                provided_patient_id=state.get("provided_patient_id"),
                allowed_patient_ids=state.get("allowed_patient_ids") or [],
                conversation_context=state.get("conversation_context"),
                resource_hint=state.get("route_resource_hint"),
                model=settings.agent_planner_model,
            )
        except AgentLlmError as exc:
            log.info("Planner LLM lỗi, dùng rule-based extractor: %s", exc)
            return await self._rule_fallback_plan_async(state, deps, reason=str(exc))

        deps.record(STAGE_PLANNER, usage)
        return {
            "plan_steps": [step.as_dict() for step in plan.steps],
            "plan_answer_mode": plan.answer_mode,
            "plan_source": plan.source,
            "plan_reason": plan.reason,
        }

    async def _rule_fallback_plan_async(
        self,
        state: AgentState,
        deps: AgentDeps,
        *,
        reason: str,
    ) -> dict[str, Any]:
        """Đường lui của planner: dùng lại chính rule-based extractor của ``/chat``."""
        request: ChatRequest = state["request"]
        intent_plan: IntentPlan = await deps.intent_extractor.extract(
            request.message,
            provided_patient_id=state.get("provided_patient_id"),
            conversation_context=state.get("conversation_context"),
        )
        plan = from_intent_plan(intent_plan)
        return {
            "plan_steps": [step.as_dict() for step in plan.steps],
            "plan_answer_mode": plan.answer_mode,
            "plan_source": plan.source,
            "plan_reason": reason,
        }

    async def _validate(self, state: AgentState, config: RunnableConfig) -> dict[str, Any]:
        request: ChatRequest = state["request"]
        plan = _plan_from_state(state)
        validated = validate_plan(
            plan,
            message=request.message,
            user_role=request.user_role,
            allowed_patient_ids=state.get("allowed_patient_ids") or [],
            provided_patient_id=state.get("provided_patient_id"),
        )
        return {
            "plan_steps": [step.as_dict() for step in validated.steps],
            "plan_answer_mode": validated.answer_mode,
        }

    async def _execute(self, state: AgentState, config: RunnableConfig) -> dict[str, Any]:
        deps = _deps(config)
        request: ChatRequest = state["request"]
        plan = _plan_from_state(state)
        planner_usage = deps.stage_usage.get(STAGE_PLANNER) or zero_usage()

        result = await execute_plan(
            plan,
            client=deps.client,
            message=request.message,
            user_role=request.user_role,
            allowed_patient_ids=state.get("allowed_patient_ids") or [],
            provided_patient_id=state.get("provided_patient_id"),
            plan_usage=planner_usage,
        )

        # Router chọn model cho answer (M16) — tách stage riêng với request router của
        # agent để dashboard không gộp nhầm hai loại chi phí định tuyến.
        routing = await deps.model_router.route(request.message, request.quota_used_ratio)
        deps.record(STAGE_MODEL_ROUTER, routing.usage)

        payload = await finalize_fhir_payload(
            result.payload,
            question=request.message,
            plan=result.plan,
            answer_generator=deps.answer_generator,
            model=routing.model,
            query_complexity=routing.complexity.value,
            routing_source=routing.source,
            router_usage=combine_usage(
                deps.stage_usage.get(STAGE_ROUTER),
                deps.stage_usage.get(STAGE_MODEL_ROUTER),
            ),
            summary_task=deps.summary_task,
            conversation_context=state.get("conversation_context"),
            answer_mode=plan.answer_mode,
            step_count=len(plan.steps),
        )
        deps.record(STAGE_ANSWER, payload.get("answer_usage"))
        deps.record(STAGE_SUMMARY, payload.get("summary_usage"))

        payload["response_status"] = result.response_status
        payload["plan_steps"] = result.executed_steps

        # Chỉ cache plan khi lượt này thật sự chạy được — không nhân bản plan hỏng.
        if state.get("plan_source") == "llm" and result.response_status in {"answered", "no_data"}:
            await get_plan_cache().save(
                question=request.message,
                user_role=request.user_role,
                conversation_context=state.get("conversation_context"),
                decision=_decision_from_state(state),
                plan=plan,
            )

        return {
            "payload": payload,
            "response_status": result.response_status,
            "resolved_patient_id": result.resolved_patient_id,
            "executed_steps": result.executed_steps,
        }

    async def _general_chat(self, state: AgentState, config: RunnableConfig) -> dict[str, Any]:
        payload, usage = await build_general_chat_payload(
            message=state["request"].message,
            model=get_settings().agent_chat_model,
        )
        return await self._finish_simple(state, config, payload, usage, tool_name="general_chat")

    async def _unsupported(self, state: AgentState, config: RunnableConfig) -> dict[str, Any]:
        payload, usage = await build_unsupported_payload(
            message=state["request"].message,
            model=get_settings().agent_chat_model,
            safety_flag=state.get("route_safety_flag") or "none",
        )
        return await self._finish_simple(state, config, payload, usage, tool_name="unsupported_question")

    async def _conversation_meta(self, state: AgentState, config: RunnableConfig) -> dict[str, Any]:
        context = state.get("conversation_context") or {}
        payload, usage = await build_conversation_meta_payload(
            message=state["request"].message,
            meta_kind=state.get("route_meta_kind"),
            conversation_context=context,
            active_patient_id=context.get("active_patient_id") or state.get("provided_patient_id"),
            model=get_settings().agent_chat_model,
        )
        return await self._finish_simple(state, config, payload, usage, tool_name="conversation_context")

    async def _finish_simple(
        self,
        state: AgentState,
        config: RunnableConfig,
        payload: dict[str, Any],
        usage: dict[str, int | float],
        *,
        tool_name: str,
    ) -> dict[str, Any]:
        deps = _deps(config)
        deps.record(STAGE_CHAT, usage)
        plan = IntentPlan(
            tool_name=tool_name,
            patient_id=payload.get("patient_id"),
            source=f"langgraph_{state.get('route_source') or 'llm'}",
            usage=deps.stage_usage.get(STAGE_ROUTER) or zero_usage(),
        )
        finalized = await finalize_simple_payload(
            payload,
            plan=plan,
            model=get_settings().agent_chat_model,
            query_complexity="simple",
            routing_source=state.get("route_source") or "llm",
            usages=[deps.stage_usage.get(STAGE_ROUTER), usage],
            summary_task=deps.summary_task,
        )
        deps.record(STAGE_SUMMARY, finalized.get("summary_usage"))
        finalized["response_status"] = "answered"
        return {"payload": finalized, "response_status": "answered"}


def _plan_from_state(state: AgentState) -> Plan:
    from langgraph_agent.planner import PlanStep

    steps = tuple(
        PlanStep(id=str(item.get("id") or "step"), tool=str(item.get("tool")), args=dict(item.get("args") or {}))
        for item in (state.get("plan_steps") or [])
        if item.get("tool")
    )
    if not steps:
        raise PlanError("Không lập được kế hoạch FHIR hợp lệ cho câu hỏi này.")
    return Plan(
        steps=steps,
        answer_mode=state.get("plan_answer_mode") or ANSWER_MODE_DATA_ONLY,
        reason=state.get("plan_reason"),
        source=state.get("plan_source") or "llm",
    )


def _decision_from_state(state: AgentState) -> RouteDecision:
    return RouteDecision(
        route=state.get("route") or ROUTE_FHIR,
        meta_kind=state.get("route_meta_kind"),
        resource_hint=state.get("route_resource_hint"),
        safety_flag=state.get("route_safety_flag") or "none",
        reason=state.get("route_reason"),
        source=state.get("route_source") or "llm",
    )


_graph: AgentGraph | None = None


def get_agent_graph() -> AgentGraph:
    global _graph
    if _graph is None:
        _graph = AgentGraph()
    return _graph


async def run_agent(
    request: ChatRequest,
    *,
    client: FhirClient,
    answer_generator: AnswerGenerator,
    cache_service: SemanticCacheService,
    model_router: ModelRouter,
    summary_generator: SummaryGenerator,
    intent_extractor: IntentExtractor,
) -> dict[str, Any]:
    """Chạy một lượt chat qua agent graph và trả payload cho Spring."""
    current_user_context.set(request.user_id)
    set_gateway_context(request.llm_key, request.user_id)

    conversation_context = compact_conversation_for_llm(request.conversation_context)
    total_message_count = (
        request.conversation_context.total_message_count if request.conversation_context else None
    )
    # Rolling summary chạy song song cả lượt; các nhánh finalize mới await.
    summary_task = asyncio.create_task(
        summary_generator.summarize(
            conversation_context=conversation_context,
            question=request.message,
            patient_id=_patient_id_hint(request),
            total_message_count=total_message_count,
        )
    )
    deps = AgentDeps(
        client=client,
        answer_generator=answer_generator,
        cache_service=cache_service,
        model_router=model_router,
        summary_generator=summary_generator,
        intent_extractor=intent_extractor,
        summary_task=summary_task,
    )

    try:
        state = await get_agent_graph().compiled().ainvoke(
            {"request": request},
            config={"configurable": {"deps": deps, "thread_id": request.session_id or request.user_id}},
        )
    except BaseException:
        if not summary_task.done():
            summary_task.cancel()
        raise

    payload = state.get("payload")
    if not isinstance(payload, dict):
        raise PlanError("Agent không tạo được phản hồi hợp lệ.")

    payload.setdefault("agent_route", state.get("route") or "cache")
    payload["stage_usage"] = deps.stage_usage
    payload["prompt_versions"] = {
        "router": ROUTER_PROMPT_VERSION,
        "planner": PLANNER_PROMPT_VERSION,
        "chat": CHAT_PROMPT_VERSION,
    }
    return payload


__all__ = [
    "AgentDeps",
    "AgentError",
    "GatewayBudgetExceededError",
    "get_agent_graph",
    "run_agent",
]
