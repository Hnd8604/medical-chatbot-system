"""Test cho agent/planning/: router, planner, rule planner, validator, plan cache.

Validator là lớp enforce chính sách — thứ dễ hỏng nhất của một agent nhiều bước — nên
được test kỹ nhất ở đây.
"""

import unittest
from unittest import mock

from agent.errors import PlanError, PolicyError
from agent.planning.plan import Plan, PlanStep, plan_from_dict
from agent.planning.plan_cache import PATIENT_PLACEHOLDER, _plan_from_payload, _redact_step, refill_identifiers
from agent.planning.router import parse_route, reset_route_cache
from agent.planning.rule_planner import extract_resource_reference, is_fhir_status_request, rule_plan
from agent.planning.validator import validate_plan
from agent.state import ANSWER_MODE_DATA_ONLY, ANSWER_MODE_EXPLAIN
from app.config import get_settings
from llm.client import LlmCallError


def make_plan(*steps: PlanStep, answer_mode: str = ANSWER_MODE_DATA_ONLY) -> Plan:
    return Plan(steps=tuple(steps), answer_mode=answer_mode)


# --------------------------------------------------------------------- router


class RouterParsingTests(unittest.TestCase):
    def test_parses_fenced_json(self):
        decision = parse_route('```json\n{"route": "fhir", "resource_hint": "Condition"}\n```')
        self.assertEqual(decision.route, "fhir")
        self.assertEqual(decision.resource_hint, "Condition")

    def test_clarification_route_becomes_fhir(self):
        # clarification là response_status của executor, không phải một route.
        self.assertEqual(parse_route('{"route": "clarification_needed"}').route, "fhir")

    def test_unknown_route_raises(self):
        with self.assertRaises(LlmCallError):
            parse_route('{"route": "sql"}')

    def test_conversation_meta_without_kind_defaults_to_current_context(self):
        self.assertEqual(parse_route('{"route": "conversation_meta"}').meta_kind, "current_context")

    def test_unknown_resource_hint_and_safety_flag_are_dropped(self):
        decision = parse_route('{"route": "fhir", "resource_hint": "Invoice", "safety_flag": "???"}')
        self.assertIsNone(decision.resource_hint)
        self.assertEqual(decision.safety_flag, "none")


class RouteCacheTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        reset_route_cache()

    async def asyncTearDown(self):
        reset_route_cache()

    async def _decide(self, router_module, message, context=None):
        return await router_module.decide_route(
            message=message,
            user_role="DOCTOR",
            provided_patient_id=None,
            conversation_context=context,
            model="m",
        )

    async def test_second_identical_question_skips_llm(self):
        from agent.planning import router

        calls = []

        async def fake_call_llm(**kwargs):
            calls.append(kwargs)
            return mock.Mock(content='{"route": "fhir"}', usage={"input_tokens": 10, "output_tokens": 2})

        with mock.patch.object(router, "call_llm", fake_call_llm):
            first, first_usage = await self._decide(router, "Bệnh nhân này dùng thuốc gì")
            # Không dấu vẫn phải trúng cùng một entry cache.
            second, second_usage = await self._decide(router, "Benh nhan nay dung thuoc gi")

        self.assertEqual(len(calls), 1)
        self.assertEqual((first.source, second.source), ("llm", "route_cache"))
        self.assertEqual(second_usage["input_tokens"], 0)
        self.assertGreater(first_usage["input_tokens"], 0)

    async def test_context_shape_splits_cache_entries(self):
        from agent.planning import router

        calls = []

        async def fake_call_llm(**kwargs):
            calls.append(kwargs)
            return mock.Mock(content='{"route": "fhir"}', usage={"input_tokens": 1, "output_tokens": 1})

        with mock.patch.object(router, "call_llm", fake_call_llm):
            await self._decide(router, "bệnh nhân này dùng thuốc gì")
            await self._decide(router, "bệnh nhân này dùng thuốc gì", {"active_patient_id": "BN2026-00001"})
        # Có/không có bệnh nhân đang hoạt động là hai ngữ cảnh khác nhau.
        self.assertEqual(len(calls), 2)


