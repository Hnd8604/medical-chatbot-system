"""Test cho LangGraph agent (M-LG).

Trọng tâm: lớp enforce chính sách (plan_validator + guard trong executor) và các
đường lui khi LLM lỗi — đây là hai thứ mà một agent nhiều bước dễ hỏng nhất.
"""

import asyncio
import unittest
from unittest import mock

from agents.answer_generator import AnswerResult
from agents.intent.models import IntentPlan
from agents.summary_generator import SummaryResult
from api.chat_schemas import ChatRequest
from app.config import get_settings
from langgraph_agent.errors import AgentLlmError, PlanError, PolicyError
from langgraph_agent.evidence_budget import apply_evidence_budget
from langgraph_agent.plan_cache import PATIENT_PLACEHOLDER, _redact_step
from langgraph_agent.plan_executor import execute_plan
from langgraph_agent.plan_validator import validate_plan
from langgraph_agent.planner import Plan, PlanStep, from_intent_plan, plan_from_dict
from langgraph_agent.request_router import parse_route, reset_route_cache
from langgraph_agent.state import ANSWER_MODE_DATA_ONLY, ANSWER_MODE_EXPLAIN


def run(coro):
    return asyncio.run(coro)


def make_plan(*steps: PlanStep, answer_mode: str = ANSWER_MODE_DATA_ONLY) -> Plan:
    return Plan(steps=tuple(steps), answer_mode=answer_mode)


def make_request(**overrides) -> ChatRequest:
    payload = {
        "user_id": "u1",
        "user_role": "DOCTOR",
        "message": "Bệnh nhân BN2026-00001 đang dùng thuốc gì?",
        "allowed_patient_ids": [],
    }
    payload.update(overrides)
    return ChatRequest(**payload)


# --------------------------------------------------------------------- router


class RouterParsingTests(unittest.TestCase):
    def test_parses_fenced_json(self):
        decision = parse_route('```json\n{"route": "fhir", "resource_hint": "Condition"}\n```')
        self.assertEqual(decision.route, "fhir")
        self.assertEqual(decision.resource_hint, "Condition")

    def test_legacy_clarification_route_becomes_fhir(self):
        # clarification là response_status của executor, không phải một route.
        self.assertEqual(parse_route('{"route": "clarification_needed"}').route, "fhir")

    def test_unknown_route_raises(self):
        with self.assertRaises(AgentLlmError):
            parse_route('{"route": "sql"}')

    def test_conversation_meta_without_kind_defaults_to_current_context(self):
        decision = parse_route('{"route": "conversation_meta"}')
        self.assertEqual(decision.meta_kind, "current_context")

    def test_unknown_resource_hint_and_safety_flag_are_dropped(self):
        decision = parse_route('{"route": "fhir", "resource_hint": "Invoice", "safety_flag": "???"}')
        self.assertIsNone(decision.resource_hint)
        self.assertEqual(decision.safety_flag, "none")


class RouteCacheTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        reset_route_cache()

    async def asyncTearDown(self):
        reset_route_cache()

    async def test_second_identical_question_skips_llm(self):
        from langgraph_agent import request_router

        calls = []

        async def fake_call_llm(**kwargs):
            calls.append(kwargs)
            return mock.Mock(
                content='{"route": "fhir"}',
                usage={"input_tokens": 10, "output_tokens": 2, "estimated_cost_usd": 0},
            )

        with mock.patch.object(request_router, "call_llm", fake_call_llm):
            first, first_usage = await request_router.decide_route(
                message="Bệnh nhân này dùng thuốc gì",
                user_role="DOCTOR",
                provided_patient_id=None,
                conversation_context=None,
                model="m",
            )
            second, second_usage = await request_router.decide_route(
                # Không dấu vẫn phải trúng cùng một entry cache.
                message="Benh nhan nay dung thuoc gi",
                user_role="DOCTOR",
                provided_patient_id=None,
                conversation_context=None,
                model="m",
            )

        self.assertEqual(len(calls), 1)
        self.assertEqual(first.source, "llm")
        self.assertEqual(second.source, "route_cache")
        self.assertEqual(second_usage["input_tokens"], 0)
        self.assertGreater(first_usage["input_tokens"], 0)

    async def test_context_shape_splits_cache_entries(self):
        from langgraph_agent import request_router

        calls = []

        async def fake_call_llm(**kwargs):
            calls.append(kwargs)
            return mock.Mock(content='{"route": "fhir"}', usage={"input_tokens": 1, "output_tokens": 1})

        with mock.patch.object(request_router, "call_llm", fake_call_llm):
            await request_router.decide_route(
                message="bệnh nhân này dùng thuốc gì",
                user_role="DOCTOR",
                provided_patient_id=None,
                conversation_context=None,
                model="m",
            )
            await request_router.decide_route(
                message="bệnh nhân này dùng thuốc gì",
                user_role="DOCTOR",
                provided_patient_id=None,
                conversation_context={"active_patient_id": "BN2026-00001"},
                model="m",
            )
        # Có/không có bệnh nhân đang hoạt động là hai ngữ cảnh khác nhau.
        self.assertEqual(len(calls), 2)


