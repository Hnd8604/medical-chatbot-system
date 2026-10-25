"""Tool thuốc / y lệnh thuốc (FHIR MedicationRequest)."""

from __future__ import annotations

from typing import Any

from fhir.client import FhirClient
from fhir.normalizer import normalize_medication_request_bundle
from tools.filters import date_params, encounter_params, filter_metadata, scope_text, search_kwargs
from tools.formatting import (
    display_vi,
    evidence_item,
    format_all_patient_answer,
    format_patient_identity,
)
from tools.patients import coverage_note, fetch_patient_page, map_patients, with_patient_context


async def get_medication_requests(
    client: FhirClient,
    *,
    patient_id: str,
    limit: int = 5,
    date_from: str | None = None,
    date_to: str | None = None,
    encounter_id: str | None = None,
) -> dict[str, Any]:
    medications = await _search(
        client, patient_id, limit, date_from=date_from, date_to=date_to, encounter_id=encounter_id
    )
    scope = scope_text(date_from=date_from, date_to=date_to, encounter_id=encounter_id)
    if not medications:
        answer = f"Không tìm thấy bản ghi thuốc nào cho Bệnh nhân {patient_id}{scope}."
    else:
        answer = (
            f"Theo dữ liệu FHIR hiện có, Bệnh nhân {patient_id} có "
            f"{len(medications)} y lệnh thuốc{scope}: {_names(medications)}."
        )
    payload: dict[str, Any] = {
        "answer": answer,
        "intent": "medications",
        "patient_id": patient_id,
        "evidence": [
            evidence_item("MedicationRequest", item.get("id"), display_vi(item.get("medication")), item)
            for item in medications
        ],
    }
    filters = filter_metadata(date_from=date_from, date_to=date_to, encounter_id=encounter_id)
    if filters:
        payload["applied_filters"] = filters
    return payload


async def get_all_patient_medication_requests(client: FhirClient, *, limit: int = 5) -> dict[str, Any]:
    patients, total = await fetch_patient_page(client)
    patients = [patient for patient in patients if patient.get("id")]
    results = await map_patients(patients, lambda patient: _search(client, patient["id"], limit))

    summaries: list[str] = []
    evidence: list[dict[str, Any]] = []
    for patient, medications in zip(patients, results):
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

    answer = format_all_patient_answer(
        summaries,
        empty_message="Không tìm thấy bệnh nhân nào để kiểm tra thuốc.",
        prefix="Theo dữ liệu FHIR hiện có, thuốc của các bệnh nhân là",
    )
    return {
        "answer": answer + (coverage_note(len(patients), total) if summaries else ""),
        "intent": "medications",
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
    encounter_id: str | None = None,
) -> list[dict[str, Any]]:
    filters = {**date_params("authoredon", date_from, date_to), **encounter_params(encounter_id)}
    bundle = await client.search_patient_resources(
        "MedicationRequest", patient_id, count=limit, **search_kwargs(filters)
    )
    return normalize_medication_request_bundle(bundle)


def _names(medications: list[dict[str, Any]]) -> str:
    return ", ".join(str(display_vi(item.get("medication") or item.get("id"))) for item in medications)
