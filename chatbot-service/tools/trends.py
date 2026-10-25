"""Tool xu hướng một chỉ số theo thời gian ("HbA1c 6 tháng qua tăng hay giảm").

Mọi con số xu hướng (đầu, cuối, thấp nhất, cao nhất, chênh lệch, %) do **code** tính từ
evidence rồi gắn thành một mục evidence tóm tắt — answer LLM chỉ diễn đạt lại, không tự
làm phép tính, và grounding check đối chiếu được từng con số.
"""

from __future__ import annotations

from typing import Any

from fhir.client import FhirClient
from nlp.observation_types import (
    BP_DIASTOLIC_CODE,
    BP_SYSTOLIC_CODE,
    canonical_observation_type,
    observation_type_label,
)
from nlp.value_filter import format_number
from tools.filters import filter_metadata, scope_text
from tools.formatting import display_vi, evidence_item, summary_evidence_item
from tools.observations import search_observations


TREND_MAX_POINTS = 50
# |thay đổi| dưới ngưỡng này (so với lần đo đầu) coi là "ổn định".
STABLE_PERCENT = 5.0


async def get_observation_trend(
    client: FhirClient,
    *,
    patient_id: str,
    observation_type: str | None = None,
    date_from: str | None = None,
    date_to: str | None = None,
    limit: int = 20,
) -> dict[str, Any]:
    canonical = canonical_observation_type(observation_type) or observation_type
    label = observation_type_label(canonical)
    scope = scope_text(date_from=date_from, date_to=date_to)
    observations = await search_observations(
        client,
        patient_id,
        limit=min(max(limit, 2), TREND_MAX_POINTS),
        observation_type=canonical,
        date_from=date_from,
        date_to=date_to,
    )
    # search_observations trả mới nhất trước; chuỗi xu hướng đọc theo thời gian tăng dần.
    chronological = sorted(observations, key=_observed_at)
    series = [stats for stats in (_series_stats(name, points) for name, points in _series(chronological, canonical)) if stats]

    if not observations:
        answer = f"Không tìm thấy bản ghi {label} nào cho Bệnh nhân {patient_id}{scope}."
    elif not series:
        answer = f"Các bản ghi {label} của Bệnh nhân {patient_id}{scope} không có giá trị số để đánh giá xu hướng."
    else:
        answer = f"Theo dữ liệu FHIR hiện có, {label} của Bệnh nhân {patient_id}{scope}: " + " ".join(
            _describe(stats) for stats in series
        )

    evidence = [
        evidence_item("Observation", item.get("id"), display_vi(item.get("code")), item)
        for item in observations
    ]
    trend = {
        "observation_type": canonical,
        "measure": label,
        "points": len(chronological),
        "series": series,
        **filter_metadata(date_from=date_from, date_to=date_to),
    }
    if series:
        evidence.append(summary_evidence_item("ObservationTrend", " ".join(_describe(s) for s in series), trend))

    payload: dict[str, Any] = {
        "answer": answer,
        "intent": "observation_trend",
        "patient_id": patient_id,
        "observation_type": canonical,
        "evidence": evidence,
    }
    filters = filter_metadata(date_from=date_from, date_to=date_to)
    if filters:
        payload["applied_filters"] = filters
    return payload


def _series(observations: list[dict[str, Any]], observation_type: str | None) -> list[tuple[str, list[dict[str, Any]]]]:
    """Tách chuỗi số: huyết áp thành tâm thu/tâm trương, loại khác một chuỗi."""
    if canonical_observation_type(observation_type) == "blood_pressure":
        systolic: list[dict[str, Any]] = []
        diastolic: list[dict[str, Any]] = []
        for observation in observations:
            for component in observation.get("components") or []:
                codes = {c.get("code") for c in ((component.get("code_detail") or {}).get("coding") or []) if c}
                text = str(component.get("code") or "").lower()
                point = _point(observation, component.get("value"))
                if point is None:
                    continue
                if BP_SYSTOLIC_CODE in codes or "systolic" in text:
                    systolic.append(point)
                elif BP_DIASTOLIC_CODE in codes or "diastolic" in text:
                    diastolic.append(point)
        return [("Huyết áp tâm thu", systolic), ("Huyết áp tâm trương", diastolic)]
    points = [p for p in (_point(o, o.get("value")) for o in observations) if p is not None]
    return [(observation_type_label(observation_type).capitalize() if observation_type else "Chỉ số", points)]


