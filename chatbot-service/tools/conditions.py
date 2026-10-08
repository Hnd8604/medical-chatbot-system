"""Tool chẩn đoán / tình trạng bệnh đã ghi nhận (FHIR Condition)."""

from __future__ import annotations

from typing import Any

from fhir.client import FhirClient
from fhir.normalizer import normalize_condition_bundle
from tools.formatting import (
    display_vi,
    evidence_item,
    format_all_patient_answer,
    format_patient_identity,
)
from tools.patients import fetch_patients, with_patient_context


async def get_conditions(client: FhirClient, *, patient_id: str, limit: int = 5) -> dict[str, Any]:
    conditions = await _search(client, patient_id, limit)
    if not conditions:
        answer = f"Không tìm thấy bản ghi chẩn đoán/tình trạng bệnh nào cho Bệnh nhân {patient_id}."
    else:
        answer = (
            f"Theo dữ liệu FHIR hiện có, Bệnh nhân {patient_id} có "
            f"{len(conditions)} bản ghi chẩn đoán/tình trạng bệnh: {_names(conditions)}."
        )
    return {
        "answer": answer,
        "intent": "conditions",
        "patient_id": patient_id,
        "evidence": [
            evidence_item("Condition", item.get("id"), display_vi(item.get("code")), item)
            for item in conditions
        ],
    }


async def get_all_patient_conditions(client: FhirClient, *, limit: int = 5) -> dict[str, Any]:
    summaries: list[str] = []
    evidence: list[dict[str, Any]] = []
    for patient in await fetch_patients(client):
        patient_id = patient.get("id")
        if not patient_id:
            continue
        conditions = await _search(client, patient_id, limit)
        patient_label = format_patient_identity(patient)
        if not conditions:
            summaries.append(f"{patient_label}: không có bản ghi chẩn đoán")
            continue
        summaries.append(f"{patient_label}: {_names(conditions)}")
        evidence.extend(
            evidence_item(
                "Condition",
                item.get("id"),
                f"{patient_label}: {display_vi(item.get('code'))}",
                with_patient_context(item, patient),
            )
            for item in conditions
        )

    return {
        "answer": format_all_patient_answer(
            summaries,
            empty_message="Không tìm thấy bệnh nhân nào để kiểm tra chẩn đoán.",
            prefix="Theo dữ liệu FHIR hiện có, chẩn đoán/tình trạng bệnh của các bệnh nhân là",
        ),
        "intent": "conditions",
        "patient_id": None,
        "all_patients": True,
        "evidence": evidence,
    }


async def _search(client: FhirClient, patient_id: str, limit: int) -> list[dict[str, Any]]:
    bundle = await client.search_patient_resources("Condition", patient_id, count=limit)
    return normalize_condition_bundle(bundle)


def _names(conditions: list[dict[str, Any]]) -> str:
    return ", ".join(str(display_vi(item.get("code") or item.get("id"))) for item in conditions)
