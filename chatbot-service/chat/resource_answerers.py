import logging
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
from fhir.client import FhirClient, FhirClientError, FhirNotFoundError
from fhir.normalizer import (
    normalize_capability_statement,
    normalize_condition,
    normalize_condition_bundle,
    normalize_encounter,
    normalize_encounter_bundle,
    normalize_medication_request,
    normalize_medication_request_bundle,
    normalize_observation,
    normalize_observation_bundle,
    normalize_patient,
    normalize_patient_bundle,
)
from terminology.enrichment_service import get_enrichment_service

log = logging.getLogger(__name__)

# Các resource type mà get_resource_by_id được phép truy xuất; chặn các
# resource ngoài phạm vi sản phẩm (vd AuditEvent, Practitioner nội bộ).
SUPPORTED_RESOURCE_NORMALIZERS = {
    "Patient": normalize_patient,
    "Encounter": normalize_encounter,
    "Observation": normalize_observation,
    "Condition": normalize_condition,
    "MedicationRequest": normalize_medication_request,
}


async def _answer_fhir_status(client: FhirClient) -> dict[str, Any]:
    try:
        status_info = normalize_capability_statement(await client.get_metadata())
    except FhirClientError as exc:
        return {
            "answer": f"HAPI FHIR Server hiện không khả dụng: {exc.user_message}",
            "intent": "fhir_status",
            "patient_id": None,
            "evidence": [],
            "usage": _zero_usage(),
        }
    software = _value_or_unknown(status_info.get("software"))
    fhir_version = _value_or_unknown(status_info.get("fhir_version"))
    answer = (
        "HAPI FHIR Server đang hoạt động bình thường. "
        f"Phần mềm: {software}; phiên bản FHIR: {fhir_version}."
    )
    return {
        "answer": answer,
        "intent": "fhir_status",
        "patient_id": None,
        "evidence": [
            _evidence("CapabilityStatement", None, "Trạng thái HAPI FHIR Server", status_info)
        ],
        "usage": _zero_usage(),
    }


async def _answer_resource_by_id(client: FhirClient, plan: IntentPlan) -> dict[str, Any]:
    canonical_types = {name.lower(): name for name in SUPPORTED_RESOURCE_NORMALIZERS}
    resource_type = canonical_types.get((plan.resource_type or "").strip().lower())
    resource_id = (plan.resource_id or "").strip()
    if not resource_type or not resource_id:
        supported = ", ".join(SUPPORTED_RESOURCE_NORMALIZERS)
        return {
            "answer": (
                "Vui lòng cung cấp loại resource và mã resource cần tra cứu. "
                f"Các loại được hỗ trợ: {supported}."
            ),
            "intent": "resource",
            "patient_id": plan.patient_id,
            "evidence": [],
            "usage": _zero_usage(),
        }

    try:
        resource = SUPPORTED_RESOURCE_NORMALIZERS[resource_type](
            await client.get_resource(resource_type, resource_id)
        )
    except FhirNotFoundError:
        return {
            "answer": f"Không tìm thấy {resource_type}/{resource_id} trong FHIR Server.",
            "intent": "resource",
            "patient_id": plan.patient_id,
            "evidence": [],
            "usage": _zero_usage(),
        }

    summary = _resource_summary(resource_type, resource)
    answer = f"Theo dữ liệu FHIR hiện có, {resource_type}/{resource_id}: {summary}."
    return {
        "answer": answer,
        "intent": "resource",
        "patient_id": plan.patient_id,
        "evidence": [_evidence(resource_type, resource.get("id") or resource_id, summary, resource)],
        "usage": _zero_usage(),
    }


