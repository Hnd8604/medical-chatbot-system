from enum import Enum
from functools import lru_cache

from agents.intent.models import IntentPlan
from agents.intent.text_utils import normalize_text


class QueryComplexity(str, Enum):
    SIMPLE = "simple"
    COMPLEX = "complex"



_ANALYSIS_KEYWORDS = [
    # Phân tích / giải thích
    "phan tich", "giai thich", "lam ro", "lam sao", "the nao", "nhu the nao",
    "vi sao", "tai sao", "do la gi", "la gi", "y nghia", "co nghia la",
    "hieu the nao", "noi ro", "chi tiet hon", "ro hon",
    # So sánh / xu hướng / diễn biến
    "so sanh", "doi chieu", "khac nhau", "khac biet", "chenh lech", "thay doi",
    "xu huong", "dien bien", "tien trien", "tang giam", "tang hay giam",
    "co tang khong", "co giam khong", "bien dong", "qua thoi gian", "theo thoi gian",
    "lich su", "tien su",
    # Đánh giá / nhận xét
    "danh gia", "nhan xet", "nhan dinh", "ket luan", "tong hop", "tong ket",
    "tom tat", "tom luoc", "tinh trang", "tinh hinh", "the trang", "suc khoe",
    # Bình thường / nguy hiểm / cảnh báo
    "binh thuong khong", "co binh thuong", "binh thuong hay khong",
    "co on khong", "on khong", "co sao khong", "co nguy hiem khong",
    "nguy hiem", "nguy co", "rui ro", "bat thuong", "co bat thuong",
    "canh bao", "luu y", "dang lo", "co dang lo", "nghiem trong", "co nghiem trong",
    "anh huong", "tac dong", "hau qua", "bien chung",
    # Lời khuyên / khuyến nghị
    "loi khuyen", "khuyen nghi", "khuyen", "nen lam gi", "can lam gi",
    "phai lam gi", "co nen", "co can", "de nghi", "goi y", "huong dan",
    "lam gi tiep", "buoc tiep theo", "xu ly the nao", "dieu tri the nao",
    "phong ngua", "phong tranh", "cai thien", "khac phuc",
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
