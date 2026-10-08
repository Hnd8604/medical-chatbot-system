"""Test cho agent/answer/ và agent/memory/: sinh câu trả lời, evidence budget, finalize,
terminology có điều kiện, rolling summary và nén ngữ cảnh hội thoại."""

import json
import unittest
from types import SimpleNamespace
from unittest import mock

from agent.answer.evidence_budget import apply_evidence_budget
from agent.answer.finalize import (
    NO_PATIENT_CACHE_KEY,
    CacheTarget,
    TurnMeta,
    build_memory_update,
    enrich_terminology,
    finalize_answer,
)
from agent.answer.generator import (
    LlmAnswerGenerator,
    TemplateAnswerGenerator,
    clean_llm_answer,
    compact_evidence_for_llm,
)
from agent.memory.context import compact_conversation_for_llm
from agent.memory.summary import LlmSummaryGenerator, NoopSummaryGenerator, SummaryResult
from llm.client import GatewayBudgetExceededError, LlmCallError, LlmResult
from llm.model_select import ModelChoice
from terminology.schemas import ExternalKnowledgeItem, KnowledgeType, TerminologySource


def llm_result(content: str, input_tokens: int = 40, output_tokens: int = 20) -> LlmResult:
    return LlmResult(
        content=content,
        model="gpt-4o-mini",
        usage={"input_tokens": input_tokens, "output_tokens": output_tokens, "estimated_cost_usd": 0},
    )


# ------------------------------------------------------------------ generator


class AnswerGeneratorTests(unittest.IsolatedAsyncioTestCase):
    async def test_template_generator_returns_fallback_answer(self):
        result = await TemplateAnswerGenerator().generate(
            question="HbA1c?",
            intent="observations",
            tool_name="get_observations",
            patient_id="BN2026-00003",
            evidence=[],
            fallback_answer="Template answer",
        )
        self.assertEqual(result.answer, "Template answer")
        self.assertEqual(result.source, "template")
        self.assertEqual(result.usage["input_tokens"], 0)

    def test_compact_evidence_keeps_needed_fields_and_drops_extra_fields(self):
        result = compact_evidence_for_llm(
            [
                {
                    "resource_type": "Observation",
                    "id": "obs-1",
                    "summary": "HbA1c",
                    "data": {
                        "code": "HbA1c",
                        "value": {"value": 7.2, "unit": "%"},
                        "raw_resource": {"large": "not needed"},
                    },
                }
            ]
        )
        self.assertEqual(result[0]["data"]["value"]["value"], 7.2)
        self.assertNotIn("raw_resource", result[0]["data"])

    def test_clean_llm_answer_removes_basic_markdown(self):
        self.assertEqual(
            clean_llm_answer("1. **Loại khám**: `Asthma follow-up visit`"),
            "1. Loại khám: Asthma follow-up visit",
        )

    async def test_llm_generator_sends_conversation_and_returns_usage(self):
        captured = {}

        async def fake_call_llm(**kwargs):
            captured.update(kwargs)
            return llm_result("HbA1c **7.2%** là cao.")

        conversation = {"memory_summary": "Đang xem Patient/BN2026-00003."}
        with mock.patch("agent.answer.generator.call_llm", fake_call_llm):
            result = await LlmAnswerGenerator(model="gpt-4o-mini").generate(
                question="Chỉ số đó có cao không?",
                intent="observations",
                tool_name="get_observations",
                patient_id="BN2026-00003",
                evidence=[{"resource_type": "Observation", "id": "obs-1", "summary": "HbA1c", "data": {}}],
                fallback_answer="Template answer",
                conversation_context=conversation,
            )
        self.assertEqual(result.source, "llm")
        self.assertEqual(result.answer, "HbA1c 7.2% là cao.")
        self.assertEqual(result.usage["input_tokens"], 40)
        self.assertEqual(captured["stage"], "agent_answer")
        self.assertEqual(captured["user_payload"]["conversation"], conversation)

    async def test_llm_error_falls_back_to_template(self):
        async def broken(**kwargs):
            raise LlmCallError("agent_answer", "gateway down")

        with mock.patch("agent.answer.generator.call_llm", broken):
            result = await LlmAnswerGenerator(model="m").generate(
                question="q",
                intent="observations",
                tool_name="get_observations",
                patient_id=None,
                evidence=[{"resource_type": "Observation", "id": "o", "data": {}}],
                fallback_answer="Template answer",
            )
        self.assertEqual((result.answer, result.source), ("Template answer", "template_fallback"))

    async def test_no_evidence_skips_llm(self):
        async def must_not_call(**kwargs):
            raise AssertionError("không được gọi LLM khi không có evidence")

        with mock.patch("agent.answer.generator.call_llm", must_not_call):
            result = await LlmAnswerGenerator(model="m").generate(
                question="q",
                intent="observations",
                tool_name="get_observations",
                patient_id=None,
                evidence=[],
                fallback_answer="Không có dữ liệu.",
            )
        self.assertEqual(result.source, "template_no_evidence")


