import unittest
from fastapi import HTTPException

from api.chat_routes import (
    _detect_intent,
    _apply_selected_patient_context,
    _apply_context_reference_context,
    _answer_context_resource_if_applicable,
    _finalize_chat_response,
    _observation_matches_type,
    _resolve_patient_id_for_tool,
    _resolve_patient_id,
    chat,
    ChatRequest,
)
from agents.answer_generator import AnswerResult
from agents.intent_extractor import (
    IntentPlan,
    TOOL_GET_CONDITIONS,
    TOOL_GET_ENCOUNTERS,
    TOOL_GET_MEDICATIONS,
    TOOL_GET_OBSERVATIONS,
    TOOL_GET_PATIENT,
    TOOL_SEARCH_PATIENTS,
    TOOL_UNSUPPORTED,
    RuleBasedIntentExtractor,
    apply_all_patient_scope,
    add_observation_type_hint,
    apply_patient_id_hint,
    apply_patient_search_criteria_hint,
    enforce_patient_list_routing,
    enforce_contact_detail_routing,
    plan_from_tool_call,
)


class FakeAnswerGenerator:
    async def generate(self, **kwargs):
        return AnswerResult(
            answer=f"LLM: {kwargs['fallback_answer']}",
            source="llm",
            usage={"input_tokens": 20, "output_tokens": 5, "estimated_cost_usd": 0.01},
        )


class FakePatientSearchClient:
    async def search_patients_flexible(self, **kwargs):
        return {
            "entry": [
                {
                    "resource": {
                        "resourceType": "Patient",
                        "id": "demo-patient-001",
                        "name": [{"family": "Nguyen", "given": ["Van A"]}],
                        "gender": "male",
                        "birthDate": "2003-01-01",
                        "telecom": [{"system": "phone", "value": "0900000001"}],
                    }
                },
                {
                    "resource": {
                        "resourceType": "Patient",
                        "id": "demo-patient-006",
                        "name": [{"family": "Nguyen", "given": ["Van B"]}],
                        "gender": "male",
                        "birthDate": "2004-02-02",
                        "telecom": [{"system": "phone", "value": "0900000006"}],
                    }
                },
            ]
        }


class FakeResourceClient:
    async def get_resource(self, resource_type: str, resource_id: str):
        return {
            "resourceType": resource_type,
            "id": resource_id,
            "status": "final",
            "code": {"text": "Blood pressure"},
            "subject": {"reference": "Patient/demo-patient-001"},
            "component": [
                {
                    "code": {"text": "Systolic blood pressure"},
                    "valueQuantity": {"value": 150, "unit": "mmHg"},
                },
                {
                    "code": {"text": "Diastolic blood pressure"},
                    "valueQuantity": {"value": 92, "unit": "mmHg"},
                },
            ],
        }


class FakeIntentExtractor:
    def __init__(self, plan: IntentPlan):
        self.plan = plan

    async def extract(self, message: str, provided_patient_id: str | None = None) -> IntentPlan:
        return self.plan


class FakeCacheService:
    async def get_cached_answer(self, **kwargs):
        return None


def make_request(**overrides) -> ChatRequest:
    payload = {
        "user_id": "user-001",
        "user_role": "DOCTOR",
        "message": "test message",
    }
    payload.update(overrides)
    return ChatRequest(**payload)


