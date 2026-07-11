import unittest
from unittest.mock import patch

from agents.intent.models import IntentPlan
from agents.intent.constants import TOOL_GET_OBSERVATIONS, TOOL_EXPLAIN_CONCEPT
from chat.response_builder import _maybe_enrich_terminology
from terminology.schemas import ExternalKnowledgeItem, KnowledgeType, TerminologySource


def _item() -> ExternalKnowledgeItem:
    return ExternalKnowledgeItem(
        source=TerminologySource.LOINC,
        type=KnowledgeType.LAB_TEST,
        code="4548-4",
        display="Hemoglobin A1c",
        summary="LOINC term: Hemoglobin A1c",
    )


class FakeService:
    def __init__(self, items):
        self.items = items
        self.calls = 0

    async def enrich_payload(self, payload, *, language="en"):
        self.calls += 1
        return self.items


_EVIDENCE = [
    {
        "resource_type": "Observation",
        "id": "o1",
        "data": {"resource_type": "Observation", "code_detail": {"text": "HbA1c", "coding": [{"system": "http://loinc.org", "code": "4548-4"}]}},
    }
]


class ConditionalEnrichmentTests(unittest.IsolatedAsyncioTestCase):
    async def test_skips_when_not_explain(self) -> None:
        fake = FakeService([_item()])
        plan = IntentPlan(tool_name=TOOL_GET_OBSERVATIONS, explain=False)
        with patch("chat.response_builder.get_enrichment_service", return_value=fake):
            result = await _maybe_enrich_terminology({"evidence": _EVIDENCE}, plan)
        self.assertEqual(result, [])
        self.assertEqual(fake.calls, 0)  # câu hỏi dữ liệu thuần -> không gọi API ngoài

    async def test_enriches_when_explain(self) -> None:
        fake = FakeService([_item()])
        plan = IntentPlan(tool_name=TOOL_GET_OBSERVATIONS, explain=True)
        with patch("chat.response_builder.get_enrichment_service", return_value=fake):
            result = await _maybe_enrich_terminology({"evidence": _EVIDENCE}, plan)
        self.assertEqual(fake.calls, 1)
        self.assertEqual(result[0]["code"], "4548-4")
        self.assertEqual(result[0]["source"], "LOINC")

    async def test_explain_but_no_evidence_skips(self) -> None:
        fake = FakeService([_item()])
        plan = IntentPlan(tool_name=TOOL_GET_OBSERVATIONS, explain=True)
        with patch("chat.response_builder.get_enrichment_service", return_value=fake):
            result = await _maybe_enrich_terminology({"evidence": []}, plan)
        self.assertEqual(result, [])
        self.assertEqual(fake.calls, 0)


class ExplainConceptAnswererTests(unittest.IsolatedAsyncioTestCase):
    async def test_concept_answer_attaches_external_knowledge(self) -> None:
        from chat.resource_answerers import _answer_explain_concept

        fake = FakeService([_item()])
        plan = IntentPlan(tool_name=TOOL_EXPLAIN_CONCEPT, term="HbA1c", code="4548-4", explain=True)
        with patch("chat.resource_answerers.get_enrichment_service", return_value=fake):
            payload = await _answer_explain_concept(plan)
        self.assertEqual(payload["intent"], "explain_concept")
        self.assertEqual(payload["evidence"], [])  # không có dữ liệu bệnh nhân
        self.assertIn("external_knowledge", payload)
        self.assertEqual(payload["external_knowledge"][0]["code"], "4548-4")
        self.assertEqual(fake.calls, 1)

    async def test_concept_answer_without_results_has_no_external_knowledge(self) -> None:
        from chat.resource_answerers import _answer_explain_concept

        fake = FakeService([])
        plan = IntentPlan(tool_name=TOOL_EXPLAIN_CONCEPT, term="khong ton tai", explain=True)
        with patch("chat.resource_answerers.get_enrichment_service", return_value=fake):
            payload = await _answer_explain_concept(plan)
        self.assertNotIn("external_knowledge", payload)
        self.assertTrue(payload["answer"])  # vẫn có câu trả lời fallback


if __name__ == "__main__":
    unittest.main()
