from typing import Literal

from pydantic import BaseModel, Field


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
