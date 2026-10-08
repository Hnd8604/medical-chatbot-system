"""Nhận diện cách câu hỏi tham chiếu tới bệnh nhân: mã rõ ràng, "của tôi", "bệnh nhân này", tiêu chí tìm kiếm."""

from __future__ import annotations

import re

from nlp.text import (
    normalize_text,
    contains_any,
    normalize_patient_id,
    extract_phone,
    extract_birth_date,
    extract_identifier,
    extract_patient_name,
)
from nlp.vocabulary import PATIENT_LIST_KEYWORDS


SELF_PATIENT_REFERENCE_PHRASES = [
    "thong tin cua toi",
    "thong tin ca nhan cua toi",
    "ho so cua toi",
    "toi la ai",
    "so dien thoai cua toi",
    "ngay sinh cua toi",
]

# Tham chiếu ngữ cảnh tới bệnh nhân đang chọn ("bệnh nhân này/đó", "hồ sơ này"...).
# Đây KHÔNG phải tiêu chí tìm kiếm: nếu để nguyên, extract_patient_name sẽ bắt nhầm
# "này"/"đó" thành tên -> tìm kiếm rỗng dù đã có patient_id đang chọn.
CONTEXTUAL_PATIENT_REFERENCE_PHRASES = [
    "benh nhan nay",
    "benh nhan do",
    "benh nhan kia",
    "benh nhan hien tai",
    "benh nhan dang chon",
    "benh nhan da chon",
    "nguoi benh nay",
    "nguoi benh do",
    "ho so nay",
    "ho so benh nhan nay",
]


def is_self_patient_reference(message: str) -> bool:
    return contains_any(normalize_text(message), SELF_PATIENT_REFERENCE_PHRASES)


def is_contextual_patient_reference(message: str) -> bool:
    return contains_any(normalize_text(message), CONTEXTUAL_PATIENT_REFERENCE_PHRASES)


def is_patient_list_request(message: str) -> bool:
    return contains_any(normalize_text(message), PATIENT_LIST_KEYWORDS)


def extract_patient_search_criteria(message: str) -> dict[str, str]:
    """Tiêu chí tìm bệnh nhân trong câu hỏi, khoá trùng tham số của tool ``search_patients``."""
    if (
        resolve_explicit_patient_id(message)
        or is_patient_list_request(message)
        or is_self_patient_reference(message)
        or is_contextual_patient_reference(message)
    ):
        return {}

    criteria: dict[str, str] = {}
    phone = extract_phone(message)
    if phone:
        criteria["phone"] = phone

    birth_date = extract_birth_date(message)
    if birth_date:
        criteria["birth_date"] = birth_date

    identifier = extract_identifier(message)
    if identifier:
        criteria["identifier"] = identifier

    name = extract_patient_name(message)
    if name:
        criteria["name"] = name

    return criteria


def resolve_patient_id_for_request(message: str, provided_patient_id: str | None = None) -> str | None:
    return (
        resolve_explicit_patient_id(message)
        or normalize_patient_id(provided_patient_id)
    )


def resolve_explicit_patient_id(message: str) -> str | None:
    patient_ref = re.search(r"Patient/([A-Za-z0-9.-]+)", message, flags=re.IGNORECASE)
    if patient_ref:
        return patient_ref.group(1)

    # Ma ho so benh nhan thuc te, vd BN2026-00005.
    record_id = re.search(r"\bBN\d{4}-\d{5}\b", message, flags=re.IGNORECASE)
    if record_id:
        return record_id.group(0).upper()

    # Cho phep tra cuu ngan gon "benh nhan 5" -> BN2026-00005.
    numbered_patient = re.search(
        r"\b(?:patient|benh\s+nhan)\s*(?:so\s*)?[-#:]?\s*0*([1-9][0-9]*)\b",
        normalize_text(message),
        flags=re.IGNORECASE,
    )
    if numbered_patient:
        return f"BN2026-{int(numbered_patient.group(1)):05d}"

    return None
