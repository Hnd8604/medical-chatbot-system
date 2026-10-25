"""Test cho low-cost mode: rule router, template ở nhánh chat, finalize rẻ, summary thưa,
và ngưỡng hạ model."""

import asyncio
import unittest
from unittest import mock

from agent.answer.finalize import CacheTarget, TurnMeta, finalize_answer
from agent.memory.summary import SUMMARY_WINDOW_MESSAGES, SummaryResult, low_cost_summary_due
from agent.nodes.simple import GENERAL_CHAT_FALLBACK, UNSUPPORTED_FALLBACK, conversation_meta_template
from agent.planning.rule_router import rule_route
from api.schemas import ChatResponse
from app.config import Settings
from llm.model_select import ModelChoice, select_answer_model
from tests.test_agent_answer import _EVIDENCE, FakeCache, FakeEnrichment, FakeGenerator, _condition_payload, _knowledge_item
from tests.test_agent_graph import AgentGraphFixture, make_request


LOW = 0.95


# ---------------------------------------------------------------- rule router


class RuleRouterTests(unittest.TestCase):
    def assertRoute(self, message, route, *, safety_flag="none", meta_kind=None):
        decision = rule_route(message)
        self.assertEqual(decision.route, route, message)
        self.assertEqual(decision.safety_flag, safety_flag, message)
        self.assertEqual(decision.meta_kind, meta_kind, message)
        self.assertEqual(decision.source, "low_cost")

    def test_unsafe_requests_are_blocked(self):
        for message, flag in (
            ("Tôi có nên ngưng thuốc huyết áp không?", "treatment_request"),
            ("Bệnh nhân nên uống thuốc gì?", "treatment_request"),
            ("Tôi có thể tự tăng liều insulin không?", "treatment_request"),
            ("Giảm liều được không bác sĩ?", "treatment_request"),
            ("Kê đơn cho tôi thuốc ngủ", "treatment_request"),
            ("Đau đầu uống thuốc gì cho khỏi?", "treatment_request"),
            ("Làm sao để chữa tiểu đường?", "treatment_request"),
            ("Tôi đau đầu, hãy chẩn đoán tôi bị bệnh gì", "diagnosis_request"),
            ("Chóng mặt buồn nôn là bị gì vậy", "diagnosis_request"),
            ("Có phải tôi bị ung thư không?", "diagnosis_request"),
            ("Tôi nên làm gì bây giờ?", "medical_advice"),
            ("Should I stop taking metformin?", "treatment_request"),
        ):
            self.assertRoute(message, "unsupported", safety_flag=flag)

    def test_data_questions_still_go_to_fhir(self):
        for message in (
            "Bệnh nhân BN2026-00001 đang dùng thuốc gì?",
            "Bệnh nhân BN2026-00001 bị bệnh gì?",
            "Bệnh nhân có được kê đơn thuốc nào không?",
            "Chẩn đoán của bệnh nhân Nguyễn Văn A",
            "Lần khám đầu tiên bệnh nhân bị bệnh gì?",
            "Liều metformin hiện tại của bệnh nhân là bao nhiêu?",
            "HbA1c là gì?",
            "Chào bạn, cho mình xem thuốc của BN2026-00001",
            "nền tảng tăng huyết áp của bệnh nhân",
        ):
            self.assertRoute(message, "fhir")

    def test_small_talk_goes_to_general_chat(self):
        for message in ("Xin chào", "chào bạn nhé!", "Cảm ơn nhiều", "hi", "Xin chào, bạn làm được gì?", "Bạn là ai?"):
            self.assertRoute(message, "general_chat")

    def test_conversation_questions_go_to_meta(self):
        for message, kind in (
            ("Tóm tắt cuộc trò chuyện giúp tôi", "summary"),
            ("Bạn vừa nói gì?", "last_answer"),
            ("Câu trước tôi hỏi gì nhỉ, câu hỏi trước ấy", "last_answer"),
            ("Mình đang nói về bệnh nhân nào?", "current_context"),
        ):
            self.assertRoute(message, "conversation_meta", meta_kind=kind)


