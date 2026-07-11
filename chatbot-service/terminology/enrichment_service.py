from __future__ import annotations

import asyncio
from functools import lru_cache
from typing import Any

from app.config import get_settings
from terminology.cache import TerminologyCache, build_cache_key
from terminology.code_extractor import extract_codes_from_payload
from terminology.loinc_client import LoincClient, is_loinc_code
from terminology.loinc_parser import parse_loinc_lookup_response
from terminology.medlineplus_client import MedlinePlusClient, build_medlineplus_request
from terminology.medlineplus_parser import parse_medlineplus_response
from terminology.rxnorm_client import RXNORM_SYSTEM, RxNormClient, is_rxnorm_code, is_rxnorm_text_lookup_candidate
from terminology.rxnorm_parser import parse_rxnorm_properties_response, parse_rxnorm_rxcui_response
from terminology.schemas import ExternalKnowledgeItem, KnowledgeType, TerminologyCode, TerminologySource


DEFAULT_MAX_REQUESTS_PER_SOURCE = 5


class TerminologyEnrichmentService:
    """Optional terminology enrichment over normalized FHIR evidence.

    Fetches metadata/definitions for LOINC (lab), RxNorm (drug) and MedlinePlus
    (patient education) codes found in normalized evidence. The three sources fan
    out concurrently via ``asyncio.gather``; each source degrades to an empty list
    on error so terminology failures never break the main answer. Results are
    cached globally (keyed by code, not patient) for repeatable, non-sensitive lookups.
    """

    def __init__(
        self,
        *,
        settings: Any | None = None,
        medlineplus_client: MedlinePlusClient | None = None,
        rxnorm_client: RxNormClient | None = None,
        loinc_client: LoincClient | None = None,
        cache: TerminologyCache | None = None,
        max_requests_per_source: int = DEFAULT_MAX_REQUESTS_PER_SOURCE,
    ) -> None:
        self.settings = settings or get_settings()
        timeout = getattr(self.settings, "terminology_timeout_seconds", 5)
        self.medlineplus_client = medlineplus_client or MedlinePlusClient(timeout_seconds=timeout)
        self.rxnorm_client = rxnorm_client or RxNormClient(timeout_seconds=timeout)
        self.loinc_client = loinc_client or LoincClient(
            timeout_seconds=timeout,
            username=getattr(self.settings, "loinc_username", None),
            password=getattr(self.settings, "loinc_password", None),
        )
        self.cache = cache or TerminologyCache(
            default_ttl_seconds=getattr(self.settings, "terminology_cache_ttl_seconds", 43200)
        )
        self.max_requests_per_source = max_requests_per_source

    async def enrich_payload(
        self,
        payload: dict[str, Any] | None,
        *,
        language: str = "en",
    ) -> list[ExternalKnowledgeItem]:
        """Return compact terminology knowledge for supported codes in a payload."""
        codes = extract_codes_from_payload(payload)
        if not codes:
            return []

        tasks = []
        if getattr(self.settings, "medlineplus_enabled", False):
            tasks.append(self._enrich_medlineplus(codes, language=language))
        if getattr(self.settings, "rxnorm_enabled", False):
            tasks.append(self._enrich_rxnorm(codes))
        if getattr(self.settings, "loinc_enabled", False):
            tasks.append(self._enrich_loinc(codes))
        if not tasks:
            return []

        grouped = await asyncio.gather(*tasks, return_exceptions=True)
        results: list[ExternalKnowledgeItem] = []
        seen: set[tuple[str, ...]] = set()
        for group in grouped:
            if isinstance(group, BaseException) or not isinstance(group, list):
                continue
            for item in group:
                key = _item_key(item)
                if key in seen:
                    continue
                seen.add(key)
                results.append(item)
        return results

    async def _enrich_medlineplus(
        self,
        codes: list[TerminologyCode],
        *,
        language: str,
    ) -> list[ExternalKnowledgeItem]:
        results: list[ExternalKnowledgeItem] = []
        attempted = 0
        for code in codes:
            request = build_medlineplus_request(code, language=language)
            if request is None:
                continue
            if attempted >= self.max_requests_per_source:
                break
            attempted += 1

            cache_key = build_medlineplus_cache_key(code, request.knowledge_type, language=language)
            cached = self.cache.get(cache_key)
            if cached is not None:
                results.extend(_items_from_cached(cached))
                continue
            items = await self._fetch_medlineplus_items(code, request.params, request.knowledge_type)
            self.cache.set(
                cache_key,
                [item.as_dict() for item in items],
                ttl_seconds=getattr(self.settings, "terminology_cache_ttl_seconds", None),
            )
            results.extend(items)
        return results

    async def _fetch_medlineplus_items(
        self,
        code: TerminologyCode,
        params: dict[str, str],
        knowledge_type: KnowledgeType,
    ) -> list[ExternalKnowledgeItem]:
        try:
            raw = await self.medlineplus_client.fetch(params)
        except Exception:
            return []
        return parse_medlineplus_response(raw, code, knowledge_type)

    async def _enrich_rxnorm(self, codes: list[TerminologyCode]) -> list[ExternalKnowledgeItem]:
        results: list[ExternalKnowledgeItem] = []
        attempted = 0
        for code in codes:
            if not (is_rxnorm_code(code) or is_rxnorm_text_lookup_candidate(code)):
                continue
            if attempted >= self.max_requests_per_source:
                break
            attempted += 1

            cache_key = build_rxnorm_cache_key(code)
            cached = self.cache.get(cache_key)
            if cached is not None:
                results.extend(_items_from_cached(cached))
                continue
            items = await self._fetch_rxnorm_items(code)
            self.cache.set(
                cache_key,
                [item.as_dict() for item in items],
                ttl_seconds=getattr(
                    self.settings,
                    "rxnorm_cache_ttl_seconds",
                    getattr(self.settings, "terminology_cache_ttl_seconds", None),
                ),
            )
            results.extend(items)
        return results

    async def _fetch_rxnorm_items(self, code: TerminologyCode) -> list[ExternalKnowledgeItem]:
        try:
            source_code = code
            rxcui = code.code if is_rxnorm_code(code) else None
            if not rxcui:
                lookup = await self.rxnorm_client.find_rxcui_by_string(code.display or code.text or "")
                rxcui = parse_rxnorm_rxcui_response(lookup)
                if not rxcui:
                    return []
                source_code = TerminologyCode(
                    resource_type=code.resource_type,
                    resource_id=code.resource_id,
                    source_field=code.source_field,
                    system=RXNORM_SYSTEM,
                    code=rxcui,
                    display=code.display,
                    text=code.text,
                )
            raw = await self.rxnorm_client.fetch_properties(rxcui)
        except Exception:
            return []
        return parse_rxnorm_properties_response(raw, source_code)

    async def _enrich_loinc(self, codes: list[TerminologyCode]) -> list[ExternalKnowledgeItem]:
        if hasattr(self.loinc_client, "has_credentials") and not self.loinc_client.has_credentials:
            return []

        results: list[ExternalKnowledgeItem] = []
        attempted = 0
        for code in codes:
            if not is_loinc_code(code):
                continue
            if attempted >= self.max_requests_per_source:
                break
            attempted += 1

            cache_key = build_loinc_cache_key(code)
            cached = self.cache.get(cache_key)
            if cached is not None:
                results.extend(_items_from_cached(cached))
                continue
            items = await self._fetch_loinc_items(code)
            self.cache.set(
                cache_key,
                [item.as_dict() for item in items],
                ttl_seconds=getattr(
                    self.settings,
                    "loinc_cache_ttl_seconds",
                    getattr(self.settings, "terminology_cache_ttl_seconds", None),
                ),
            )
            results.extend(items)
        return results

    async def _fetch_loinc_items(self, code: TerminologyCode) -> list[ExternalKnowledgeItem]:
        try:
            raw = await self.loinc_client.lookup(code.code or "")
        except Exception:
            return []
        return parse_loinc_lookup_response(raw, code)