# ------------------------------------------------------------ evidence budget


class EvidenceBudgetTests(unittest.TestCase):
    def _item(self, resource_id, time):
        return {
            "resource_type": "Observation",
            "id": resource_id,
            "summary": "HbA1c",
            "data": {
                "effective_time": time,
                "value": {"value": 7.2, "unit": "%"},
                "note": ["x" * 200],
                "reference_range": [{"text": "y" * 200}],
            },
        }

    def test_under_budget_is_untouched(self):
        evidence = [self._item("OBS-1", "2026-01-01")]
        result, stats = apply_evidence_budget(evidence, max_chars=100000)
        self.assertEqual(result, evidence)
        self.assertEqual(stats["dropped_items"], 0)

    def test_optional_fields_pruned_first(self):
        evidence = [self._item(f"OBS-{i}", f"2026-01-0{i}") for i in range(1, 4)]
        result, stats = apply_evidence_budget(evidence, max_chars=900)
        self.assertGreater(stats["pruned_fields"], 0)
        for item in result:
            self.assertNotIn("note", item["data"])
            self.assertIn("value", item["data"])

    def test_oldest_records_dropped_and_one_always_kept(self):
        evidence = [self._item(f"OBS-{i}", f"2026-01-0{i}") for i in range(1, 5)]
        result, stats = apply_evidence_budget(evidence, max_chars=10)
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["id"], "OBS-4")
        self.assertEqual(stats["dropped_items"], 3)

    def test_empty_input(self):
        result, stats = apply_evidence_budget([], max_chars=100)
        self.assertEqual(result, [])
        self.assertEqual(stats["chars_before"], 0)


# ------------------------------------------------------------------- finalize


class FakeGenerator:
    def __init__(self, source="llm"):
        self.source = source
        self.calls = 0

    async def generate(self, **kwargs):
        from agent.answer.generator import AnswerResult

        self.calls += 1
        return AnswerResult(
            answer=f"LLM: {kwargs['fallback_answer']}",
            source=self.source,
            usage={"input_tokens": 30, "output_tokens": 8, "estimated_cost_usd": 0},
            model="gpt-4o-mini",
        )


class FakeCache:
    def __init__(self):
        self.saved = []

    async def save_to_cache(self, **kwargs):
        self.saved.append(kwargs)


def _condition_payload():
    return {
        "answer": "Theo dữ liệu FHIR hiện có, Bệnh nhân BN2026-00001 có 1 bản ghi chẩn đoán.",
        "intent": "conditions",
        "patient_id": "BN2026-00001",
        "evidence": [{"resource_type": "Condition", "id": "COND-1", "summary": "Đái tháo đường", "data": {}}],
    }


