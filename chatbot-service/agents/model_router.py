import json
import logging
from collections import OrderedDict
from dataclasses import dataclass, field
from enum import Enum
from functools import lru_cache

from agents.intent.text_utils import normalize_text

log = logging.getLogger(__name__)


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

_QUOTA_DOWNGRADE_RATIO = 0.8

_ROUTER_CACHE_MAX_SIZE = 512


def _zero_usage() -> dict[str, int | float]:
    return {"input_tokens": 0, "output_tokens": 0, "estimated_cost_usd": 0}


@dataclass
class RoutingDecision:
    model: str
    complexity: QueryComplexity
    source: str = "keyword"  # keyword | llm_router
    usage: dict[str, int | float] = field(default_factory=_zero_usage)


class ModelRouter:
    """Classifier Router: phân loại SIMPLE/COMPLEX bằng từ khóa, không tốn LLM call."""

    def __init__(self, model_simple: str, model_complex: str) -> None:
        self.model_simple = model_simple
        self.model_complex = model_complex

    async def route(
        self,
        message: str,
        quota_used_ratio: float = 0.0,
    ) -> RoutingDecision:
        complexity = self._classify(message)
        return RoutingDecision(
            model=self._pick_model(complexity, quota_used_ratio),
            complexity=complexity,
            source="keyword",
        )

    def _pick_model(self, complexity: QueryComplexity, quota_used_ratio: float) -> str:
        # Cost-aware: quota gần cạn thì hạ cấp câu hỏi COMPLEX xuống model rẻ.
        if complexity == QueryComplexity.COMPLEX and quota_used_ratio >= _QUOTA_DOWNGRADE_RATIO:
            return self.model_simple
        return self.model_complex if complexity == QueryComplexity.COMPLEX else self.model_simple

    def _classify(self, message: str) -> QueryComplexity:
        normalized = normalize_text(message)
        if any(kw in normalized for kw in _ANALYSIS_KEYWORDS):
            return QueryComplexity.COMPLEX
        return QueryComplexity.SIMPLE


_ROUTER_TOOL_DEFINITIONS = [
    {
        "type": "function",
        "function": {
            "name": "classify_complexity",
            "description": "Phan loai do phuc tap cua cau hoi y te tieng Viet.",
            "parameters": {
                "type": "object",
                "properties": {
                    "complexity": {
                        "type": "string",
                        "enum": ["simple", "complex"],
                        "description": (
                            "simple: tra cuu du lieu truc tiep (xem chi so, danh sach thuoc, "
                            "thong tin benh nhan, lan kham, chan doan da ghi nhan). "
                            "complex: yeu cau suy luan tren du lieu (phan tich, so sanh, "
                            "xu huong, danh gia nguy co, loi khuyen, tong hop tinh trang)."
                        ),
                    }
                },
                "required": ["complexity"],
            },
        },
    }
]

_ROUTER_SYSTEM_PROMPT = (
    "Ban la bo phan loai cau hoi cho chatbot y te tieng Viet. "
    "Chi goi tool classify_complexity de gan nhan simple hoac complex. "
    "Khong tra loi cau hoi, khong giai thich."
)