def _resource_summary(resource_type: str, resource: dict[str, Any]) -> str:
    if resource_type == "Patient":
        return _format_patient_summary(resource)
    if resource_type == "Encounter":
        return _format_encounter(resource)
    if resource_type == "Observation":
        return _format_observation(resource)
    if resource_type == "Condition":
        return _display_vi(resource.get("code") or resource.get("id"))
    return _display_vi(resource.get("medication") or resource.get("id"))


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
        criteria_prefix = f" phù hợp với {criteria_text}" if criteria_text else ""
        answer = (
            f"Theo dữ liệu FHIR hiện có, hệ thống tìm thấy {len(patients)} bệnh nhân: "
            f"{summary}."
        )
        if criteria_prefix:
            answer = f"Theo dữ liệu FHIR hiện có, hệ thống tìm thấy {len(patients)} bệnh nhân{criteria_prefix}: {summary}."
        if has_patient_search_criteria(plan) and len(patients) > 1:
            answer = (
                "Tìm thấy nhiều bệnh nhân phù hợp. "
                "Vui lòng chọn đúng bệnh nhân hoặc cung cấp thêm ngày sinh, số điện thoại, mã định danh: "
                f"{summary}."
            )
    return {
        "answer": answer,
        "intent": "list_patients",
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
        f"Theo dữ liệu FHIR hiện có, Bệnh nhân {patient_id} có họ tên {name}, "
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
                f"cho Bệnh nhân {patient_id}."
            )
        else:
            answer = f"Không tìm thấy bản ghi chỉ số/xét nghiệm nào cho Bệnh nhân {patient_id}."
    else:
        summary = "; ".join(_format_observation(item) for item in observations)
        answer = (
            f"Theo dữ liệu FHIR hiện có, Bệnh nhân {patient_id} có "
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
            summaries.append(f"{patient_label}: không có bản ghi lần khám")

    answer = _format_all_patient_answer(
        summaries,
        empty_message="Không tìm thấy bệnh nhân nào để kiểm tra lần khám.",
        prefix="Theo dữ liệu FHIR hiện có, lần khám của các bệnh nhân là",
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
        answer = f"Không tìm thấy bản ghi lần khám nào cho Bệnh nhân {patient_id}."
    else:
        summary = "; ".join(_format_encounter(item) for item in encounters)
        answer = (
            f"Theo dữ liệu FHIR hiện có, Bệnh nhân {patient_id} có "
            f"{len(encounters)} bản ghi lần khám gần đây: {summary}."
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
        answer = f"Không tìm thấy bản ghi chẩn đoán/tình trạng bệnh nào cho Bệnh nhân {patient_id}."
    else:
        condition_names = ", ".join(_display_vi(item.get("code") or item.get("id")) for item in conditions)
        answer = (
            f"Theo dữ liệu FHIR hiện có, Bệnh nhân {patient_id} có "
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
        answer = f"Không tìm thấy bản ghi thuốc nào cho Bệnh nhân {patient_id}."
    else:
        medication_names = ", ".join(_display_vi(item.get("medication") or item.get("id")) for item in medications)
        answer = (
            f"Theo dữ liệu FHIR hiện có, Bệnh nhân {patient_id} có "
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
    if not has_patient_search_criteria(plan) and not plan.patient_id:
        return {
            "answer": (
                "Mình chưa xác định được bệnh nhân cụ thể. "
                "Hãy chọn bệnh nhân trên giao diện hoặc cung cấp mã bệnh nhân, tên, số điện thoại, ngày sinh hay mã định danh."
            ),
            "intent": plan.intent,
            "patient_id": None,
            "evidence": [],
            "usage": _zero_usage(),
        }

    if not has_patient_search_criteria(plan):
        return plan.patient_id

    patients = await _search_patients_for_plan(client, plan, limit=3)
    if len(patients) == 1:
        patient_id = patients[0].get("id")
        return patient_id if isinstance(patient_id, str) and patient_id else plan.patient_id

    criteria_text = _format_patient_search_criteria(plan)
    if not patients:
        answer = f"Không tìm thấy bệnh nhân phù hợp với {criteria_text or 'tiêu chí đã cung cấp'}."
    else:
        summary = "; ".join(_format_patient_summary(patient) for patient in patients)
        answer = (
            "Tìm thấy nhiều bệnh nhân phù hợp. "
            "Vui lòng cung cấp thêm mã bệnh nhân, ngày sinh hoặc số điện thoại để xác định chính xác: "
            f"{summary}."
        )

    return {
        "answer": answer,
        "intent": "list_patients",
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


LOINC_SYSTEM = "http://loinc.org"


async def _answer_explain_concept(plan: IntentPlan) -> dict[str, Any]:
    """Explain a standalone medical concept (no patient), e.g. "HbA1c là gì".

    Không truy xuất FHIR — dựng evidence tổng hợp từ term/code rồi tra terminology
    (LOINC/RxNorm/MedlinePlus). Kết quả gắn top-level ``external_knowledge`` để answer
    generator tóm tắt sang tiếng Việt; ``evidence`` để rỗng (không có dữ liệu bệnh nhân).
    """
    term = (plan.term or "").strip()
    code = (plan.code or "").strip()
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
        fallback = f"Dưới đây là thông tin tham khảo về {label} từ nguồn thuật ngữ y khoa."
    else:
        fallback = (
            f"Hiện chưa tra được thông tin thuật ngữ cho \"{label}\". "
            "Bạn có thể cung cấp mã chuẩn (ví dụ mã LOINC của xét nghiệm) để tra chính xác hơn."
        )

    payload: dict[str, Any] = {
        "answer": fallback,
        "intent": "explain_concept",
        "patient_id": None,
        "evidence": [],
        "usage": _zero_usage(),
    }
    if external_knowledge:
        payload["external_knowledge"] = external_knowledge
    return payload


def _synthetic_concept_evidence(term: str, code: str) -> list[dict[str, Any]]:
    """Build synthetic evidence so the terminology extractor can resolve a bare concept.

    - Có mã: coi như mã LOINC (phổ biến cho câu hỏi "chỉ số/xét nghiệm") -> Observation.
    - Có tên: dùng làm medication text để RxNorm tra theo tên (find_rxcui_by_string) +
      MedlinePlus tra thuốc theo tên. Việc tra LOINC theo tên tự do (\\$expand) là mở rộng sau.
    """
    items: list[dict[str, Any]] = []
    text = term or None
    if code:
        items.append(
            _evidence(
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
            _evidence(
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
