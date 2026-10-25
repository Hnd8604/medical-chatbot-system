"""Agent với bộ lọc mới: validator, rule planner, plan cache, executor theo lần khám, triage cấp cứu."""

import unittest
from datetime import date
from unittest import mock

import httpx

from agent.errors import PlanError
from agent.execution.executor import execute_plan
from agent.planning.plan import Plan, PlanStep
from agent.planning.plan_cache import (
    TIME_RANGE_PLACEHOLDER,
    VALUE_FILTER_PLACEHOLDER,
    _has_literal_encounter,
    _plan_from_payload,
    _redact_step,
    refill_identifiers,
)
from agent.planning.rule_planner import rule_plan
from agent.planning.validator import validate_plan
from agent.nodes.triage import EMERGENCY_ANSWER, SELF_HARM_ANSWER
from fhir.client import FhirClient
from tests.fakes import FakeFhirClient, encounter_resource, medication_resource
from tests.test_agent_graph import AgentGraphFixture, make_request


TODAY = date(2026, 10, 23)


def make_plan(*steps: PlanStep) -> Plan:
    return Plan(steps=tuple(steps))


def validate(plan: Plan, message: str, *, role: str = "DOCTOR") -> Plan:
    return validate_plan(
        plan,
        message=message,
        user_role=role,
        allowed_patient_ids=["BN1"] if role == "USER" else [],
        provided_patient_id="BN1",
        today=TODAY,
    )


class ValidatorFilterTests(unittest.TestCase):
    def test_time_range_filled_from_question_when_planner_omits_it(self):
        plan = validate(make_plan(PlanStep(id="o", tool="get_observations", args={})), "huyết áp 3 tháng qua")
        args = plan.steps[0].args
        self.assertEqual((args["date_from"], args["date_to"]), ("2026-07-23", "2026-10-23"))
        self.assertEqual(args["observation_type"], "blood_pressure")

    def test_rule_range_overrides_planner_date_arithmetic(self):
        plan = validate(
            make_plan(PlanStep(id="o", tool="get_observations", args={"date_from": "2026-08-01", "date_to": "2026-08-31"})),
            "đường huyết tháng trước",
        )
        self.assertEqual(plan.steps[0].args["date_from"], "2026-09-01")
        self.assertEqual(plan.steps[0].args["date_to"], "2026-09-30")

    def test_only_steps_the_planner_dated_get_the_range(self):
        plan = validate(
            make_plan(
                PlanStep(id="m", tool="get_medication_requests", args={}),
                PlanStep(id="o", tool="get_observations", args={"date_from": "2026-03-01", "date_to": "2026-03-31"}),
            ),
            "đang dùng thuốc gì và huyết áp tháng 3 thế nào",
        )
        self.assertNotIn("date_from", plan.steps[0].args)
        self.assertEqual(plan.steps[1].args["date_from"], "2026-03-01")

    def test_invalid_planner_dates_are_dropped(self):
        plan = validate(
            make_plan(PlanStep(id="o", tool="get_observations", args={"date_from": "tháng 3", "date_to": "2026-02-30"})),
            "huyết áp gần nhất",
        )
        self.assertNotIn("date_from", plan.steps[0].args)
        self.assertNotIn("date_to", plan.steps[0].args)

    def test_value_filter_inferred_and_normalized(self):
        plan = validate(
            make_plan(PlanStep(id="a", tool="get_all_patient_observations", args={"observation_type": "HbA1c"})),
            "Bệnh nhân nào có HbA1c > 7?",
        )
        self.assertEqual(plan.steps[0].args["observation_type"], "hba1c")
        self.assertEqual((plan.steps[0].args["value_op"], plan.steps[0].args["value"]), ("gt", 7.0))

        plan = validate(
            make_plan(
                PlanStep(
                    id="a", tool="get_all_patient_observations", args={"observation_type": "spo2", "value_op": "<", "value": "95"}
                )
            ),
            "SpO2 của các bệnh nhân",
        )
        self.assertEqual((plan.steps[0].args["value_op"], plan.steps[0].args["value"]), ("lt", 95.0))

    def test_threshold_without_observation_type_is_a_broken_plan(self):
        with self.assertRaises(PlanError):
            validate(make_plan(PlanStep(id="a", tool="get_all_patient_observations", args={})), "bệnh nhân nào có chỉ số > 7")

    def test_blood_pressure_component_inferred(self):
        plan = validate(
            make_plan(PlanStep(id="a", tool="get_all_patient_observations", args={"observation_type": "blood_pressure"})),
            "bệnh nhân nào có huyết áp tâm trương >= 90",
        )
        self.assertEqual(plan.steps[0].args["component"], "diastolic")

    def test_trend_requires_observation_type(self):
        plan = validate(make_plan(PlanStep(id="t", tool="get_observation_trend", args={})), "HbA1c có giảm không")
        self.assertEqual(plan.steps[0].args["observation_type"], "hba1c")
        with self.assertRaises(PlanError):
            validate(make_plan(PlanStep(id="t", tool="get_observation_trend", args={})), "xu hướng gần đây thế nào")

    def test_encounter_reference_must_point_to_get_encounters(self):
        plan = validate(
            make_plan(
                PlanStep(id="v", tool="get_encounters", args={"limit": 1}),
                PlanStep(id="m", tool="get_medication_requests", args={"encounter_id": "$v.encounter_id"}),
            ),
            "thuốc trong lần khám gần nhất",
        )
        self.assertEqual(plan.steps[1].args["encounter_id"], "$v.encounter_id")
        with self.assertRaises(PlanError):
            validate(
                make_plan(
                    PlanStep(id="c", tool="get_conditions", args={}),
                    PlanStep(id="m", tool="get_medication_requests", args={"encounter_id": "$c.encounter_id"}),
                ),
                "thuốc trong lần khám gần nhất",
            )

    def test_literal_encounter_id_is_normalized(self):
        plan = validate(
            make_plan(PlanStep(id="m", tool="get_medication_requests", args={"encounter_id": "Encounter/ENC-9"})),
            "thuốc của lần khám ENC-9",
        )
        self.assertEqual(plan.steps[0].args["encounter_id"], "ENC-9")