# -------------------------------------------------------------------- planner


class PlanParsingTests(unittest.TestCase):
    def test_multi_step_plan(self):
        plan = plan_from_dict(
            {
                "answer_mode": "explain_with_external_knowledge",
                "steps": [
                    {"id": "resolve_patient", "tool": "search_patients", "args": {"name": "A"}},
                    {"id": "meds", "tool": "get_medication_requests", "args": {"patient_id": "$resolve_patient.patient_id"}},
                ],
            },
            stage="agent_planner",
        )
        self.assertEqual(len(plan.steps), 2)
        self.assertTrue(plan.explain)

    def test_unknown_answer_mode_falls_back_to_data_only(self):
        plan = plan_from_dict({"answer_mode": "freestyle", "steps": [{"tool": "fhir_status"}]}, stage="p")
        self.assertEqual(plan.answer_mode, ANSWER_MODE_DATA_ONLY)

    def test_empty_steps_raise(self):
        with self.assertRaises(LlmCallError):
            plan_from_dict({"steps": []}, stage="p")

    def test_duplicate_step_ids_are_made_unique(self):
        plan = plan_from_dict({"steps": [{"id": "s", "tool": "fhir_status"}, {"id": "s", "tool": "fhir_status"}]}, stage="p")
        self.assertNotEqual(plan.steps[0].id, plan.steps[1].id)


class RulePlannerTests(unittest.TestCase):
    def _tools(self, message, **kwargs):
        plan = rule_plan(message, **kwargs)
        return [step.tool for step in plan.steps] if plan else None

    def test_keyword_routing(self):
        cases = {
            "benh nhan dang dung thuoc gi": "get_medication_requests",
            "spo2 cua benh nhan 1 la bao nhieu": "get_observations",
            "benh nhan co di ung gi khong": "get_conditions",
            "benh nhan nhap vien lan nao": "get_encounters",
            "benh nhan co lich su cap cuu khong": "get_encounters",
            "FHIR server co hoat dong khong?": "fhir_status",
            "chi tiet Observation/OBS-2026-00002": "get_resource_by_id",
            "thong tin benh nhan": "get_patient_by_id",
        }
        for message, tool in cases.items():
            # Step cuối là step lấy dữ liệu (có thể có search_patients đứng trước).
            self.assertEqual(self._tools(message)[-1], tool, message)

    def test_unrecognized_question_returns_none(self):
        self.assertIsNone(rule_plan("thoi tiet hom nay the nao"))

    def test_status_without_fhir_keyword_is_not_status(self):
        self.assertNotEqual(self._tools("trang thai benh nhan 1 the nao"), ["fhir_status"])

    def test_observation_type_and_explain(self):
        plan = rule_plan("HbA1c của bệnh nhân BN2026-00001 có ý nghĩa gì")
        self.assertEqual(plan.steps[0].args["observation_type"], "hba1c")
        self.assertEqual(plan.steps[0].args["patient_id"], "BN2026-00001")
        self.assertEqual(plan.answer_mode, ANSWER_MODE_EXPLAIN)
        self.assertEqual(plan.source, "rule_fallback")

    def test_selected_patient_is_used_for_contextual_reference(self):
        plan = rule_plan("Bệnh nhân này đang dùng những thuốc nào?", provided_patient_id="BN2026-00001")
        self.assertEqual(len(plan.steps), 1)
        self.assertEqual(plan.steps[0].args["patient_id"], "BN2026-00001")

    def test_named_patient_resolves_before_data_step(self):
        plan = rule_plan("Thuốc của bệnh nhân Nguyễn Văn A", provided_patient_id="BN2026-00009")
        self.assertEqual([step.tool for step in plan.steps], ["search_patients", "get_medication_requests"])
        self.assertEqual(plan.steps[0].args["name"], "Nguyễn Văn A")
        self.assertEqual(plan.steps[1].args["patient_id"], "$resolve_patient.patient_id")

    def test_all_patient_scope(self):
        self.assertEqual(self._tools("thuoc cua tat ca benh nhan"), ["get_all_patient_medication_requests"])

    def test_resource_reference_helpers(self):
        self.assertEqual(extract_resource_reference("xem Encounter/ENC-2026-00004"), ("Encounter", "ENC-2026-00004"))
        self.assertEqual(extract_resource_reference("xem medicationrequest/MR-1"), ("MedicationRequest", "MR-1"))
        self.assertIsNone(extract_resource_reference("Patient/BN2026-00001"))
        self.assertTrue(is_fhir_status_request("trạng thái FHIR server"))
        self.assertFalse(is_fhir_status_request("trạng thái bệnh nhân"))


