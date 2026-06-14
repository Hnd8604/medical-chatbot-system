from typing import Any

from agents.intent_extractor import IntentPlan, has_patient_search_criteria, normalize_text
from chat.formatters import (
    _format_all_patient_answer,
    _format_encounter,
    _format_observation,
    _format_patient_identity,
    _format_patient_search_criteria,
    _format_patient_summary,
)
from chat.response_builder import _zero_usage
from chat.text_helpers import _contains_any, _display_vi, _gender_vi, _value_or_unknown
from fhir.client import FhirClient
from fhir.normalizer import (
    normalize_condition_bundle,
    normalize_encounter_bundle,
    normalize_medication_request_bundle,
    normalize_observation_bundle,
    normalize_patient,
    normalize_patient_bundle,
)


async def _answer_patients(client: FhirClient, plan: IntentPlan) -> dict[str, Any]:
    bundle = await client.search_patients_flexible(
        count=plan.limit,
        name=plan.search_name,
        phone=plan.search_phone,
        birth_date=plan.search_birth_date,
        identifier=plan.search_identifier,
    )
    patients = normalize_patient_bundle(bundle)
    if not patients:
        answer = "Không tìm thấy bệnh nhân nào trong FHIR Server."
    else:
        summary = "; ".join(_format_patient_summary(patient) for patient in patients)
        criteria_text = _format_patient_search_criteria(plan)
        criteria_prefix = f" phu hop voi {criteria_text}" if criteria_text else ""
        answer = (
            f"Theo dữ liệu FHIR hiện có, hệ thống tìm thấy {len(patients)} bệnh nhân: "
            f"{summary}."
        )
        if criteria_prefix:
            answer = f"Theo du lieu FHIR hien co, he thong tim thay {len(patients)} benh nhan{criteria_prefix}: {summary}."
        if has_patient_search_criteria(plan) and len(patients) > 1:
            answer = (
                "Tim thay nhieu benh nhan phu hop. "
                "Vui long chon dung benh nhan hoac cung cap them ngay sinh, so dien thoai, ma dinh danh: "
                f"{summary}."
            )
    return {
        "answer": answer,
        "intent": "patients",
        "patient_id": None,
        "evidence": [
            _evidence("Patient", patient.get("id"), _format_patient_summary(patient), patient)
            for patient in patients
        ],
        **_patient_selection_payload(plan, patients),
        "usage": _zero_usage(),
    }

async def _answer_patient(client: FhirClient, patient_id: str) -> dict[str, Any]:
    patient = normalize_patient(await client.get_patient(patient_id))
    phone = _value_or_unknown(patient.get("phone"))
    name = _value_or_unknown(patient.get("name"))
    gender = _gender_vi(patient.get("gender"))
    birth_date = _value_or_unknown(patient.get("birth_date"))
    answer = (
        f"Theo dữ liệu FHIR hiện có, Bệnh nhân Patient/{patient_id} có họ tên {name}, "
        f"giới tính {gender}, ngày sinh {birth_date}, số điện thoại {phone}."
    )
    summary = (
        f"Họ tên: {name}; "
        f"giới tính: {gender}; "
        f"ngày sinh: {birth_date}; "
        f"số điện thoại: {phone}"
    )
    return {
        "answer": answer,
        "intent": "patient",
        "patient_id": patient_id,
        "evidence": [_evidence("Patient", patient.get("id"), summary, patient)],
        "usage": _zero_usage(),
    }

async def _answer_all_patient_observations(
    client: FhirClient,
    limit: int,
    observation_type: str | None = None,
) -> dict[str, Any]:
    patients = await _get_patients(client, limit=20)
    summaries = []
    evidence = []
    for patient in patients:
        patient_id = patient.get("id")
        if not patient_id:
            continue
        search_count = max(limit, 20) if observation_type else limit
        bundle = await client.search_patient_resources(
            "Observation",
            patient_id,
            count=search_count,
            sort="-date",
        )
        observations = normalize_observation_bundle(bundle)
        if observation_type:
            observations = [
                item for item in observations
                if _observation_matches_type(item, observation_type)
            ][:limit]
        patient_label = _format_patient_identity(patient)
        if observations:
            observation_summary = "; ".join(_format_observation(item) for item in observations)
            summaries.append(f"{patient_label}: {observation_summary}")
            evidence.extend(
                _evidence(
                    "Observation",
                    item.get("id"),
                    f"{patient_label}: {_display_vi(item.get('code'))}",
                    _with_patient_context(item, patient),
                )
                for item in observations
            )
        else:
            summaries.append(f"{patient_label}: không có bản ghi phù hợp")

    answer = _format_all_patient_answer(
        summaries,
        empty_message="Không tìm thấy bệnh nhân nào để kiểm tra chỉ số/xét nghiệm.",
        prefix="Theo dữ liệu FHIR hiện có, chỉ số/xét nghiệm của các bệnh nhân là",
    )
    return {
        "answer": answer,
        "intent": "observations",
        "patient_id": None,
        "evidence": evidence,
        "usage": _zero_usage(),
    }

