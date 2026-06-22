from enum import Enum
from functools import lru_cache

from agents.intent.constants import TOOL_GET_CONDITIONS, TOOL_GET_OBSERVATIONS
from agents.intent.models import IntentPlan


class QueryComplexity(str, Enum):
    SIMPLE = "simple"
    COMPLEX = "complex"


_COMPLEX_TOOLS = {TOOL_GET_OBSERVATIONS, TOOL_GET_CONDITIONS}

_ANALYSIS_KEYWORDS = [
    "phân tích", "giải thích", "so sánh", "xu hướng", "diễn biến",
    "bình thường không", "nguy hiểm", "đánh giá", "cảnh báo", "nhận xét",
    "có ổn không", "ý nghĩa", "tại sao", "interpret", "analyze", "trend",
]

_CONFIDENCE_THRESHOLD = 0.65


class ModelRouter:
    def __init__(self, model_simple: str, model_complex: str) -> None:
        self.model_simple = model_simple
        self.model_complex = model_complex

    def route(self, message: str, plan: IntentPlan) -> tuple[str, QueryComplexity]:
        complexity = self._classify(message, plan)
        model = self.model_complex if complexity == QueryComplexity.COMPLEX else self.model_simple
        return model, complexity

    def _classify(self, message: str, plan: IntentPlan) -> QueryComplexity:
        if plan.tool_name in _COMPLEX_TOOLS:
            return QueryComplexity.COMPLEX
        if any(kw in message.lower() for kw in _ANALYSIS_KEYWORDS):
            return QueryComplexity.COMPLEX
        if plan.confidence_score < _CONFIDENCE_THRESHOLD:
            return QueryComplexity.COMPLEX
        return QueryComplexity.SIMPLE


@lru_cache
def get_model_router() -> ModelRouter:
    from app.config import get_settings
    settings = get_settings()
    return ModelRouter(model_simple=settings.model_simple, model_complex=settings.model_complex)