# -------------------------------------------------------------------- planner


class PlannerParsingTests(unittest.TestCase):
    def test_multi_step_plan(self):
        plan = plan_from_dict(
            {
                "answer_mode": "explain_with_external_knowledge",
                "steps": [
                    {"id": "resolve_patient", "tool": "search_patients", "args": {"name": "A"}},
                    {"id": "meds", "tool": "get_medication_requests", "args": {"patient_id": "$resolve_patient.patient_id"}},
                ],
            }
        )
        self.assertEqual(len(plan.steps), 2)
        self.assertTrue(plan.explain)

    def test_unknown_answer_mode_falls_back_to_data_only(self):
        plan = plan_from_dict({"answer_mode": "freestyle", "steps": [{"tool": "fhir_status"}]})
        self.assertEqual(plan.answer_mode, ANSWER_MODE_DATA_ONLY)

    def test_empty_steps_raise(self):
        with self.assertRaises(AgentLlmError):
            plan_from_dict({"steps": []})

    def test_duplicate_step_ids_are_made_unique(self):
        plan = plan_from_dict(
            {"steps": [{"id": "s", "tool": "fhir_status"}, {"id": "s", "tool": "fhir_status"}]}
        )
        self.assertNotEqual(plan.steps[0].id, plan.steps[1].id)

    def test_rule_fallback_builds_single_step(self):
        plan = from_intent_plan(
            IntentPlan(tool_name="get_conditions", patient_id="BN2026-00001", limit=7, explain=True)
        )
        self.assertEqual(plan.source, "rule_fallback")
        self.assertEqual(len(plan.steps), 1)
        self.assertEqual(plan.steps[0].tool, "get_conditions")
        self.assertEqual(plan.steps[0].args["patient_id"], "BN2026-00001")
        self.assertEqual(plan.answer_mode, ANSWER_MODE_EXPLAIN)


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
            make_plan(
                PlanStep(
                    id="s",
                    tool="get_conditions",
                    args={"patient_id": "BN2026-00001", "limit": 999, "sql": "DROP"},
                )
            )
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
            self._validate(
                make_plan(PlanStep(id="s", tool="get_conditions")),
                role="USER",
                allowed=[],
            )

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
        # Cờ agent_allow_user_resource_lookup mặc định false (quyết định §2.3d).
        with self.assertRaises(PolicyError):
            self._validate(
                make_plan(
                    PlanStep(
                        id="s",
                        tool="get_resource_by_id",
                        args={"resource_type": "Observation", "resource_id": "OBS-1"},
                    )
                ),
                role="USER",
                allowed=["BN2026-00001"],
            )

    def test_resource_lookup_requires_supported_type(self):
        with self.assertRaises(PlanError):
            self._validate(
                make_plan(
                    PlanStep(id="s", tool="get_resource_by_id", args={"resource_type": "AuditEvent", "resource_id": "x"})
                )
            )

    def test_resource_type_is_canonicalized(self):
        validated = self._validate(
            make_plan(
                PlanStep(id="s", tool="get_resource_by_id", args={"resource_type": "observation", "resource_id": "OBS-1"})
            )
        )
        self.assertEqual(validated.steps[0].args["resource_type"], "Observation")

    def test_dangling_step_reference_is_dropped(self):
        validated = self._validate(
            make_plan(
                PlanStep(id="meds", tool="get_medication_requests", args={"patient_id": "$ghost.patient_id"})
            ),
            message="thuốc của bệnh nhân",
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
        settings = get_settings()
        steps = [PlanStep(id=f"s{i}", tool="fhir_status") for i in range(settings.agent_max_plan_steps + 3)]
        validated = self._validate(make_plan(*steps))
        self.assertEqual(len(validated.steps), settings.agent_max_plan_steps)


# ------------------------------------------------------------------- executor


class FakeFhirClient:
    """Trả dữ liệu FHIR thô tối thiểu cho các normalizer thật."""

    def __init__(self, *, patients=None):
        self.patients = patients if patients is not None else [_patient("BN2026-00001")]
        self.calls: list[str] = []

    async def get_patient(self, patient_id):
        self.calls.append(f"get_patient:{patient_id}")
        return _patient(patient_id)

    async def search_patients_flexible(self, **kwargs):
        self.calls.append("search_patients")
        return {"entry": [{"resource": patient} for patient in self.patients]}

    async def search_patients(self, count=20):
        return {"entry": [{"resource": patient} for patient in self.patients]}

    async def search_patient_resources(self, resource_type, patient_id, count=10, sort=None):
        self.calls.append(f"{resource_type}:{patient_id}")
        if resource_type == "Condition":
            return {
                "entry": [
                    {
                        "resource": {
                            "resourceType": "Condition",
                            "id": "COND-1",
                            "code": {"text": "Đái tháo đường type 2"},
                            "subject": {"reference": f"Patient/{patient_id}"},
                        }
                    }
                ]
            }
        if resource_type == "MedicationRequest":
            return {
                "entry": [
                    {
                        "resource": {
                            "resourceType": "MedicationRequest",
                            "id": "MED-1",
                            "medicationCodeableConcept": {"text": "Metformin"},
                            "subject": {"reference": f"Patient/{patient_id}"},
                        }
                    }
                ]
            }
        return {"entry": []}


def _patient(patient_id):
    return {
        "resourceType": "Patient",
        "id": patient_id,
        "name": [{"family": "Nguyen", "given": ["Van A"]}],
        "gender": "male",
        "birthDate": "2003-01-01",
        "telecom": [{"system": "phone", "value": "0900000001"}],
    }


class PlanExecutorTests(unittest.IsolatedAsyncioTestCase):
    async def _execute(self, plan, *, client=None, role="DOCTOR", allowed=None, patient_id=None):
        return await execute_plan(
            plan,
            client=client or FakeFhirClient(),
            message="test",
            user_role=role,
            allowed_patient_ids=allowed or [],
            provided_patient_id=patient_id,
            plan_usage={"input_tokens": 5, "output_tokens": 1, "estimated_cost_usd": 0},
        )

    async def test_multi_step_merges_evidence(self):
        result = await self._execute(
            make_plan(
                PlanStep(id="c", tool="get_conditions", args={"patient_id": "BN2026-00001", "limit": 5}),
                PlanStep(id="m", tool="get_medication_requests", args={"patient_id": "BN2026-00001", "limit": 5}),
            )
        )
        self.assertEqual(result.response_status, "answered")
        self.assertEqual(len(result.payload["evidence"]), 2)
        self.assertEqual(result.plan.tool_name, "multi_tool_plan")
        self.assertEqual(result.resolved_patient_id, "BN2026-00001")

    async def test_single_step_keeps_template_answer(self):
        result = await self._execute(
            make_plan(PlanStep(id="c", tool="get_conditions", args={"patient_id": "BN2026-00001", "limit": 5}))
        )
        # Câu template của answerer cũ được giữ nguyên -> fast-path dùng lại được.
        self.assertIn("BN2026-00001", result.payload["answer"])
        self.assertEqual(result.plan.tool_name, "get_conditions")

    async def test_ambiguous_search_stops_for_selection(self):
        client = FakeFhirClient(patients=[_patient("BN2026-00001"), _patient("BN2026-00002")])
        result = await self._execute(
            make_plan(
                PlanStep(id="resolve_patient", tool="search_patients", args={"name": "Nguyen", "limit": 3}),
                PlanStep(id="m", tool="get_medication_requests", args={"patient_id": "$resolve_patient.patient_id"}),
            ),
            client=client,
        )
        self.assertEqual(result.response_status, "patient_selection_needed")
        self.assertTrue(result.payload["needs_patient_selection"])
        # Dừng trước khi chạm dữ liệu bệnh nhân nào.
        self.assertNotIn("MedicationRequest:BN2026-00001", client.calls)

    async def test_resolved_patient_flows_into_next_step(self):
        client = FakeFhirClient()
        result = await self._execute(
            make_plan(
                PlanStep(id="resolve_patient", tool="search_patients", args={"name": "Nguyen", "limit": 3}),
                PlanStep(id="m", tool="get_medication_requests", args={"patient_id": "$resolve_patient.patient_id"}),
            ),
            client=client,
        )
        self.assertEqual(result.response_status, "answered")
        self.assertIn("MedicationRequest:BN2026-00001", client.calls)

    async def test_missing_patient_asks_for_clarification(self):
        result = await self._execute(
            make_plan(PlanStep(id="m", tool="get_medication_requests", args={}))
        )
        self.assertEqual(result.response_status, "clarification_needed")
        self.assertEqual(result.payload["evidence"], [])

    async def test_user_cannot_reach_patient_resolved_at_runtime(self):
        # Validator không thấy được giá trị đến từ $resolve_patient; guard trong
        # executor là chốt chặn thứ hai.
        with self.assertRaises(PolicyError):
            await self._execute(
                make_plan(
                    PlanStep(id="resolve_patient", tool="search_patients", args={"name": "Nguyen"}),
                    PlanStep(id="m", tool="get_medication_requests", args={"patient_id": "$resolve_patient.patient_id"}),
                ),
                role="USER",
                allowed=["BN2026-00099"],
            )

    async def test_fhir_error_becomes_tool_error_payload(self):
        from fhir.client import FhirClientError

        class BrokenClient(FakeFhirClient):
            async def search_patient_resources(self, *args, **kwargs):
                raise FhirClientError("FHIR Server đang bận.", "boom")

        result = await self._execute(
            make_plan(PlanStep(id="c", tool="get_conditions", args={"patient_id": "BN2026-00001"})),
            client=BrokenClient(),
        )
        self.assertEqual(result.response_status, "tool_error")
        self.assertIn("FHIR Server đang bận.", result.payload["answer"])

    async def test_independent_steps_run_concurrently(self):
        order: list[str] = []

        class SlowClient(FakeFhirClient):
            async def search_patient_resources(self, resource_type, patient_id, count=10, sort=None):
                order.append(f"start:{resource_type}")
                await asyncio.sleep(0.02)
                order.append(f"end:{resource_type}")
                return await super().search_patient_resources(resource_type, patient_id, count, sort)

        await self._execute(
            make_plan(
                PlanStep(id="c", tool="get_conditions", args={"patient_id": "BN2026-00001"}),
                PlanStep(id="m", tool="get_medication_requests", args={"patient_id": "BN2026-00001"}),
            ),
            client=SlowClient(),
        )
        # Chạy song song: cả hai step bắt đầu trước khi step đầu kết thúc.
        self.assertEqual(order[0].startswith("start:"), True)
        self.assertEqual(order[1].startswith("start:"), True)


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
            # Giá trị đo là dữ liệu chính, không bao giờ bị bỏ.
            self.assertIn("value", item["data"])

    def test_oldest_records_dropped_and_one_always_kept(self):
        evidence = [self._item(f"OBS-{i}", f"2026-01-0{i}") for i in range(1, 5)]
        result, stats = apply_evidence_budget(evidence, max_chars=10)
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["id"], "OBS-4")  # mới nhất được giữ
        self.assertEqual(stats["dropped_items"], 3)

    def test_empty_input(self):
        result, stats = apply_evidence_budget([], max_chars=100)
        self.assertEqual(result, [])
        self.assertEqual(stats["chars_before"], 0)


