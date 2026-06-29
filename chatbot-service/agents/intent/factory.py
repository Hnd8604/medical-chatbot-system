from __future__ import annotations

from agents.intent.models import IntentPlan, IntentExtractor
from agents.intent.rule_extractor import RuleBasedIntentExtractor
from agents.intent.llm_extractor import LLMIntentExtractor


def get_intent_extractor() -> IntentExtractor:
    from app.config import get_settings
    settings = get_settings()
    if settings.use_llm:
        return LLMIntentExtractor(
            api_key=settings.llm_api_key,
            model=settings.model_simple,
            timeout_seconds=settings.llm_request_timeout_seconds,
            base_url=settings.llm_base_url,
        )
    return RuleBasedIntentExtractor()
