import json
import unittest
from types import SimpleNamespace

from agents.summary_generator import (
    LlmSummaryGenerator,
    NoopSummaryGenerator,
    SummaryResult,
)


def _make_generator(trigger: int = 6) -> LlmSummaryGenerator:
    return LlmSummaryGenerator(
        api_key="sk-test",
        model="gpt-4o-mini",
        timeout_seconds=5,
        base_url="http://localhost:4000",
        trigger_message_count=trigger,
        max_output_tokens=128,
    )


class FakeCompletions:
    def __init__(self, content: str | None = "Tóm tắt mới.", error: Exception | None = None) -> None:
        self.content = content
        self.error = error
        self.calls: list[dict] = []

    async def create(self, **kwargs):
        self.calls.append(kwargs)
        if self.error:
            raise self.error
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content=self.content))],
            usage=SimpleNamespace(prompt_tokens=50, completion_tokens=30),
            model="gpt-4o-mini",
        )


class FakeBudgetError(Exception):
    def __init__(self) -> None:
        super().__init__("ExceededBudget: crossed budget")
        self.body = {"error": {"type": "budget_exceeded", "message": "ExceededBudget"}}


def _attach_fake_client(generator: LlmSummaryGenerator, completions: FakeCompletions) -> None:
    generator.client = SimpleNamespace(chat=SimpleNamespace(completions=completions))


_CONTEXT = {
    "memory_summary": "Đang trao đổi về Patient/BN2026-00003.",
    "recent_messages": [
        {"role": "user", "content": "HbA1c của bệnh nhân BN2026-00003?"},
        {"role": "assistant", "content": "HbA1c là 7.2%."},
    ],
}


class SummaryGeneratorTests(unittest.IsolatedAsyncioTestCase):
    async def test_noop_generator_returns_empty_summary(self) -> None:
        result = await NoopSummaryGenerator().summarize(
            conversation_context=_CONTEXT,
            question="Chỉ số đó có cao không?",
            patient_id="BN2026-00003",
            total_message_count=8,
        )

        self.assertEqual(result.summary, "")
        self.assertEqual(result.source, "disabled")
        self.assertEqual(result.usage["input_tokens"], 0)

    async def test_summary_skipped_below_trigger_does_not_call_llm(self) -> None:
        generator = _make_generator(trigger=6)
        completions = FakeCompletions()
        _attach_fake_client(generator, completions)

        result = await generator.summarize(
            conversation_context=_CONTEXT,
            question="Chỉ số đó có cao không?",
            patient_id="BN2026-00003",
            total_message_count=2,
        )

        self.assertEqual(result.summary, "")
        self.assertEqual(result.source, "skipped")
        self.assertEqual(len(completions.calls), 0)

    async def test_summary_skipped_when_total_message_count_missing(self) -> None:
        generator = _make_generator()
        completions = FakeCompletions()
        _attach_fake_client(generator, completions)

        result = await generator.summarize(
            conversation_context=_CONTEXT,
            question="Chỉ số đó có cao không?",
            patient_id="BN2026-00003",
            total_message_count=None,
        )

        self.assertEqual(result.source, "skipped")
        self.assertEqual(len(completions.calls), 0)

    async def test_summary_triggered_calls_llm_and_returns_usage(self) -> None:
        generator = _make_generator(trigger=6)
        completions = FakeCompletions(content="Đã xem HbA1c 7.2% của Patient/BN2026-00003.")
        _attach_fake_client(generator, completions)

        result = await generator.summarize(
            conversation_context=_CONTEXT,
            question="Lần khám gần nhất của bệnh nhân đó?",
            patient_id="BN2026-00003",
            total_message_count=6,
        )

        self.assertEqual(result.source, "llm")
        self.assertEqual(result.summary, "Đã xem HbA1c 7.2% của Patient/BN2026-00003.")
        self.assertEqual(result.usage["input_tokens"], 50)
        self.assertEqual(result.usage["output_tokens"], 30)
        self.assertEqual(len(completions.calls), 1)
        user_payload = json.loads(completions.calls[0]["messages"][1]["content"])
        self.assertEqual(user_payload["previous_summary"], _CONTEXT["memory_summary"])
        self.assertEqual(user_payload["latest_question"], "Lần khám gần nhất của bệnh nhân đó?")
        self.assertNotIn("latest_answer", user_payload)

    async def test_summary_llm_error_returns_empty_summary_without_raising(self) -> None:
        generator = _make_generator()
        completions = FakeCompletions(error=RuntimeError("gateway down"))
        _attach_fake_client(generator, completions)

        result = await generator.summarize(
            conversation_context=_CONTEXT,
            question="Lần khám gần nhất?",
            patient_id="BN2026-00003",
            total_message_count=8,
        )

        self.assertEqual(result.summary, "")
        self.assertEqual(result.source, "error")
        self.assertIn("gateway down", result.reason or "")

    async def test_summary_budget_error_is_swallowed(self) -> None:
        generator = _make_generator()
        completions = FakeCompletions(error=FakeBudgetError())
        _attach_fake_client(generator, completions)

        result = await generator.summarize(
            conversation_context=_CONTEXT,
            question="Lần khám gần nhất?",
            patient_id="BN2026-00003",
            total_message_count=8,
        )

        self.assertEqual(result.summary, "")
        self.assertEqual(result.source, "error")

    async def test_summary_result_defaults_are_zero_usage(self) -> None:
        result = SummaryResult()
        self.assertEqual(result.usage["input_tokens"], 0)
        self.assertEqual(result.source, "skipped")


if __name__ == "__main__":
    unittest.main()
