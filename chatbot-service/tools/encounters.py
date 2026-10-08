"""Tool lần khám (FHIR Encounter)."""

from __future__ import annotations

from typing import Any

from fhir.client import FhirClient
from fhir.normalizer import normalize_encounter_bundle
from tools.formatting import (
    evidence_item,
    format_all_patient_answer,
    format_encounter,
    format_patient_identity,
)
from tools.patients import fetch_patients, with_patient_context


async def get_encounters(client: FhirClient, *, patient_id: str, limit: int = 5) -> dict[str, Any]:
    encounters = await _search(client, patient_id, limit)
    if not encounters:
        answer = f"Không tìm thấy bản ghi lần khám nào cho Bệnh nhân {patient_id}."
    else:
        summary = "; ".join(format_encounter(item) for item in encounters)
        answer = (
            f"Theo dữ liệu FHIR hiện có, Bệnh nhân {patient_id} có "
            f"{len(encounters)} bản ghi lần khám gần đây: {summary}."
        )
    return {
        "answer": answer,
        "intent": "encounters",
        "patient_id": patient_id,
        "evidence": [
            evidence_item("Encounter", item.get("id"), format_encounter(item), item)
            for item in encounters
        ],
    }


async def get_all_patient_encounters(client: FhirClient, *, limit: int = 5) -> dict[str, Any]:
    summaries: list[str] = []
    evidence: list[dict[str, Any]] = []
    for patient in await fetch_patients(client):
        patient_id = patient.get("id")
        if not patient_id:
            continue
        encounters = await _search(client, patient_id, limit)
        patient_label = format_patient_identity(patient)
        if not encounters:
            summaries.append(f"{patient_label}: không có bản ghi lần khám")
            continue
        summaries.append(f"{patient_label}: " + "; ".join(format_encounter(item) for item in encounters))
        evidence.extend(
            evidence_item(
                "Encounter",
                item.get("id"),
                f"{patient_label}: {format_encounter(item)}",
                with_patient_context(item, patient),
            )
            for item in encounters
        )

    return {
        "answer": format_all_patient_answer(
            summaries,
            empty_message="Không tìm thấy bệnh nhân nào để kiểm tra lần khám.",
            prefix="Theo dữ liệu FHIR hiện có, lần khám của các bệnh nhân là",
        ),
        "intent": "encounters",
        "patient_id": None,
        "all_patients": True,
        "evidence": evidence,
    }


async def _search(client: FhirClient, patient_id: str, limit: int) -> list[dict[str, Any]]:
    bundle = await client.search_patient_resources("Encounter", patient_id, count=limit, sort="-date")
    return normalize_encounter_bundle(bundle)
