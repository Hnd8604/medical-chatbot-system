from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from terminology.loinc_client import LOINC_SYSTEM
from terminology.schemas import ExternalKnowledgeItem, KnowledgeType, TerminologyCode, TerminologySource


_LOINC_PROPERTY_FIELDS = {
    "COMPONENT": "component",
    "PROPERTY": "property",
    "TIME_ASPCT": "time",
    "SYSTEM": "system",
    "SCALE_TYP": "scale",
    "METHOD_TYP": "method",
    "CLASS": "class",
    "STATUS": "status",
    "LONG_COMMON_NAME": "long_common_name",
    "SHORTNAME": "short_name",
    "EXAMPLE_UCUM_UNITS": "example_ucum_units",
}


def parse_loinc_lookup_response(
    raw: Any,
    source_code: TerminologyCode,
) -> list[ExternalKnowledgeItem]:
    """Parse FHIR CodeSystem/$lookup Parameters into compact LOINC metadata."""
    parameters = _mapping(raw).get("parameter")
    if not isinstance(parameters, list):
        return []

    display: str | None = None
    fields: dict[str, Any] = {}
    for parameter in parameters:
        if not isinstance(parameter, Mapping):
            continue
        name = _clean(parameter.get("name"))
        if name == "display":
            display = _value_from_parameter(parameter)
        elif name == "name":
            value = _value_from_parameter(parameter)
            if value:
                fields["name"] = value
        elif name == "version":
            value = _value_from_parameter(parameter)
            if value:
                fields["version"] = value
        elif name == "property":
            _merge_property(fields, parameter)

    final_display = display or fields.get("long_common_name") or source_code.display or source_code.text
    if not (final_display or fields):
        return []

    summary = _summary(final_display, fields)
    return [
        ExternalKnowledgeItem(
            source=TerminologySource.LOINC,
            type=KnowledgeType.LAB_TEST,
            system=LOINC_SYSTEM,
            code=source_code.code,
            display=final_display,
            summary=summary,
            fields=fields,
        )
    ]


def _merge_property(fields: dict[str, Any], parameter: Mapping[str, Any]) -> None:
    parts = parameter.get("part")
    if not isinstance(parts, list):
        return
    code: str | None = None
    value: str | None = None
    for part in parts:
        if not isinstance(part, Mapping):
            continue
        name = _clean(part.get("name"))
        if name == "code":
            code = _value_from_parameter(part)
        elif name == "value":
            value = _value_from_parameter(part)
    key = _LOINC_PROPERTY_FIELDS.get(str(code or "").upper())
    if key and value:
        fields[key] = value


def _value_from_parameter(parameter: Mapping[str, Any]) -> str | None:
    for key in (
        "valueString",
        "valueCode",
        "valueBoolean",
        "valueInteger",
        "valueDecimal",
        "valueUri",
        "valueDateTime",
    ):
        value = _clean(parameter.get(key))
        if value is not None:
            return value
    coding = parameter.get("valueCoding")
    if isinstance(coding, Mapping):
        return _clean(coding.get("display")) or _clean(coding.get("code"))
    return None


def _summary(display: str | None, fields: Mapping[str, Any]) -> str | None:
    parts: list[str] = []
    if display:
        parts.append(f"LOINC term: {display}")
    for key in ("component", "property", "time", "system", "scale", "method", "class"):
        value = _clean(fields.get(key))
        if value:
            parts.append(f"{key}: {value}")
    return "; ".join(parts) or None


def _mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _clean(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None
