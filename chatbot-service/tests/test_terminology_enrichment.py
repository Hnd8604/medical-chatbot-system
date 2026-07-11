import unittest

import httpx

from terminology.enrichment_service import TerminologyEnrichmentService
from terminology.loinc_client import LoincClient
from terminology.medlineplus_client import MedlinePlusClient
from terminology.rxnorm_client import RxNormClient
from terminology.schemas import TerminologySource


class _Settings:
    """Minimal stand-in for app Settings used by the enrichment service."""

    def __init__(self, *, loinc=True, rxnorm=True, medlineplus=True, loinc_creds=True) -> None:
        self.loinc_enabled = loinc
        self.rxnorm_enabled = rxnorm
        self.medlineplus_enabled = medlineplus
        self.terminology_timeout_seconds = 5
        self.terminology_cache_ttl_seconds = 100
        self.rxnorm_cache_ttl_seconds = 100
        self.loinc_cache_ttl_seconds = 100
        self.loinc_username = "u" if loinc_creds else None
        self.loinc_password = "p" if loinc_creds else None


def _loinc_response(request: httpx.Request) -> httpx.Response:
    return httpx.Response(200, json={"parameter": [{"name": "display", "valueString": "Hemoglobin A1c"}]})


def _rxnorm_response(request: httpx.Request) -> httpx.Response:
    return httpx.Response(200, json={"properties": {"rxcui": "860975", "name": "metformin", "tty": "IN"}})


def _medlineplus_response(request: httpx.Request) -> httpx.Response:
    return httpx.Response(200, json={"feed": {"entry": [{"title": "HbA1c", "summary": "A blood sugar test.", "link": {"href": "https://medlineplus.gov/x"}}]}})


def _build_service(settings: _Settings, *, loinc_h=_loinc_response, rxnorm_h=_rxnorm_response, mp_h=_medlineplus_response) -> TerminologyEnrichmentService:
    return TerminologyEnrichmentService(
        settings=settings,
        loinc_client=LoincClient(username="u", password="p", transport=httpx.MockTransport(loinc_h)),
        rxnorm_client=RxNormClient(transport=httpx.MockTransport(rxnorm_h)),
        medlineplus_client=MedlinePlusClient(transport=httpx.MockTransport(mp_h)),
    )


_PAYLOAD = {
    "evidence": [
        {
            "resource_type": "Observation",
            "id": "o1",
            "data": {
                "resource_type": "Observation",
                "code_detail": {"text": "HbA1c", "coding": [{"system": "http://loinc.org", "code": "4548-4", "display": "HbA1c"}]},
            },
        },
        {
            "resource_type": "MedicationRequest",
            "id": "m1",
            "data": {
                "resource_type": "MedicationRequest",
                "medication_detail": {"text": "metformin", "coding": [{"system": "http://www.nlm.nih.gov/research/umls/rxnorm", "code": "860975"}]},
            },
        },
    ]
}


class EnrichmentServiceTests(unittest.IsolatedAsyncioTestCase):
    async def test_fans_out_across_three_sources(self) -> None:
        svc = _build_service(_Settings())
        items = await svc.enrich_payload(_PAYLOAD, language="en")
        sources = {str(i.source) for i in items}
        self.assertIn(str(TerminologySource.LOINC), sources)
        self.assertIn(str(TerminologySource.RXNORM), sources)
        self.assertIn(str(TerminologySource.MEDLINEPLUS), sources)

    async def test_loinc_skipped_without_credentials_others_run(self) -> None:
        svc = TerminologyEnrichmentService(
            settings=_Settings(loinc_creds=False),
            loinc_client=LoincClient(username=None, password=None, transport=httpx.MockTransport(_loinc_response)),
            rxnorm_client=RxNormClient(transport=httpx.MockTransport(_rxnorm_response)),
            medlineplus_client=MedlinePlusClient(transport=httpx.MockTransport(_medlineplus_response)),
        )
        items = await svc.enrich_payload(_PAYLOAD, language="en")
        sources = {str(i.source) for i in items}
        self.assertNotIn(str(TerminologySource.LOINC), sources)
        self.assertIn(str(TerminologySource.RXNORM), sources)
        self.assertIn(str(TerminologySource.MEDLINEPLUS), sources)

    async def test_cache_hit_avoids_second_http_call(self) -> None:
        calls = {"n": 0}

        def counting_loinc(request: httpx.Request) -> httpx.Response:
            calls["n"] += 1
            return _loinc_response(request)

        settings = _Settings(rxnorm=False, medlineplus=False)  # cô lập LOINC
        svc = _build_service(settings, loinc_h=counting_loinc)
        await svc.enrich_payload(_PAYLOAD, language="en")
        await svc.enrich_payload(_PAYLOAD, language="en")
        self.assertEqual(calls["n"], 1)  # lần 2 lấy từ cache, không gọi HTTP lại

    async def test_one_source_error_does_not_break_others(self) -> None:
        def boom(request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectError("rxnorm down")

        svc = _build_service(_Settings(), rxnorm_h=boom)
        items = await svc.enrich_payload(_PAYLOAD, language="en")
        sources = {str(i.source) for i in items}
        self.assertNotIn(str(TerminologySource.RXNORM), sources)
        self.assertIn(str(TerminologySource.LOINC), sources)  # nguồn khác vẫn trả kết quả

    async def test_no_codes_returns_empty(self) -> None:
        svc = _build_service(_Settings())
        items = await svc.enrich_payload({"evidence": []}, language="en")
        self.assertEqual(items, [])

    async def test_all_sources_disabled_returns_empty(self) -> None:
        svc = _build_service(_Settings(loinc=False, rxnorm=False, medlineplus=False))
        items = await svc.enrich_payload(_PAYLOAD, language="en")
        self.assertEqual(items, [])


if __name__ == "__main__":
    unittest.main()
