"""State của agent graph.

Giữ đúng những key thật sự có node đọc/ghi. Bản tham chiếu mang ~35 key trong đó có
5–6 key chết từ đời graph trước; state càng to thì checkpoint (M-LG4) càng nặng và
càng dễ lọt dữ liệu bệnh nhân vào nơi không nên có.

Dependency theo request (FhirClient, AnswerGenerator, cache, summary task...) **không**
nằm trong state — chúng đi qua ``config["configurable"]["deps"]`` để state luôn
serialize được khi bật checkpointer.
"""

from __future__ import annotations

from typing import Any, TypedDict

from api.chat_schemas import ChatRequest


ROUTE_GENERAL_CHAT = "general_chat"
ROUTE_CONVERSATION_META = "conversation_meta"
ROUTE_FHIR = "fhir"
ROUTE_UNSUPPORTED = "unsupported"

RESPONSE_STATUS_ANSWERED = "answered"
RESPONSE_STATUS_NO_DATA = "no_data"
RESPONSE_STATUS_CLARIFICATION = "clarification_needed"
RESPONSE_STATUS_PATIENT_SELECTION = "patient_selection_needed"
RESPONSE_STATUS_TOOL_ERROR = "tool_error"

ANSWER_MODE_DATA_ONLY = "data_only"
ANSWER_MODE_EXPLAIN = "explain_with_external_knowledge"


class AgentState(TypedDict, total=False):
    # --- đầu vào đã chuẩn hoá ---
    request: ChatRequest
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
    route_source: str  # llm | route_cache | fallback | low_cost

    # --- planner ---
    plan_steps: list[dict[str, Any]]
    plan_answer_mode: str
    plan_source: str  # llm | plan_cache | rule_fallback
    plan_reason: str | None

    # --- executor ---
    response_status: str
    resolved_patient_id: str | None
    executed_steps: list[dict[str, Any]]

    # --- kế toán ---
    # {stage: {input_tokens, output_tokens, estimated_cost_usd}} — cho phép dashboard
    # tách "planner tốn bao nhiêu token so với answer".
    stage_usage: dict[str, dict[str, int | float]]

    # --- kết quả ---
    payload: dict[str, Any]
