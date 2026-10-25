"""Loại chỉ số/xét nghiệm: suy từ câu hỏi, ánh xạ sang mã LOINC và lọc Observation.

Mỗi loại có danh sách mã LOINC để tool lọc **ngay trên FHIR server** (tham số ``code``),
thay vì tải N bản ghi mới nhất rồi mới lọc — cách cũ báo nhầm "không có dữ liệu" khi
bệnh nhân có nhiều sinh hiệu mới hơn xét nghiệm cần tìm.
"""

from __future__ import annotations

import re
from typing import Any

from nlp.text import normalize_text


LOINC_SYSTEM = "http://loinc.org"

# Loại chỉ số → mã LOINC thường gặp. Mã đầu tiên là mã "chính" của loại.
OBSERVATION_LOINC_CODES: dict[str, tuple[str, ...]] = {
    "blood_pressure": ("85354-9", "55284-4", "8480-6", "8462-4"),
    "glucose": ("2339-0", "2345-7", "15074-8", "1558-6"),
    "heart_rate": ("8867-4",),
    "ldl": ("18262-6", "13457-7", "2089-1"),
    "hdl": ("2085-9",),
    "cholesterol": ("2093-3",),
    "hba1c": ("4548-4", "17856-6"),
    "hemoglobin": ("718-7",),
    "spo2": ("2708-6", "59408-5"),
    "respiratory_rate": ("9279-1",),
    "body_temperature": ("8310-5",),
    "weight": ("29463-7", "3141-9"),
    "height": ("8302-2",),
    "bmi": ("39156-5",),
    "creatinine": ("2160-0", "38483-4"),
    "urea": ("3094-0", "6299-2"),
    "ferritin": ("2276-4",),
    "bilirubin": ("1975-2",),
    "ast": ("1920-8",),
    "alt": ("1742-6",),
    "triglyceride": ("2571-8",),
    "tsh": ("3016-3",),
}

OBSERVATION_TYPE_LABELS: dict[str, str] = {
    "blood_pressure": "huyết áp",
    "glucose": "đường huyết",
    "heart_rate": "nhịp tim",
    "ldl": "LDL-cholesterol",
    "hdl": "HDL-cholesterol",
    "cholesterol": "cholesterol toàn phần",
    "hba1c": "HbA1c",
    "hemoglobin": "hemoglobin",
    "spo2": "SpO2",
    "respiratory_rate": "nhịp thở",
    "body_temperature": "nhiệt độ cơ thể",
    "weight": "cân nặng",
    "height": "chiều cao",
    "bmi": "BMI",
    "creatinine": "creatinine",
    "urea": "ure",
    "ferritin": "ferritin",
    "bilirubin": "bilirubin",
    "ast": "AST (SGOT)",
    "alt": "ALT (SGPT)",
    "triglyceride": "triglyceride",
    "tsh": "TSH",
}

# Thành phần của huyết áp (Observation dạng component).
BP_SYSTOLIC_CODE = "8480-6"
BP_DIASTOLIC_CODE = "8462-4"
BP_COMPONENT_CODES = {"systolic": BP_SYSTOLIC_CODE, "diastolic": BP_DIASTOLIC_CODE}