class ChatRoutesTests(unittest.TestCase):
    def test_detects_medication_intent(self) -> None:
        self.assertEqual(_detect_intent("Patient/demo-patient-001 has what medications?"), "medications")

    def test_detects_vietnamese_observation_intent_without_accents(self) -> None:
        self.assertEqual(_detect_intent("huyet ap cua demo-patient-001"), "observations")

    def test_detects_vietnamese_phone_as_patient_intent(self) -> None:
        self.assertEqual(_detect_intent("so dien thoai cua benh nhan 001"), "patient")

    def test_detects_patient_list_intent(self) -> None:
        self.assertEqual(_detect_intent("danh sach benh nhan hien co"), "patients")

    def test_detects_patient_search_by_name_intent(self) -> None:
        self.assertEqual(_detect_intent("tim benh nhan Nguyen Van A"), "patients")

    def test_detects_vietnamese_encounter_intent(self) -> None:
        self.assertEqual(_detect_intent("lich su kham cua benh nhan 001"), "encounters")

    def test_resolves_patient_reference(self) -> None:
        request = make_request(message="Show medications for Patient/demo-patient-001")

        self.assertEqual(_resolve_patient_id(request), "demo-patient-001")

    def test_resolves_numbered_demo_patient(self) -> None:
        request = make_request(message="so dien thoai cua benh nhan 001")

        self.assertEqual(_resolve_patient_id(request), "demo-patient-001")

    def test_resolves_message_patient_before_request_patient(self) -> None:
        request = ChatRequest(
            user_id="user-001",
            user_role="DOCTOR",
            message="so dien thoai cua benh nhan 004",
            patient_id="demo-patient-001",
        )

        self.assertEqual(_resolve_patient_id(request), "demo-patient-004")

    def test_matches_vietnamese_blood_pressure_observation_type(self) -> None:
        observation = {
            "code": "Blood pressure",
            "components": [
                {"code": "Systolic blood pressure"},
                {"code": "Diastolic blood pressure"},
            ],
        }

        self.assertTrue(_observation_matches_type(observation, "huyết áp"))

    def test_matches_vietnamese_heart_rate_observation_type(self) -> None:
        observation = {
            "code": "Heart rate",
            "components": [],
        }

        self.assertTrue(_observation_matches_type(observation, "nhịp tim"))

    def test_no_longer_defaults_demo_patient(self) -> None:
        request = make_request(message="Show medications")

        self.assertIsNone(_resolve_patient_id(request))

    def test_context_active_patient_is_used_as_patient_hint(self) -> None:
        request = ChatRequest(
            user_id="user-001",
            user_role="DOCTOR",
            message="benh nhan do dang dung thuoc gi?",
            conversation_context={"active_patient_id": "demo-patient-004"},
        )

        self.assertEqual(_resolve_patient_id(request), "demo-patient-004")

    def test_selected_patient_context_clears_search_criteria_for_resource_tool(self) -> None:
        request = make_request(message="thuoc cua benh nhan Nguyen", patient_id="demo-patient-006")
        plan = IntentPlan(
            tool_name=TOOL_GET_MEDICATIONS,
            patient_id="demo-patient-006",
            search_name="Nguyen",
        )

        result = _apply_selected_patient_context(request, plan)

        self.assertEqual(result.tool_name, TOOL_GET_MEDICATIONS)
        self.assertEqual(result.patient_id, "demo-patient-006")
        self.assertIsNone(result.search_name)

    def test_context_reference_does_not_override_explicit_resource_intent(self) -> None:
        request = ChatRequest(
            user_id="user-001",
            user_role="DOCTOR",
            message="benh nhan do dang dung thuoc gi?",
            conversation_context={
                "active_patient_id": "demo-patient-001",
                "last_resource_type": "Observation",
                "last_resource_id": "obs-1",
            },
        )
        plan = IntentPlan(tool_name=TOOL_GET_MEDICATIONS, patient_id="demo-patient-001")

        result = _apply_context_reference_context(request, plan)

        self.assertEqual(result.tool_name, TOOL_GET_MEDICATIONS)

    def test_context_reference_routes_unsupported_to_last_resource_tool(self) -> None:
        request = ChatRequest(
            user_id="user-001",
            user_role="DOCTOR",
            message="chi so do co cao khong?",
            conversation_context={
                "active_patient_id": "demo-patient-001",
                "last_resource_type": "Observation",
                "last_resource_id": "obs-1",
            },
        )
        plan = IntentPlan(tool_name=TOOL_UNSUPPORTED, patient_id="demo-patient-001")

        result = _apply_context_reference_context(request, plan)

        self.assertEqual(result.tool_name, TOOL_GET_OBSERVATIONS)
        self.assertEqual(result.source, "rules_context_reference")

    def test_selected_patient_context_turns_contact_search_into_patient_lookup(self) -> None:
        request = make_request(message="so dien thoai cua Nguyen", patient_id="demo-patient-006")
        plan = IntentPlan(
            tool_name=TOOL_SEARCH_PATIENTS,
            patient_id="demo-patient-006",
            search_name="Nguyen",
        )

        result = _apply_selected_patient_context(request, plan)

        self.assertEqual(result.tool_name, TOOL_GET_PATIENT)
        self.assertEqual(result.patient_id, "demo-patient-006")
        self.assertIsNone(result.search_name)


