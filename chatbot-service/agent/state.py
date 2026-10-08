"""State và dependency theo request của agent graph.

State chỉ giữ những key thật sự có node đọc/ghi và luôn serialize được. Dependency theo
request (FhirClient, AnswerGenerator, cache, summary task...) **không** nằm trong state —
chúng đi qua ``config["configurable"]["deps"]``.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, TypedDict

from api.schemas import ChatRequest
from llm.usage import combine_usage, zero_usage

if TYPE_CHECKING:
    from agent.answer.generator import AnswerGenerator
    from agent.memory.summary import SummaryGenerator, SummaryResult
    from cache.semantic_cache import SemanticCacheService
    from fhir.client import FhirClient


ROUTE_GENERAL_CHAT = "general_chat"
ROUTE_CONVERSATION_META = "conversation_meta"
ROUTE_FHIR = "fhir"
ROUTE_UNSUPPORTED = "unsupported"
ROUTE_CACHE = "cache"

RESPONSE_STATUS_ANSWERED = "answered"
RESPONSE_STATUS_PARTIAL = "partial"
RESPONSE_STATUS_NO_DATA = "no_data"
RESPONSE_STATUS_CLARIFICATION = "clarification_needed"
RESPONSE_STATUS_PATIENT_SELECTION = "patient_selection_needed"
RESPONSE_STATUS_TOOL_ERROR = "tool_error"

ANSWER_MODE_DATA_ONLY = "data_only"
ANSWER_MODE_EXPLAIN = "explain_with_external_knowledge"

# Nhãn stage: dùng cho stage_usage trong response và metadata.stage ở spend log gateway.
STAGE_ROUTER = "agent_router"
STAGE_PLANNER = "agent_planner"
STAGE_CHAT = "agent_chat"
STAGE_ANSWER = "agent_answer"
STAGE_SUMMARY = "agent_summary"


class AgentState(TypedDict, total=False):
    # --- đầu vào đã chuẩn hoá ---
    request: ChatRequest
    trace_id: str
    allowed_patient_ids: list[str]
    provided_patient_id: str | None
    conversation_context: dict[str, Any] | None
    low_cost_mode: bool

    # --- cache ---
    cache_hit: bool

    # --- router ---
    route: str
    route_meta_kind: str | None
    route_resource_hint: str | None
    route_safety_flag: str
    route_reason: str | None
    route_source: str  # llm | route_cache | plan_cache | fallback | low_cost

    # --- planner ---
    plan_steps: list[dict[str, Any]]
    plan_answer_mode: str
    plan_source: str  # llm | plan_cache | rule_fallback
    plan_reason: str | None
    plan_error: str | None

    # --- executor ---
    response_status: str
    resolved_patient_id: str | None
    executed_steps: list[dict[str, Any]]
    failed_steps: list[dict[str, Any]]

    # --- kết quả ---
    payload: dict[str, Any]


@dataclass
class AgentDeps:
    """Phụ thuộc theo request. Ở ngoài state để state luôn serialize được."""

    client: "FhirClient"
    answer_generator: "AnswerGenerator"
    cache_service: "SemanticCacheService"
    summary_task: "asyncio.Task[SummaryResult] | None" = None
    # {stage: {input_tokens, output_tokens, estimated_cost_usd}} — cho phép dashboard
    # tách "planner tốn bao nhiêu token so với answer".
    stage_usage: dict[str, dict[str, int | float]] = field(default_factory=dict)

    def record(self, stage: str, usage: dict[str, int | float] | None) -> None:
        # Stage không tốn token (cache, fallback, template) thì không ghi: stage_usage
        # chỉ liệt kê các LLM call thật sự xảy ra.
        if not usage or not (usage.get("input_tokens") or usage.get("output_tokens")):
            return
        current = self.stage_usage.get(stage) or zero_usage()
        self.stage_usage[stage] = combine_usage(current, usage)

    def usage_of(self, *stages: str) -> dict[str, int | float]:
        return combine_usage(*(self.stage_usage.get(stage) for stage in stages))

    def cancel_summary(self) -> None:
        if self.summary_task and not self.summary_task.done():
            self.summary_task.cancel()


def get_deps(config: Any) -> AgentDeps:
    return config["configurable"]["deps"]
