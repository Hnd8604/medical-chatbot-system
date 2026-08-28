"""Plan cache — cache "ý định đã validate", tách khỏi cache "câu trả lời".

Vì sao cần tầng thứ hai:

Semantic cache đang có là ``câu hỏi → câu trả lời``. Answer chứa dữ liệu bệnh nhân nên
buộc phải TTL ngắn và scope theo từng user (``docs/product-spec.md`` §8.3) → hit rate
thấp, và mọi miss đều trả giá đủ router + planner + answer.

Plan thì **không chứa PHI**: nó chỉ là "gọi tool nào, với hình dạng tham số nào".
"Bệnh nhân này đang dùng thuốc gì" và "thuốc của bệnh nhân đó là gì" cho cùng một plan
dù khác bệnh nhân, khác user. Nên plan cache:

- TTL dài (mặc định 1 ngày) thay vì 5 phút.
- Dùng chung giữa mọi user, chỉ tách theo ``user_role`` (vì chính sách khác nhau).
- Hit ⇒ bỏ được CẢ router lẫn planner, nhưng **vẫn gọi FHIR** nên dữ liệu luôn tươi.

An toàn: ``patient_id`` không bao giờ được lưu — nó bị thay bằng placeholder, và
plan lấy từ cache **vẫn phải chạy lại ``validate_plan``** trước khi thực thi, nên
cache không thể trở thành đường vòng qua chính sách role.
"""

from __future__ import annotations

import logging
import time
import uuid
from typing import Any

from agents.intent.text_utils import normalize_text
from app.config import get_settings
from langgraph_agent.planner import Plan, PlanStep
from langgraph_agent.request_router import RouteDecision
from langgraph_agent.state import ANSWER_MODE_DATA_ONLY
from services.semantic_cache import get_semantic_cache


log = logging.getLogger(__name__)

# Giá trị thay cho mọi patient_id/resource_id thật trước khi ghi cache.
PATIENT_PLACEHOLDER = "$active_patient"
_REDACTED_ARG_KEYS = ("patient_id", "resource_id", "identifier", "phone", "birth_date", "name")


def _context_shape(conversation_context: dict[str, Any] | None) -> str:
    """Chữ ký *hình thái* ngữ cảnh — không chứa giá trị, chỉ có/không.

    Cần thiết vì cùng một câu "bệnh nhân này dùng thuốc gì" phải cho plan khác nhau
    tuỳ có bệnh nhân đang hoạt động hay không.
    """
    context = conversation_context or {}
    return "|".join(
        [
            "p1" if context.get("active_patient_id") else "p0",
            "r1" if context.get("last_resource_type") and context.get("last_resource_id") else "r0",
        ]
    )


