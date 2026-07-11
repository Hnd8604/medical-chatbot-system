from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from terminology.rxnorm_client import RXNORM_SYSTEM
from terminology.schemas import ExternalKnowledgeItem, KnowledgeType, TerminologyCode, TerminologySource


def parse_rxnorm_properties_response(
    raw: Any,
    source_code: TerminologyCode,
) -> list[ExternalKnowledgeItem]:
    """Parse RxNorm concept properties into compact external knowledge."""
    properties = _mapping(raw).get("properties")
    if not isinstance(properties, Mapping):
        return []

    rxcui = _clean(properties.get("rxcui")) or source_code.code
    name = _clean(properties.get("name")) or source_code.display or source_code.text
    if not (rxcui or name):
        return []

    tty = _clean(properties.get("tty"))
    synonym = _clean(properties.get("synonym"))
    language = _clean(properties.get("language"))
    suppress = _clean(properties.get("suppress"))
    fields: dict[str, Any] = {}
    if rxcui:
        fields["rxcui"] = rxcui
    if name:
        fields["name"] = name
    if tty:
        fields["tty"] = tty
    if synonym:
        fields["synonym"] = synonym
    if language:
        fields["language"] = language
    if suppress:
        fields["suppress"] = suppress

    summary_parts = []
    if name:
        summary_parts.append(f"RxNorm concept: {name}")
    if tty:
        summary_parts.append(f"term type: {tty}")

    return [
        ExternalKnowledgeItem(
            source=TerminologySource.RXNORM,
            type=KnowledgeType.MEDICATION,
            system=RXNORM_SYSTEM,
            code=rxcui,
            display=name,
            summary="; ".join(summary_parts) or None,
            fields=fields,
        )
    ]


def parse_rxnorm_rxcui_response(raw: Any) -> str | None:
    """Return the first RxCUI from a findRxcuiByString response."""
    id_group = _mapping(raw).get("idGroup")
    if not isinstance(id_group, Mapping):
        return None
    values = id_group.get("rxnormId")
    if isinstance(values, list):
        for value in values:
            text = _clean(value)
            if text:
                return text
    return _clean(values)


def _mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _clean(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None
