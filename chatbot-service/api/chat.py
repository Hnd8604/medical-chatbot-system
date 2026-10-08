"""``POST /chat`` — một lượt chat qua agent graph.

Spring đã xác thực người dùng và quyền sở hữu session; route này chỉ ánh xạ lỗi của
agent sang HTTP. Chính sách theo role được enforce trong ``agent.planning.validator``.
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import ValidationError

from agent.answer.generator import AnswerGenerator, get_answer_generator
from agent.errors import AgentError
from agent.graph import run_agent
from agent.memory.summary import SummaryGenerator, get_summary_generator
from api.schemas import ChatRequest, ChatResponse
from cache.semantic_cache import SemanticCacheService, get_semantic_cache
from fhir.client import FhirClient, FhirClientError, get_fhir_client
from llm.client import GatewayBudgetExceededError


log = logging.getLogger(__name__)

router = APIRouter(tags=["chat"])


@router.post("/chat")
async def chat(
    request: ChatRequest,
    client: FhirClient = Depends(get_fhir_client),
    answer_generator: AnswerGenerator = Depends(get_answer_generator),
    cache_service: SemanticCacheService = Depends(get_semantic_cache),
    summary_generator: SummaryGenerator = Depends(get_summary_generator),
) -> dict[str, Any]:
    try:
        payload = await run_agent(
            request,
            client=client,
            answer_generator=answer_generator,
            cache_service=cache_service,
            summary_generator=summary_generator,
        )
    except GatewayBudgetExceededError as exc:
        # Virtual key vượt budget cost/ngày. Spring chuyển tiếp nguyên status/detail.
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Đã đạt hạn mức chi phí AI trong ngày. Vui lòng thử lại sau khi hạn mức được đặt lại.",
        ) from exc
    except AgentError as exc:
        # PolicyError -> 403. PlanError không tới đây: graph đã đổi thành clarification.
        raise exc.as_http() from exc
    except FhirClientError as exc:
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=exc.user_message) from exc

    _check_contract(payload)
    return payload


def _check_contract(payload: dict[str, Any]) -> None:
    """Contract lệch là bug của chatbot: log kèm trace_id để tra, nhưng không làm vỡ lượt chat.

    Trả nguyên payload (không phải ``ChatResponse.model_dump``) để giữ đúng các trường extra.
    """
    try:
        ChatResponse.model_validate(payload)
    except ValidationError as exc:
        log.error(
            "Response /chat lệch contract (trace_id=%s): %s",
            payload.get("trace_id"),
            exc.errors(include_input=False, include_url=False),
        )