class ConversationMetaTemplateTests(unittest.TestCase):
    CONTEXT = {
        "memory_summary": "Đang xem HbA1c của BN2026-00003.",
        "active_patient_id": "BN2026-00003",
        "recent_messages": [
            {"role": "user", "content": "HbA1c của bệnh nhân?"},
            {"role": "assistant", "content": "HbA1c là 7.2%."},
        ],
    }

    def test_builds_answers_from_context(self):
        self.assertIn("Đang xem HbA1c", conversation_meta_template(self.CONTEXT, "summary"))
        last = conversation_meta_template(self.CONTEXT, "last_answer")
        self.assertIn("HbA1c là 7.2%.", last)
        self.assertIn("HbA1c của bệnh nhân?", last)
        self.assertIn("BN2026-00003", conversation_meta_template(self.CONTEXT, "current_context"))

    def test_summary_without_memory_lists_recent_questions(self):
        context = {"recent_messages": self.CONTEXT["recent_messages"]}
        self.assertIn("- HbA1c của bệnh nhân?", conversation_meta_template(context, "summary"))

    def test_empty_context_gives_none(self):
        self.assertIsNone(conversation_meta_template({}, "summary"))
        self.assertIsNone(conversation_meta_template({}, "last_answer"))


# ---------------------------------------------------------------- summary cadence


class LowCostSummaryCadenceTests(unittest.TestCase):
    def test_runs_every_two_turns_from_trigger(self):
        due = [count for count in range(0, 30, 2) if low_cost_summary_due(count)]
        self.assertEqual(due, [8, 12, 16, 20, 24, 28])

    def test_odd_counts_still_hit_every_two_turns(self):
        due = [count for count in range(9, 30, 2) if low_cost_summary_due(count)]
        self.assertEqual(due, [9, 13, 17, 21, 25, 29])

    def test_one_missed_summary_loses_no_message(self):
        # Mô phỏng: lượt có `count` tin trước nó thì summary thấy tin (count-window, count].
        # Lỡ bất kỳ MỘT lần đến hạn nào (trừ lần đầu, xem dưới), mọi tin tới lần tóm tắt
        # thành công cuối vẫn phải được nhìn thấy. Lần đầu (count=8) lỡ thì chế độ thường
        # cũng mất tin — chưa có summary cũ nào để nối vào.
        due = [count for count in range(8, 60, 2) if low_cost_summary_due(count)]
        for missed in due[1:]:
            done = [count for count in due if count != missed]
            covered: set[int] = set()
            for count in done:
                covered |= set(range(max(count - SUMMARY_WINDOW_MESSAGES + 1, 1), count + 1))
            self.assertEqual(covered, set(range(1, done[-1] + 1)), f"lỡ lần count={missed}")

    def test_unknown_count_never_runs(self):
        self.assertFalse(low_cost_summary_due(None))


# ---------------------------------------------------------------- model select


class QuotaDowngradeThresholdTests(unittest.TestCase):
    def _choose(self, ratio, **settings):
        with mock.patch(
            "llm.model_select.get_settings",
            return_value=Settings(model_simple="cheap", model_complex="strong", **settings),
        ):
            return select_answer_model(step_count=2, explain=False, all_patients=False, quota_used_ratio=ratio)

    def test_threshold_is_inclusive(self):
        self.assertEqual(self._choose(0.8).source, "quota_downgrade")
        self.assertEqual(self._choose(0.79).model, "strong")

    def test_threshold_is_configurable(self):
        self.assertEqual(self._choose(0.6, quota_downgrade_ratio=0.5).model, "cheap")


# ---------------------------------------------------------------- finalize