async def _answer_observations(
    client: FhirClient,
    patient_id: str,
    limit: int,
    observation_type: str | None = None,
) -> dict[str, Any]:
    search_count = max(limit, 20) if observation_type else limit
    bundle = await client.search_patient_resources("Observation", patient_id, count=search_count, sort="-date")
    observations = normalize_observation_bundle(bundle)
    if observation_type:
        observations = [
            item for item in observations
            if _observation_matches_type(item, observation_type)
        ][:limit]

    if not observations:
        if observation_type:
            answer = (
                f"Không tìm thấy bản ghi {_display_vi(observation_type)} phù hợp "
                f"cho Bệnh nhân Patient/{patient_id}."
            )
        else:
            answer = f"Không tìm thấy bản ghi chỉ số/xét nghiệm nào cho Bệnh nhân Patient/{patient_id}."
    else:
        summary = "; ".join(_format_observation(item) for item in observations)
        answer = (
            f"Theo dữ liệu FHIR hiện có, Bệnh nhân Patient/{patient_id} có "
            f"{len(observations)} bản ghi chỉ số/xét nghiệm gần đây: {summary}."
        )
    return {
        "answer": answer,
        "intent": "observations",
        "patient_id": patient_id,
        "evidence": [
            _evidence("Observation", item.get("id"), _display_vi(item.get("code")), item)
            for item in observations
        ],
        "usage": _zero_usage(),
    }

async def _answer_all_patient_encounters(client: FhirClient, limit: int) -> dict[str, Any]:
    patients = await _get_patients(client, limit=20)
    summaries = []
    evidence = []
    for patient in patients:
        patient_id = patient.get("id")
        if not patient_id:
            continue
        bundle = await client.search_patient_resources(
            "Encounter",
            patient_id,
            count=limit,
            sort="-date",
        )
        encounters = normalize_encounter_bundle(bundle)
        patient_label = _format_patient_identity(patient)
        if encounters:
            encounter_summary = "; ".join(_format_encounter(item) for item in encounters)
            summaries.append(f"{patient_label}: {encounter_summary}")
            evidence.extend(
                _evidence(
                    "Encounter",
                    item.get("id"),
                    f"{patient_label}: {_format_encounter(item)}",
                    _with_patient_context(item, patient),
                )
                for item in encounters
            )
        else:
            summaries.append(f"{patient_label}: khÃ´ng cÃ³ báº£n ghi láº§n khÃ¡m")

    answer = _format_all_patient_answer(
        summaries,
        empty_message="KhÃ´ng tÃ¬m tháº¥y bá»‡nh nhÃ¢n nÃ o Ä‘á»ƒ kiá»ƒm tra láº§n khÃ¡m.",
        prefix="Theo dá»¯ liá»‡u FHIR hiá»‡n cÃ³, láº§n khÃ¡m cá»§a cÃ¡c bá»‡nh nhÃ¢n lÃ ",
    )
    return {
        "answer": answer,
        "intent": "encounters",
        "patient_id": None,
        "evidence": evidence,
        "usage": _zero_usage(),
    }

async def _answer_encounters(client: FhirClient, patient_id: str, limit: int) -> dict[str, Any]:
    bundle = await client.search_patient_resources(
        "Encounter",
        patient_id,
        count=limit,
        sort="-date",
    )
    encounters = normalize_encounter_bundle(bundle)
    if not encounters:
        answer = f"KhÃ´ng tÃ¬m tháº¥y báº£n ghi láº§n khÃ¡m nÃ o cho Bá»‡nh nhÃ¢n Patient/{patient_id}."
    else:
        summary = "; ".join(_format_encounter(item) for item in encounters)
        answer = (
            f"Theo dá»¯ liá»‡u FHIR hiá»‡n cÃ³, Bá»‡nh nhÃ¢n Patient/{patient_id} cÃ³ "
            f"{len(encounters)} báº£n ghi láº§n khÃ¡m gáº§n Ä‘Ã¢y: {summary}."
        )
    return {
        "answer": answer,
        "intent": "encounters",
        "patient_id": patient_id,
        "evidence": [
            _evidence("Encounter", item.get("id"), _format_encounter(item), item)
            for item in encounters
        ],
        "usage": _zero_usage(),
    }