# ---------------------------------------------------------------- plan cache


class PlanCacheRedactionTests(unittest.TestCase):
    def test_identifying_values_are_replaced(self):
        step = PlanStep(
            id="m",
            tool="get_medication_requests",
            args={"patient_id": "BN2026-00001", "limit": 10, "name": "Nguyễn Văn A"},
        )
        redacted = _redact_step(step)
        self.assertEqual(redacted["args"]["patient_id"], PATIENT_PLACEHOLDER)
        self.assertEqual(redacted["args"]["name"], PATIENT_PLACEHOLDER)
        # Tham số không định danh vẫn giữ giá trị.
        self.assertEqual(redacted["args"]["limit"], 10)

    def test_placeholder_is_stripped_when_loading(self):
        from langgraph_agent.plan_cache import _plan_from_payload

        plan = _plan_from_payload(
            {
                "steps": [{"id": "m", "tool": "get_medication_requests", "args": {"patient_id": PATIENT_PLACEHOLDER}}],
                "answer_mode": ANSWER_MODE_DATA_ONLY,
            }
        )
        self.assertIsNotNone(plan)
        self.assertNotIn("patient_id", plan.steps[0].args)
        self.assertEqual(plan.source, "plan_cache")


# --------------------------------------------------------------- graph (e2e)


