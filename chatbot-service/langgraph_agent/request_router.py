"""LLM #1 — chọn route cấp cao, trước khi tốn model đắt cho planner.

Giảm chi phí bằng LRU cache: cùng một câu hỏi (kể cả khác dấu tiếng Việt) trong cùng
một *hình thái ngữ cảnh* thì tái dùng quyết định cũ, không gọi LLM lần hai. Khoá cache
có kèm chữ ký ngữ cảnh vì "bệnh nhân này đang dùng thuốc gì" phải được route khác nhau
tuỳ theo đã có bệnh nhân đang hoạt động hay chưa.
"""

from __future__ import annotations

import logging
from collections import OrderedDict
from dataclasses import dataclass
from typing import Any

from agents.intent.text_utils import normalize_text
from app.config import get_settings
from langgraph_agent.errors import AgentLlmError
from langgraph_agent.llm import (
    STAGE_ROUTER,
    LlmResult,
    call_llm,
    clean_text,
    parse_json_object,
    zero_usage,
)
from langgraph_agent.prompts import ROUTER_SYSTEM_PROMPT
from langgraph_agent.state import (
    ROUTE_CONVERSATION_META,
    ROUTE_FHIR,
    ROUTE_GENERAL_CHAT,
    ROUTE_UNSUPPORTED,
)


log = logging.getLogger(__name__)

SUPPORTED_ROUTES = frozenset(
    {ROUTE_GENERAL_CHAT, ROUTE_CONVERSATION_META, ROUTE_FHIR, ROUTE_UNSUPPORTED}
)
SUPPORTED_META_KINDS = frozenset({"summary", "last_answer", "current_context"})
SUPPORTED_RESOURCE_HINTS = frozenset(
    {"Patient", "Observation", "Condition", "Encounter", "MedicationRequest"}
)
SUPPORTED_SAFETY_FLAGS = frozenset(
    {
        "none",
        "medical_advice",
        "diagnosis_request",
        "treatment_request",
        "unauthorized_patient_access",
    }
)


@dataclass(frozen=True)
class RouteDecision:
    route: str
    meta_kind: str | None = None
    resource_hint: str | None = None
    safety_flag: str = "none"
    reason: str | None = None
    source: str = "llm"

    def as_dict(self) -> dict[str, Any]:
        return {
            "route": self.route,
            "meta_kind": self.meta_kind,
            "resource_hint": self.resource_hint,
            "safety_flag": self.safety_flag,
            "reason": self.reason,
            "source": self.source,
        }


# Route mặc định khi LLM lỗi: fhir là lựa chọn an toàn nhất vì planner + validator
# vẫn chặn quyền phía sau, còn general_chat/unsupported sẽ nuốt mất câu hỏi thật.
FALLBACK_DECISION = RouteDecision(
    route=ROUTE_FHIR,
    reason="Router LLM không khả dụng; mặc định về nhánh FHIR.",
    source="fallback",
)


class _RouteCache:
    """LRU nhỏ trong tiến trình cho quyết định route."""

    def __init__(self, max_size: int) -> None:
        self.max_size = max_size
        self._entries: OrderedDict[tuple, RouteDecision] = OrderedDict()

    def get(self, key: tuple) -> RouteDecision | None:
        if self.max_size <= 0:
            return None
        decision = self._entries.get(key)
        if decision is None:
            return None
        self._entries.move_to_end(key)
        return decision

    def put(self, key: tuple, decision: RouteDecision) -> None:
        if self.max_size <= 0:
            return
        self._entries[key] = decision
        self._entries.move_to_end(key)
        while len(self._entries) > self.max_size:
            self._entries.popitem(last=False)

    def clear(self) -> None:
        self._entries.clear()


_route_cache: _RouteCache | None = None


def _cache() -> _RouteCache:
    global _route_cache
    if _route_cache is None:
        _route_cache = _RouteCache(get_settings().agent_route_cache_size)
    return _route_cache


def reset_route_cache() -> None:
    """Dùng trong test và khi đổi prompt version."""
    global _route_cache
    _route_cache = None


def _cache_key(message: str, user_role: str, context: dict[str, Any] | None) -> tuple:
    context = context or {}
    # Chỉ đưa *hình thái* ngữ cảnh vào khoá, không đưa giá trị (tránh cache nở theo
    # số bệnh nhân và tránh giữ mã bệnh nhân trong bộ nhớ tiến trình).
    return (
        normalize_text(message),
        user_role,
        bool(context.get("active_patient_id")),
        bool(context.get("last_resource_type") and context.get("last_resource_id")),
    )


async def decide_route(
    *,
    message: str,
    user_role: str,
    provided_patient_id: str | None,
    conversation_context: dict[str, Any] | None,
    model: str,
) -> tuple[RouteDecision, dict[str, int | float]]:
    """Trả (quyết định, usage). Usage = 0 khi lấy từ cache hoặc khi fallback."""
    key = _cache_key(message, user_role, conversation_context)
    cached = _cache().get(key)
    if cached is not None:
        log.info("[AGENT ROUTE CACHE HIT] route=%s", cached.route)
        return RouteDecision(**{**cached.as_dict(), "source": "route_cache"}), zero_usage()

    payload = {
        "message": message,
        "normalized_message": normalize_text(message),
        "user_role": user_role,
        "provided_patient_id": provided_patient_id,
        "context": conversation_context or {},
    }
    result: LlmResult = await call_llm(
        stage=STAGE_ROUTER,
        model=model,
        system_prompt=ROUTER_SYSTEM_PROMPT,
        user_payload=payload,
        max_output_tokens=200,
    )
    decision = parse_route(result.content)
    _cache().put(key, decision)
    return decision, result.usage


def parse_route(content: str) -> RouteDecision:
    payload = parse_json_object(content, stage=STAGE_ROUTER)

    route = clean_text(payload.get("route"))
    # Bản tham chiếu từng có route "clarification_needed"; nó là response_status của
    # executor chứ không phải route, nên quy về fhir thay vì báo lỗi.
    if route == "clarification_needed":
        route = ROUTE_FHIR
    if route not in SUPPORTED_ROUTES:
        raise AgentLlmError(STAGE_ROUTER, f"Route không hợp lệ: {route!r}")

    meta_kind = clean_text(payload.get("meta_kind"))
    if route == ROUTE_CONVERSATION_META:
        if meta_kind not in SUPPORTED_META_KINDS:
            # Thiếu meta_kind không đáng để hỏng cả lượt; "current_context" là mặc
            # định an toàn nhất vì nó chỉ đọc lại ngữ cảnh đang có.
            meta_kind = "current_context"
    else:
        meta_kind = None

    resource_hint = clean_text(payload.get("resource_hint"))
    if resource_hint not in SUPPORTED_RESOURCE_HINTS:
        resource_hint = None

    safety_flag = clean_text(payload.get("safety_flag")) or "none"
    if safety_flag not in SUPPORTED_SAFETY_FLAGS:
        safety_flag = "none"

    return RouteDecision(
        route=route,
        meta_kind=meta_kind,
        resource_hint=resource_hint,
        safety_flag=safety_flag,
        reason=clean_text(payload.get("reason")),
    )
