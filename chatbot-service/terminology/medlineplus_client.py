from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import httpx

from terminology.schemas import KnowledgeType, TerminologyCode


MEDLINEPLUS_BASE_URL = "https://connect.medlineplus.gov/service"
MEDLINEPLUS_JSON_RESPONSE_TYPE = "application/json"

LOINC_OID = "2.16.840.1.113883.6.1"
RXNORM_OID = "2.16.840.1.113883.6.88"
NDC_OID = "2.16.840.1.113883.6.69"
ICD10CM_OID = "2.16.840.1.113883.6.90"
SNOMED_CT_OID = "2.16.840.1.113883.6.96"

_LOINC_SYSTEMS = {"http://loinc.org", f"urn:oid:{LOINC_OID}", LOINC_OID}
_RXNORM_SYSTEMS = {
    "http://www.nlm.nih.gov/research/umls/rxnorm",
    f"urn:oid:{RXNORM_OID}",
    RXNORM_OID,
}
_ICD10CM_SYSTEMS = {
    "http://hl7.org/fhir/sid/icd-10-cm",
    "http://hl7.org/fhir/sid/icd-10",
    f"urn:oid:{ICD10CM_OID}",
    ICD10CM_OID,
}
_SNOMED_SYSTEMS = {
    "http://snomed.info/sct",
    f"urn:oid:{SNOMED_CT_OID}",
    SNOMED_CT_OID,
}


@dataclass(frozen=True)
class MedlinePlusRequest:
    """Compact request metadata for one MedlinePlus Connect lookup."""

    params: dict[str, str]
    knowledge_type: KnowledgeType


class MedlinePlusClient:
    """Async client for MedlinePlus Connect JSON lookups (public API, no key)."""

    def __init__(
        self,
        *,
        base_url: str = MEDLINEPLUS_BASE_URL,
        timeout_seconds: float = 5,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self.base_url = base_url
        self.timeout_seconds = timeout_seconds
        self.transport = transport

    async def fetch(self, params: dict[str, str]) -> dict[str, Any] | None:
        """Fetch one MedlinePlus response, returning None for transport failures."""
        try:
            async with httpx.AsyncClient(
                timeout=self.timeout_seconds,
                follow_redirects=True,
                transport=self.transport,
            ) as client:
                response = await client.get(self.base_url, params=params)
                response.raise_for_status()
                data = response.json()
        except (httpx.HTTPError, ValueError):
            return None
        return data if isinstance(data, dict) else None


def build_medlineplus_request(
    code: TerminologyCode,
    *,
    language: str = "en",
) -> MedlinePlusRequest | None:
    """Build MedlinePlus Connect query params for a supported extracted code."""
    normalized_language = _language(language)
    system = _norm_system(code.system)
    display = _clean(code.display or code.text)

    if system in _LOINC_SYSTEMS and code.code:
        return _coded_request(
            code_system=LOINC_OID,
            code=code.code,
            display=display,
            language=normalized_language,
            knowledge_type=KnowledgeType.LAB_TEST,
        )

    if system in _RXNORM_SYSTEMS and code.code:
        return _coded_request(
            code_system=RXNORM_OID,
            code=code.code,
            display=display,
            language=normalized_language,
            knowledge_type=KnowledgeType.MEDICATION,
        )

    if code.resource_type == "Condition" and system in _ICD10CM_SYSTEMS and code.code:
        return _coded_request(
            code_system=ICD10CM_OID,
            code=code.code,
            display=display,
            language=normalized_language,
            knowledge_type=KnowledgeType.CONDITION,
        )

    if code.resource_type == "Condition" and system in _SNOMED_SYSTEMS and code.code:
        return _coded_request(
            code_system=SNOMED_CT_OID,
            code=code.code,
            display=display,
            language=normalized_language,
            knowledge_type=KnowledgeType.CONDITION,
        )

    if (
        normalized_language == "en"
        and code.resource_type == "MedicationRequest"
        and not code.code
        and display
    ):
        return MedlinePlusRequest(
            params=_base_params(
                code_system=NDC_OID,
                code=None,
                display=display,
                language=normalized_language,
            ),
            knowledge_type=KnowledgeType.MEDICATION,
        )

    return None


def _coded_request(
    *,
    code_system: str,
    code: str,
    display: str | None,
    language: str,
    knowledge_type: KnowledgeType,
) -> MedlinePlusRequest:
    return MedlinePlusRequest(
        params=_base_params(
            code_system=code_system,
            code=code,
            display=display,
            language=language,
        ),
        knowledge_type=knowledge_type,
    )


def _base_params(
    *,
    code_system: str,
    code: str | None,
    display: str | None,
    language: str,
) -> dict[str, str]:
    params = {
        "mainSearchCriteria.v.cs": code_system,
        "knowledgeResponseType": MEDLINEPLUS_JSON_RESPONSE_TYPE,
        "informationRecipient.languageCode.c": language,
    }
    if code:
        params["mainSearchCriteria.v.c"] = code
    if display:
        params["mainSearchCriteria.v.dn"] = display
    return params


def _language(value: str) -> str:
    normalized = str(value or "en").strip().lower()
    return "es" if normalized in {"es", "sp", "spa"} else "en"


def _norm_system(value: str | None) -> str:
    return str(value or "").strip().lower()


def _clean(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None