def _point(observation: dict[str, Any], quantity: Any) -> dict[str, Any] | None:
    if not isinstance(quantity, dict):
        return None
    value = quantity.get("value")
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return {
        "value": value,
        "unit": quantity.get("unit") or "",
        "date": _observed_at(observation)[:10],
        "observation_id": observation.get("id"),
    }


def _series_stats(name: str, points: list[dict[str, Any]]) -> dict[str, Any] | None:
    if not points:
        return None
    decimals = max(_decimals(point["value"]) for point in points)
    first, last = points[0], points[-1]
    lowest = min(points, key=lambda point: point["value"])
    highest = max(points, key=lambda point: point["value"])
    stats: dict[str, Any] = {
        "name": name,
        "unit": first["unit"],
        "count": len(points),
        "values": [{"date": point["date"], "value": point["value"]} for point in points],
        "first": {"date": first["date"], "value": first["value"]},
        "last": {"date": last["date"], "value": last["value"]},
        "min": {"date": lowest["date"], "value": lowest["value"]},
        "max": {"date": highest["date"], "value": highest["value"]},
    }
    if len(points) < 2:
        stats["direction"] = "chưa đủ dữ liệu"
        return stats
    change = round(last["value"] - first["value"], decimals)
    percent = round(100 * change / first["value"], 1) if first["value"] else None
    stats["change"] = change
    stats["change_percent"] = percent
    if percent is not None and abs(percent) < STABLE_PERCENT:
        stats["direction"] = "ổn định"
    else:
        stats["direction"] = "tăng" if change > 0 else "giảm" if change < 0 else "ổn định"
    return stats


def _describe(stats: dict[str, Any]) -> str:
    unit = f" {stats['unit']}" if stats["unit"] else ""
    first, last = stats["first"], stats["last"]
    if stats["count"] < 2:
        return (
            f"{stats['name']}: chỉ có 1 lần đo ({format_number(first['value'])}{unit} ngày {first['date']}), "
            "chưa đủ dữ liệu để đánh giá xu hướng."
        )
    sequence = " → ".join(format_number(point["value"]) for point in stats["values"])
    change = stats["change"]
    start, end = format_number(first["value"]), format_number(last["value"])
    if change > 0:
        movement = f"tăng từ {start} lên {end}{unit}"
    elif change < 0:
        movement = f"giảm từ {start} xuống {end}{unit}"
    else:
        movement = f"không đổi ở mức {start}{unit}"
    sign = "+" if change > 0 else ""
    percent = f", {sign}{format_number(stats['change_percent'])}%" if stats.get("change_percent") is not None else ""
    return (
        f"{stats['name']} có {stats['count']} lần đo từ {first['date']} đến {last['date']} ({sequence}{unit}): "
        f"{movement} (thay đổi {sign}{format_number(change)}{unit}{percent}), nhìn chung {stats['direction']}; "
        f"thấp nhất {format_number(stats['min']['value'])}{unit} ({stats['min']['date']}), "
        f"cao nhất {format_number(stats['max']['value'])}{unit} ({stats['max']['date']})."
    )


def _observed_at(observation: dict[str, Any]) -> str:
    value = observation.get("effective_time") or observation.get("issued")
    if isinstance(value, dict):
        value = value.get("start") or value.get("end")
    return str(value or "")


def _decimals(value: float) -> int:
    text = format_number(float(value))
    return len(text.split(".")[1]) if "." in text else 0
