from __future__ import annotations

import re

from agents.intent.text_utils import (
    normalize_text,
    contains_any,
    normalize_patient_id,
    extract_phone,
    extract_birth_date,
    extract_identifier,
    extract_patient_name,
)
from agents.intent.vocabulary import PATIENT_LIST_KEYWORDS


SELF_PATIENT_REFERENCE_PHRASES = [
    "thong tin cua toi",
    "thong tin ca nhan cua toi",
    "ho so cua toi",
    "toi la ai",
    "so dien thoai cua toi",
    "ngay sinh cua toi",
]


def is_self_patient_reference(message: str) -> bool:
    return contains_any(normalize_text(message), SELF_PATIENT_REFERENCE_PHRASES)


def is_patient_list_request(message: str) -> bool:
    return contains_any(normalize_text(message), PATIENT_LIST_KEYWORDS)


def has_patient_search_criteria(plan: object) -> bool:
    return any([
        getattr(plan, "search_name", None),
        getattr(plan, "search_phone", None),
        getattr(plan, "search_birth_date", None),
        getattr(plan, "search_identifier", None),
    ])


def extract_patient_search_criteria(message: str) -> dict[str, str]:
    if resolve_explicit_patient_id(message) or is_patient_list_request(message) or is_self_patient_reference(message):
        return {}

    criteria: dict[str, str] = {}
    phone = extract_phone(message)
    if phone:
        criteria["search_phone"] = phone

    birth_date = extract_birth_date(message)
    if birth_date:
        criteria["search_birth_date"] = birth_date

    identifier = extract_identifier(message)
    if identifier:
        criteria["search_identifier"] = identifier

    name = extract_patient_name(message)
    if name:
        criteria["search_name"] = name

    return criteria


def resolve_patient_id_for_request(message: str, provided_patient_id: str | None = None) -> str | None:
    return (
        resolve_explicit_patient_id(message)
        or normalize_patient_id(provided_patient_id)
    )


def resolve_patient_id(message: str) -> str | None:
    return resolve_explicit_patient_id(message)


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