class FakeAnswerGenerator:
    def __init__(self):
        self.calls = 0

    async def generate(self, **kwargs):
        self.calls += 1
        return AnswerResult(
            answer=f"LLM: {kwargs['fallback_answer']}",
            source="llm",
            usage={"input_tokens": 30, "output_tokens": 8, "estimated_cost_usd": 0},
            model="gpt-4o-mini",
        )


class FakeCacheService:
    def __init__(self, hit=None):
        self.hit = hit
        self.saved = []
        self.settings = get_settings()

    async def get_cached_answer(self, user_id, patient_id, question):
        return self.hit

    async def save_to_cache(self, **kwargs):
        self.saved.append(kwargs)


class FakeModelRouter:
    async def route(self, message, quota_used_ratio=0.0):
        from agents.model_router import QueryComplexity, RoutingDecision

        return RoutingDecision(model="gpt-4o-mini", complexity=QueryComplexity.SIMPLE, source="keyword")


class FakeSummaryGenerator:
    async def summarize(self, **kwargs):
        return SummaryResult()


class FakeIntentExtractor:
    def __init__(self, plan=None):
        self.plan = plan or IntentPlan(tool_name="get_conditions", patient_id="BN2026-00001")

    async def extract(self, message, provided_patient_id=None, conversation_context=None):
        return self.plan


class AgentGraphTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        reset_route_cache()
        self.answer_generator = FakeAnswerGenerator()
        self.cache = FakeCacheService()
        self.client = FakeFhirClient()
        # Plan cache đòi Qdrant; ở test luôn coi là miss và bỏ qua ghi.
        self.plan_cache_patch = mock.patch(
            "langgraph_agent.graph.get_plan_cache",
            return_value=mock.Mock(
                lookup=mock.AsyncMock(return_value=None),
                save=mock.AsyncMock(return_value=None),
            ),
        )
        self.plan_cache_patch.start()

    async def asyncTearDown(self):
        self.plan_cache_patch.stop()
        reset_route_cache()

    async def _run(self, request, *, llm_responses):
        from langgraph_agent import graph as graph_module

        calls: list[str] = []

        async def fake_call_llm(*, stage, **kwargs):
            calls.append(stage)
            if stage not in llm_responses:
                raise AgentLlmError(stage, "không có phản hồi giả lập")
            return mock.Mock(
                content=llm_responses[stage],
                model="gpt-4o-mini",
                usage={"input_tokens": 10, "output_tokens": 3, "estimated_cost_usd": 0},
            )

        with mock.patch("langgraph_agent.request_router.call_llm", fake_call_llm), \
             mock.patch("langgraph_agent.planner.call_llm", fake_call_llm), \
             mock.patch("langgraph_agent.simple_answers.call_llm", fake_call_llm):
            payload = await graph_module.run_agent(
                request,
                client=self.client,
                answer_generator=self.answer_generator,
                cache_service=self.cache,
                model_router=FakeModelRouter(),
                summary_generator=FakeSummaryGenerator(),
                intent_extractor=FakeIntentExtractor(),
            )
        return payload, calls

    async def test_fhir_route_end_to_end(self):
        payload, calls = await self._run(
            make_request(message="Bệnh nhân BN2026-00001 có chẩn đoán gì?"),
            llm_responses={
                "agent_router": '{"route": "fhir", "resource_hint": "Condition"}',
                "agent_planner": '{"answer_mode": "data_only", "steps": [{"id": "c", "tool": "get_conditions", "args": {"patient_id": "BN2026-00001"}}]}',
            },
        )
        self.assertEqual(payload["agent_route"], "fhir")
        self.assertEqual(payload["response_status"], "answered")
        self.assertEqual(payload["intent"], "conditions")
        self.assertTrue(payload["answer"].startswith("LLM: "))
        self.assertEqual(calls, ["agent_router", "agent_planner"])
        # stage_usage phải tách được từng chặng cho dashboard.
        self.assertIn("agent_router", payload["stage_usage"])
        self.assertIn("agent_planner", payload["stage_usage"])
        self.assertIn("agent_answer", payload["stage_usage"])

    async def test_cache_hit_costs_zero_llm_calls(self):
        self.cache.hit = ("Câu trả lời cũ", "conditions", {"input_tokens": 100, "output_tokens": 20}, "openai", "gpt-4o-mini")
        payload, calls = await self._run(make_request(), llm_responses={})
        self.assertEqual(calls, [])
        self.assertEqual(payload["answer"], "Câu trả lời cũ")
        self.assertEqual(payload["agent_route"], "cache")
        self.assertEqual(payload["usage"]["input_tokens"], 0)
        self.assertEqual(payload["saved_usage"]["saved_input_tokens"], 100)
        self.assertEqual(self.answer_generator.calls, 0)

    async def test_general_chat_route_skips_fhir(self):
        payload, calls = await self._run(
            make_request(message="Xin chào, bạn làm được gì?"),
            llm_responses={
                "agent_router": '{"route": "general_chat"}',
                "agent_chat": '{"answer": "Chào bạn, mình tra cứu hồ sơ y tế."}',
            },
        )
        self.assertEqual(payload["agent_route"], "general_chat")
        self.assertEqual(payload["answer"], "Chào bạn, mình tra cứu hồ sơ y tế.")
        self.assertEqual(payload["evidence"], [])
        self.assertEqual(self.client.calls, [])
        self.assertEqual(self.answer_generator.calls, 0)

    async def test_unsupported_route_falls_back_to_template_when_llm_fails(self):
        from langgraph_agent.simple_answers import UNSUPPORTED_FALLBACK

        payload, _ = await self._run(
            make_request(message="Tôi đau đầu, hãy chẩn đoán tôi bị bệnh gì"),
            llm_responses={
                "agent_router": '{"route": "unsupported", "safety_flag": "diagnosis_request"}',
            },
        )
        self.assertEqual(payload["answer"], UNSUPPORTED_FALLBACK)
        self.assertEqual(payload["safety_flag"], "diagnosis_request")

    async def test_planner_failure_falls_back_to_rule_extractor(self):
        payload, calls = await self._run(
            make_request(message="Bệnh nhân BN2026-00001 có chẩn đoán gì?"),
            llm_responses={"agent_router": '{"route": "fhir"}'},  # planner sẽ lỗi
        )
        # Không 503: rơi về rule-based extractor và vẫn trả lời được.
        self.assertEqual(payload["response_status"], "answered")
        self.assertEqual(payload["intent_source"], "langgraph_rule_fallback")
        self.assertEqual(calls, ["agent_router", "agent_planner"])

    async def test_router_failure_defaults_to_fhir(self):
        payload, _ = await self._run(
            make_request(message="Bệnh nhân BN2026-00001 có chẩn đoán gì?"),
            llm_responses={
                "agent_planner": '{"answer_mode": "data_only", "steps": [{"id": "c", "tool": "get_conditions", "args": {"patient_id": "BN2026-00001"}}]}',
            },
        )
        self.assertEqual(payload["agent_route"], "fhir")
        self.assertEqual(payload["response_status"], "answered")

    async def test_user_role_violation_returns_policy_error(self):
        with self.assertRaises(PolicyError):
            await self._run(
                make_request(
                    user_role="USER",
                    allowed_patient_ids=["BN2026-00001"],
                    message="Cho tôi danh sách tất cả bệnh nhân",
                ),
                llm_responses={
                    "agent_router": '{"route": "fhir"}',
                    "agent_planner": '{"answer_mode": "data_only", "steps": [{"id": "p", "tool": "search_patients", "args": {"name": "Nguyen"}}]}',
                },
            )

    async def test_budget_error_propagates(self):
        from agents.gateway_context import GatewayBudgetExceededError
        from langgraph_agent import graph as graph_module

        async def budget_call(**kwargs):
            raise GatewayBudgetExceededError("ExceededBudget")

        with mock.patch("langgraph_agent.request_router.call_llm", budget_call):
            with self.assertRaises(GatewayBudgetExceededError):
                await graph_module.run_agent(
                    make_request(),
                    client=self.client,
                    answer_generator=self.answer_generator,
                    cache_service=self.cache,
                    model_router=FakeModelRouter(),
                    summary_generator=FakeSummaryGenerator(),
                    intent_extractor=FakeIntentExtractor(),
                )


