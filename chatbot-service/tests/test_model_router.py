"""
Unit tests for agents/model_router.py (M16 - Model Routing).

Covers:
- Keyword classifier router (SIMPLE/COMPLEX + quota downgrade).
- LLM router hybrid: keyword fast-path, LLM classification, fallback, cache.
- build_model_router factory selection.
"""
import json
import unittest
from types import SimpleNamespace

from agents.model_router import (
    LLMModelRouter,
    ModelRouter,
    QueryComplexity,
    build_model_router,
)
from app.config import Settings


MODEL_SIMPLE = "gpt-4o-mini"
MODEL_COMPLEX = "gpt-4.1-mini"


def _llm_response(arguments: str | None, *, tool_name: str = "classify_complexity"):
    tool_calls = []
    if arguments is not None:
        tool_calls.append(
            SimpleNamespace(function=SimpleNamespace(name=tool_name, arguments=arguments))
        )
    return SimpleNamespace(
        choices=[SimpleNamespace(message=SimpleNamespace(tool_calls=tool_calls))],
        usage=SimpleNamespace(prompt_tokens=30, completion_tokens=5),
    )


class FakeChatCompletions:
    def __init__(self, response=None, error: Exception | None = None):
        self.response = response
        self.error = error
        self.call_count = 0

    async def create(self, **kwargs):
        self.call_count += 1
        if self.error:
            raise self.error
        return self.response


def _fake_client(completions: FakeChatCompletions):
    return SimpleNamespace(chat=SimpleNamespace(completions=completions))


def _llm_router(completions: FakeChatCompletions) -> LLMModelRouter:
    router = LLMModelRouter(
        api_key="sk-test",
        router_model="gpt-4o-mini",
        timeout_seconds=5,
        model_simple=MODEL_SIMPLE,
        model_complex=MODEL_COMPLEX,
        base_url="http://localhost:4000",
    )
    router.client = _fake_client(completions)
    return router


class KeywordModelRouterTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.router = ModelRouter(model_simple=MODEL_SIMPLE, model_complex=MODEL_COMPLEX)

    async def test_simple_lookup_routes_to_simple_model(self):
        decision = await self.router.route("thuoc cua benh nhan 1")
        self.assertEqual(decision.complexity, QueryComplexity.SIMPLE)
        self.assertEqual(decision.model, MODEL_SIMPLE)
        self.assertEqual(decision.source, "keyword")

    async def test_analysis_keyword_routes_to_complex_model(self):
        decision = await self.router.route("phan tich xu huong duong huyet cua benh nhan 1")
        self.assertEqual(decision.complexity, QueryComplexity.COMPLEX)
        self.assertEqual(decision.model, MODEL_COMPLEX)

    async def test_complex_downgrades_to_simple_when_quota_high(self):
        decision = await self.router.route(
            "phan tich xu huong duong huyet", quota_used_ratio=0.85
        )
        self.assertEqual(decision.complexity, QueryComplexity.COMPLEX)
        self.assertEqual(decision.model, MODEL_SIMPLE)

    async def test_usage_is_zero_for_keyword_router(self):
        decision = await self.router.route("thuoc cua benh nhan 1")
        self.assertEqual(decision.usage["input_tokens"], 0)
        self.assertEqual(decision.usage["output_tokens"], 0)


