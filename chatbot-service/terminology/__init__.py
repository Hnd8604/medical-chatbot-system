"""Terminology enrichment: compact external knowledge for FHIR codes.

Fetches LOINC (lab), RxNorm (drug) and MedlinePlus (patient education) metadata
for codes found in normalized evidence. Pure data-fetch layer (no LLM); the
enrichment result is attached to the chat payload as ``external_knowledge`` and
summarized into Vietnamese by the answer generator.
"""

from terminology.cache import TerminologyCache
from terminology.code_extractor import extract_codes_from_payload
from terminology.enrichment_service import TerminologyEnrichmentService, get_enrichment_service
from terminology.loinc_client import LoincClient
from terminology.loinc_parser import parse_loinc_lookup_response
from terminology.medlineplus_client import MedlinePlusClient, build_medlineplus_request
from terminology.medlineplus_parser import parse_medlineplus_response
from terminology.rxnorm_client import RxNormClient
from terminology.rxnorm_parser import parse_rxnorm_properties_response, parse_rxnorm_rxcui_response
from terminology.schemas import (
    ExternalKnowledgeItem,
    KnowledgeType,
    TerminologyCode,
    TerminologySource,
)

__all__ = [
    "ExternalKnowledgeItem",
    "KnowledgeType",
    "LoincClient",
    "MedlinePlusClient",
    "RxNormClient",
    "TerminologyCache",
    "TerminologyCode",
    "TerminologyEnrichmentService",
    "TerminologySource",
    "build_medlineplus_request",
    "extract_codes_from_payload",
    "get_enrichment_service",
    "parse_loinc_lookup_response",
    "parse_medlineplus_response",
    "parse_rxnorm_properties_response",
    "parse_rxnorm_rxcui_response",
]