def build_medlineplus_cache_key(
    code: TerminologyCode,
    knowledge_type: KnowledgeType,
    *,
    language: str = "en",
) -> str:
    """Build a MedlinePlus cache key without patient/resource identifiers."""
    return build_cache_key(
        source=TerminologySource.MEDLINEPLUS.value,
        system=code.system,
        code=code.code,
        text=code.text or code.display,
        knowledge_type=f"{knowledge_type.value}:{_language(language)}",
    )


def build_rxnorm_cache_key(code: TerminologyCode) -> str:
    """Build a RxNorm cache key without patient/resource identifiers."""
    return build_cache_key(
        source=TerminologySource.RXNORM.value,
        system=code.system,
        code=code.code,
        text=code.text or code.display,
        knowledge_type=KnowledgeType.MEDICATION.value,
    )


def build_loinc_cache_key(code: TerminologyCode) -> str:
    """Build a LOINC cache key without patient/resource identifiers."""
    return build_cache_key(
        source=TerminologySource.LOINC.value,
        system=code.system,
        code=code.code,
        text=code.text or code.display,
        knowledge_type=KnowledgeType.LAB_TEST.value,
    )


def _items_from_cached(value: Any) -> list[ExternalKnowledgeItem]:
    if not isinstance(value, list):
        return []
    items: list[ExternalKnowledgeItem] = []
    for item in value:
        if isinstance(item, ExternalKnowledgeItem):
            items.append(item)
        elif isinstance(item, dict):
            items.append(
                ExternalKnowledgeItem(
                    source=item.get("source") or TerminologySource.MEDLINEPLUS,
                    type=item.get("type") or KnowledgeType.PATIENT_EDUCATION,
                    system=item.get("system"),
                    code=item.get("code"),
                    display=item.get("display"),
                    summary=item.get("summary"),
                    url=item.get("url"),
                    fields=item.get("fields") if isinstance(item.get("fields"), dict) else {},
                )
            )
    return items


def _item_key(item: ExternalKnowledgeItem) -> tuple[str, ...]:
    return (
        str(item.source),
        str(item.type),
        str(item.system or "").lower(),
        str(item.code or "").lower(),
        str(item.url or "").lower(),
        str(item.display or "").lower(),
    )


def _language(value: str) -> str:
    normalized = str(value or "en").strip().lower()
    return "es" if normalized in {"es", "sp", "spa"} else "en"


@lru_cache
def get_enrichment_service() -> TerminologyEnrichmentService:
    """Return a process-wide enrichment service (shares the terminology cache)."""
    return TerminologyEnrichmentService()
