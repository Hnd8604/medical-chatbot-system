"""LLM planner — lập kế hoạch truy xuất FHIR nhiều bước.

Planner chỉ ĐỀ XUẤT. Mọi ràng buộc (allowlist, limit, quyền theo role) do
``agent.planning.validator`` quyết định.
"""

from __future__ import annotations

from typing import Any

from agent.planning.plan import Plan, plan_from_dict
from agent.prompts import PLANNER_SYSTEM_PROMPT_TEMPLATE
from agent.state import STAGE_PLANNER
from app.config import get_settings
from llm.client import call_llm, parse_json_object
from tools.registry import tool_catalog_for_prompt


async def create_plan(
    *,
    message: str,
    user_role: str,
    provided_patient_id: str | None,
    allowed_patient_ids: list[str],
    conversation_context: dict[str, Any] | None,
    resource_hint: str | None,
    model: str,
) -> tuple[Plan, dict[str, int | float]]:
    """Trả (plan, usage). Raise ``LlmCallError`` để node rơi về rule planner."""
    system_prompt = PLANNER_SYSTEM_PROMPT_TEMPLATE.format(
        tool_catalog=tool_catalog_for_prompt(),
        max_steps=get_settings().max_plan_steps,
    )
    result = await call_llm(
        stage=STAGE_PLANNER,
        model=model,
        system_prompt=system_prompt,
        user_payload={
            "message": message,
            "user_role": user_role,
            "provided_patient_id": provided_patient_id,
            "allowed_patient_ids": allowed_patient_ids,
            "resource_hint": resource_hint,
            "context": conversation_context or {},
        },
        max_output_tokens=500,
    )
    plan = plan_from_dict(parse_json_object(result.content, stage=STAGE_PLANNER), stage=STAGE_PLANNER)
    return plan, result.usage