async def _answer_all_patient_conditions(client: FhirClient, limit: int) -> dict[str, Any]:
    patients = await _get_patients(client, limit=20)
    summaries = []
    evidence = []
    for patient in patients:
        patient_id = patient.get("id")
        if not patient_id:
            continue
        bundle = await client.search_patient_resources("Condition", patient_id, count=limit)
        conditions = normalize_condition_bundle(bundle)
        patient_label = _format_patient_identity(patient)
        if conditions:
            condition_names = ", ".join(_display_vi(item.get("code") or item.get("id")) for item in conditions)
            summaries.append(f"{patient_label}: {condition_names}")
            evidence.extend(
                _evidence(
                    "Condition",
                    item.get("id"),
                    f"{patient_label}: {_display_vi(item.get('code'))}",
                    _with_patient_context(item, patient),
                )
                for item in conditions
            )
        else:
            summaries.append(f"{patient_label}: không có bản ghi chẩn đoán")

    answer = _format_all_patient_answer(
        summaries,
        empty_message="Không tìm thấy bệnh nhân nào để kiểm tra chẩn đoán.",
        prefix="Theo dữ liệu FHIR hiện có, chẩn đoán/tình trạng bệnh của các bệnh nhân là",
    )
    return {
        "answer": answer,
        "intent": "conditions",
        "patient_id": None,
        "evidence": evidence,
        "usage": _zero_usage(),
    }

async def _answer_conditions(client: FhirClient, patient_id: str, limit: int) -> dict[str, Any]:
    bundle = await client.search_patient_resources("Condition", patient_id, count=limit)
    conditions = normalize_condition_bundle(bundle)
    if not conditions:
        answer = f"Không tìm thấy bản ghi chẩn đoán/tình trạng bệnh nào cho Bệnh nhân Patient/{patient_id}."
    else:
        condition_names = ", ".join(_display_vi(item.get("code") or item.get("id")) for item in conditions)
        answer = (
            f"Theo dữ liệu FHIR hiện có, Bệnh nhân Patient/{patient_id} có "
            f"{len(conditions)} bản ghi chẩn đoán/tình trạng bệnh: {condition_names}."
        )
    return {
        "answer": answer,
        "intent": "conditions",
        "patient_id": patient_id,
        "evidence": [
            _evidence("Condition", item.get("id"), _display_vi(item.get("code")), item)
            for item in conditions
        ],
        "usage": _zero_usage(),
    }

async def _answer_all_patient_medications(client: FhirClient, limit: int) -> dict[str, Any]:
    patients = await _get_patients(client, limit=20)
    summaries = []
    evidence = []
    for patient in patients:
        patient_id = patient.get("id")
        if not patient_id:
            continue
        bundle = await client.search_patient_resources("MedicationRequest", patient_id, count=limit)
        medications = normalize_medication_request_bundle(bundle)
        patient_label = _format_patient_identity(patient)
        if medications:
            medication_names = ", ".join(_display_vi(item.get("medication") or item.get("id")) for item in medications)
            summaries.append(f"{patient_label}: {medication_names}")
            evidence.extend(
                _evidence(
                    "MedicationRequest",
                    item.get("id"),
                    f"{patient_label}: {_display_vi(item.get('medication'))}",
                    _with_patient_context(item, patient),
                )
                for item in medications
            )
        else:
            summaries.append(f"{patient_label}: không có bản ghi thuốc")

    answer = _format_all_patient_answer(
        summaries,
        empty_message="Không tìm thấy bệnh nhân nào để kiểm tra thuốc.",
        prefix="Theo dữ liệu FHIR hiện có, thuốc của các bệnh nhân là",
    )
    return {
        "answer": answer,
        "intent": "medications",
        "patient_id": None,
        "evidence": evidence,
        "usage": _zero_usage(),
    }

async def _answer_medications(client: FhirClient, patient_id: str, limit: int) -> dict[str, Any]:
    bundle = await client.search_patient_resources("MedicationRequest", patient_id, count=limit)
    medications = normalize_medication_request_bundle(bundle)
    if not medications:
        answer = f"Không tìm thấy bản ghi thuốc nào cho Bệnh nhân Patient/{patient_id}."
    else:
        medication_names = ", ".join(_display_vi(item.get("medication") or item.get("id")) for item in medications)
        answer = (
            f"Theo dữ liệu FHIR hiện có, Bệnh nhân Patient/{patient_id} có "
            f"{len(medications)} y lệnh thuốc: {medication_names}."
        )
    return {
        "answer": answer,
        "intent": "medications",
        "patient_id": patient_id,
        "evidence": [
            _evidence("MedicationRequest", item.get("id"), _display_vi(item.get("medication")), item)
            for item in medications
        ],
        "usage": _zero_usage(),
    }

