from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class RecentMessage(BaseModel):
    role: str
    content: str


class ConversationContext(BaseModel):
    memory_summary: str | None = None
    active_patient_id: str | None = None
    last_intent: str | None = None
    last_tool_name: str | None = None
    last_resource_type: str | None = None
    last_resource_id: str | None = None
    recent_messages: list[RecentMessage] = Field(default_factory=list)
    # Tổng số message của session TRƯỚC khi lưu message hiện tại (Spring đếm).
    # None => Spring cũ chưa gửi => không kích hoạt rolling summary.
    total_message_count: int | None = None


class ChatRequest(BaseModel):
    user_id: str = Field(min_length=1)
    user_role: Literal["USER", "DOCTOR", "ADMIN"]
    session_id: str | None = None
    message: str = Field(min_length=1)
    patient_id: str | None = None
    allowed_patient_ids: list[str] = Field(default_factory=list)
    patient_scope: str | None = None
    conversation_context: ConversationContext | None = None

    quota_used_ratio: float = Field(default=0.0, ge=0.0)

    # Virtual key LiteLLM cua user (do Spring cap). None => dung master key mac dinh.
    llm_key: str | None = None


ResponseStatus = Literal[
    "answered",
    "partial",
    "no_data",
    "clarification_needed",
    "patient_selection_needed",
    "tool_error",
]
AgentRoute = Literal["fhir", "general_chat", "conversation_meta", "unsupported", "cache"]


class PlanStepRef(BaseModel):
    id: str
    tool: str


class FailedStep(BaseModel):
    id: str
    tool: str
    error_code: str


class GroundingInfo(BaseModel):
    checked: bool
    # None khi không kiểm (câu template, nhánh không-FHIR, cache hit).
    passed: bool | None = None


class ChatResponse(BaseModel):
    """Contract của ``POST /chat`` cho Spring/FE.

    Chỉ khai báo các trường client cần dựa vào; trường khác (usage chi tiết, evidence,
    memory_update...) đi qua ``extra="allow"`` để thêm trường không làm vỡ contract.
    """

    model_config = ConfigDict(extra="allow")

    trace_id: str
    answer: str
    response_status: ResponseStatus
    agent_route: AgentRoute
    intent: str
    tool_name: str
    intent_source: str | None = None
    answer_source: str | None = None
    patient_id: str | None = None
    evidence: list[dict[str, Any]] = Field(default_factory=list)
    plan_steps: list[PlanStepRef] = Field(default_factory=list)
    failed_steps: list[FailedStep] = Field(default_factory=list)
    grounding: GroundingInfo | None = None
    stage_usage: dict[str, dict[str, int | float]] = Field(default_factory=dict)