class LowCostFinalizeTests(unittest.IsolatedAsyncioTestCase):
    async def _finalize(self, payload, *, low_cost, answer_mode="data_only", explain=False, settings=None):
        generator = FakeGenerator()
        fake_enrichment = FakeEnrichment([_knowledge_item()])

        async def summarize():
            return SummaryResult()

        with mock.patch("agent.answer.finalize.get_enrichment_service", return_value=fake_enrichment), \
             mock.patch("agent.answer.finalize.get_settings", return_value=settings or Settings()):
            result = await finalize_answer(
                payload,
                question="Bệnh nhân có chẩn đoán gì?",
                meta=TurnMeta(tool_name="get_conditions", intent="conditions", patient_id="BN2026-00001", explain=explain),
                answer_generator=generator,
                model_choice=ModelChoice("gpt-4o-mini", "simple", "plan"),
                summary_task=asyncio.ensure_future(summarize()),
                conversation_context=None,
                answer_mode=answer_mode,
                step_count=1,
                cache=CacheTarget(service=FakeCache(), user_id="u1"),
                low_cost=low_cost,
            )
        return result, generator, fake_enrichment

    async def test_simple_data_turn_uses_template_without_flag(self):
        payload, generator, _ = await self._finalize(_condition_payload(), low_cost=True)
        self.assertEqual(generator.calls, 0)
        self.assertEqual(payload["answer_source"], "template_low_cost")

    async def test_normal_mode_keeps_llm_answer(self):
        payload, generator, _ = await self._finalize(_condition_payload(), low_cost=False)
        self.assertEqual(generator.calls, 1)
        self.assertEqual(payload["answer_source"], "llm")

    async def test_terminology_skipped_in_low_cost(self):
        payload = {**_condition_payload(), "evidence": _EVIDENCE}
        _, _, enrichment = await self._finalize(payload, low_cost=True, answer_mode="explain_with_external_knowledge", explain=True)
        self.assertEqual(enrichment.calls, 0)
        _, _, enrichment = await self._finalize(payload, low_cost=False, answer_mode="explain_with_external_knowledge", explain=True)
        self.assertEqual(enrichment.calls, 1)

    async def test_smaller_evidence_budget_in_low_cost(self):
        evidence = [
            {"resource_type": "Condition", "id": f"C{i}", "summary": "x" * 200, "data": {}} for i in range(10)
        ]
        settings = Settings(evidence_max_chars=100000, low_cost_evidence_max_chars=600)
        payload, _, _ = await self._finalize(
            {**_condition_payload(), "evidence": evidence}, low_cost=True, answer_mode="explain_with_external_knowledge", settings=settings
        )
        self.assertLess(len(payload["evidence"]), 10)
        self.assertIn("evidence_pruning", payload)


# ---------------------------------------------------------------- graph end-to-end


class CountingSummaryGenerator:
    def __init__(self):
        self.calls = 0
        self.contexts = []

    async def summarize(self, **kwargs):
        self.calls += 1
        self.contexts.append(kwargs["conversation_context"])
        return SummaryResult()