class RulePlannerFilterTests(unittest.TestCase):
    def _steps(self, message: str):
        plan = rule_plan(message, provided_patient_id="BN1")
        return [(step.tool, step.args) for step in plan.steps] if plan else None

    def test_trend_question(self):
        steps = self._steps("HbA1c của bệnh nhân này tăng hay giảm?")
        self.assertEqual(steps[0][0], "get_observation_trend")
        self.assertEqual(steps[0][1]["observation_type"], "hba1c")

    def test_cohort_threshold_question(self):
        steps = self._steps("Bệnh nhân nào có HbA1c > 7?")
        self.assertEqual(steps, [
            ("get_all_patient_observations", {"observation_type": "hba1c", "value_op": "gt", "value": 7.0, "limit": 20})
        ])

    def test_latest_visit_medications(self):
        steps = self._steps("Thuốc được kê trong lần khám gần nhất của bệnh nhân này")
        self.assertEqual([tool for tool, _ in steps], ["get_encounters", "get_medication_requests"])
        self.assertEqual(steps[1][1]["encounter_id"], "$last_visit.encounter_id")


class PlanCacheFilterTests(unittest.TestCase):
    def _cached(self, *steps: PlanStep) -> Plan:
        payload = {"route": "fhir", "steps": [_redact_step(step) for step in steps]}
        return _plan_from_payload(payload)

    def test_dates_and_thresholds_are_not_stored(self):
        redacted = _redact_step(
            PlanStep(id="a", tool="get_all_patient_observations", args={"observation_type": "hba1c", "value_op": "gt", "value": 7})
        )
        self.assertEqual(redacted["args"]["value"], VALUE_FILTER_PLACEHOLDER)
        redacted = _redact_step(PlanStep(id="o", tool="get_observations", args={"date_from": "2026-03-01"}))
        self.assertEqual(redacted["args"]["date_from"], TIME_RANGE_PLACEHOLDER)

    def test_threshold_refilled_from_current_question(self):
        cached = self._cached(
            PlanStep(id="a", tool="get_all_patient_observations", args={"observation_type": "hba1c", "value_op": "gt", "value": 7})
        )
        plan = refill_identifiers(cached, question="Bệnh nhân nào có HbA1c > 8?", conversation_context=None)
        self.assertEqual((plan.steps[0].args["value_op"], plan.steps[0].args["value"]), ("gt", 8.0))
        self.assertIsNone(refill_identifiers(cached, question="HbA1c của các bệnh nhân", conversation_context=None))

    def test_time_range_refilled_from_current_question(self):
        cached = self._cached(PlanStep(id="o", tool="get_observations", args={"observation_type": "hba1c", "date_from": "2026-03-01"}))
        with mock.patch("agent.planning.plan_cache.extract_time_range") as extract:
            extract.return_value = mock.Mock(as_args=lambda: {"date_from": "2026-04-01", "date_to": "2026-04-30"})
            plan = refill_identifiers(cached, question="HbA1c tháng 4", conversation_context=None)
        self.assertEqual(plan.steps[0].args["date_from"], "2026-04-01")
        self.assertIsNone(refill_identifiers(cached, question="HbA1c gần nhất", conversation_context=None))

    def test_different_observation_type_is_a_miss(self):
        cached = self._cached(PlanStep(id="o", tool="get_observations", args={"observation_type": "blood_pressure"}))
        self.assertIsNone(refill_identifiers(cached, question="đường huyết của bệnh nhân này", conversation_context=None))
        self.assertIsNotNone(refill_identifiers(cached, question="huyết áp của bệnh nhân này", conversation_context=None))

    def test_literal_encounter_plans_are_not_cached(self):
        self.assertTrue(_has_literal_encounter(PlanStep(id="m", tool="get_medication_requests", args={"encounter_id": "ENC-1"})))
        self.assertFalse(
            _has_literal_encounter(PlanStep(id="m", tool="get_medication_requests", args={"encounter_id": "$v.encounter_id"}))
        )


