from __future__ import annotations

import json
from typing import Any

from agents.intent.models import IntentPlan
from agents.intent.guardrails import (
    enforce_patient_list_routing,
    apply_all_patient_scope,
    apply_patient_search_criteria_hint,
    apply_patient_id_hint,
    enforce_contact_detail_routing,
    add_observation_type_hint,
)
from agents.intent.fhir_tools import FHIR_TOOL_DEFINITIONS
from agents.intent.rule_extractor import RuleBasedIntentExtractor
from agents.intent.text_utils import normalize_text
from agents.intent.constants import (
    TOOL_TO_INTENT,
    TOOL_UNSUPPORTED,
    TOOL_GET_MEDICATIONS,
    TOOL_GET_CONDITIONS,
    TOOL_GET_OBSERVATIONS,
    TOOL_GET_ENCOUNTERS,
    DEFAULT_PATIENT_ID,
)
from agents.intent.text_utils import string_or_none, clamp
from agents.intent.patient_utils import normalize_patient_id
from agents.intent.text_utils import normalize_phone
from agents.intent.text_utils import normalize_birth_date

_LLM_CONFIDENCE = 0.92


class OpenAIIntentExtractor:
    def __init__(self, api_key: str, model: str, timeout_seconds: float, base_url: str | None = None) -> None:
        from openai import AsyncOpenAI

        self.client = AsyncOpenAI(api_key=api_key, base_url=base_url, timeout=timeout_seconds)
        self.model = model
        self.fallback = RuleBasedIntentExtractor()

    async def extract(self, message: str, provided_patient_id: str | None = None) -> IntentPlan:
        patient_id_hint = normalize_patient_id(provided_patient_id)
        system_prompt = (
            "You extract the user's intent for a medical chatbot. "
            "The product is for Vietnamese users, so Vietnamese medical wording is expected. "
            "Select exactly one tool. Use FHIR tools for structured patient data. "
            "Do not answer the medical question. Do not invent patient data. "
            f"If the user did not provide a patient id, use {DEFAULT_PATIENT_ID} for local demo. "
            "Vietnamese 'benh nhan' means patient, not condition. "
            "Questions about all patients, patient list, 'tat ca benh nhan', or 'danh sach benh nhan' must use search_patients. "
            "Questions that identify a patient by name, phone, birth date, or identifier must use search_patients unless a clear FHIR patient id is provided. "
            "Questions about phone, contact, 'so dien thoai', or 'dien thoai' must use get_patient_by_id only when a patient id is provided; otherwise use search_patients with name or phone criteria. "
            "Questions about encounters, visits, appointments, 'lan kham', 'lich su kham', or 'kham gan nhat' must use get_encounters."
        )
        user_prompt = {
            "message": message,
            "normalized_message": normalize_text(message),
            "provided_patient_id": patient_id_hint,
            "allowed_patient_id_default": DEFAULT_PATIENT_ID,
        }

        try:
            response = await self.client.chat.completions.create(
                model=self.model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": json.dumps(user_prompt)},
                ],
                tools=FHIR_TOOL_DEFINITIONS,
                tool_choice="required",
                temperature=0,
            )
        except Exception:
            return await self.fallback.extract(message, provided_patient_id)

        tool_calls = response.choices[0].message.tool_calls or []
        if not tool_calls:
            return await self.fallback.extract(message, provided_patient_id)

        tool_call = tool_calls[0] # Lấy tool đầu tiên LLM chọn
        arguments = _parse_tool_arguments(tool_call.function.arguments)
        usage = {
            "input_tokens": getattr(response.usage, "prompt_tokens", 0) if response.usage else 0,
            "output_tokens": getattr(response.usage, "completion_tokens", 0) if response.usage else 0,
            "estimated_cost_usd": 0,
        }
        plan = _plan_from_tool_call(
            tool_name=tool_call.function.name,
            arguments=arguments,
            provided_patient_id=provided_patient_id,
            usage=usage,
            source="llm",
            confidence_score=_LLM_CONFIDENCE,
        )
        plan = enforce_patient_list_routing(message, plan)
        plan = apply_all_patient_scope(message, plan)
        plan = apply_patient_search_criteria_hint(message, plan)
        plan = apply_patient_id_hint(message, provided_patient_id, plan)
        plan = enforce_contact_detail_routing(message, plan)
        return add_observation_type_hint(message, plan)


def _parse_tool_arguments(raw_arguments: str | None) -> dict[str, Any]:
    if not raw_arguments:
        return {}
    try:
        parsed = json.loads(raw_arguments)
    except json.JSONDecodeError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _plan_from_tool_call(
    *,
    tool_name: str,
    arguments: dict[str, Any],
    provided_patient_id: str | None,
    usage: dict[str, int | float],
    source: str,
    confidence_score: float = _LLM_CONFIDENCE,
) -> IntentPlan:
    patient_id = normalize_patient_id(provided_patient_id) or normalize_patient_id(arguments.get("patient_id"))
    if not patient_id:
        patient_id = DEFAULT_PATIENT_ID

    limit = arguments.get("limit", 5)
    if not isinstance(limit, int):
        limit = 5

    if tool_name == TOOL_GET_MEDICATIONS:
        limit = clamp(limit, 1, 50)
    elif tool_name == TOOL_GET_CONDITIONS:
        limit = clamp(limit, 1, 50)
    elif tool_name == TOOL_GET_OBSERVATIONS:
        limit = clamp(limit, 1, 20)
    elif tool_name == TOOL_GET_ENCOUNTERS:
        limit = clamp(limit, 1, 20)
    else:
        limit = clamp(limit, 1, 50)

    if tool_name not in TOOL_TO_INTENT:
        tool_name = TOOL_UNSUPPORTED

    return IntentPlan(
        tool_name=tool_name,
        patient_id=patient_id,
        search_name=string_or_none(arguments.get("name")),
        search_phone=normalize_phone(string_or_none(arguments.get("phone"))),
        search_birth_date=normalize_birth_date(string_or_none(arguments.get("birth_date"))),
        search_identifier=string_or_none(arguments.get("identifier")),
        observation_type=string_or_none(arguments.get("observation_type")),
        limit=limit,
        all_patients=False,
        reason=string_or_none(arguments.get("reason")),
        source=source,
        usage=usage,
        confidence_score=confidence_score,
    )
