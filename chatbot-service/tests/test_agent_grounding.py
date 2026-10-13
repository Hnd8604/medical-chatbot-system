"""Test cho agent/answer/grounding.py và chỗ móc grounding trong finalize_answer."""

import asyncio
import unittest
from datetime import date
from unittest import mock

from agent.answer.finalize import CacheTarget, TurnMeta, finalize_answer
from agent.answer.generator import AnswerResult
from agent.answer.grounding import check_answer
from agent.memory.summary import SummaryResult
from app.config import get_settings
from llm.model_select import ModelChoice


EVIDENCE = [
    {
        "resource_type": "Observation",
        "id": "obs-1",
        "summary": "Glucose 7.25 mmol/L",
        "data": {
            "value": {"value": 7.25, "unit": "mmol/L"},
            "effective_time": "2024-03-05T08:30:00+07:00",
            "subject": "Patient/BN2026-00001",
        },
    },
    {"resource_type": "Observation", "id": "obs-2", "summary": "Huyết áp 120/80 mmHg", "data": {}},
]


def check(answer, **kwargs):
    kwargs.setdefault("evidence", EVIDENCE)
    kwargs.setdefault("patient_id", "BN2026-00001")
    return check_answer(answer, **kwargs)


def kinds(result):
    return [violation["kind"] for violation in result.violations]


class CheckAnswerTests(unittest.TestCase):
    def test_answer_built_from_evidence_passes(self):
        result = check("Bệnh nhân BN2026-00001 có glucose 7.25 mmol/L, huyết áp 120/80 mmHg.")
        self.assertTrue(result.passed, result.violations)

    def test_unknown_patient_id_fails(self):
        result = check("Bệnh nhân BN2026-00002 có glucose 7.25 mmol/L.")
        self.assertEqual(kinds(result), ["patient_id"])

    def test_unknown_resource_reference_fails(self):
        self.assertTrue(check("Kết quả Observation/obs-1.").passed)
        self.assertEqual(kinds(check("Kết quả Observation/obs-9.")), ["resource_ref"])

    def test_fabricated_numbers_fail(self):
        result = check("Glucose 9,8 mmol/L và nhịp tim 72 lần/phút.")
        self.assertEqual(result.violations, [{"kind": "number", "value": "9,8"}, {"kind": "number", "value": "72"}])

    def test_vietnamese_decimal_comma_and_rounding(self):
        self.assertTrue(check("Glucose 7,25 mmol/L.").passed)
        self.assertTrue(check("Glucose khoảng 7,3 mmol/L.").passed)
        self.assertFalse(check("Glucose khoảng 7,4 mmol/L.").passed)

    def test_vietnamese_date_matches_iso_evidence(self):
        self.assertTrue(check("Đo ngày 05/03/2024 lúc 08:30, năm 2024.").passed)
        self.assertEqual(kinds(check("Đo ngày 06/03/2024.")), ["date"])

    def test_small_integers_are_not_checked(self):
        self.assertTrue(check("Có 3 kết quả trong 2 lần khám.").passed)

    def test_question_and_template_answer_are_valid_sources(self):
        self.assertTrue(check("Tìm thấy 12 bệnh nhân.", template_answer="Tìm thấy 12 bệnh nhân phù hợp.").passed)
        self.assertTrue(check("Bệnh nhân BN2026-00007 chưa có dữ liệu.", question="BN2026-00007 có gì?").passed)

    def test_age_derived_from_birth_date_is_allowed(self):
        born = date(date.today().year - 45, 1, 1).isoformat()
        evidence = [{"resource_type": "Patient", "id": "BN2026-00001", "data": {"birth_date": born}}]
        self.assertTrue(check("Bệnh nhân 45 tuổi.", evidence=evidence).passed)
        self.assertFalse(check("Bệnh nhân 62 tuổi.", evidence=evidence).passed)


class FixedGenerator:
    def __init__(self, answer, source="llm"):
        self.answer = answer
        self.source = source

    async def generate(self, **kwargs):
        return AnswerResult(
            answer=self.answer,
            source=self.source,
            usage={"input_tokens": 30, "output_tokens": 8, "cost_usd": 0.0},
            model="gpt-4o-mini",
        )


class FakeCache:
    def __init__(self):
        self.saved = []

    async def save_to_cache(self, **kwargs):
        self.saved.append(kwargs)


TEMPLATE = "Theo dữ liệu FHIR hiện có, Bệnh nhân BN2026-00001 có glucose 7.25 mmol/L."


class FinalizeGroundingTests(unittest.IsolatedAsyncioTestCase):
    async def _finalize(self, answer, *, mode="enforce", source="llm"):
        cache = FakeCache()

        async def summarize():
            return SummaryResult()

        settings = get_settings().model_copy(update={"grounding_check_mode": mode})
        with mock.patch("agent.answer.finalize.get_settings", return_value=settings):
            payload = await finalize_answer(
                {"answer": TEMPLATE, "intent": "observations", "patient_id": "BN2026-00001", "evidence": list(EVIDENCE)},
                question="Glucose của bệnh nhân?",
                meta=TurnMeta(tool_name="get_observations", intent="observations", patient_id="BN2026-00001"),
                answer_generator=FixedGenerator(answer, source),
                model_choice=ModelChoice("gpt-4o-mini", "simple", "plan"),
                summary_task=asyncio.ensure_future(summarize()),
                conversation_context=None,
                answer_mode="data_only",
                step_count=1,
                cache=CacheTarget(service=cache, user_id="u1"),
            )
        return payload, cache

    async def test_grounded_answer_is_kept_and_cached(self):
        payload, cache = await self._finalize("Glucose của bệnh nhân là 7,25 mmol/L.")
        self.assertEqual(payload["answer_source"], "llm")
        self.assertEqual(payload["grounding"], {"checked": True, "passed": True})
        self.assertEqual(len(cache.saved), 1)

    async def test_enforce_replaces_ungrounded_answer_with_template(self):
        payload, cache = await self._finalize("Glucose của bệnh nhân là 9,8 mmol/L.")
        self.assertEqual(payload["answer"], TEMPLATE)
        self.assertEqual(payload["answer_source"], "template_grounding_fallback")
        self.assertEqual(payload["grounding"], {"checked": True, "passed": False})
        # Token answer đã tiêu thật nên vẫn được tính.
        self.assertEqual(payload["answer_usage"]["input_tokens"], 30)
        self.assertEqual(cache.saved, [])

    async def test_log_mode_keeps_answer_but_skips_cache(self):
        payload, cache = await self._finalize("Glucose của bệnh nhân là 9,8 mmol/L.", mode="log")
        self.assertEqual(payload["answer"], "Glucose của bệnh nhân là 9,8 mmol/L.")
        self.assertEqual(payload["answer_source"], "llm")
        self.assertEqual(payload["grounding"]["passed"], False)
        self.assertEqual(cache.saved, [])

    async def test_off_mode_and_template_answers_are_not_checked(self):
        payload, cache = await self._finalize("Glucose là 9,8 mmol/L.", mode="off")
        self.assertEqual(payload["grounding"], {"checked": False, "passed": None})
        self.assertEqual(len(cache.saved), 1)
        payload, _ = await self._finalize(TEMPLATE, source="template_fast_path")
        self.assertEqual(payload["grounding"]["checked"], False)


if __name__ == "__main__":
    unittest.main()