async def _resolve_patient_id_for_tool(client: FhirClient, plan: IntentPlan) -> str | dict[str, Any]:
    if not has_patient_search_criteria(plan):
        return plan.patient_id

    patients = await _search_patients_for_plan(client, plan, limit=3)
    if len(patients) == 1:
        patient_id = patients[0].get("id")
        return patient_id if isinstance(patient_id, str) and patient_id else plan.patient_id

    criteria_text = _format_patient_search_criteria(plan)
    if not patients:
        answer = f"Khong tim thay benh nhan phu hop voi {criteria_text or 'tieu chi da cung cap'}."
    else:
        summary = "; ".join(_format_patient_summary(patient) for patient in patients)
        answer = (
            "Tim thay nhieu benh nhan phu hop. "
            "Vui long cung cap them ma benh nhan, ngay sinh hoac so dien thoai de xac dinh chinh xac: "
            f"{summary}."
        )

    return {
        "answer": answer,
        "intent": "patients",
        "patient_id": None,
        "evidence": [
            _evidence("Patient", patient.get("id"), _format_patient_summary(patient), patient)
            for patient in patients
        ],
        **_patient_selection_payload(plan, patients),
        "usage": _zero_usage(),
    }

async def _get_patients(client: FhirClient, limit: int) -> list[dict[str, Any]]:
    bundle = await client.search_patients(count=limit)
    return normalize_patient_bundle(bundle)

async def _search_patients_for_plan(client: FhirClient, plan: IntentPlan, limit: int | None = None) -> list[dict[str, Any]]:
    bundle = await client.search_patients_flexible(
        count=limit or plan.limit,
        name=plan.search_name,
        phone=plan.search_phone,
        birth_date=plan.search_birth_date,
        identifier=plan.search_identifier,
    )
    return normalize_patient_bundle(bundle)

def _evidence(resource_type: str, resource_id: Any, summary: Any, data: Any | None = None) -> dict[str, Any]:
    return {
        "resource_type": resource_type,
        "id": resource_id,
        "summary": summary,
        "data": data,
    }

def _patient_selection_payload(plan: IntentPlan, patients: list[dict[str, Any]]) -> dict[str, Any]:
    if not has_patient_search_criteria(plan) or len(patients) <= 1:
        return {}
    return {
        "needs_patient_selection": True,
        "patient_candidates": [_patient_candidate(patient) for patient in patients],
    }

def _patient_candidate(patient: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": patient.get("id"),
        "name": patient.get("name"),
        "gender": patient.get("gender"),
        "birth_date": patient.get("birth_date"),
        "phone": patient.get("phone"),
        "identifier": patient.get("identifier"),
    }

def _with_patient_context(resource: dict[str, Any], patient: dict[str, Any]) -> dict[str, Any]:
    return {
        **resource,
        "patient": {
            "id": patient.get("id"),
            "name": patient.get("name"),
            "gender": patient.get("gender"),
            "birth_date": patient.get("birth_date"),
            "phone": patient.get("phone"),
        },
    }

def _observation_matches_type(observation: dict[str, Any], observation_type: str) -> bool:
    wanted = normalize_text(observation_type)
    searchable_parts = [
        normalize_text(str(observation.get("code") or "")),
        *[
            normalize_text(str(component.get("code") or ""))
            for component in observation.get("components", [])
        ],
    ]
    searchable = " ".join(searchable_parts)

    if _contains_any(wanted, ["blood pressure", "blood_pressure", "huyet ap"]):
        return _contains_any(searchable, ["blood pressure", "systolic", "diastolic", "huyet ap"])
    if _contains_any(wanted, ["glucose", "duong huyet"]):
        return _contains_any(searchable, ["glucose", "duong huyet"])
    if _contains_any(wanted, ["heart rate", "heart_rate", "nhip tim"]):
        return _contains_any(searchable, ["heart rate", "nhip tim"])
    if _contains_any(wanted, ["cholesterol"]):
        return _contains_any(searchable, ["cholesterol"])
    if _contains_any(wanted, ["hba1c", "a1c"]):
        return _contains_any(searchable, ["hba1c", "a1c", "hemoglobin"])
    return wanted in searchable
