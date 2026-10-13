"""Test end-to-end cho agent graph và route ``POST /chat``.

LLM được giả lập ở từng stage; FHIR là FakeFhirClient; plan cache luôn miss (cần Qdrant).
"""

import unittest
from unittest import mock

from fastapi.testclient import TestClient

from agent.answer.generator import AnswerResult
from agent.errors import PolicyError
from agent.graph import run_agent
from agent.memory.summary import SummaryResult
from agent.nodes.simple import UNRECOGNIZED_QUESTION_ANSWER, UNSUPPORTED_FALLBACK
from agent.planning.router import reset_route_cache
from api.schemas import ChatRequest, ChatResponse
from app.config import get_settings
from fhir.client import FhirClientError
from llm.client import LlmCallError, start_call_log
from tests.fakes import FakeFhirClient


def make_request(**overrides) -> ChatRequest:
    payload = {
        "user_id": "u1",
        "user_role": "DOCTOR",
        "message": "Bệnh nhân BN2026-00001 đang dùng thuốc gì?",
        "allowed_patient_ids": [],
    }
    payload.update(overrides)
    return ChatRequest(**payload)


class FakeAnswerGenerator:
    def __init__(self):
        self.calls = 0

    async def generate(self, **kwargs):
        self.calls += 1
        return AnswerResult(
            answer=f"LLM: {kwargs['fallback_answer']}",
            source="llm",
            usage={"input_tokens": 30, "output_tokens": 8, "cost_usd": 0.0},
            model="gpt-4o-mini",
        )


class FakeCacheService:
    def __init__(self, hit=None):
        self.hit = hit
        self.saved = []
        self.settings = get_settings()
        self.lookups = []

    async def get_cached_answer(self, user_id, patient_id, question):
        self.lookups.append(patient_id)
        return self.hit

    async def save_to_cache(self, **kwargs):
        self.saved.append(kwargs)


class FakeSummaryGenerator:
    async def summarize(self, **kwargs):
        return SummaryResult()


PLANNER_CONDITIONS = (
    '{"answer_mode": "data_only", "steps": [{"id": "c", "tool": "get_conditions", '
    '"args": {"patient_id": "BN2026-00001"}}]}'
)


class AgentGraphTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        reset_route_cache()
        self.answer_generator = FakeAnswerGenerator()
        self.cache = FakeCacheService()
        self.client = FakeFhirClient()
        self.plan_cache = mock.Mock(
            lookup=mock.AsyncMock(return_value=None),
            save=mock.AsyncMock(return_value=None),
        )
        patchers = [
            mock.patch("agent.nodes.route.get_plan_cache", return_value=self.plan_cache),
            mock.patch("agent.nodes.execute.get_plan_cache", return_value=self.plan_cache),
        ]
        for patcher in patchers:
            patcher.start()
            self.addAsyncCleanup(patcher.stop) if hasattr(self, "addAsyncCleanup") else self.addCleanup(patcher.stop)

    async def asyncTearDown(self):
        reset_route_cache()

    async def _run(self, request, *, llm_responses):
        calls: list[str] = []

        async def fake_call_llm(*, stage, **kwargs):
            calls.append(stage)
            if stage not in llm_responses:
                raise LlmCallError(stage, "không có phản hồi giả lập")
            return mock.Mock(
                content=llm_responses[stage],
                model="gpt-4o-mini",
                usage={"input_tokens": 10, "output_tokens": 3, "cost_usd": 0.0},
            )

        with mock.patch("agent.planning.router.call_llm", fake_call_llm), \
             mock.patch("agent.planning.planner.call_llm", fake_call_llm), \
             mock.patch("agent.nodes.simple.call_llm", fake_call_llm):
            payload = await run_agent(
                request,
                client=self.client,
                answer_generator=self.answer_generator,
                cache_service=self.cache,
                summary_generator=FakeSummaryGenerator(),
            )
        return payload, calls

    async def test_fhir_route_end_to_end(self):
        payload, calls = await self._run(
            make_request(message="Bệnh nhân BN2026-00001 có chẩn đoán gì?"),
            llm_responses={
                "agent_router": '{"route": "fhir", "resource_hint": "Condition"}',
                "agent_planner": PLANNER_CONDITIONS,
            },
        )
        self.assertEqual(payload["agent_route"], "fhir")
        self.assertEqual(payload["response_status"], "answered")
        self.assertEqual(payload["intent"], "conditions")
        self.assertEqual(payload["tool_name"], "get_conditions")
        self.assertEqual(payload["intent_source"], "agent_llm")
        self.assertEqual(payload["routing_source"], "plan")
        self.assertTrue(payload["answer"].startswith("LLM: "))
        self.assertEqual(calls, ["agent_router", "agent_planner"])
        self.assertTrue(payload["trace_id"])
        # plan_steps không lộ args (có thể chứa định danh bệnh nhân).
        self.assertEqual(payload["plan_steps"], [{"id": "c", "tool": "get_conditions"}])
        for stage in ("agent_router", "agent_planner", "agent_answer"):
            self.assertIn(stage, payload["stage_usage"])
        # usage = router + planner + answer.
        self.assertEqual(payload["usage"]["input_tokens"], 50)
        self.assertEqual(len(self.cache.saved), 1)
        self.plan_cache.save.assert_awaited_once()

    async def test_cache_lookup_uses_patient_named_in_question(self):
        await self._run(make_request(message="Bệnh nhân BN2026-00001 có chẩn đoán gì?"), llm_responses={})
        self.assertEqual(self.cache.lookups, ["BN2026-00001"])

    async def test_cache_hit_costs_zero_llm_calls(self):
        self.cache.hit = (
            "Câu trả lời cũ",
            "conditions",
            {"input_tokens": 100, "output_tokens": 20, "cost_usd": 0.00003},
            "openai",
            "gpt-4o-mini",
        )
        payload, calls = await self._run(make_request(), llm_responses={})
        self.assertEqual(calls, [])
        self.assertEqual(payload["answer"], "Câu trả lời cũ")
        self.assertEqual(payload["agent_route"], "cache")
        self.assertEqual(payload["usage"]["input_tokens"], 0)
        self.assertEqual(payload["saved_usage"]["saved_input_tokens"], 100)
        # Chi phí tiết kiệm = cost gateway của câu trả lời gốc lúc ghi cache.
        self.assertEqual(payload["saved_usage"]["saved_cost_usd"], 0.00003)
        self.assertEqual(self.answer_generator.calls, 0)

    async def test_general_chat_route_skips_fhir(self):
        payload, _ = await self._run(
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
        payload, _ = await self._run(
            make_request(message="Tôi đau đầu, hãy chẩn đoán tôi bị bệnh gì"),
            llm_responses={"agent_router": '{"route": "unsupported", "safety_flag": "diagnosis_request"}'},
        )
        self.assertEqual(payload["answer"], UNSUPPORTED_FALLBACK)
        self.assertEqual(payload["safety_flag"], "diagnosis_request")

    async def test_planner_failure_falls_back_to_rule_planner(self):
        payload, calls = await self._run(
            make_request(message="Bệnh nhân BN2026-00001 có chẩn đoán gì?"),
            llm_responses={"agent_router": '{"route": "fhir"}'},  # planner sẽ lỗi
        )
        # Không 503: rơi về rule planner và vẫn trả lời được.
        self.assertEqual(payload["response_status"], "answered")
        self.assertEqual(payload["intent_source"], "agent_rule_fallback")
        self.assertEqual(calls, ["agent_router", "agent_planner"])
        self.plan_cache.save.assert_not_awaited()  # chỉ cache plan của LLM

    async def test_router_failure_defaults_to_fhir(self):
        payload, _ = await self._run(
            make_request(message="Bệnh nhân BN2026-00001 có chẩn đoán gì?"),
            llm_responses={"agent_planner": PLANNER_CONDITIONS},
        )
        self.assertEqual(payload["agent_route"], "fhir")
        self.assertEqual(payload["response_status"], "answered")

    async def test_unrecognized_question_without_llm_asks_again(self):
        payload, _ = await self._run(make_request(message="thời tiết hôm nay thế nào"), llm_responses={})
        self.assertEqual(payload["response_status"], "clarification_needed")
        self.assertEqual(payload["answer"], UNRECOGNIZED_QUESTION_ANSWER)
        self.assertEqual(self.client.calls, [])

    async def test_low_cost_mode_uses_no_routing_llm(self):
        payload, calls = await self._run(
            make_request(message="Bệnh nhân BN2026-00001 có chẩn đoán gì?", quota_used_ratio=0.95),
            llm_responses={"agent_router": '{"route": "fhir"}', "agent_planner": PLANNER_CONDITIONS},
        )
        self.assertEqual(calls, [])
        self.assertEqual(payload["response_status"], "answered")
        self.assertEqual(payload["intent_source"], "agent_rule_fallback")

    async def test_user_role_violation_returns_policy_error(self):
        with self.assertRaises(PolicyError):
            await self._run(
                make_request(user_role="USER", allowed_patient_ids=["BN2026-00001"], message="Cho tôi danh sách tất cả bệnh nhân"),
                llm_responses={
                    "agent_router": '{"route": "fhir"}',
                    "agent_planner": '{"steps": [{"id": "p", "tool": "search_patients", "args": {"name": "Nguyen"}}]}',
                },
            )

    async def test_one_failed_step_returns_partial_answer_with_note(self):
        class HalfBrokenClient(FakeFhirClient):
            async def search_patient_resources(self, resource_type, *args, **kwargs):
                if resource_type == "MedicationRequest":
                    raise FhirClientError("FHIR Server đang bận.", "boom")
                return await super().search_patient_resources(resource_type, *args, **kwargs)

        self.client = HalfBrokenClient()
        payload, _ = await self._run(
            make_request(message="Bệnh nhân BN2026-00001 có chẩn đoán và thuốc gì?"),
            llm_responses={
                "agent_router": '{"route": "fhir"}',
                "agent_planner": (
                    '{"steps": [{"id": "c", "tool": "get_conditions", "args": {"patient_id": "BN2026-00001"}}, '
                    '{"id": "m", "tool": "get_medication_requests", "args": {"patient_id": "BN2026-00001"}}]}'
                ),
            },
        )
        self.assertEqual(payload["response_status"], "partial")
        self.assertEqual(payload["failed_steps"], [{"id": "m", "tool": "get_medication_requests", "error_code": "fhir_error"}])
        self.assertTrue(payload["answer"].endswith("Lưu ý: chưa lấy được dữ liệu đơn thuốc do lỗi hệ thống; vui lòng thử lại sau."))
        # Câu trả lời thiếu dữ liệu không được cache ở đâu cả.
        self.assertEqual(self.cache.saved, [])
        self.plan_cache.save.assert_not_awaited()
        ChatResponse.model_validate(payload)

    async def test_invalid_plan_asks_again_instead_of_400(self):
        payload, calls = await self._run(
            make_request(message="Cho tôi xem resource đó"),
            llm_responses={
                "agent_router": '{"route": "fhir"}',
                "agent_planner": '{"steps": [{"id": "r", "tool": "get_resource_by_id", "args": {}}]}',
            },
        )
        self.assertEqual(payload["response_status"], "clarification_needed")
        self.assertEqual(payload["answer"], UNRECOGNIZED_QUESTION_ANSWER)
        self.assertIn("resource_type", payload["answer_reason"])
        self.assertEqual(self.client.calls, [])
        # Token của planner đã tiêu vẫn được tính vào usage.
        self.assertEqual(calls, ["agent_router", "agent_planner"])
        self.assertEqual(payload["usage"]["input_tokens"], 20)

    async def test_every_route_satisfies_response_contract(self):
        scenarios = [
            (make_request(message="Bệnh nhân BN2026-00001 có chẩn đoán gì?"),
             {"agent_router": '{"route": "fhir"}', "agent_planner": PLANNER_CONDITIONS}),
            (make_request(message="Xin chào"), {"agent_router": '{"route": "general_chat"}'}),
            (make_request(message="thời tiết hôm nay thế nào"), {}),
        ]
        for request, responses in scenarios:
            reset_route_cache()
            payload, _ = await self._run(request, llm_responses=responses)
            ChatResponse.model_validate(payload)
        self.cache.hit = ("Câu trả lời cũ", "conditions", {"input_tokens": 1, "output_tokens": 1}, "openai", "gpt-4o-mini")
        payload, _ = await self._run(make_request(), llm_responses={})
        response = ChatResponse.model_validate(payload)
        # Trường ngoài contract vẫn đi qua (extra="allow").
        self.assertIn("saved_usage", response.model_dump())


class ChatRouteTests(unittest.TestCase):
    """Ánh xạ lỗi của agent sang HTTP ở ``POST /chat``."""

    def setUp(self):
        from fastapi import FastAPI

        from api.chat import router
        from cache.semantic_cache import get_semantic_cache

        app = FastAPI()
        app.include_router(router)
        # Không khởi tạo Qdrant/fastembed thật trong test.
        app.dependency_overrides[get_semantic_cache] = FakeCacheService
        self.client = TestClient(app)
        self.body = make_request().model_dump()

    def _post(self, error):
        async def raising(*args, **kwargs):
            raise error

        with mock.patch("api.chat.run_agent", raising):
            return self.client.post("/chat", json=self.body)

    def test_policy_error_is_403(self):
        self.assertEqual(self._post(PolicyError("cấm")).status_code, 403)

    def test_error_body_carries_calls_already_spent(self):
        spent = {
            "stage": "agent_router",
            "provider": "openai",
            "model": "gpt-4o-mini",
            "input_tokens": 120,
            "output_tokens": 9,
            "cost_usd": 0.00002,
            "call_id": "call-1",
        }

        async def router_ok_then_fhir_down(*args, **kwargs):
            start_call_log().append(spent)
            raise FhirClientError("FHIR Server đang bận.", "boom")

        with mock.patch("api.chat.run_agent", router_ok_then_fhir_down):
            response = self.client.post("/chat", json=self.body)
        self.assertEqual(response.status_code, 502)
        body = response.json()
        self.assertTrue(body["detail"])
        self.assertEqual(body["llm_calls"], [spent])

    def test_unexpected_error_is_500_with_calls(self):
        with self.assertLogs("api.chat", "ERROR"):
            response = self._post(RuntimeError("boom"))
        self.assertEqual(response.status_code, 500)
        self.assertEqual(response.json()["llm_calls"], [])

    def test_contract_violation_is_logged_not_raised(self):
        async def broken_payload(*args, **kwargs):
            return {"answer": "x", "trace_id": "t1"}

        with mock.patch("api.chat.run_agent", broken_payload), self.assertLogs("api.chat", "ERROR") as logs:
            response = self.client.post("/chat", json=self.body)
        self.assertEqual(response.status_code, 200)
        self.assertIn("trace_id=t1", logs.output[0])

    def test_langgraph_endpoint_is_gone(self):
        self.assertEqual(self.client.post("/chat/langgraph", json=self.body).status_code, 404)


if __name__ == "__main__":
    unittest.main()