class FinalizeTests(unittest.IsolatedAsyncioTestCase):
    def _meta(self, **overrides):
        values = {
            "tool_name": "get_conditions",
            "intent": "conditions",
            "patient_id": "BN2026-00001",
            "source": "agent_llm",
            "usage": {"input_tokens": 10, "output_tokens": 2, "estimated_cost_usd": 0},
        }
        values.update(overrides)
        return TurnMeta(**values)

    async def _finalize(self, payload, *, generator=None, cache=None, summary=None, meta=None):
        import asyncio

        async def summarize():
            return summary or SummaryResult()

        return await finalize_answer(
            payload,
            question="Bệnh nhân có chẩn đoán gì?",
            meta=meta or self._meta(),
            answer_generator=generator or FakeGenerator(),
            model_choice=ModelChoice("gpt-4o-mini", "simple", "plan"),
            summary_task=asyncio.ensure_future(summarize()),
            conversation_context=None,
            answer_mode="data_only",
            step_count=1,
            cache=CacheTarget(service=cache, user_id="u1") if cache is not None else None,
        )

    async def test_llm_answer_combines_usage_and_saves_cache(self):
        cache = FakeCache()
        payload = await self._finalize(
            _condition_payload(),
            cache=cache,
            summary=SummaryResult(summary="Tóm tắt", usage={"input_tokens": 5, "output_tokens": 5}, source="llm"),
        )
        self.assertTrue(payload["answer"].startswith("LLM: "))
        self.assertEqual(payload["answer_source"], "llm")
        self.assertEqual(payload["usage"]["input_tokens"], 45)
        self.assertEqual(payload["tool_name"], "get_conditions")
        self.assertEqual(payload["intent_source"], "agent_llm")
        self.assertEqual(payload["memory_update"]["summary"], "Tóm tắt")
        self.assertEqual(payload["memory_update"]["active_patient_id"], "BN2026-00001")
        self.assertEqual(len(cache.saved), 1)
        self.assertEqual(cache.saved[0]["patient_id"], "BN2026-00001")
        # Cache lưu chi phí tiết kiệm được (router+planner+answer), không gồm summary.
        self.assertEqual(cache.saved[0]["usage"]["input_tokens"], 40)

    async def test_template_answer_is_not_cached(self):
        cache = FakeCache()
        await self._finalize(_condition_payload(), generator=FakeGenerator(source="template_fallback"), cache=cache)
        self.assertEqual(cache.saved, [])

    async def test_patient_selection_skips_answer_and_memory(self):
        generator = FakeGenerator()
        payload = await self._finalize(
            {
                "answer": "Tìm thấy nhiều bệnh nhân phù hợp.",
                "intent": "list_patients",
                "patient_id": None,
                "evidence": [],
                "needs_patient_selection": True,
                "patient_candidates": [{"id": "A"}, {"id": "B"}],
            },
            generator=generator,
        )
        self.assertEqual(generator.calls, 0)
        self.assertEqual(payload["answer_source"], "template_patient_selection")
        self.assertEqual(payload["pending_question"], "Bệnh nhân có chẩn đoán gì?")
        self.assertNotIn("memory_update", payload)

    def test_memory_update_without_patient_or_evidence_needs_summary(self):
        meta = self._meta(patient_id=None)
        self.assertIsNone(build_memory_update({"evidence": []}, meta))
        update = build_memory_update({"evidence": []}, meta, summary="Tóm tắt")
        self.assertEqual(update["summary"], "Tóm tắt")
        self.assertEqual(update["evidence_refs"], [])

    def test_no_patient_cache_key_never_leaks(self):
        self.assertTrue(NO_PATIENT_CACHE_KEY.startswith("__"))


# ---------------------------------------------------------------- terminology


def _knowledge_item():
    return ExternalKnowledgeItem(
        source=TerminologySource.LOINC,
        type=KnowledgeType.LAB_TEST,
        code="4548-4",
        display="Hemoglobin A1c",
        summary="LOINC term: Hemoglobin A1c",
    )


class FakeEnrichment:
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
        "data": {"code_detail": {"text": "HbA1c", "coding": [{"system": "http://loinc.org", "code": "4548-4"}]}},
    }
]


class TerminologyEnrichmentTests(unittest.IsolatedAsyncioTestCase):
    async def test_skips_when_not_explain(self):
        fake = FakeEnrichment([_knowledge_item()])
        with mock.patch("agent.answer.finalize.get_enrichment_service", return_value=fake):
            self.assertEqual(await enrich_terminology(_EVIDENCE, explain=False), [])
        self.assertEqual(fake.calls, 0)  # câu hỏi dữ liệu thuần -> không gọi API ngoài

    async def test_enriches_when_explain(self):
        fake = FakeEnrichment([_knowledge_item()])
        with mock.patch("agent.answer.finalize.get_enrichment_service", return_value=fake):
            result = await enrich_terminology(_EVIDENCE, explain=True)
        self.assertEqual(result[0]["code"], "4548-4")
        self.assertEqual(result[0]["source"], "LOINC")

    async def test_explain_without_evidence_skips(self):
        fake = FakeEnrichment([_knowledge_item()])
        with mock.patch("agent.answer.finalize.get_enrichment_service", return_value=fake):
            self.assertEqual(await enrich_terminology([], explain=True), [])
        self.assertEqual(fake.calls, 0)


