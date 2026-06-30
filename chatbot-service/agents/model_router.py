from enum import Enum
from functools import lru_cache

from agents.intent.models import IntentPlan
from agents.intent.text_utils import normalize_text


class QueryComplexity(str, Enum):
    SIMPLE = "simple"
    COMPLEX = "complex"



_ANALYSIS_KEYWORDS = [
    "phan tich", "giai thich", "so sanh", "xu huong", "dien bien",
    "binh thuong khong", "nguy hiem", "danh gia", "canh bao", "nhan xet",
    "co on khong", "y nghia", "tai sao",
]

_CONFIDENCE_THRESHOLD = 0.65

_QUOTA_DOWNGRADE_RATIO = 0.8


class ModelRouter:
    def __init__(self, model_simple: str, model_complex: str) -> None:
        self.model_simple = model_simple
        self.model_complex = model_complex

    def route(
        self,
        message: str,
        plan: IntentPlan,
        quota_used_ratio: float = 0.0,
    ) -> tuple[str, QueryComplexity]:
        complexity = self._classify(message, plan)
   
        if complexity == QueryComplexity.COMPLEX and quota_used_ratio >= _QUOTA_DOWNGRADE_RATIO:
            return self.model_simple, complexity
        model = self.model_complex if complexity == QueryComplexity.COMPLEX else self.model_simple
        return model, complexity

    def _classify(self, message: str, plan: IntentPlan) -> QueryComplexity:
        normalized = normalize_text(message)
        if any(kw in normalized for kw in _ANALYSIS_KEYWORDS):
            return QueryComplexity.COMPLEX
        if plan.confidence_score < _CONFIDENCE_THRESHOLD:
            return QueryComplexity.COMPLEX
        return QueryComplexity.SIMPLE


@lru_cache
def get_model_router() -> ModelRouter:
    from app.config import get_settings
    settings = get_settings()
    return ModelRouter(model_simple=settings.model_simple, model_complex=settings.model_complex)