# ------------------------------------------------------------------ validator


class PlanValidatorTests(unittest.TestCase):
    def _validate(self, plan, *, role="DOCTOR", allowed=None, message="thuốc của bệnh nhân"):
        return validate_plan(
            plan,
            message=message,
            user_role=role,
            allowed_patient_ids=allowed or [],
            provided_patient_id=None,
        )

    def test_unknown_tool_rejected(self):
        with self.assertRaises(PlanError):
            self._validate(make_plan(PlanStep(id="s", tool="drop_table")))

    def test_unknown_args_dropped_and_limit_clamped(self):
        validated = self._validate(
            make_plan(PlanStep(id="s", tool="get_conditions", args={"patient_id": "BN2026-00001", "limit": 999, "sql": "DROP"}))
        )
        args = validated.steps[0].args
        self.assertNotIn("sql", args)
        self.assertEqual(args["limit"], 20)

    def test_user_cannot_search_patients(self):
        with self.assertRaises(PolicyError):
            self._validate(
                make_plan(PlanStep(id="s", tool="search_patients", args={"name": "B"})),
                role="USER",
                allowed=["BN2026-00001"],
            )

    def test_user_cannot_query_all_patients(self):
        with self.assertRaises(PolicyError):
            self._validate(
                make_plan(PlanStep(id="s", tool="get_all_patient_medication_requests")),
                role="USER",
                allowed=["BN2026-00001"],
            )

    def test_user_without_linked_patient_rejected(self):
        with self.assertRaises(PolicyError):
            self._validate(make_plan(PlanStep(id="s", tool="get_conditions")), role="USER", allowed=[])

    def test_user_cannot_reach_another_patient(self):
        with self.assertRaises(PolicyError):
            self._validate(
                make_plan(PlanStep(id="s", tool="get_conditions", args={"patient_id": "BN2026-00009"})),
                role="USER",
                allowed=["BN2026-00001"],
                message="chẩn đoán của BN2026-00009",
            )

    def test_user_patient_id_is_injected(self):
        validated = self._validate(
            make_plan(PlanStep(id="s", tool="get_conditions")),
            role="USER",
            allowed=["BN2026-00001"],
            message="chẩn đoán của tôi",
        )
        self.assertEqual(validated.steps[0].args["patient_id"], "BN2026-00001")

    def test_user_resource_lookup_blocked_by_default(self):
        with self.assertRaises(PolicyError):
            self._validate(
                make_plan(PlanStep(id="s", tool="get_resource_by_id", args={"resource_type": "Observation", "resource_id": "OBS-1"})),
                role="USER",
                allowed=["BN2026-00001"],
            )

    def test_resource_lookup_requires_supported_type(self):
        with self.assertRaises(PlanError):
            self._validate(
                make_plan(PlanStep(id="s", tool="get_resource_by_id", args={"resource_type": "AuditEvent", "resource_id": "x"}))
            )

    def test_resource_type_is_canonicalized(self):
        validated = self._validate(
            make_plan(PlanStep(id="s", tool="get_resource_by_id", args={"resource_type": "observation", "resource_id": "OBS-1"}))
        )
        self.assertEqual(validated.steps[0].args["resource_type"], "Observation")

    def test_dangling_step_reference_is_dropped(self):
        validated = self._validate(
            make_plan(PlanStep(id="meds", tool="get_medication_requests", args={"patient_id": "$ghost.patient_id"}))
        )
        self.assertNotIn("patient_id", validated.steps[0].args)

    def test_valid_step_reference_is_kept(self):
        validated = self._validate(
            make_plan(
                PlanStep(id="resolve_patient", tool="search_patients", args={"name": "A"}),
                PlanStep(id="meds", tool="get_medication_requests", args={"patient_id": "$resolve_patient.patient_id"}),
            )
        )
        self.assertEqual(validated.steps[1].args["patient_id"], "$resolve_patient.patient_id")

    def test_steps_are_capped(self):
        max_steps = get_settings().max_plan_steps
        validated = self._validate(make_plan(*[PlanStep(id=f"s{i}", tool="fhir_status") for i in range(max_steps + 3)]))
        self.assertEqual(len(validated.steps), max_steps)


