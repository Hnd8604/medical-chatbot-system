"""Test cho tools/: payload của từng tool và danh mục tool."""

import unittest
from unittest import mock

from fhir.client import FhirClientError
from terminology.schemas import ExternalKnowledgeItem, KnowledgeType, TerminologySource
from tests.fakes import FakeFhirClient, patient_resource
from tools.concepts import explain_concept
from tools.patients import get_patient, search_patients
from tools.registry import ALL_PATIENT_TOOLS, PATIENT_SCOPED_TOOLS, TOOL_REGISTRY, tool_catalog_for_prompt
from tools.resources import fhir_status, get_resource_by_id


class PatientToolTests(unittest.IsolatedAsyncioTestCase):
    async def test_single_match_resolves_patient(self):
        payload = await search_patients(FakeFhirClient(), name="Nguyen Van A")
        self.assertEqual(payload["patient_id"], "BN2026-00001")
        self.assertEqual(payload["patient_search"]["name"], "Nguyen Van A")
        self.assertNotIn("needs_patient_selection", payload)

    async def test_multiple_matches_need_selection(self):
        client = FakeFhirClient(patients=[patient_resource("BN2026-00001"), patient_resource("BN2026-00002")])
        payload = await search_patients(client, name="Nguyen")
        self.assertIsNone(payload["patient_id"])
        self.assertTrue(payload["needs_patient_selection"])
        self.assertEqual([c["id"] for c in payload["patient_candidates"]], ["BN2026-00001", "BN2026-00002"])

    async def test_listing_without_criteria_does_not_resolve(self):
        payload = await search_patients(FakeFhirClient())
        self.assertIsNone(payload["patient_id"])
        self.assertNotIn("patient_search", payload)

    async def test_get_patient_answer(self):
        payload = await get_patient(FakeFhirClient(), patient_id="BN2026-00001")
        self.assertIn("BN2026-00001", payload["answer"])
        self.assertEqual(payload["evidence"][0]["resource_type"], "Patient")


class ResourceToolTests(unittest.IsolatedAsyncioTestCase):
    async def test_fhir_status_ok(self):
        payload = await fhir_status(FakeFhirClient())
        self.assertIn("đang hoạt động", payload["answer"])

    async def test_fhir_status_down_is_an_answer_not_an_error(self):
        class DownClient(FakeFhirClient):
            async def get_metadata(self):
                raise FhirClientError("FHIR Server hiện không khả dụng.", "boom")

        payload = await fhir_status(DownClient())
        self.assertIn("không khả dụng", payload["answer"])
        self.assertEqual(payload["evidence"], [])

    async def test_get_resource_by_id(self):
        client = FakeFhirClient()
        payload = await get_resource_by_id(client, resource_type="observation", resource_id="OBS-1")
        self.assertIn("get_resource:Observation/OBS-1", client.calls)
        self.assertEqual(payload["evidence"][0]["id"], "OBS-1")

    async def test_unsupported_resource_type_is_rejected_without_fhir_call(self):
        client = FakeFhirClient()
        payload = await get_resource_by_id(client, resource_type="AuditEvent", resource_id="x")
        self.assertEqual(payload["evidence"], [])
        self.assertEqual(client.calls, [])


class FakeEnrichment:
    def __init__(self, items):
        self.items = items

    async def enrich_payload(self, payload, *, language="en"):
        return self.items


class ConceptToolTests(unittest.IsolatedAsyncioTestCase):
    async def test_concept_answer_attaches_external_knowledge(self):
        item = ExternalKnowledgeItem(
            source=TerminologySource.LOINC,
            type=KnowledgeType.LAB_TEST,
            code="4548-4",
            display="Hemoglobin A1c",
            summary="LOINC term",
        )
        with mock.patch("tools.concepts.get_enrichment_service", return_value=FakeEnrichment([item])):
            payload = await explain_concept(FakeFhirClient(), term="HbA1c", code="4548-4")
        self.assertEqual(payload["intent"], "explain_concept")
        self.assertEqual(payload["evidence"], [])  # không có dữ liệu bệnh nhân
        self.assertEqual(payload["external_knowledge"][0]["code"], "4548-4")

    async def test_concept_without_results_still_answers(self):
        with mock.patch("tools.concepts.get_enrichment_service", return_value=FakeEnrichment([])):
            payload = await explain_concept(FakeFhirClient(), term="khong ton tai")
        self.assertNotIn("external_knowledge", payload)
        self.assertTrue(payload["answer"])


class RegistryTests(unittest.TestCase):
    def test_catalog_lists_every_tool(self):
        catalog = tool_catalog_for_prompt()
        for name in TOOL_REGISTRY:
            self.assertIn(name, catalog)

    def test_scope_sets(self):
        self.assertIn("get_conditions", PATIENT_SCOPED_TOOLS)
        self.assertIn("get_all_patient_conditions", ALL_PATIENT_TOOLS)
        self.assertNotIn("search_patients", PATIENT_SCOPED_TOOLS)


if __name__ == "__main__":
    unittest.main()
