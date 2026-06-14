from __future__ import annotations

from agents.intent.models import IntentPlan, IntentExtractor
from agents.intent.rule_extractor import RuleBasedIntentExtractor
from agents.intent.openai_extractor import OpenAIIntentExtractor


def get_intent_extractor() -> IntentExtractor:
    from app.config import get_settings
    settings = get_settings()
    if settings.use_openai_llm and settings.openai_api_key:
        return OpenAIIntentExtractor(
            api_key=settings.openai_api_key,
            model=settings.llm_model,
            timeout_seconds=settings.llm_request_timeout_seconds,
            base_url=settings.openai_base_url,
        )
    return RuleBasedIntentExtractor()