class LLMModelRouter:
    """LLM Router lai (hybrid):

    1. Fast-path: keyword classifier nói COMPLEX thì tin luôn (precision cao, khỏi tốn LLM call).
    2. Keyword nói SIMPLE thì nhờ model rẻ xác nhận lại (vá phần recall của keyword).
    3. LLM lỗi/timeout/kết quả lạ → dùng kết quả keyword.
    """

    def __init__(
        self,
        api_key: str,
        router_model: str,
        timeout_seconds: float,
        model_simple: str,
        model_complex: str,
        base_url: str | None = None,
    ) -> None:
        from openai import AsyncOpenAI

        self.client = AsyncOpenAI(api_key=api_key, base_url=base_url, timeout=timeout_seconds)
        self.router_model = router_model
        self.fallback = ModelRouter(model_simple, model_complex)
        self._classification_cache: OrderedDict[str, QueryComplexity] = OrderedDict()

    @property
    def model_simple(self) -> str:
        return self.fallback.model_simple

    @property
    def model_complex(self) -> str:
        return self.fallback.model_complex

    async def route(
        self,
        message: str,
        quota_used_ratio: float = 0.0,
    ) -> RoutingDecision:
        keyword_complexity = self.fallback._classify(message)
        if keyword_complexity == QueryComplexity.COMPLEX:
            return RoutingDecision(
                model=self.fallback._pick_model(keyword_complexity, quota_used_ratio),
                complexity=keyword_complexity,
                source="keyword",
            )

        cached = self._cache_get(message)
        if cached is not None:
            return RoutingDecision(
                model=self.fallback._pick_model(cached, quota_used_ratio),
                complexity=cached,
                source="llm_router",
            )

        complexity, usage, source = await self._llm_classify(message, keyword_complexity)
        if source == "llm_router":
            self._cache_put(message, complexity)
        return RoutingDecision(
            model=self.fallback._pick_model(complexity, quota_used_ratio),
            complexity=complexity,
            source=source,
            usage=usage,
        )

    async def _llm_classify(
        self,
        message: str,
        keyword_complexity: QueryComplexity,
    ) -> tuple[QueryComplexity, dict[str, int | float], str]:
        try:
            response = await self.client.chat.completions.create(
                model=self.router_model,
                messages=[
                    {"role": "system", "content": _ROUTER_SYSTEM_PROMPT},
                    {"role": "user", "content": message},
                ],
                tools=_ROUTER_TOOL_DEFINITIONS,
                tool_choice={"type": "function", "function": {"name": "classify_complexity"}},
                temperature=0,
            )
        except Exception:
            log.exception("LLM router loi, fallback keyword classifier")
            return keyword_complexity, _zero_usage(), "keyword"

        usage = {
            "input_tokens": getattr(response.usage, "prompt_tokens", 0) if response.usage else 0,
            "output_tokens": getattr(response.usage, "completion_tokens", 0) if response.usage else 0,
            "estimated_cost_usd": 0,
        }
        complexity = _parse_complexity(response)
        if complexity is None:
            log.warning("LLM router tra ket qua khong hop le, fallback keyword classifier")
            return keyword_complexity, usage, "keyword"

        log.info(
            "LLM router model=%s complexity=%s usage=%s",
            self.router_model,
            complexity.value,
            usage,
        )
        return complexity, usage, "llm_router"

    def _cache_get(self, message: str) -> QueryComplexity | None:
        key = normalize_text(message)
        cached = self._classification_cache.get(key)
        if cached is not None:
            self._classification_cache.move_to_end(key)
        return cached

    def _cache_put(self, message: str, complexity: QueryComplexity) -> None:
        key = normalize_text(message)
        self._classification_cache[key] = complexity
        self._classification_cache.move_to_end(key)
        while len(self._classification_cache) > _ROUTER_CACHE_MAX_SIZE:
            self._classification_cache.popitem(last=False)


def _parse_complexity(response) -> QueryComplexity | None:
    tool_calls = response.choices[0].message.tool_calls or []
    if not tool_calls:
        return None
    try:
        arguments = json.loads(tool_calls[0].function.arguments or "{}")
    except json.JSONDecodeError:
        return None
    value = arguments.get("complexity") if isinstance(arguments, dict) else None
    try:
        return QueryComplexity(value)
    except ValueError:
        return None


def build_model_router(settings) -> ModelRouter | LLMModelRouter:
    if settings.use_llm and settings.enable_llm_router:
        return LLMModelRouter(
            api_key=settings.llm_api_key,
            router_model=settings.model_router,
            timeout_seconds=settings.llm_request_timeout_seconds,
            model_simple=settings.model_simple,
            model_complex=settings.model_complex,
            base_url=settings.llm_base_url,
        )
    return ModelRouter(model_simple=settings.model_simple, model_complex=settings.model_complex)


@lru_cache
def get_model_router() -> ModelRouter | LLMModelRouter:
    from app.config import get_settings
    return build_model_router(get_settings())