class PlanCache:
    """Lưu plan trong một collection Qdrant riêng, dùng chung embedding model."""

    def __init__(self) -> None:
        self._ready = False

    @property
    def _cache(self):
        # Dùng lại instance semantic cache: cùng Qdrant client và cùng model fastembed
        # (model nặng, không nên nạp lần hai).
        return get_semantic_cache()

    @property
    def collection(self) -> str:
        return get_settings().plan_cache_collection_name

    async def init_collection(self) -> None:
        from qdrant_client.models import Distance, VectorParams

        settings = get_settings()
        try:
            if not await self._cache.client.collection_exists(self.collection):
                await self._cache.client.create_collection(
                    collection_name=self.collection,
                    vectors_config=VectorParams(
                        size=settings.cache_vector_size,
                        distance=Distance.COSINE,
                    ),
                )
                log.info("Đã tạo Qdrant collection '%s' cho plan cache.", self.collection)
            self._ready = True
        except Exception as exc:
            log.error("Không khởi tạo được plan cache: %s", exc)

    async def lookup(
        self,
        *,
        question: str,
        user_role: str,
        conversation_context: dict[str, Any] | None,
    ) -> tuple[RouteDecision, Plan] | None:
        from qdrant_client.models import FieldCondition, Filter, MatchValue, Range

        settings = get_settings()
        if not settings.enable_plan_cache:
            return None
        try:
            vector = await self._cache._get_embedding(normalize_text(question))
            result = await self._cache.client.query_points(
                collection_name=self.collection,
                query=vector,
                query_filter=Filter(
                    must=[
                        FieldCondition(key="user_role", match=MatchValue(value=user_role)),
                        FieldCondition(
                            key="context_shape",
                            match=MatchValue(value=_context_shape(conversation_context)),
                        ),
                        FieldCondition(
                            key="created_at",
                            range=Range(gte=time.time() - settings.plan_cache_ttl_seconds),
                        ),
                    ]
                ),
                limit=1,
            )
        except Exception as exc:
            log.warning("Plan cache lookup lỗi, coi như miss: %s", exc)
            return None

        points = result.points
        if not points or points[0].score < settings.plan_cache_similarity_threshold:
            return None

        payload = points[0].payload or {}
        decision = _decision_from_payload(payload)
        plan = _plan_from_payload(payload)
        if decision is None or plan is None:
            return None
        log.info("[PLAN CACHE HIT] score=%.3f route=%s", points[0].score, decision.route)
        return decision, plan

    async def save(
        self,
        *,
        question: str,
        user_role: str,
        conversation_context: dict[str, Any] | None,
        decision: RouteDecision,
        plan: Plan | None,
    ) -> None:
        from qdrant_client.models import PointStruct

        settings = get_settings()
        if not settings.enable_plan_cache:
            return
        try:
            vector = await self._cache._get_embedding(normalize_text(question))
            await self._cache.client.upsert(
                collection_name=self.collection,
                points=[
                    PointStruct(
                        id=str(uuid.uuid4()),
                        vector=vector,
                        payload={
                            "user_role": user_role,
                            "context_shape": _context_shape(conversation_context),
                            "route": decision.route,
                            "meta_kind": decision.meta_kind,
                            "resource_hint": decision.resource_hint,
                            "safety_flag": decision.safety_flag,
                            "answer_mode": plan.answer_mode if plan else ANSWER_MODE_DATA_ONLY,
                            "steps": [_redact_step(step) for step in plan.steps] if plan else [],
                            "created_at": time.time(),
                        },
                    )
                ],
            )
        except Exception as exc:
            log.warning("Plan cache save lỗi, bỏ qua: %s", exc)


def _redact_step(step: PlanStep) -> dict[str, Any]:
    """Bỏ mọi giá trị định danh trước khi ghi — plan cache phải sạch PHI."""
    args = {}
    for key, value in step.args.items():
        if key in _REDACTED_ARG_KEYS:
            # Giữ *hình dạng* tham số (tool này cần patient_id) chứ không giữ giá trị.
            args[key] = PATIENT_PLACEHOLDER
        else:
            args[key] = value
    return {"id": step.id, "tool": step.tool, "args": args}


def _decision_from_payload(payload: dict[str, Any]) -> RouteDecision | None:
    route = payload.get("route")
    if not isinstance(route, str) or not route:
        return None
    return RouteDecision(
        route=route,
        meta_kind=payload.get("meta_kind"),
        resource_hint=payload.get("resource_hint"),
        safety_flag=payload.get("safety_flag") or "none",
        reason="Lấy từ plan cache.",
        source="plan_cache",
    )


def _plan_from_payload(payload: dict[str, Any]) -> Plan | None:
    raw_steps = payload.get("steps")
    if not isinstance(raw_steps, list) or not raw_steps:
        return None
    steps: list[PlanStep] = []
    for raw in raw_steps:
        if not isinstance(raw, dict) or not raw.get("tool"):
            continue
        args = {
            key: value
            for key, value in (raw.get("args") or {}).items()
            # Placeholder được validator/executor điền lại bằng bệnh nhân của lượt này.
            if value != PATIENT_PLACEHOLDER
        }
        steps.append(PlanStep(id=str(raw.get("id") or "step"), tool=str(raw["tool"]), args=args))
    if not steps:
        return None
    return Plan(
        steps=tuple(steps),
        answer_mode=payload.get("answer_mode") or ANSWER_MODE_DATA_ONLY,
        reason="Lấy từ plan cache.",
        source="plan_cache",
    )


_plan_cache: PlanCache | None = None


def get_plan_cache() -> PlanCache:
    global _plan_cache
    if _plan_cache is None:
        _plan_cache = PlanCache()
    return _plan_cache
