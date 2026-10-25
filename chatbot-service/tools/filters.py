"""Tham số lọc dùng chung cho các tool: khoảng ngày và lần khám (encounter).

Mọi bộ lọc đều chạy **trên FHIR server** (tham số search), không lọc sau khi tải.
"""

from __future__ import annotations

from typing import Any

from nlp.time_range import normalize_iso_date, range_label


def date_params(search_param: str, date_from: Any = None, date_to: Any = None) -> dict[str, Any]:
    """``{"date": ["ge2026-01-01", "le2026-03-31"]}`` — FHIR lặp tham số = AND."""
    values = []
    start, end = normalize_iso_date(date_from), normalize_iso_date(date_to)
    if start:
        values.append(f"ge{start}")
    if end:
        values.append(f"le{end}")
    if not values:
        return {}
    return {search_param: values if len(values) > 1 else values[0]}


def normalize_encounter_id(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    cleaned = value.strip().removeprefix("Encounter/").strip()
    return cleaned or None


def encounter_params(encounter_id: Any) -> dict[str, Any]:
    cleaned = normalize_encounter_id(encounter_id)
    return {"encounter": f"Encounter/{cleaned}"} if cleaned else {}


def search_kwargs(params: dict[str, Any]) -> dict[str, Any]:
    # Chỉ truyền ``params`` khi có bộ lọc: giữ tương thích với client/fake cũ.
    return {"params": params} if params else {}


def scope_text(*, date_from: Any = None, date_to: Any = None, encounter_id: Any = None) -> str:
    """Mô tả phạm vi lọc cho câu template, vd " trong lần khám Encounter/ENC-1 (từ ... đến ...)"."""
    parts = []
    cleaned = normalize_encounter_id(encounter_id)
    if cleaned:
        parts.append(f"trong lần khám Encounter/{cleaned}")
    label = range_label(date_from, date_to)
    if label:
        parts.append(label)
    return (" " + ", ".join(parts)) if parts else ""


def filter_metadata(*, date_from: Any = None, date_to: Any = None, encounter_id: Any = None) -> dict[str, Any]:
    """Phạm vi lọc gắn vào payload (``applied_filters``) để answer LLM/FE biết đã lọc gì."""
    meta: dict[str, Any] = {}
    start, end = normalize_iso_date(date_from), normalize_iso_date(date_to)
    if start:
        meta["date_from"] = start
    if end:
        meta["date_to"] = end
    cleaned = normalize_encounter_id(encounter_id)
    if cleaned:
        meta["encounter_id"] = cleaned
    return meta
