from __future__ import annotations

import re
import unicodedata
from typing import Any


def normalize_text(text: str) -> str:
    normalized = unicodedata.normalize("NFD", text)
    without_accents = "".join(char for char in normalized if unicodedata.category(char) != "Mn")
    return without_accents.lower().replace("đ", "d").replace("Đ", "d")


def contains_any(text: str, keywords: list[str]) -> bool:
    return any(keyword in text for keyword in keywords)


def string_or_none(value: Any) -> str | None:
    return value if isinstance(value, str) and value.strip() else None


def clamp(value: int, minimum: int, maximum: int) -> int:
    return max(minimum, min(value, maximum))


def extract_phone(message: str) -> str | None:
    match = re.search(r"(?<!\d)(?:\+?84|0)[\d\s.-]{8,14}\d(?!\d)", message)
    if not match:
        return None
    return normalize_phone(match.group(0))


def normalize_phone(value: str | None) -> str | None:
    if not value:
        return None
    digits = re.sub(r"\D", "", value)
    if digits.startswith("84") and len(digits) >= 10:
        digits = "0" + digits[2:]
    return digits or None


def extract_birth_date(message: str) -> str | None:
    iso_match = re.search(r"\b(19|20)\d{2}-\d{2}-\d{2}\b", message)
    if iso_match:
        return iso_match.group(0)

    date_match = re.search(r"\b([0-3]?\d)[/-]([0-1]?\d)[/-]((?:19|20)\d{2})\b", message)
    if not date_match:
        return None
    day = int(date_match.group(1))
    month = int(date_match.group(2))
    year = int(date_match.group(3))
    if not (1 <= day <= 31 and 1 <= month <= 12):
        return None
    return f"{year:04d}-{month:02d}-{day:02d}"


def normalize_birth_date(value: str | None) -> str | None:
    if not value:
        return None
    parsed = extract_birth_date(value)
    return parsed or value.strip()


def extract_identifier(message: str) -> str | None:
    match = re.search(
        r"\b(?:identifier|ma dinh danh|mã định danh|cccd|cmnd|bhyt)\s*[:#-]?\s*([A-Za-z0-9.-]{4,})",
        message,
        flags=re.IGNORECASE,
    )
    return match.group(1) if match else None


def extract_patient_name(message: str) -> str | None:
    if contains_any(normalize_text(message), ["tat ca", "danh sach", "liet ke", "toan bo"]):
        return None

    patterns = [
        r"(?:bệnh nhân|benh nhan|patient)\s+([A-Za-zÀ-ỹ][A-Za-zÀ-ỹ\s.'-]{1,80})",
        r"(?:tên|ten|name)\s+(?:là|la)?\s*([A-Za-zÀ-ỹ][A-Za-zÀ-ỹ\s.'-]{1,80})",
        r"(?:của|cua)\s+(?:bệnh nhân|benh nhan)?\s*([A-Za-zÀ-ỹ][A-Za-zÀ-ỹ\s.'-]{1,80})",
        r"(?:tìm|tim|search|find)\s+(?:bệnh nhân|benh nhan|patient)?\s*([A-Za-zÀ-ỹ][A-Za-zÀ-ỹ\s.'-]{1,80})",
    ]
    for pattern in patterns:
        match = re.search(pattern, message, flags=re.IGNORECASE)
        if not match:
            continue
        cleaned = clean_name_candidate(match.group(1))
        if cleaned:
            return cleaned
    return None


def clean_name_candidate(candidate: str) -> str | None:
    value = re.sub(r"\s+", " ", candidate).strip(" .,'-")
    if not value:
        return None

    stop_phrases = [
        " sinh ngay",
        " ngày sinh",
        " ngay sinh",
        " so dien thoai",
        " số điện thoại",
        " sdt",
        " dang",
        " đang",
        " co ",
        " có ",
        " kham",
        " khám",
        " chan doan",
        " chẩn đoán",
        " huyet ap",
        " huyết áp",
        " thuoc",
        " thuốc",
        " thong tin",
        " thông tin",
    ]
    lowered = normalize_text(f" {value} ")
    cut_at = len(value)
    for phrase in stop_phrases:
        index = lowered.find(normalize_text(phrase))
        if index >= 0:
            cut_at = min(cut_at, max(0, index - 1))
    value = value[:cut_at].strip(" .,'-")

    lowered_value = normalize_text(value)
    if lowered_value.startswith(("sdt", "so dien thoai", "ngay sinh", "sinh ngay", "phone")):
        return None
    if lowered_value in {
        "ai", "nao", "hien co", "tat ca", "danh sach", "benh nhan",
        # Đại từ chỉ định thuần (không phải tên): "này", "kia", "ấy", "nó", "hiện tại".
        # ("đó" -> "do" bị loại để tránh trùng họ "Đỗ".)
        "nay", "kia", "ay", "no", "hien tai",
    }:
        return None
    if re.fullmatch(r"\d+", value):
        return None
    if len(value) < 2:
        return None
    return value


def normalize_patient_id(patient_id: Any) -> str | None:
    if not isinstance(patient_id, str):
        return None
    normalized = patient_id.removeprefix("Patient/").strip()
    return normalized or None
