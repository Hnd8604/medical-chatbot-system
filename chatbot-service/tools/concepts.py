"""Tool giải thích khái niệm y khoa chung — không gắn bệnh nhân, không gọi FHIR.

Dựng evidence tổng hợp từ term/code rồi tra terminology (LOINC/RxNorm/MedlinePlus).
Kết quả gắn top-level ``external_knowledge`` để answer LLM tóm tắt sang tiếng Việt;
``evidence`` để rỗng vì không có dữ liệu bệnh nhân nào.
"""

from __future__ import annotations

import logging
from typing import Any

from fhir.client import FhirClient
from terminology.enrichment_service import get_enrichment_service
from tools.formatting import evidence_item


log = logging.getLogger(__name__)

LOINC_SYSTEM = "http://loinc.org"


async def explain_concept(
    client: FhirClient,
    *,
    term: str | None = None,
    code: str | None = None,
) -> dict[str, Any]:
    term = (term or "").strip()
    code = (code or "").strip()
    label = term or code or "khái niệm"

    external_knowledge: list[dict[str, Any]] = []
    synthetic = _synthetic_concept_evidence(term, code)
    if synthetic:
        try:
            items = await get_enrichment_service().enrich_payload({"evidence": synthetic}, language="en")
            external_knowledge = [item.as_dict() for item in items]
        except Exception:
            log.exception("explain_concept enrichment error")

    if external_knowledge:
        answer = f"Dưới đây là thông tin tham khảo về {label} từ nguồn thuật ngữ y khoa."
    else:
        answer = (
            f"Hiện chưa tra được thông tin thuật ngữ cho \"{label}\". "
            "Bạn có thể cung cấp mã chuẩn (ví dụ mã LOINC của xét nghiệm) để tra chính xác hơn."
        )

    payload: dict[str, Any] = {
        "answer": answer,
        "intent": "explain_concept",
        "patient_id": None,
        "evidence": [],
    }
    if external_knowledge:
        payload["external_knowledge"] = external_knowledge
    return payload


def _synthetic_concept_evidence(term: str, code: str) -> list[dict[str, Any]]:
    """Evidence giả lập để bộ trích mã terminology tra được một khái niệm trơn.

    - Có mã: coi như mã LOINC (phổ biến cho câu hỏi "chỉ số/xét nghiệm") -> Observation.
    - Có tên: dùng làm medication text để RxNorm tra theo tên + MedlinePlus tra thuốc theo tên.
    """
    items: list[dict[str, Any]] = []
    text = term or None
    if code:
        items.append(
            evidence_item(
                "Observation",
                None,
                text or code,
                {
                    "resource_type": "Observation",
                    "code": text or code,
                    "code_detail": {
                        "text": text,
                        "coding": [{"system": LOINC_SYSTEM, "code": code, "display": text}],
                    },
                },
            )
        )
    if text:
        items.append(
            evidence_item(
                "MedicationRequest",
                None,
                text,
                {
                    "resource_type": "MedicationRequest",
                    "medication": text,
                    "medication_detail": {"text": text, "coding": []},
                },
            )
        )
    return items
