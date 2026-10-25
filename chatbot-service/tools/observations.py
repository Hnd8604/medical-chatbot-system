"""Tool chỉ số / xét nghiệm / sinh hiệu (FHIR Observation).

Lọc theo loại chỉ số bằng mã LOINC **trên FHIR server** (tham số ``code``). Chỉ khi server
không trả gì (dữ liệu không gắn mã LOINC) mới lùi về tải rộng rồi so tên.
"""

from __future__ import annotations

from collections.abc import Iterable
from typing import Any

from fhir.client import FhirClient
from fhir.normalizer import normalize_observation_bundle, normalize_patient_bundle
from nlp.observation_types import (
    BP_COMPONENT_CODES,
    canonical_observation_type,
    loinc_code_param,
    observation_matches_type,
    observation_type_label,
)
from nlp.value_filter import compare, describe
from tools.filters import date_params, encounter_params, filter_metadata, scope_text, search_kwargs
from tools.formatting import (
    display_vi,
    evidence_item,
    format_all_patient_answer,
    format_observation,
    format_patient_identity,
    summary_evidence_item,
)
from tools.patients import coverage_note, fetch_patient_page, map_patients, with_patient_context


# Đường lùi khi lọc theo tên: tải rộng hơn limit vì lọc diễn ra sau khi tải.
NAME_FILTER_SCAN = 50
# Truy vấn nhóm bệnh nhân theo một loại chỉ số: đọc tối đa ngần này Observation.
COHORT_MAX_OBSERVATIONS = 1000
COHORT_PAGE_SIZE = 200