class IntentExtractorTests(unittest.IsolatedAsyncioTestCase):
    async def test_user_role_is_blocked_before_fhir_access(self) -> None:
        request = make_request(user_role="USER", message="tim benh nhan Nguyen")
        plan = IntentPlan(
            tool_name=TOOL_SEARCH_PATIENTS,
            search_name="Nguyen",
            source="rules",
        )

        with self.assertRaises(HTTPException) as ctx:
            await chat(
                request=request,
                client=FakePatientSearchClient(),
                intent_extractor=FakeIntentExtractor(plan),
                answer_generator=FakeAnswerGenerator(),
                cache_service=FakeCacheService(),
            )

        self.assertEqual(ctx.exception.status_code, 403)

    async def test_doctor_role_can_access_fhir_chat_flow(self) -> None:
        request = make_request(user_role="DOCTOR", message="tim benh nhan Nguyen")
        plan = IntentPlan(
            tool_name=TOOL_SEARCH_PATIENTS,
            search_name="Nguyen",
            source="rules",
        )

        payload = await chat(
            request=request,
            client=FakePatientSearchClient(),
            intent_extractor=FakeIntentExtractor(plan),
            answer_generator=FakeAnswerGenerator(),
            cache_service=FakeCacheService(),
        )

        self.assertTrue(payload["needs_patient_selection"])
        self.assertEqual(payload["answer_source"], "template_patient_selection")

    async def test_ambiguous_patient_resolution_returns_candidates_without_llm(self) -> None:
        plan = IntentPlan(
            tool_name=TOOL_GET_MEDICATIONS,
            patient_id="demo-patient-001",
            search_name="Nguyen",
            source="rules",
        )

        payload = await _resolve_patient_id_for_tool(FakePatientSearchClient(), plan)
        result = await _finalize_chat_response(
            payload,
            "thuoc cua benh nhan Nguyen",
            plan,
            FakeAnswerGenerator(),
        )

        self.assertTrue(result["needs_patient_selection"])
        self.assertEqual(len(result["patient_candidates"]), 2)
        self.assertEqual(result["patient_candidates"][0]["id"], "demo-patient-001")
        self.assertEqual(result["answer_source"], "template_patient_selection")
        self.assertEqual(result["pending_question"], "thuoc cua benh nhan Nguyen")

    async def test_missing_patient_context_returns_clarification_instead_of_demo_patient(self) -> None:
        plan = IntentPlan(
            tool_name=TOOL_GET_MEDICATIONS,
            patient_id=None,
            source="rules",
        )

        payload = await _resolve_patient_id_for_tool(FakePatientSearchClient(), plan)

        self.assertIsInstance(payload, dict)
        self.assertIsNone(payload["patient_id"])
        self.assertIn("xác định được bệnh nhân cụ thể", payload["answer"])

    async def test_finalize_chat_response_adds_llm_answer_metadata_and_combined_usage(self) -> None:
        payload = {
            "answer": "Template answer",
            "intent": "observations",
            "patient_id": "demo-patient-003",
            "evidence": [{"resource_type": "Observation", "id": "obs-1", "summary": "HbA1c"}],
            "usage": {"input_tokens": 0, "output_tokens": 0, "estimated_cost_usd": 0},
        }
        plan = IntentPlan(
            tool_name=TOOL_GET_OBSERVATIONS,
            patient_id="demo-patient-003",
            source="llm",
            usage={"input_tokens": 10, "output_tokens": 3, "estimated_cost_usd": 0},
        )

        result = await _finalize_chat_response(
            payload,
            "hba1c cua benh nhan 003",
            plan,
            FakeAnswerGenerator(),
        )

        self.assertEqual(result["answer"], "LLM: Template answer")
        self.assertEqual(result["answer_source"], "llm")
        self.assertEqual(result["answer_usage"]["input_tokens"], 20)
        self.assertEqual(result["usage"]["input_tokens"], 30)
        self.assertEqual(result["tool_name"], TOOL_GET_OBSERVATIONS)
        self.assertEqual(result["memory_update"]["active_patient_id"], "demo-patient-003")
        self.assertEqual(result["memory_update"]["last_resource_type"], "Observation")
        self.assertEqual(result["memory_update"]["last_resource_id"], "obs-1")

    async def test_context_resource_reference_fetches_last_observation(self) -> None:
        request = ChatRequest(
            user_id="user-001",
            user_role="DOCTOR",
            message="chi so do co cao khong?",
            conversation_context={
                "active_patient_id": "demo-patient-001",
                "last_resource_type": "Observation",
                "last_resource_id": "obs-1",
            },
        )
        plan = IntentPlan(
            tool_name=TOOL_GET_OBSERVATIONS,
            patient_id="demo-patient-001",
            source="rules_context_reference",
        )

        payload = await _answer_context_resource_if_applicable(FakeResourceClient(), request, plan)

        self.assertIsNotNone(payload)
        self.assertEqual(payload["patient_id"], "demo-patient-001")
        self.assertEqual(payload["evidence"][0]["resource_type"], "Observation")
        self.assertEqual(payload["evidence"][0]["id"], "obs-1")

    async def test_rule_based_extractor_returns_fhir_tool_plan(self) -> None:
        plan = await RuleBasedIntentExtractor().extract(
            "What medications is Patient/demo-patient-001 taking?"
        )

        self.assertEqual(plan.tool_name, TOOL_GET_MEDICATIONS)
        self.assertEqual(plan.intent, "medications")
        self.assertEqual(plan.patient_id, "demo-patient-001")
        self.assertEqual(plan.source, "rules")

    async def test_rule_based_extractor_routes_phone_to_patient_tool(self) -> None:
        plan = await RuleBasedIntentExtractor().extract("so dien thoai cua benh nhan 001")

        self.assertEqual(plan.tool_name, TOOL_GET_PATIENT)
        self.assertEqual(plan.intent, "patient")
        self.assertEqual(plan.patient_id, "demo-patient-001")

    async def test_rule_based_extractor_routes_patient_list(self) -> None:
        plan = await RuleBasedIntentExtractor().extract("danh sach benh nhan hien co")

        self.assertEqual(plan.tool_name, TOOL_SEARCH_PATIENTS)
        self.assertEqual(plan.intent, "patients")
        self.assertEqual(plan.limit, 20)

    async def test_rule_based_extractor_routes_patient_name_search(self) -> None:
        plan = await RuleBasedIntentExtractor().extract("tim benh nhan Nguyen Van A")

        self.assertEqual(plan.tool_name, TOOL_SEARCH_PATIENTS)
        self.assertEqual(plan.search_name, "Nguyen Van A")

    async def test_rule_based_extractor_keeps_search_criteria_for_named_medication_question(self) -> None:
        plan = await RuleBasedIntentExtractor().extract("thuoc cua benh nhan Thi B Tran")

        self.assertEqual(plan.tool_name, TOOL_GET_MEDICATIONS)
        self.assertEqual(plan.search_name, "Thi B Tran")

    async def test_rule_based_extractor_extracts_phone_and_birth_date(self) -> None:
        plan = await RuleBasedIntentExtractor().extract("tim benh nhan sdt 0900 000 001 sinh ngay 01/01/2003")

        self.assertEqual(plan.tool_name, TOOL_SEARCH_PATIENTS)
        self.assertEqual(plan.search_phone, "0900000001")
        self.assertEqual(plan.search_birth_date, "2003-01-01")

    async def test_rule_based_extractor_routes_all_patient_medications(self) -> None:
        plan = await RuleBasedIntentExtractor().extract("tat ca benh nhan dang dung thuoc gi")

        self.assertEqual(plan.tool_name, TOOL_GET_MEDICATIONS)
        self.assertTrue(plan.all_patients)

    async def test_rule_based_extractor_adds_vietnamese_observation_type(self) -> None:
        plan = await RuleBasedIntentExtractor().extract("huyet ap cua benh nhan 001")

        self.assertEqual(plan.tool_name, TOOL_GET_OBSERVATIONS)
        self.assertEqual(plan.observation_type, "blood_pressure")

    async def test_rule_based_extractor_adds_vietnamese_heart_rate_type(self) -> None:
        plan = await RuleBasedIntentExtractor().extract("nhip tim cua benh nhan 001")

        self.assertEqual(plan.tool_name, TOOL_GET_OBSERVATIONS)
        self.assertEqual(plan.observation_type, "heart_rate")

    async def test_rule_based_extractor_routes_vietnamese_encounters(self) -> None:
        plan = await RuleBasedIntentExtractor().extract("benh nhan 005 kham gan nhat khi nao")

        self.assertEqual(plan.tool_name, TOOL_GET_ENCOUNTERS)
        self.assertEqual(plan.intent, "encounters")
        self.assertEqual(plan.patient_id, "demo-patient-005")

    async def test_rule_based_extractor_routes_all_patient_encounters(self) -> None:
        plan = await RuleBasedIntentExtractor().extract("lich su kham cua tat ca benh nhan")

        self.assertEqual(plan.tool_name, TOOL_GET_ENCOUNTERS)
        self.assertTrue(plan.all_patients)

    def test_guardrail_routes_phone_to_patient_tool(self) -> None:
        plan = IntentPlan(
            tool_name=TOOL_GET_CONDITIONS,
            patient_id="demo-patient-001",
            source="llm",
            usage={"input_tokens": 10, "output_tokens": 4, "estimated_cost_usd": 0},
        )

        routed = enforce_contact_detail_routing("so dien thoai cua benh nhan 001", plan)

        self.assertEqual(routed.tool_name, TOOL_GET_PATIENT)
        self.assertEqual(routed.intent, "patient")
        self.assertEqual(routed.source, "llm_guardrail")

    def test_guardrail_routes_patient_list_tool(self) -> None:
        plan = IntentPlan(tool_name=TOOL_GET_PATIENT, patient_id="demo-patient-001", source="llm")

        routed = enforce_patient_list_routing("liet ke tat ca benh nhan", plan)

        self.assertEqual(routed.tool_name, TOOL_SEARCH_PATIENTS)
        self.assertEqual(routed.intent, "patients")
        self.assertEqual(routed.source, "llm_guardrail")

    def test_all_patient_scope_changes_patient_list_to_medications(self) -> None:
        plan = IntentPlan(tool_name=TOOL_SEARCH_PATIENTS, patient_id="demo-patient-001", source="llm")

        routed = apply_all_patient_scope("tat ca benh nhan dang dung thuoc gi", plan)

        self.assertEqual(routed.tool_name, TOOL_GET_MEDICATIONS)
        self.assertTrue(routed.all_patients)

    def test_observation_type_hint_handles_vietnamese_blood_pressure(self) -> None:
        plan = IntentPlan(tool_name=TOOL_GET_OBSERVATIONS, patient_id="demo-patient-001", source="llm")

        routed = add_observation_type_hint("huyet ap cua benh nhan 001", plan)

        self.assertEqual(routed.observation_type, "blood_pressure")

    def test_patient_id_hint_overrides_llm_default_when_message_has_number(self) -> None:
        plan = IntentPlan(tool_name=TOOL_GET_PATIENT, patient_id="demo-patient-001", source="llm")

        routed = apply_patient_id_hint("so dien thoai cua benh nhan 004", None, plan)

        self.assertEqual(routed.patient_id, "demo-patient-004")

    def test_patient_id_hint_prefers_message_over_provided_field(self) -> None:
        plan = IntentPlan(tool_name=TOOL_GET_PATIENT, patient_id="demo-patient-001", source="llm")

        routed = apply_patient_id_hint("so dien thoai cua benh nhan 004", "demo-patient-001", plan)

        self.assertEqual(routed.patient_id, "demo-patient-004")

    def test_patient_search_criteria_hint_adds_name_to_llm_plan(self) -> None:
        plan = IntentPlan(tool_name=TOOL_GET_MEDICATIONS, patient_id="demo-patient-001", source="llm")

        routed = apply_patient_search_criteria_hint("thuoc cua benh nhan Thi B Tran", plan)

        self.assertEqual(routed.tool_name, TOOL_GET_MEDICATIONS)
        self.assertEqual(routed.search_name, "Thi B Tran")
        self.assertEqual(routed.source, "llm_guardrail")

    def test_plan_from_tool_call_prefers_provided_patient_id(self) -> None:
        plan = plan_from_tool_call(
            tool_name=TOOL_GET_OBSERVATIONS,
            arguments={
                "patient_id": "demo-patient-from-llm",
                "observation_type": "glucose",
                "limit": 100,
            },
            provided_patient_id="demo-patient-001",
            usage={"input_tokens": 10, "output_tokens": 5, "estimated_cost_usd": 0},
            source="llm",
        )

        self.assertEqual(plan.tool_name, TOOL_GET_OBSERVATIONS)
        self.assertEqual(plan.patient_id, "demo-patient-001")
        self.assertEqual(plan.observation_type, "glucose")
        self.assertEqual(plan.limit, 20)
        self.assertEqual(plan.source, "llm")


if __name__ == "__main__":
    unittest.main()
