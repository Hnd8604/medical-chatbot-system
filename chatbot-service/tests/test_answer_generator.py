import unittest

from agents.answer_generator import (
    TemplateAnswerGenerator,
    clean_llm_answer,
    combine_usage,
    compact_evidence_for_llm,
)


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


if __name__ == "__main__":
    unittest.main()