# Thứ tự quan trọng: loại cụ thể đứng trước loại chung ("hemoglobin a1c" là HbA1c, không
# phải hemoglobin; "ldl cholesterol" là LDL). Mẫu chạy trên văn bản đã normalize_text.
_TYPE_PATTERNS: tuple[tuple[str, str], ...] = (
    ("blood_pressure", r"blood[ _]pressure|huyet ap|tam thu|tam truong|systolic|diastolic"),
    ("glucose", r"glucose|duong huyet|duong mau|duong trong mau"),
    ("heart_rate", r"heart[ _]rate|nhip tim|tan so tim"),
    ("ldl", r"\bldl\b"),
    ("hdl", r"\bhdl\b"),
    ("hba1c", r"hba1c|\ba1c\b|hemoglobin a1c"),
    ("cholesterol", r"cholesterol|cholesteron"),
    ("hemoglobin", r"hemoglobin|huyet sac to|\bhgb\b"),
    ("spo2", r"spo2|bao hoa oxy|oxygen saturation|oxy mau"),
    ("respiratory_rate", r"nhip tho|tan so tho|respiratory"),
    ("body_temperature", r"nhiet do|than nhiet|body[ _]temperature|\btemperature\b"),
    ("weight", r"can nang|\bweight\b"),
    ("height", r"chieu cao|\bheight\b"),
    ("bmi", r"\bbmi\b|chi so khoi co the"),
    ("creatinine", r"creatinin"),
    ("urea", r"\bure\b|\burea\b|\bbun\b"),
    ("ferritin", r"ferritin"),
    ("bilirubin", r"bilirubin"),
    ("ast", r"\bast\b|sgot"),
    ("alt", r"\balt\b|sgpt"),
    ("triglyceride", r"triglycerid"),
    ("tsh", r"\btsh\b|thyrotropin"),
)


def infer_observation_type(message: str) -> str | None:
    text = normalize_text(message or "")
    for observation_type, pattern in _TYPE_PATTERNS:
        if re.search(pattern, text):
            return observation_type
    return None


def canonical_observation_type(value: Any) -> str | None:
    """Đưa giá trị planner sinh ra ("huyết áp", "HbA1c", "blood_pressure") về khoá chuẩn."""
    if not isinstance(value, str) or not value.strip():
        return None
    key = normalize_text(value).strip().replace(" ", "_")
    if key in OBSERVATION_LOINC_CODES:
        return key
    return infer_observation_type(value)


def loinc_codes_for(observation_type: Any) -> tuple[str, ...]:
    canonical = canonical_observation_type(observation_type)
    return OBSERVATION_LOINC_CODES.get(canonical or "", ())


def loinc_code_param(observation_type: Any) -> str | None:
    """Giá trị tham số ``code`` của FHIR search (OR giữa các mã), hoặc None nếu chưa biết mã."""
    codes = loinc_codes_for(observation_type)
    return ",".join(f"{LOINC_SYSTEM}|{code}" for code in codes) or None


def observation_type_label(observation_type: Any) -> str:
    canonical = canonical_observation_type(observation_type)
    if canonical:
        return OBSERVATION_TYPE_LABELS.get(canonical, canonical)
    return str(observation_type or "chỉ số")


def infer_bp_component(message: str) -> str | None:
    text = normalize_text(message or "")
    if re.search(r"tam truong|diastolic", text):
        return "diastolic"
    if re.search(r"tam thu|systolic", text):
        return "systolic"
    return None


def observation_matches_type(observation: dict[str, Any], observation_type: str) -> bool:
    """Observation có thuộc loại được hỏi không: ưu tiên mã LOINC, rồi mới so tên."""
    canonical = canonical_observation_type(observation_type)
    codes = set(OBSERVATION_LOINC_CODES.get(canonical or "", ()))
    if codes and codes & _observation_codes(observation):
        return True

    searchable = " ".join(
        [
            normalize_text(str(observation.get("code") or "")),
            *[normalize_text(str(component.get("code") or "")) for component in observation.get("components") or []],
        ]
    )
    if canonical == "blood_pressure":
        return bool(re.search(r"blood pressure|systolic|diastolic|huyet ap", searchable))
    if canonical == "hemoglobin":
        # "Hemoglobin A1c" là HbA1c, không phải hemoglobin.
        return "hemoglobin" in searchable and "a1c" not in searchable
    if canonical:
        return infer_observation_type(searchable) == canonical
    return normalize_text(observation_type) in searchable


def _observation_codes(observation: dict[str, Any]) -> set[str]:
    codes: set[str] = set()
    for detail in [observation.get("code_detail")] + [
        component.get("code_detail") for component in observation.get("components") or []
    ]:
        if not isinstance(detail, dict):
            continue
        for coding in detail.get("coding") or []:
            if isinstance(coding, dict) and coding.get("code"):
                codes.add(str(coding["code"]))
    return codes
