from typing import Any

from agents.intent_extractor import IntentPlan
from chat.text_helpers import _display_vi, _gender_vi, _value_or_unknown


def _format_resource_summary(resource_type: str, resource: dict[str, Any]) -> str:
    if resource_type == "Observation":
        return _format_observation(resource)
    if resource_type == "Encounter":
        return _format_encounter(resource)
    if resource_type == "Patient":
        return _format_patient_summary(resource)
    if resource_type == "Condition":
        return _display_vi(resource.get("code") or resource.get("id"))
    if resource_type == "MedicationRequest":
        return _display_vi(resource.get("medication") or resource.get("id"))
    return str(resource.get("id") or resource_type)

def _format_observation(observation: dict[str, Any]) -> str:
    code = _display_vi(observation.get("code") or observation.get("id"))
    value = observation.get("value")
    if value:
        return f"{code} {value.get('value')} {value.get('unit') or ''}".strip()
    components = observation.get("components") or []
    component_text = ", ".join(
        (
            f"{_display_vi(item.get('code'))} "
            f"{item.get('value', {}).get('value')} "
            f"{item.get('value', {}).get('unit') or ''}"
        ).strip()
        for item in components
    )
    return f"{code}: {component_text}" if component_text else str(code)

def _format_encounter(encounter: dict[str, Any]) -> str:
    encounter_id = encounter.get("id") or "unknown"
    type_text = _first_text(encounter.get("type")) or _display_vi(encounter.get("status")) or "lần khám"
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

def _format_patient_summary(patient: dict[str, Any]) -> str:
    patient_id = patient.get("id") or "unknown"
    name = _value_or_unknown(patient.get("name"))
    gender = _gender_vi(patient.get("gender"))
    birth_date = _value_or_unknown(patient.get("birth_date"))
    phone = _value_or_unknown(patient.get("phone"))
    return f"Patient/{patient_id} - {name}, giới tính {gender}, ngày sinh {birth_date}, SĐT {phone}"

def _format_patient_identity(patient: dict[str, Any]) -> str:
    patient_id = patient.get("id") or "unknown"
    name = _value_or_unknown(patient.get("name"))
    return f"Patient/{patient_id} ({name})"

def _format_all_patient_answer(summaries: list[str], *, empty_message: str, prefix: str) -> str:
    if not summaries:
        return empty_message
    return f"{prefix}: " + "; ".join(summaries) + "."

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

def _format_patient_search_criteria(plan: IntentPlan) -> str:
    parts = []
    if plan.search_name:
        parts.append(f"ten '{plan.search_name}'")
    if plan.search_phone:
        parts.append(f"so dien thoai '{plan.search_phone}'")
    if plan.search_birth_date:
        parts.append(f"ngay sinh '{plan.search_birth_date}'")
    if plan.search_identifier:
        parts.append(f"ma dinh danh '{plan.search_identifier}'")
    return ", ".join(parts)
