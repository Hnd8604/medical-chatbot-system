"""Định dạng dữ liệu FHIR đã normalize thành câu tiếng Việt cho câu trả lời template.

Câu template là fallback của answer LLM và là câu trả lời cuối của template fast-path,
nên nó phải tự đứng được như một câu trả lời hoàn chỉnh.
"""

from __future__ import annotations

from typing import Any


_DISPLAY_VI = {
    "blood pressure": "Huyết áp",
    "blood_pressure": "Huyết áp",
    "huyet ap": "Huyết áp",
    "blood pressure panel with all children optional": "Huyết áp",
    "systolic blood pressure": "Huyết áp tâm thu",
    "diastolic blood pressure": "Huyết áp tâm trương",
    "blood glucose": "Đường huyết",
    "glucose [mass/volume] in blood": "Đường huyết",
    "heart rate": "Nhịp tim",
    "heart_rate": "Nhịp tim",
    "nhip tim": "Nhịp tim",
    "hemoglobin a1c/hemoglobin.total in blood": "HbA1c",
    "hba1c": "HbA1c",
    "cholesterol [mass/volume] in serum or plasma": "Cholesterol toàn phần",
    "total cholesterol": "Cholesterol toàn phần",
    "prediabetes": "Tiền đái tháo đường",
    "hypertension": "Tăng huyết áp",
    "essential (primary) hypertension": "Tăng huyết áp nguyên phát",
    "acute upper respiratory infection": "Nhiễm trùng đường hô hấp trên cấp",
    "acute upper respiratory infection, unspecified": "Nhiễm trùng đường hô hấp trên cấp",
    "type 2 diabetes mellitus": "Đái tháo đường type 2",
    "type 2 diabetes mellitus without complications": "Đái tháo đường type 2 không biến chứng",
    "hyperlipidemia": "Rối loạn lipid máu",
    "hyperlipidemia, unspecified": "Rối loạn lipid máu",
    "amlodipine 5 mg tablet": "Amlodipine 5 mg",
    "amlodipine 5 mg oral tablet": "Amlodipine 5 mg",
    "metformin 500 mg tablet": "Metformin 500 mg",
    "metformin 500 mg oral tablet": "Metformin 500 mg",
    "paracetamol 500 mg tablet": "Paracetamol 500 mg",
    "acetaminophen 500 mg oral tablet": "Paracetamol 500 mg",
    "atorvastatin 20 mg tablet": "Atorvastatin 20 mg",
    "atorvastatin 20 mg oral tablet": "Atorvastatin 20 mg",
    "losartan 50 mg tablet": "Losartan 50 mg",
    "losartan 50 mg oral tablet": "Losartan 50 mg",
}


def display_vi(value: Any) -> Any:
    if not isinstance(value, str):
        return value
    return _DISPLAY_VI.get(value.lower(), value)


def value_or_unknown(value: Any) -> Any:
    return value if value else "không rõ"


def gender_vi(gender: Any) -> str:
    if gender == "male":
        return "nam"
    if gender == "female":
        return "nữ"
    if gender == "other":
        return "khác"
    return "không rõ"


def evidence_item(resource_type: str, resource_id: Any, summary: Any, data: Any | None = None) -> dict[str, Any]:
    return {
        "resource_type": resource_type,
        "id": resource_id,
        "summary": summary,
        "data": data,
    }


def summary_evidence_item(kind: str, summary: str, data: dict[str, Any]) -> dict[str, Any]:
    """Evidence do code tính ra (xu hướng, thống kê nhóm bệnh nhân), không phải resource FHIR.

    ``pinned``: evidence budget luôn giữ mục này. Đặt ở CUỐI danh sách evidence để
    ``evidence[0]`` vẫn là resource FHIR thật (Spring dùng nó làm last_resource).
    """
    return {"resource_type": kind, "id": None, "summary": summary, "data": data, "pinned": True}


def format_resource_summary(resource_type: str, resource: dict[str, Any]) -> str:
    if resource_type == "Observation":
        return format_observation(resource)
    if resource_type == "Encounter":
        return format_encounter(resource)
    if resource_type == "Patient":
        return format_patient_summary(resource)
    if resource_type == "Condition":
        return display_vi(resource.get("code") or resource.get("id"))
    if resource_type == "MedicationRequest":
        return display_vi(resource.get("medication") or resource.get("id"))
    return str(resource.get("id") or resource_type)


def format_observation(observation: dict[str, Any]) -> str:
    code = display_vi(observation.get("code") or observation.get("id"))
    value = observation.get("value")
    if value:
        return f"{code} {value.get('value')} {value.get('unit') or ''}".strip()
    components = observation.get("components") or []
    component_text = ", ".join(
        (
            f"{display_vi(item.get('code'))} "
            f"{item.get('value', {}).get('value')} "
            f"{item.get('value', {}).get('unit') or ''}"
        ).strip()
        for item in components
    )
    return f"{code}: {component_text}" if component_text else str(code)


def format_encounter(encounter: dict[str, Any]) -> str:
    encounter_id = encounter.get("id") or "unknown"
    type_text = _first_text(encounter.get("type")) or display_vi(encounter.get("status")) or "lần khám"
    period = encounter.get("period") or {}
    start = period.get("start") if isinstance(period, dict) else None
    end = period.get("end") if isinstance(period, dict) else None
    location = _encounter_location_text(encounter)
    reason = _first_text(encounter.get("reason_code"))

    parts = [f"Encounter/{encounter_id} - {type_text}"]
    if start:
        parts.append(f"bắt đầu {start}")
    if end:
        parts.append(f"kết thúc {end}")
    if location:
        parts.append(f"địa điểm {location}")
    if reason:
        parts.append(f"lý do {reason}")
    return ", ".join(parts)


def format_patient_summary(patient: dict[str, Any]) -> str:
    patient_id = patient.get("id") or "unknown"
    name = value_or_unknown(patient.get("name"))
    gender = gender_vi(patient.get("gender"))
    birth_date = value_or_unknown(patient.get("birth_date"))
    phone = value_or_unknown(patient.get("phone"))
    return f"Patient/{patient_id} - {name}, giới tính {gender}, ngày sinh {birth_date}, SĐT {phone}"


def format_patient_identity(patient: dict[str, Any]) -> str:
    patient_id = patient.get("id") or "unknown"
    name = value_or_unknown(patient.get("name"))
    return f"Patient/{patient_id} ({name})"


def format_all_patient_answer(summaries: list[str], *, empty_message: str, prefix: str) -> str:
    if not summaries:
        return empty_message
    return f"{prefix}: " + "; ".join(summaries) + "."


def format_search_criteria(criteria: dict[str, Any]) -> str:
    parts = []
    if criteria.get("name"):
        parts.append(f"ten '{criteria['name']}'")
    if criteria.get("phone"):
        parts.append(f"so dien thoai '{criteria['phone']}'")
    if criteria.get("birth_date"):
        parts.append(f"ngay sinh '{criteria['birth_date']}'")
    if criteria.get("identifier"):
        parts.append(f"ma dinh danh '{criteria['identifier']}'")
    return ", ".join(parts)


def _first_text(items: Any) -> str | None:
    if not isinstance(items, list):
        return None
    for item in items:
        if isinstance(item, dict) and item.get("text"):
            return item["text"]
    return None


def _encounter_location_text(encounter: dict[str, Any]) -> str | None:
    locations = encounter.get("location") or []
    if not isinstance(locations, list):
        return None
    for item in locations:
        if not isinstance(item, dict):
            continue
        location = item.get("location")
        if isinstance(location, dict) and location.get("display"):
            return location["display"]
    return None