class RuleFallbackTests(unittest.TestCase):
    """Đường lùi khi không có LLM (gateway lỗi / low-cost mode): lỗi do kiểm tra offline phát hiện."""

    def _tools(self, message: str, patient: str | None = "BN1"):
        plan = rule_plan(message, provided_patient_id=patient)
        return [step.tool for step in plan.steps] if plan else None

    def test_first_person_is_not_a_patient_name(self):
        # Trước đây "của tôi thế nào" bị coi là tên ⇒ search_patients ⇒ USER bị 403.
        self.assertEqual(self._tools("Kết quả xét nghiệm của tôi thế nào?"), ["get_observations"])
        self.assertEqual(self._tools("Đơn thuốc của tôi có gì?"), ["get_medication_requests"])

    def test_patient_name_edge_cases(self):
        from nlp.patient_refs import extract_patient_search_criteria

        # Họ "Đỗ" bỏ dấu trùng "đó": trước đây "Bệnh nhân Đỗ Văn Hùng" bị hiểu là "bệnh nhân đó".
        self.assertEqual(extract_patient_search_criteria("Bệnh nhân Đỗ Văn Hùng là ai"), {"name": "Đỗ Văn Hùng"})
        self.assertEqual(extract_patient_search_criteria("Bệnh nhân đó dùng thuốc gì"), {})
        self.assertEqual(extract_patient_search_criteria("benh nhan Do Van Hung dung thuoc gi"), {"name": "Do Van Hung"})
        self.assertEqual(extract_patient_search_criteria("Tìm bệnh nhân tên Trần Thị Mai"), {"name": "Trần Thị Mai"})
        self.assertEqual(extract_patient_search_criteria("Bệnh nhân Nguyễn Văn An nhập viện khi nào"), {"name": "Nguyễn Văn An"})
        self.assertEqual(extract_patient_search_criteria("Thuốc của bệnh nhân Nguyễn Văn Dũng"), {"name": "Nguyễn Văn Dũng"})
        # Giới hạn cũ đã ghi trong docs: "nhập viện" bị coi là tên.
        self.assertEqual(extract_patient_search_criteria("bệnh nhân nhập viện lần nào"), {})

    def test_data_question_about_own_record(self):
        self.assertEqual(self._tools("Trong hồ sơ của tôi ghi nhận bệnh gì?"), ["get_conditions"])
        self.assertEqual(self._tools("Số điện thoại của tôi là gì?"), ["get_patient_by_id"])

    def test_multiple_data_types_in_one_question(self):
        self.assertEqual(
            self._tools("BN2026-00001 đang dùng thuốc gì và có chẩn đoán gì?"),
            ["get_medication_requests", "get_conditions"],
        )
        self.assertEqual(
            self._tools("Cho tôi xem chẩn đoán và các lần khám của bệnh nhân Nguyễn Văn A", patient=None),
            ["search_patients", "get_conditions", "get_encounters"],
        )

    def test_latest_visit_condition(self):
        plan = rule_plan("Chẩn đoán trong lần khám gần nhất của BN2026-00002")
        self.assertEqual([s.tool for s in plan.steps], ["get_encounters", "get_conditions"])
        self.assertEqual(plan.steps[1].args["encounter_id"], "$last_visit.encounter_id")

    def test_general_concept_questions(self):
        plan = rule_plan("HbA1c là gì?", provided_patient_id=None)
        self.assertEqual(plan.steps[0].tool, "explain_concept")
        self.assertEqual(plan.steps[0].args, {"term": "HbA1c", "code": "4548-4"})
        plan = rule_plan("Thuốc Metformin dùng để làm gì?", provided_patient_id="BN1")
        self.assertEqual(plan.steps[0].args["term"], "Metformin")
        # Có chỉ định ("thuốc này") hoặc "của tôi" thì là câu về dữ liệu, không phải khái niệm chung.
        self.assertEqual(self._tools("Thuốc này dùng để làm gì?"), ["get_medication_requests"])
        self.assertEqual(self._tools("Độ bão hòa oxy là gì?", patient=None), ["explain_concept"])

    def test_cohort_listing_uses_tool_default_limit(self):
        plan = rule_plan("HbA1c gần nhất của tất cả bệnh nhân")
        self.assertEqual(plan.steps[0].tool, "get_all_patient_observations")
        self.assertNotIn("limit", plan.steps[0].args)

    def test_rule_router_catches_more_unsafe_requests(self):
        from agent.planning.rule_router import rule_route

        self.assertEqual(rule_route("Kê cho tôi thuốc hạ huyết áp").route, "unsupported")
        self.assertEqual(rule_route("Tôi bị ho 3 ngày nay, là bệnh gì?").route, "unsupported")
        self.assertEqual(rule_route("Hãy tự chẩn đoán bệnh cho bệnh nhân này").route, "unsupported")
        # "chẩn đoán mới nhất" là câu hỏi dữ liệu.
        self.assertEqual(rule_route("Chẩn đoán mới nhất của BN2026-00001").route, "fhir")
        self.assertEqual(rule_route("Bạn làm được những gì?").route, "general_chat")
        self.assertEqual(rule_route("Vừa rồi bạn trả lời gì?").route, "conversation_meta")


