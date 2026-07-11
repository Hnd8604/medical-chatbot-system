from __future__ import annotations

from collections.abc import Iterable, Mapping
from typing import Any

from terminology.schemas import TerminologyCode


def extract_codes_from_payload(payload: Mapping[str, Any] | None) -> list[TerminologyCode]:
    """Extract compact terminology codes from normalized FHIR evidence payloads."""
    if not isinstance(payload, Mapping):
        return []

    evidence = payload.get("evidence")
    if not isinstance(evidence, list):
        return []

    codes: list[TerminologyCode] = []
    seen: set[tuple[str, ...]] = set()
    for item in evidence:
        if not isinstance(item, Mapping):
            continue
        data = item.get("data")
        if not isinstance(data, Mapping):
            continue
        for code in _extract_from_evidence_item(item, data):
            key = code.dedupe_key()
            if key in seen:
                continue
            seen.add(key)
            codes.append(code)
    return codes


def _extract_from_evidence_item(
    evidence_item: Mapping[str, Any],
    data: Mapping[str, Any],
) -> Iterable[TerminologyCode]:
    resource_type = _clean(evidence_item.get("resource_type") or data.get("resource_type") or "Resource") or "Resource"
    resource_id = _clean(evidence_item.get("id") or data.get("id"))

    if resource_type == "Observation":
        primary = list(_codes_from_codeable(
            data.get("code_detail"),
            resource_type=resource_type,
            resource_id=resource_id,
            source_field="code_detail",
        ))
        yield from primary or _fallback_text_code(
            data.get("code"),
            resource_type=resource_type,
            resource_id=resource_id,
            source_field="code",
        )
        components = data.get("components")
        if isinstance(components, list):
            for index, component in enumerate(components):
                if not isinstance(component, Mapping):
                    continue
                component_codes = list(_codes_from_codeable(
                    component.get("code_detail"),
                    resource_type=resource_type,
                    resource_id=resource_id,
                    source_field=f"components[{index}].code_detail",
                    component_index=index,
                ))
                yield from component_codes or _fallback_text_code(
                    component.get("code"),
                    resource_type=resource_type,
                    resource_id=resource_id,
                    source_field=f"components[{index}].code",
                    component_index=index,
                )
        return

    if resource_type == "Condition":
        primary = list(_codes_from_codeable(
            data.get("code_detail"),
            resource_type=resource_type,
            resource_id=resource_id,
            source_field="code_detail",
        ))
        yield from primary or _fallback_text_code(
            data.get("code"),
            resource_type=resource_type,
            resource_id=resource_id,
            source_field="code",
        )
        return

    if resource_type == "MedicationRequest":
        primary = list(_codes_from_codeable(
            data.get("medication_detail"),
            resource_type=resource_type,
            resource_id=resource_id,
            source_field="medication_detail",
        ))
        yield from primary or _fallback_text_code(
            data.get("medication"),
            resource_type=resource_type,
            resource_id=resource_id,
            source_field="medication",
        )


def _codes_from_codeable(
    value: Any,
    *,
    resource_type: str,
    resource_id: str | None,
    source_field: str,
    component_index: int | None = None,
) -> Iterable[TerminologyCode]:
    if not isinstance(value, Mapping):
        return

    text = _clean(value.get("text"))
    codings = value.get("coding")
    emitted = False
    if isinstance(codings, list):
        for index, coding in enumerate(codings):
            if not isinstance(coding, Mapping):
                continue
            code = _clean(coding.get("code"))
            display = _clean(coding.get("display"))
            coding_text = text or display
            if not (code or coding_text):
                continue
            emitted = True
            yield TerminologyCode(
                resource_type=resource_type,
                resource_id=resource_id,
                source_field=source_field,
                system=_clean(coding.get("system")),
                code=code,
                display=display,
                text=coding_text,
                coding_index=index,
                component_index=component_index,
            )

    if not emitted and text:
        yield TerminologyCode(
            resource_type=resource_type,
            resource_id=resource_id,
            source_field=source_field,
            text=text,
            component_index=component_index,
        )


def _fallback_text_code(
    value: Any,
    *,
    resource_type: str,
    resource_id: str | None,
    source_field: str,
    component_index: int | None = None,
) -> Iterable[TerminologyCode]:
    text = _clean(value)
    if not text:
        return
    yield TerminologyCode(
        resource_type=resource_type,
        resource_id=resource_id,
        source_field=source_field,
        text=text,
        component_index=component_index,
    )


def _clean(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None
