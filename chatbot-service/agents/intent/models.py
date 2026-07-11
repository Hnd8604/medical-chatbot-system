from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol

from agents.intent.constants import TOOL_TO_INTENT


@dataclass(frozen=True)
class IntentPlan:
    tool_name: str
    patient_id: str | None = None
    resource_type: str | None = None
    resource_id: str | None = None
    search_name: str | None = None
    search_phone: str | None = None
    search_birth_date: str | None = None
    search_identifier: str | None = None
    observation_type: str | None = None
    limit: int = 5
    all_patients: bool = False
    # explain=True khi câu hỏi mang ý "giải thích/ý nghĩa/công dụng" của dữ liệu y khoa
    # -> bật terminology enrichment. term/code cho intent explain_concept (khái niệm thuần).
    explain: bool = False
    term: str | None = None
    code: str | None = None
    reason: str | None = None
    source: str = "rules"
    usage: dict[str, int | float] = field(default_factory=lambda: {
        "input_tokens": 0,
        "output_tokens": 0,
        "estimated_cost_usd": 0,
    })

    @property
    def intent(self) -> str:
        return TOOL_TO_INTENT.get(self.tool_name, "unknown")


class IntentExtractor(Protocol):
    async def extract(
        self,
        message: str,
        provided_patient_id: str | None = None,
        conversation_context: dict[str, Any] | None = None,
    ) -> IntentPlan:
        ...
