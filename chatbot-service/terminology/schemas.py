from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any


class TerminologySource(str, Enum):
    """Known external terminology and education sources."""

    MEDLINEPLUS = "MedlinePlus"
    RXNORM = "RxNorm"
    LOINC = "LOINC"


class KnowledgeType(str, Enum):
    """Compact external knowledge categories."""

    PATIENT_EDUCATION = "patient_education"
    MEDICATION = "medication"
    LAB_TEST = "lab_test"
    CONDITION = "condition"


@dataclass(frozen=True)
class TerminologyCode:
    """One compact code/text reference extracted from normalized FHIR evidence."""

    resource_type: str
    resource_id: str | None
    source_field: str
    system: str | None = None
    code: str | None = None
    display: str | None = None
    text: str | None = None
    coding_index: int | None = None
    component_index: int | None = None
    metadata: dict[str, Any] = field(default_factory=dict)

    def dedupe_key(self) -> tuple[str, ...]:
        """Return a stable key for de-duplicating extracted codes."""
        return (
            _norm(self.resource_type),
            _norm(self.resource_id),
            _norm(self.source_field),
            _norm(self.system),
            _norm(self.code),
            _norm(self.display or self.text),
        )

    def as_dict(self) -> dict[str, Any]:
        """Return a compact JSON-serializable representation."""
        value: dict[str, Any] = {
            "resource_type": self.resource_type,
            "resource_id": self.resource_id,
            "source_field": self.source_field,
            "system": self.system,
            "code": self.code,
            "display": self.display,
            "text": self.text,
        }
        if self.coding_index is not None:
            value["coding_index"] = self.coding_index
        if self.component_index is not None:
            value["component_index"] = self.component_index
        if self.metadata:
            value["metadata"] = dict(self.metadata)
        return value


@dataclass(frozen=True)
class ExternalKnowledgeItem:
    """Compact result shape for MedlinePlus/RxNorm/LOINC enrichment."""

    source: TerminologySource | str
    type: KnowledgeType | str
    system: str | None = None
    code: str | None = None
    display: str | None = None
    summary: str | None = None
    url: str | None = None
    fields: dict[str, Any] = field(default_factory=dict)

    def as_dict(self) -> dict[str, Any]:
        """Return a compact JSON-serializable representation."""
        return {
            "source": _enum_value(self.source),
            "type": _enum_value(self.type),
            "system": self.system,
            "code": self.code,
            "display": self.display,
            "summary": self.summary,
            "url": self.url,
            "fields": dict(self.fields),
        }


def _enum_value(value: Enum | str) -> str:
    return value.value if isinstance(value, Enum) else value


def _norm(value: Any) -> str:
    return str(value or "").strip().lower()