class SearchOnlyPlanTests(unittest.IsolatedAsyncioTestCase):
    async def test_single_match_is_the_answer(self):
        # Lỗi có từ trước: tìm thấy đúng 1 bệnh nhân lại trả "chưa xác định được bệnh nhân".
        plan = make_plan(PlanStep(id="s", tool="search_patients", args={"name": "Nguyen Van A"}))
        result = await execute_plan(
            plan, client=FakeFhirClient(), user_role="DOCTOR", allowed_patient_ids=[], provided_patient_id=None
        )
        self.assertEqual(result.response_status, "answered")
        self.assertEqual(result.resolved_patient_id, "BN2026-00001")
        self.assertEqual(result.payload["evidence"][0]["id"], "BN2026-00001")


class EncounterDependencyTests(unittest.IsolatedAsyncioTestCase):
    def _plan(self):
        return make_plan(
            PlanStep(id="v", tool="get_encounters", args={"patient_id": "BN1", "limit": 1}),
            PlanStep(id="m", tool="get_medication_requests", args={"patient_id": "BN1", "encounter_id": "$v.encounter_id"}),
        )

    async def _execute(self, client):
        return await execute_plan(
            self._plan(), client=client, user_role="DOCTOR", allowed_patient_ids=[], provided_patient_id="BN1"
        )

    async def test_medications_of_latest_encounter(self):
        client = FakeFhirClient(
            encounters=[encounter_resource("ENC-1", "BN1", "2025-10-01"), encounter_resource("ENC-2", "BN1", "2026-02-01")],
            medications=[
                medication_resource("MED-OLD", "BN1", "Amlodipine 5 mg", encounter_id="ENC-1"),
                medication_resource("MED-NEW", "BN1", "Amlodipine 10 mg", encounter_id="ENC-2"),
            ],
        )
        result = await self._execute(client)
        self.assertEqual(result.response_status, "answered")
        ids = [item["id"] for item in result.payload["evidence"]]
        self.assertEqual(ids, ["ENC-2", "MED-NEW"])
        self.assertEqual(result.executed_steps[1]["args"]["encounter_id"], "ENC-2")
        # Bước sau chạy SAU khi có kết quả bước trước.
        self.assertEqual([call.split(":")[0] for call in client.calls], ["Encounter", "MedicationRequest"])

    async def test_dependent_step_skipped_when_no_encounter(self):
        client = FakeFhirClient(encounters=[], medications=[medication_resource("MED-1", "BN1", "Metformin")])
        result = await self._execute(client)
        self.assertEqual([step["tool"] for step in result.executed_steps], ["get_encounters"])
        self.assertEqual(result.failed_steps, [])
        self.assertEqual(result.payload["evidence"], [])