class LLMModelRouterTests(unittest.IsolatedAsyncioTestCase):
    async def test_keyword_complex_fast_path_skips_llm_call(self):
        completions = FakeChatCompletions(
            response=_llm_response(json.dumps({"complexity": "simple"}))
        )
        router = _llm_router(completions)

        decision = await router.route("so sanh ket qua xet nghiem hai lan kham")

        self.assertEqual(decision.complexity, QueryComplexity.COMPLEX)
        self.assertEqual(decision.model, MODEL_COMPLEX)
        self.assertEqual(decision.source, "keyword")
        self.assertEqual(completions.call_count, 0)

    async def test_llm_classifies_complex_when_keyword_misses(self):
        completions = FakeChatCompletions(
            response=_llm_response(json.dumps({"complexity": "complex"}))
        )
        router = _llm_router(completions)

        # Câu hỏi mang tính suy luận nhưng không chứa keyword phân tích nào.
        decision = await router.route("chi so nay co dang tin cay voi benh nhan tieu duong")

        self.assertEqual(decision.complexity, QueryComplexity.COMPLEX)
        self.assertEqual(decision.model, MODEL_COMPLEX)
        self.assertEqual(decision.source, "llm_router")
        self.assertEqual(completions.call_count, 1)

    async def test_llm_confirms_simple(self):
        completions = FakeChatCompletions(
            response=_llm_response(json.dumps({"complexity": "simple"}))
        )
        router = _llm_router(completions)

        decision = await router.route("thuoc cua benh nhan 1")

        self.assertEqual(decision.complexity, QueryComplexity.SIMPLE)
        self.assertEqual(decision.model, MODEL_SIMPLE)
        self.assertEqual(decision.source, "llm_router")

    async def test_llm_router_tracks_usage(self):
        completions = FakeChatCompletions(
            response=_llm_response(json.dumps({"complexity": "simple"}))
        )
        router = _llm_router(completions)

        decision = await router.route("thuoc cua benh nhan 1")

        self.assertEqual(decision.usage["input_tokens"], 30)
        self.assertEqual(decision.usage["output_tokens"], 5)

    async def test_llm_error_falls_back_to_keyword_result(self):
        completions = FakeChatCompletions(error=RuntimeError("gateway down"))
        router = _llm_router(completions)

        decision = await router.route("thuoc cua benh nhan 1")

        self.assertEqual(decision.complexity, QueryComplexity.SIMPLE)
        self.assertEqual(decision.model, MODEL_SIMPLE)
        self.assertEqual(decision.source, "keyword")

    async def test_invalid_llm_result_falls_back_to_keyword_result(self):
        completions = FakeChatCompletions(
            response=_llm_response(json.dumps({"complexity": "banana"}))
        )
        router = _llm_router(completions)

        decision = await router.route("thuoc cua benh nhan 1")

        self.assertEqual(decision.complexity, QueryComplexity.SIMPLE)
        self.assertEqual(decision.source, "keyword")

    async def test_missing_tool_call_falls_back_to_keyword_result(self):
        completions = FakeChatCompletions(response=_llm_response(None))
        router = _llm_router(completions)

        decision = await router.route("thuoc cua benh nhan 1")

        self.assertEqual(decision.complexity, QueryComplexity.SIMPLE)
        self.assertEqual(decision.source, "keyword")

    async def test_classification_is_cached_per_normalized_message(self):
        completions = FakeChatCompletions(
            response=_llm_response(json.dumps({"complexity": "complex"}))
        )
        router = _llm_router(completions)

        first = await router.route("chi so nay co dang tin cay khong")
        # Cùng câu hỏi sau normalize (dấu tiếng Việt) → không gọi LLM lần hai.
        second = await router.route("Chỉ số này có đáng tin cậy không")

        self.assertEqual(completions.call_count, 1)
        self.assertEqual(first.complexity, QueryComplexity.COMPLEX)
        self.assertEqual(second.complexity, QueryComplexity.COMPLEX)
        self.assertEqual(second.source, "llm_router")
        self.assertEqual(second.usage["input_tokens"], 0)

    async def test_llm_complex_downgrades_when_quota_high(self):
        completions = FakeChatCompletions(
            response=_llm_response(json.dumps({"complexity": "complex"}))
        )
        router = _llm_router(completions)

        decision = await router.route(
            "chi so nay co dang tin cay khong", quota_used_ratio=0.9
        )

        self.assertEqual(decision.complexity, QueryComplexity.COMPLEX)
        self.assertEqual(decision.model, MODEL_SIMPLE)


class BuildModelRouterTests(unittest.TestCase):
    def test_defaults_to_keyword_router(self):
        settings = Settings(litellm_master_key=None, enable_llm_router=False)
        router = build_model_router(settings)
        self.assertIsInstance(router, ModelRouter)

    def test_flag_without_gateway_key_keeps_keyword_router(self):
        settings = Settings(litellm_master_key=None, enable_llm_router=True)
        router = build_model_router(settings)
        self.assertIsInstance(router, ModelRouter)

    def test_flag_with_gateway_key_builds_llm_router(self):
        settings = Settings(litellm_master_key="sk-local-dev", enable_llm_router=True)
        router = build_model_router(settings)
        self.assertIsInstance(router, LLMModelRouter)
        self.assertEqual(router.router_model, settings.model_router)
        self.assertEqual(router.model_simple, settings.model_simple)
        self.assertEqual(router.model_complex, settings.model_complex)


if __name__ == "__main__":
    unittest.main()