# ----------------------------------------------------------------- plan cache


class PlanCacheTests(unittest.TestCase):
    def test_identifying_values_are_replaced(self):
        redacted = _redact_step(
            PlanStep(id="m", tool="get_medication_requests", args={"patient_id": "BN2026-00001", "limit": 10, "name": "Nguyễn Văn A"})
        )
        self.assertEqual(redacted["args"]["patient_id"], PATIENT_PLACEHOLDER)
        self.assertEqual(redacted["args"]["name"], PATIENT_PLACEHOLDER)
        self.assertEqual(redacted["args"]["limit"], 10)

    def test_placeholder_is_stripped_when_loading(self):
        plan = _plan_from_payload(
            {
                "steps": [{"id": "m", "tool": "get_medication_requests", "args": {"patient_id": PATIENT_PLACEHOLDER}}],
                "answer_mode": ANSWER_MODE_DATA_ONLY,
            }
        )
        self.assertNotIn("patient_id", plan.steps[0].args)
        self.assertEqual(plan.source, "plan_cache")

    def test_search_criteria_refilled_from_current_question(self):
        cached = make_plan(
            PlanStep(id="resolve_patient", tool="search_patients", args={"limit": 3}),
            PlanStep(id="m", tool="get_medication_requests", args={"patient_id": "$resolve_patient.patient_id"}),
        )
        plan = refill_identifiers(cached, question="Thuốc của bệnh nhân Trần Thị B", conversation_context=None)
        self.assertEqual(plan.steps[0].args["name"], "Trần Thị B")

    def test_cache_hit_without_criteria_is_a_miss(self):
        # Không điền lại được tiêu chí ⇒ không được chạy search rỗng (sẽ liệt kê bệnh nhân).
        cached = make_plan(PlanStep(id="resolve_patient", tool="search_patients", args={"limit": 3}))
        self.assertIsNone(refill_identifiers(cached, question="thuốc của bệnh nhân này", conversation_context=None))

    def test_resource_id_refilled_from_context(self):
        cached = make_plan(PlanStep(id="r", tool="get_resource_by_id", args={}))
        plan = refill_identifiers(
            cached,
            question="chỉ số này có ý nghĩa gì",
            conversation_context={"last_resource_type": "Observation", "last_resource_id": "OBS-7"},
        )
        self.assertEqual(plan.steps[0].args, {"resource_type": "Observation", "resource_id": "OBS-7"})
        self.assertIsNone(refill_identifiers(cached, question="chỉ số này có ý nghĩa gì", conversation_context=None))


if __name__ == "__main__":
    unittest.main()
