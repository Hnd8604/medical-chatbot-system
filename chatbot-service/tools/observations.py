"""Tool chỉ số / xét nghiệm / sinh hiệu (FHIR Observation)."""

from __future__ import annotations

from typing import Any

from fhir.client import FhirClient
from fhir.normalizer import normalize_observation_bundle
from nlp.observation_types import observation_matches_type
from tools.formatting import (
    display_vi,
    evidence_item,
    format_all_patient_answer,
    format_observation,
    format_patient_identity,
)
from tools.patients import fetch_patients, with_patient_context


async def get_observations(
    client: FhirClient,
    *,
    patient_id: str,
    observation_type: str | None = None,
    limit: int = 5,
) -> dict[str, Any]:
    observations = await _search(client, patient_id, limit, observation_type)

    if not observations:
        if observation_type:
            answer = (
                f"Không tìm thấy bản ghi {display_vi(observation_type)} phù hợp "
                f"cho Bệnh nhân {patient_id}."
            )
        else:
            answer = f"Không tìm thấy bản ghi chỉ số/xét nghiệm nào cho Bệnh nhân {patient_id}."
    else:
        summary = "; ".join(format_observation(item) for item in observations)
        answer = (
            f"Theo dữ liệu FHIR hiện có, Bệnh nhân {patient_id} có "
            f"{len(observations)} bản ghi chỉ số/xét nghiệm gần đây: {summary}."
        )
    payload: dict[str, Any] = {
        "answer": answer,
        "intent": "observations",
        "patient_id": patient_id,
        "evidence": [
            evidence_item("Observation", item.get("id"), display_vi(item.get("code")), item)
            for item in observations
        ],
    }
    if observation_type:
        payload["observation_type"] = observation_type
    return payload


async def get_all_patient_observations(
    client: FhirClient,
    *,
    observation_type: str | None = None,
    limit: int = 5,
) -> dict[str, Any]:
    summaries: list[str] = []
    evidence: list[dict[str, Any]] = []
    for patient in await fetch_patients(client):
        patient_id = patient.get("id")
        if not patient_id:
            continue
        observations = await _search(client, patient_id, limit, observation_type)
        patient_label = format_patient_identity(patient)
        if not observations:
            summaries.append(f"{patient_label}: không có bản ghi phù hợp")
            continue
        summaries.append(f"{patient_label}: " + "; ".join(format_observation(item) for item in observations))
        evidence.extend(
            evidence_item(
                "Observation",
                item.get("id"),
                f"{patient_label}: {display_vi(item.get('code'))}",
                with_patient_context(item, patient),
            )
            for item in observations
        )

    payload: dict[str, Any] = {
        "answer": format_all_patient_answer(
            summaries,
            empty_message="Không tìm thấy bệnh nhân nào để kiểm tra chỉ số/xét nghiệm.",
            prefix="Theo dữ liệu FHIR hiện có, chỉ số/xét nghiệm của các bệnh nhân là",
        ),
        "intent": "observations",
        "patient_id": None,
        "all_patients": True,
        "evidence": evidence,
    }
    if observation_type:
        payload["observation_type"] = observation_type
    return payload


async def _search(
    client: FhirClient,
    patient_id: str,
    limit: int,
    observation_type: str | None,
) -> list[dict[str, Any]]:
    # Lọc theo loại diễn ra sau khi tải, nên phải tải rộng hơn limit để còn đủ bản ghi.
    search_count = max(limit, 20) if observation_type else limit
    bundle = await client.search_patient_resources("Observation", patient_id, count=search_count, sort="-date")
    observations = normalize_observation_bundle(bundle)
    if observation_type:
        observations = [
            item for item in observations if observation_matches_type(item, observation_type)
        ][:limit]
    return observations
