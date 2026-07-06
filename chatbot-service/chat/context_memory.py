"""Structural hint từ session memory (Spring gửi qua conversation_context).

Chỉ còn hint patient đang active để scoping/cache; việc hiểu câu follow-up
("cái đó", "thuốc đó"...) đã chuyển sang LLM intent extractor thông qua
``memory_summary`` + ``recent_messages`` được inject vào prompt
(xem agents/context_payload.py và agents/summary_generator.py).
"""

from agents.intent_extractor import (
    normalize_patient_id,
    resolve_patient_id_for_request,
)
from api.chat_schemas import ChatRequest


def _resolve_patient_id(request: ChatRequest) -> str:
    return resolve_patient_id_for_request(request.message, _patient_id_hint(request))


def _patient_id_hint(request: ChatRequest) -> str | None:
    request_patient_id = normalize_patient_id(request.patient_id)
    if request_patient_id:
        return request_patient_id
    if request.conversation_context:
        return normalize_patient_id(request.conversation_context.active_patient_id)
    return None