# ---------------------------------------------------------------------- memory


_CONTEXT = {
    "memory_summary": "Đang trao đổi về Patient/BN2026-00003.",
    "recent_messages": [
        {"role": "user", "content": "HbA1c của bệnh nhân BN2026-00003?"},
        {"role": "assistant", "content": "HbA1c là 7.2%."},
    ],
}


class SummaryGeneratorTests(unittest.IsolatedAsyncioTestCase):
    def _generator(self):
        return LlmSummaryGenerator(model="gpt-4o-mini", trigger_message_count=6, max_output_tokens=128)

    async def test_noop_generator_returns_empty_summary(self):
        result = await NoopSummaryGenerator().summarize(
            conversation_context=_CONTEXT, question="q", patient_id=None, total_message_count=8
        )
        self.assertEqual((result.summary, result.source), ("", "disabled"))

    async def test_below_trigger_or_unknown_count_does_not_call_llm(self):
        calls = []

        async def fake_call_llm(**kwargs):
            calls.append(kwargs)
            return llm_result("x")

        with mock.patch("agent.memory.summary.call_llm", fake_call_llm):
            for count in (2, None):
                result = await self._generator().summarize(
                    conversation_context=_CONTEXT, question="q", patient_id=None, total_message_count=count
                )
                self.assertEqual(result.source, "skipped")
        self.assertEqual(calls, [])

    async def test_triggered_summary_returns_usage(self):
        captured = {}

        async def fake_call_llm(**kwargs):
            captured.update(kwargs)
            return llm_result("Đã xem HbA1c 7.2%.", 50, 30)

        with mock.patch("agent.memory.summary.call_llm", fake_call_llm):
            result = await self._generator().summarize(
                conversation_context=_CONTEXT,
                question="Lần khám gần nhất?",
                patient_id="BN2026-00003",
                total_message_count=6,
            )
        self.assertEqual((result.source, result.summary), ("llm", "Đã xem HbA1c 7.2%."))
        self.assertEqual(result.usage["output_tokens"], 30)
        self.assertEqual(captured["stage"], "agent_summary")
        self.assertEqual(captured["user_payload"]["previous_summary"], _CONTEXT["memory_summary"])

    async def test_errors_including_budget_are_swallowed(self):
        for error in (LlmCallError("agent_summary", "gateway down"), GatewayBudgetExceededError("ExceededBudget")):
            async def broken(**kwargs):
                raise error

            with mock.patch("agent.memory.summary.call_llm", broken):
                result = await self._generator().summarize(
                    conversation_context=_CONTEXT, question="q", patient_id=None, total_message_count=8
                )
            self.assertEqual((result.summary, result.source), ("", "error"))


class ConversationContextTests(unittest.TestCase):
    def test_truncates_messages_and_keeps_summary_and_ids(self):
        context = SimpleNamespace(
            memory_summary="Tóm tắt cũ.",
            active_patient_id="BN2026-00001",
            recent_messages=[
                SimpleNamespace(role="user", content="x" * 1000),
                SimpleNamespace(role="assistant", content="ngắn"),
            ],
        )
        result = compact_conversation_for_llm(context, max_messages=6, max_chars=400)
        self.assertEqual(result["memory_summary"], "Tóm tắt cũ.")
        self.assertEqual(len(result["recent_messages"][0]["content"]), 400)
        # Router/planner/plan cache cần biết đang có bệnh nhân hoạt động.
        self.assertEqual(result["active_patient_id"], "BN2026-00001")

    def test_returns_none_when_empty(self):
        self.assertIsNone(compact_conversation_for_llm(None))
        self.assertIsNone(compact_conversation_for_llm(SimpleNamespace(memory_summary=None, recent_messages=[])))

    def test_payload_is_json_serializable(self):
        context = SimpleNamespace(memory_summary="s", recent_messages=[SimpleNamespace(role="user", content="c")])
        json.dumps(compact_conversation_for_llm(context), ensure_ascii=False)


if __name__ == "__main__":
    unittest.main()