class LowCostGraphTests(AgentGraphFixture):
    def _context(self, total):
        return {
            "active_patient_id": "BN2026-00001",
            "total_message_count": total,
            "recent_messages": [
                {"role": "user", "content": "Bệnh nhân có chẩn đoán gì?"},
                {"role": "assistant", "content": "Bệnh nhân có 1 chẩn đoán: Đái tháo đường."},
            ],
        }

    async def test_low_cost_blocks_unsafe_question_without_llm_or_fhir(self):
        payload, calls = await self._run(
            make_request(message="Tôi có nên ngưng thuốc huyết áp không?", quota_used_ratio=LOW), llm_responses={}
        )
        self.assertEqual(calls, [])
        self.assertEqual(self.client.calls, [])
        self.assertEqual(payload["agent_route"], "unsupported")
        self.assertEqual(payload["safety_flag"], "treatment_request")
        self.assertEqual(payload["answer"], UNSUPPORTED_FALLBACK)
        self.assertEqual(payload["answer_source"], "template_low_cost_unsupported")
        ChatResponse.model_validate(payload)

    async def test_low_cost_greeting_uses_template(self):
        payload, calls = await self._run(
            make_request(message="Xin chào", quota_used_ratio=LOW),
            llm_responses={"agent_chat": '{"answer": "không được gọi"}'},
        )
        self.assertEqual(calls, [])
        self.assertEqual(payload["agent_route"], "general_chat")
        self.assertEqual(payload["answer"], GENERAL_CHAT_FALLBACK)

    async def test_low_cost_conversation_meta_answers_from_context(self):
        payload, calls = await self._run(
            make_request(message="Bạn vừa nói gì?", quota_used_ratio=LOW, conversation_context=self._context(4)),
            llm_responses={},
        )
        self.assertEqual(calls, [])
        self.assertEqual(payload["agent_route"], "conversation_meta")
        self.assertIn("Đái tháo đường", payload["answer"])

    async def test_low_cost_simple_data_question_costs_zero_calls(self):
        payload, calls = await self._run(
            make_request(message="Bệnh nhân BN2026-00001 có chẩn đoán gì?", quota_used_ratio=LOW), llm_responses={}
        )
        self.assertEqual(calls, [])
        self.assertEqual(self.answer_generator.calls, 0)
        self.assertEqual(payload["answer_source"], "template_low_cost")
        self.assertEqual(payload["response_status"], "answered")

    async def _run_with_summary(self, *, ratio, total):
        summary = CountingSummaryGenerator()
        await self._run(
            make_request(
                message="Bệnh nhân BN2026-00001 có chẩn đoán gì?",
                quota_used_ratio=ratio,
                conversation_context=self._context(total),
            ),
            llm_responses={},
            summary_generator=summary,
        )
        return summary.calls

    async def test_low_cost_summary_runs_only_when_due(self):
        self.assertEqual(await self._run_with_summary(ratio=LOW, total=10), 0)
        self.assertEqual(await self._run_with_summary(ratio=LOW, total=12), 1)
        self.assertEqual(await self._run_with_summary(ratio=0.0, total=10), 1)

    async def test_summary_with_watermark_gets_only_new_messages(self):
        context = self._context(12)
        context["summarized_message_count"] = 10
        context["recent_messages"] = [
            {"role": "user" if i % 2 == 0 else "assistant", "content": f"tin {i + 5}"} for i in range(8)
        ]
        summary = CountingSummaryGenerator()
        await self._run(
            make_request(message="Bệnh nhân BN2026-00001 có chẩn đoán gì?", conversation_context=context),
            llm_responses={},
            summary_generator=summary,
        )
        self.assertEqual([m["content"] for m in summary.contexts[0]["recent_messages"]], ["tin 11", "tin 12"])

        # Low-cost: 2 tin mới chưa đủ ngưỡng 8 ⇒ không tóm tắt.
        low_cost_summary = CountingSummaryGenerator()
        await self._run(
            make_request(message="Bệnh nhân BN2026-00001 có chẩn đoán gì?", conversation_context=context, quota_used_ratio=LOW),
            llm_responses={},
            summary_generator=low_cost_summary,
        )
        self.assertEqual(low_cost_summary.calls, 0)

    async def test_summary_sees_full_spring_window(self):
        summary = CountingSummaryGenerator()
        context = self._context(8)
        context["recent_messages"] = [
            {"role": "user" if i % 2 == 0 else "assistant", "content": f"tin {i + 1}"} for i in range(8)
        ]
        await self._run(
            make_request(message="Bệnh nhân BN2026-00001 có chẩn đoán gì?", conversation_context=context),
            llm_responses={},
            summary_generator=summary,
        )
        seen = [m["content"] for m in summary.contexts[0]["recent_messages"]]
        self.assertEqual(seen, [f"tin {i}" for i in range(1, 9)])


if __name__ == "__main__":
    unittest.main()
