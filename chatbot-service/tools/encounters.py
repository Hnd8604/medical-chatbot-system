"""Tool lần khám (FHIR Encounter)."""

from __future__ import annotations

from typing import Any

from fhir.client import FhirClient
from fhir.normalizer import normalize_encounter_bundle
from tools.filters import date_params, filter_metadata, scope_text, search_kwargs
from tools.formatting import (
    evidence_item,
    format_all_patient_answer,
    format_encounter,
    format_patient_identity,
)
from tools.patients import coverage_note, fetch_patient_page, map_patients, with_patient_context


async def get_encounters(
    client: FhirClient,
    *,
    patient_id: str,
    limit: int = 5,
    date_from: str | None = None,
    date_to: str | None = None,
) -> dict[str, Any]:
    encounters = await _search(client, patient_id, limit, date_from=date_from, date_to=date_to)
    scope = scope_text(date_from=date_from, date_to=date_to)
    if not encounters:
        answer = f"Không tìm thấy bản ghi lần khám nào cho Bệnh nhân {patient_id}{scope}."
    else:
        summary = "; ".join(format_encounter(item) for item in encounters)
        answer = (
            f"Theo dữ liệu FHIR hiện có, Bệnh nhân {patient_id} có "
            f"{len(encounters)} bản ghi lần khám gần đây{scope}: {summary}."
        )
    payload: dict[str, Any] = {
        "answer": answer,
        "intent": "encounters",
        "patient_id": patient_id,
        # Mới nhất trước: step sau tham chiếu "$<step>.encounter_id" lấy lần khám đầu tiên.
        "evidence": [
            evidence_item("Encounter", item.get("id"), format_encounter(item), item)
            for item in encounters
        ],
    }
    filters = filter_metadata(date_from=date_from, date_to=date_to)
    if filters:
        payload["applied_filters"] = filters
    return payload


async def get_all_patient_encounters(client: FhirClient, *, limit: int = 5) -> dict[str, Any]:
    patients, total = await fetch_patient_page(client)
    patients = [patient for patient in patients if patient.get("id")]
    results = await map_patients(patients, lambda patient: _search(client, patient["id"], min(limit, 5)))

    summaries: list[str] = []
    evidence: list[dict[str, Any]] = []
    for patient, encounters in zip(patients, results):
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

    answer = format_all_patient_answer(
        summaries,
        empty_message="Không tìm thấy bệnh nhân nào để kiểm tra lần khám.",
        prefix="Theo dữ liệu FHIR hiện có, lần khám của các bệnh nhân là",
    )
    return {
        "answer": answer + (coverage_note(len(patients), total) if summaries else ""),
        "intent": "encounters",
        "patient_id": None,
        "all_patients": True,
        "evidence": evidence,
    }


async def _search(
    client: FhirClient,
    patient_id: str,
    limit: int,
    *,
    date_from: str | None = None,
    date_to: str | None = None,
) -> list[dict[str, Any]]:
    filters = date_params("date", date_from, date_to)
    bundle = await client.search_patient_resources(
        "Encounter", patient_id, count=limit, sort="-date", **search_kwargs(filters)
    )
    return normalize_encounter_bundle(bundle)