class EmergencyGraphTests(AgentGraphFixture):
    async def test_router_failure_falls_back_to_rule_router(self):
        # Gateway lỗi: câu xin kê đơn vẫn bị chặn thay vì mặc định đi nhánh FHIR.
        payload, calls = await self._run(make_request(message="Kê cho tôi thuốc hạ huyết áp"), llm_responses={})
        self.assertEqual(calls[0], "agent_router")
        self.assertEqual(payload["agent_route"], "unsupported")

    async def test_emergency_answered_before_cache_and_llm(self):
        payload, calls = await self._run(make_request(message="Tôi đang đau ngực và khó thở quá"), llm_responses={})
        self.assertEqual(calls, [])
        self.assertEqual(self.cache.lookups, [])  # chạy trước cả semantic cache
        self.assertEqual(payload["agent_route"], "emergency")
        self.assertEqual(payload["answer"], EMERGENCY_ANSWER)
        self.assertIn("115", payload["answer"])
        self.assertEqual(payload["response_status"], "answered")
        self.assertEqual(payload["usage"]["input_tokens"], 0)
        self.assertEqual(self.cache.saved, [])

    async def test_self_harm_answer(self):
        payload, _ = await self._run(make_request(message="Tôi muốn tự tử"), llm_responses={})
        self.assertEqual(payload["answer"], SELF_HARM_ANSWER)
        self.assertEqual(payload["answer_source"], "template_emergency_self_harm")

    async def test_record_question_with_symptom_still_goes_to_fhir(self):
        payload, _ = await self._run(
            make_request(message="BN2026-00001 có chẩn đoán gì liên quan khó thở?"),
            llm_responses={},
        )
        self.assertNotEqual(payload["agent_route"], "emergency")


class FhirClientPagingTests(unittest.IsolatedAsyncioTestCase):
    async def test_search_resources_follows_next_links_through_base_url(self):
        seen: list[str] = []

        def handler(request: httpx.Request) -> httpx.Response:
            seen.append(str(request.url))
            if "_getpages" not in request.url.params:
                return httpx.Response(
                    200,
                    json={
                        "resourceType": "Bundle",
                        "total": 3,
                        "entry": [{"resource": {"id": "1"}, "search": {"mode": "match"}}] * 2,
                        "link": [{"relation": "next", "url": "http://hapi-internal:8080/fhir?_getpages=abc&_getpagesoffset=2"}],
                    },
                )
            return httpx.Response(200, json={"resourceType": "Bundle", "entry": [{"resource": {"id": "3"}}]})

        client = FhirClient(base_url="http://fhir.test/fhir", transport=httpx.MockTransport(handler))
        bundle = await client.search_resources("Observation", {"code": "http://loinc.org|4548-4"})

        self.assertEqual(len(bundle["entry"]), 3)
        self.assertEqual(bundle["total"], 3)
        self.assertFalse(bundle["truncated"])
        # Trang sau đi qua base_url đã cấu hình, không theo host nội bộ trong link.
        self.assertTrue(seen[1].startswith("http://fhir.test/fhir?"))

    async def test_search_patient_resources_merges_filter_params(self):
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.url.params.get_list("date"), ["ge2026-01-01", "le2026-03-31"])
            self.assertEqual(request.url.params["code"], "http://loinc.org|4548-4")
            self.assertEqual(request.url.params["patient"], "Patient/BN1")
            return httpx.Response(200, json={"resourceType": "Bundle", "entry": []})

        client = FhirClient(base_url="http://fhir.test", transport=httpx.MockTransport(handler))
        await client.search_patient_resources(
            "Observation",
            "BN1",
            count=5,
            params={"date": ["ge2026-01-01", "le2026-03-31"], "code": "http://loinc.org|4548-4"},
        )


if __name__ == "__main__":
    unittest.main()
