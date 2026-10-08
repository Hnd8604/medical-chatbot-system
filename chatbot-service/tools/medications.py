"""Tool thuốc / y lệnh thuốc (FHIR MedicationRequest)."""

from __future__ import annotations

from typing import Any

from fhir.client import FhirClient
from fhir.normalizer import normalize_medication_request_bundle
from tools.formatting import (
    display_vi,
    evidence_item,
    format_all_patient_answer,
    format_patient_identity,
)
from tools.patients import fetch_patients, with_patient_context


async def get_medication_requests(client: FhirClient, *, patient_id: str, limit: int = 5) -> dict[str, Any]:
    medications = await _search(client, patient_id, limit)
    if not medications:
        answer = f"Không tìm thấy bản ghi thuốc nào cho Bệnh nhân {patient_id}."
    else:
        answer = (
            f"Theo dữ liệu FHIR hiện có, Bệnh nhân {patient_id} có "
            f"{len(medications)} y lệnh thuốc: {_names(medications)}."
        )
    return {
        "answer": answer,
        "intent": "medications",
        "patient_id": patient_id,
        "evidence": [
            evidence_item("MedicationRequest", item.get("id"), display_vi(item.get("medication")), item)
            for item in medications
        ],
    }


async def get_all_patient_medication_requests(client: FhirClient, *, limit: int = 5) -> dict[str, Any]:
    summaries: list[str] = []
    evidence: list[dict[str, Any]] = []
    for patient in await fetch_patients(client):
        patient_id = patient.get("id")
        if not patient_id:
            continue
        medications = await _search(client, patient_id, limit)
        patient_label = format_patient_identity(patient)
        if not medications:
            summaries.append(f"{patient_label}: không có bản ghi thuốc")
            continue
        summaries.append(f"{patient_label}: {_names(medications)}")
        evidence.extend(
            evidence_item(
                "MedicationRequest",
                item.get("id"),
                f"{patient_label}: {display_vi(item.get('medication'))}",
                with_patient_context(item, patient),
            )
            for item in medications
        )

    return {
        "answer": format_all_patient_answer(
            summaries,
            empty_message="Không tìm thấy bệnh nhân nào để kiểm tra thuốc.",
            prefix="Theo dữ liệu FHIR hiện có, thuốc của các bệnh nhân là",
        ),
        "intent": "medications",
        "patient_id": None,
        "all_patients": True,
        "evidence": evidence,
    }


async def _search(client: FhirClient, patient_id: str, limit: int) -> list[dict[str, Any]]:
    bundle = await client.search_patient_resources("MedicationRequest", patient_id, count=limit)
    return normalize_medication_request_bundle(bundle)


def _names(medications: list[dict[str, Any]]) -> str:
    return ", ".join(str(display_vi(item.get("medication") or item.get("id"))) for item in medications)