async def get_observations(
    client: FhirClient,
    *,
    patient_id: str,
    observation_type: str | None = None,
    limit: int = 5,
    date_from: str | None = None,
    date_to: str | None = None,
    encounter_id: str | None = None,
) -> dict[str, Any]:
    observations = await search_observations(
        client,
        patient_id,
        limit=limit,
        observation_type=observation_type,
        date_from=date_from,
        date_to=date_to,
        encounter_id=encounter_id,
    )
    scope = scope_text(date_from=date_from, date_to=date_to, encounter_id=encounter_id)

    if not observations:
        if observation_type:
            answer = (
                f"Không tìm thấy bản ghi {observation_type_label(observation_type)} phù hợp "
                f"cho Bệnh nhân {patient_id}{scope}."
            )
        else:
            answer = f"Không tìm thấy bản ghi chỉ số/xét nghiệm nào cho Bệnh nhân {patient_id}{scope}."
    else:
        summary = "; ".join(format_observation(item) for item in observations)
        answer = (
            f"Theo dữ liệu FHIR hiện có, Bệnh nhân {patient_id} có "
            f"{len(observations)} bản ghi chỉ số/xét nghiệm gần đây{scope}: {summary}."
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
        payload["observation_type"] = canonical_observation_type(observation_type) or observation_type
    filters = filter_metadata(date_from=date_from, date_to=date_to, encounter_id=encounter_id)
    if filters:
        payload["applied_filters"] = filters
    return payload


async def search_observations(
    client: FhirClient,
    patient_id: str,
    *,
    limit: int,
    observation_type: str | None = None,
    date_from: str | None = None,
    date_to: str | None = None,
    encounter_id: str | None = None,
) -> list[dict[str, Any]]:
    """Observation mới nhất trước, đã lọc theo loại / khoảng ngày / lần khám."""
    filters = {**date_params("date", date_from, date_to), **encounter_params(encounter_id)}
    code = loinc_code_param(observation_type)
    if code:
        bundle = await client.search_patient_resources(
            "Observation", patient_id, count=limit, sort="-date", params={**filters, "code": code}
        )
        observations = normalize_observation_bundle(bundle)
        if observations:
            return observations

    if not observation_type:
        bundle = await client.search_patient_resources(
            "Observation", patient_id, count=limit, sort="-date", **search_kwargs(filters)
        )
        return normalize_observation_bundle(bundle)

    # Đường lùi: dữ liệu không gắn mã LOINC đã biết ⇒ tải rộng rồi so theo tên.
    bundle = await client.search_patient_resources(
        "Observation", patient_id, count=max(limit, NAME_FILTER_SCAN), sort="-date", **search_kwargs(filters)
    )
    return [
        item for item in normalize_observation_bundle(bundle) if observation_matches_type(item, observation_type)
    ][:limit]


async def get_all_patient_observations(
    client: FhirClient,
    *,
    observation_type: str | None = None,
    value_op: str | None = None,
    value: float | None = None,
    component: str | None = None,
    limit: int = 20,
) -> dict[str, Any]:
    if loinc_code_param(observation_type):
        return await _cohort_by_code(
            client,
            observation_type=observation_type or "",
            value_op=value_op,
            threshold=value,
            component=component,
            limit=limit,
        )
    return await _per_patient(client, observation_type=observation_type, limit=limit)


# --------------------------------------------------------------------------- nhóm bệnh nhân


async def _cohort_by_code(
    client: FhirClient,
    *,
    observation_type: str,
    value_op: str | None,
    threshold: float | None,
    component: str | None,
    limit: int,
) -> dict[str, Any]:
    """Một truy vấn cho mọi bệnh nhân: lấy kết quả MỚI NHẤT của từng người rồi lọc ngưỡng.

    Thay cho vòng lặp từng bệnh nhân (chỉ duyệt được 20 người đầu và không lọc theo giá
    trị). Lọc ngưỡng chạy bằng code trên giá trị đã normalize nên đúng nghĩa ``>``/``≥``.
    """
    bundle = await client.search_resources(
        "Observation",
        {
            "code": loinc_code_param(observation_type),
            "_sort": "-date",
            "_count": COHORT_PAGE_SIZE,
            "_include": "Observation:subject",
        },
        max_results=COHORT_MAX_OBSERVATIONS,
    )
    observations = normalize_observation_bundle(bundle)
    patients = {patient.get("id"): patient for patient in normalize_patient_bundle(bundle)}

    latest: dict[str, dict[str, Any]] = {}
    for observation in observations:  # đã sắp mới nhất trước
        patient_id = _subject_id(observation)
        if patient_id and patient_id not in latest:
            latest[patient_id] = observation

    canonical = canonical_observation_type(observation_type) or observation_type
    label = _value_label(canonical, component)
    filtered = value_op is not None and threshold is not None
    rows: list[tuple[str, dict[str, Any], float | None, str]] = []
    for patient_id, observation in latest.items():
        number, unit = comparable_value(observation, canonical, component)
        if filtered and (number is None or not compare(number, value_op, threshold)):
            continue
        rows.append((patient_id, observation, number, unit))

    if filtered:
        descending = value_op in {"gt", "ge"}
        rows.sort(key=lambda row: (row[2] is None, -(row[2] or 0) if descending else (row[2] or 0)))
    listed = rows[: max(1, limit)]
    unit = next((row[3] for row in rows if row[3]), "")
    condition = f"{label} {describe(value_op, threshold)}{(' ' + unit) if unit else ''}" if filtered else ""

    lines = [
        f"{format_patient_identity(patients.get(patient_id) or {'id': patient_id})}: "
        f"{format_observation(observation)} ({_observed_at(observation)})"
        for patient_id, observation, _, _ in listed
    ]
    checked = len(latest)
    if not checked:
        answer = f"Không tìm thấy bản ghi {label} nào trong dữ liệu FHIR hiện có."
    elif filtered and not rows:
        answer = (
            f"Theo dữ liệu FHIR hiện có, đã kiểm tra kết quả {label} gần nhất của {checked} bệnh nhân "
            f"có dữ liệu: không có bệnh nhân nào có {condition}."
        )
    elif filtered:
        answer = (
            f"Theo dữ liệu FHIR hiện có, đã kiểm tra kết quả {label} gần nhất của {checked} bệnh nhân "
            f"có dữ liệu: {len(rows)} bệnh nhân có {condition}: " + "; ".join(lines) + "."
        )
    else:
        answer = (
            f"Theo dữ liệu FHIR hiện có, kết quả {label} gần nhất của {checked} bệnh nhân có dữ liệu: "
            + "; ".join(lines)
            + "."
        )
    if len(listed) < len(rows):
        answer += f" Danh sách hiển thị {len(listed)}/{len(rows)} bệnh nhân."
    if bundle.get("truncated"):
        answer += f" Lưu ý: chỉ đọc {len(observations)} bản ghi mới nhất, có thể còn bệnh nhân chưa được kiểm tra."

    cohort = {
        "observation_type": canonical,
        "measure": label,
        "basis": "kết quả gần nhất của mỗi bệnh nhân",
        "patients_checked": checked,
        "patients_matched": len(rows) if filtered else checked,
        "patients_listed": len(listed),
        "observations_read": len(observations),
        "truncated": bool(bundle.get("truncated")),
    }
    if filtered:
        cohort["filter"] = {"op": value_op, "value": threshold, "unit": unit, "text": condition}

    evidence = [
        evidence_item(
            "Observation",
            observation.get("id"),
            f"{format_patient_identity(patients.get(patient_id) or {'id': patient_id})}: {display_vi(observation.get('code'))}",
            with_patient_context(observation, patients.get(patient_id) or {"id": patient_id}),
        )
        for patient_id, observation, _, _ in listed
    ]
    evidence.append(summary_evidence_item("ObservationCohort", _cohort_summary(cohort), cohort))
    return {
        "answer": answer,
        "intent": "observations",
        "patient_id": None,
        "all_patients": True,
        "observation_type": canonical,
        "evidence": evidence,
    }


def comparable_value(
    observation: dict[str, Any], observation_type: str | None, component: str | None = None
) -> tuple[float | None, str]:
    """Giá trị số dùng để so ngưỡng. Huyết áp: tâm thu (mặc định) hoặc tâm trương."""
    if canonical_observation_type(observation_type) == "blood_pressure":
        wanted = BP_COMPONENT_CODES.get(component or "systolic", BP_COMPONENT_CODES["systolic"])
        keyword = "diastolic" if wanted == BP_COMPONENT_CODES["diastolic"] else "systolic"
        for item in observation.get("components") or []:
            codes = {coding.get("code") for coding in ((item.get("code_detail") or {}).get("coding") or []) if coding}
            if wanted in codes or keyword in str(item.get("code") or "").lower():
                return _quantity_number(item.get("value"))
        return None, ""
    return _quantity_number(observation.get("value"))


def _quantity_number(quantity: Any) -> tuple[float | None, str]:
    if not isinstance(quantity, dict):
        return None, ""
    raw = quantity.get("value")
    if isinstance(raw, bool) or not isinstance(raw, (int, float)):
        return None, ""
    return float(raw), str(quantity.get("unit") or "")


def _value_label(observation_type: str, component: str | None) -> str:
    if canonical_observation_type(observation_type) == "blood_pressure":
        return "huyết áp tâm trương" if component == "diastolic" else "huyết áp tâm thu"
    return observation_type_label(observation_type)


def _cohort_summary(cohort: dict[str, Any]) -> str:
    text = (
        f"Đã kiểm tra {cohort['measure']} gần nhất của {cohort['patients_checked']} bệnh nhân; "
        f"{cohort['patients_matched']} bệnh nhân phù hợp"
    )
    if cohort.get("filter"):
        text += f" điều kiện {cohort['filter']['text']}"
    return text + f"; liệt kê {cohort['patients_listed']}."


def _subject_id(observation: dict[str, Any]) -> str | None:
    subject = observation.get("subject")
    if isinstance(subject, str) and subject:
        return subject.removeprefix("Patient/")
    return None


def _observed_at(observation: dict[str, Any]) -> str:
    value = observation.get("effective_time")
    if isinstance(value, dict):
        value = value.get("start")
    return str(value)[:10] if value else "không rõ ngày"


# --------------------------------------------------------------------------- theo từng bệnh nhân


async def _per_patient(client: FhirClient, *, observation_type: str | None, limit: int) -> dict[str, Any]:
    """Không rõ loại chỉ số: duyệt trang đầu danh sách bệnh nhân, song song, báo rõ phạm vi."""
    patients, total = await fetch_patient_page(client)
    per_patient_limit = min(limit, 5)
    results = await map_patients(
        [patient for patient in patients if patient.get("id")],
        lambda patient: search_observations(
            client, patient["id"], limit=per_patient_limit, observation_type=observation_type
        ),
    )
    summaries: list[str] = []
    evidence: list[dict[str, Any]] = []
    for patient, observations in zip([p for p in patients if p.get("id")], results):
        patient_label = format_patient_identity(patient)
        if not observations:
            summaries.append(f"{patient_label}: không có bản ghi phù hợp")
            continue
        summaries.append(f"{patient_label}: " + "; ".join(format_observation(item) for item in observations))
        evidence.extend(_patient_evidence(observations, patient, patient_label))

    answer = format_all_patient_answer(
        summaries,
        empty_message="Không tìm thấy bệnh nhân nào để kiểm tra chỉ số/xét nghiệm.",
        prefix="Theo dữ liệu FHIR hiện có, chỉ số/xét nghiệm của các bệnh nhân là",
    )
    payload: dict[str, Any] = {
        "answer": answer + (coverage_note(len(patients), total) if summaries else ""),
        "intent": "observations",
        "patient_id": None,
        "all_patients": True,
        "evidence": evidence,
    }
    if observation_type:
        payload["observation_type"] = observation_type
    return payload


def _patient_evidence(
    observations: Iterable[dict[str, Any]], patient: dict[str, Any], patient_label: str
) -> list[dict[str, Any]]:
    return [
        evidence_item(
            "Observation",
            item.get("id"),
            f"{patient_label}: {display_vi(item.get('code'))}",
            with_patient_context(item, patient),
        )
        for item in observations
    ]

