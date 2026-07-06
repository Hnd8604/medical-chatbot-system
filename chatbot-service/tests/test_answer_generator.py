import json
import unittest
from types import SimpleNamespace

from agents.answer_generator import (
    LLMAnswerGenerator,
    TemplateAnswerGenerator,
    clean_llm_answer,
    combine_usage,
    compact_evidence_for_llm,
)
from agents.context_payload import compact_conversation_for_llm


class AnswerGeneratorTests(unittest.IsolatedAsyncioTestCase):
    async def test_template_generator_returns_fallback_answer(self) -> None:
        result = await TemplateAnswerGenerator().generate(
            question="HbA1c cua benh nhan 003?",
            intent="observations",
            tool_name="get_observations",
            patient_id="BN2026-00003",
            evidence=[],
            fallback_answer="Template answer",
        )

        self.assertEqual(result.answer, "Template answer")
        self.assertEqual(result.source, "template")
        self.assertEqual(result.usage["input_tokens"], 0)

    def test_compact_evidence_keeps_needed_fields_and_drops_extra_fields(self) -> None:
        evidence = [
            {
                "resource_type": "Observation",
                "id": "obs-1",
                "summary": "HbA1c",
                "data": {
                    "id": "obs-1",
                    "code": "HbA1c",
                    "value": {"value": 7.2, "unit": "%"},
                    "effective_time": "2026-05-24T14:18:00+07:00",
                    "interpretation": [{"text": "High"}],
                    "reference_range": [{"text": "Non-diabetes reference threshold."}],
                    "raw_resource": {"large": "not needed"},
                },
            }
        ]

        result = compact_evidence_for_llm(evidence)

        self.assertEqual(result[0]["data"]["code"], "HbA1c")
        self.assertEqual(result[0]["data"]["value"]["value"], 7.2)
        self.assertNotIn("raw_resource", result[0]["data"])

    def test_combine_usage_adds_token_counts_and_cost(self) -> None:
        result = combine_usage(
            {"input_tokens": 10, "output_tokens": 4, "estimated_cost_usd": 0.01},
            {"input_tokens": 20, "output_tokens": 8, "estimated_cost_usd": 0.02},
        )

        self.assertEqual(result["input_tokens"], 30)
        self.assertEqual(result["output_tokens"], 12)
        self.assertAlmostEqual(result["estimated_cost_usd"], 0.03)

    def test_clean_llm_answer_removes_basic_markdown(self) -> None:
        result = clean_llm_answer("1. **Loại khám**: `Asthma follow-up visit`")

        self.assertEqual(result, "1. Loại khám: Asthma follow-up visit")

    async def test_template_generator_accepts_conversation_context(self) -> None:
        result = await TemplateAnswerGenerator().generate(
            question="Chỉ số đó có cao không?",
            intent="observations",
            tool_name="get_observations",
            patient_id="BN2026-00003",
            evidence=[],
            fallback_answer="Template answer",
            conversation_context={"memory_summary": "abc", "recent_messages": []},
        )

        self.assertEqual(result.answer, "Template answer")

    async def test_llm_generator_includes_conversation_in_user_payload(self) -> None:
        captured: dict = {}

        class FakeCompletions:
            async def create(self, **kwargs):
                captured.update(kwargs)
                return SimpleNamespace(
                    choices=[SimpleNamespace(message=SimpleNamespace(content="HbA1c 7.2% là cao."))],
                    usage=SimpleNamespace(prompt_tokens=40, completion_tokens=20),
                    model="gpt-4o-mini",
                )

        generator = LLMAnswerGenerator(
            api_key="sk-test", model="gpt-4o-mini", timeout_seconds=5, base_url="http://localhost:4000"
        )
        generator.client = SimpleNamespace(chat=SimpleNamespace(completions=FakeCompletions()))

        conversation = {
            "memory_summary": "Đang trao đổi về Patient/BN2026-00003, đã xem HbA1c.",
            "recent_messages": [{"role": "user", "content": "HbA1c của bệnh nhân BN2026-00003?"}],
        }
        result = await generator.generate(
            question="Chỉ số đó có cao không?",
            intent="observations",
            tool_name="get_observations",
            patient_id="BN2026-00003",
            evidence=[{"resource_type": "Observation", "id": "obs-1", "summary": "HbA1c", "data": {}}],
            fallback_answer="Template answer",
            conversation_context=conversation,
        )

        self.assertEqual(result.source, "llm")
        user_payload = json.loads(captured["messages"][1]["content"])
        self.assertEqual(user_payload["conversation"], conversation)

    def test_compact_conversation_truncates_messages_and_keeps_summary(self) -> None:
        context = SimpleNamespace(
            memory_summary="Tóm tắt cũ.",
            recent_messages=[
                SimpleNamespace(role="user", content="x" * 1000),
                SimpleNamespace(role="assistant", content="ngắn"),
            ],
        )

        result = compact_conversation_for_llm(context, max_messages=6, max_chars=400)

        self.assertEqual(result["memory_summary"], "Tóm tắt cũ.")
        self.assertEqual(len(result["recent_messages"]), 2)
        self.assertEqual(len(result["recent_messages"][0]["content"]), 400)

    def test_compact_conversation_returns_none_when_empty(self) -> None:
        self.assertIsNone(compact_conversation_for_llm(None))
        self.assertIsNone(
            compact_conversation_for_llm(SimpleNamespace(memory_summary=None, recent_messages=[]))
        )


if __name__ == "__main__":
    unittest.main()