# ------------------------------------------------------------------ eval set


class EvalDatasetTests(unittest.TestCase):
    """Bộ eval là lưới chống regression khi sửa prompt, nên bản thân nó phải hợp lệ.

    Test này KHÔNG gọi mạng: chỉ kiểm nhãn có nhất quán với registry tool hiện tại.
    """

    def setUp(self):
        from tests.eval.run_eval import load_cases

        self.cases = load_cases()

    def test_ids_unique_and_non_empty(self):
        ids = [case["id"] for case in self.cases]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertGreaterEqual(len(ids), 40)

    def test_expected_tools_exist_in_registry(self):
        from fhir.tool_registry import TOOL_REGISTRY

        for case in self.cases:
            for tool in case.get("expected_tools", []):
                self.assertIn(tool, TOOL_REGISTRY, f"case {case['id']} gán tool lạ: {tool}")

    def test_routes_and_roles_are_valid(self):
        from langgraph_agent.request_router import SUPPORTED_META_KINDS, SUPPORTED_ROUTES

        for case in self.cases:
            self.assertIn(case["expected_route"], SUPPORTED_ROUTES, case["id"])
            self.assertIn(case.get("role", "DOCTOR"), {"USER", "DOCTOR", "ADMIN"}, case["id"])
            if "expected_meta_kind" in case:
                self.assertIn(case["expected_meta_kind"], SUPPORTED_META_KINDS, case["id"])

    def test_user_cases_declare_allowed_patients(self):
        # Nhãn cho role USER mà quên allowed_patient_ids sẽ luôn fail vì lý do sai.
        for case in self.cases:
            if case.get("role") == "USER" and case["expected_route"] == "fhir":
                self.assertTrue(case.get("allowed_patient_ids"), case["id"])

    def test_every_case_builds_a_valid_request(self):
        from tests.eval.run_eval import build_request

        for case in self.cases:
            ChatRequest(**build_request(case, "eval-user"))


if __name__ == "__main__":
    unittest.main()
