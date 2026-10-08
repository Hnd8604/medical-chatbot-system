"""Test cho llm/: gateway context, nhận diện lỗi budget, pricing, chọn model, settings."""

import unittest
from unittest import mock

from app.config import Settings
from llm.client import (
    GatewayBudgetExceededError,
    LlmCallError,
    call_llm,
    gateway_call_kwargs,
    is_budget_error,
    parse_json_object,
    raise_if_budget_exceeded,
    set_gateway_context,
)
from llm.model_select import select_answer_model
from llm.pricing import apply_estimated_cost, estimate_cost_usd, provider_and_pricing_model
from llm.usage import combine_usage


class GatewayContextTests(unittest.TestCase):
    def tearDown(self) -> None:
        # Reset contextvars de khong ro ri sang test khac.
        set_gateway_context(None, None)

    def test_no_context_returns_empty_kwargs(self) -> None:
        set_gateway_context(None, None)
        self.assertEqual(gateway_call_kwargs(), {})

    def test_virtual_key_becomes_authorization_header(self) -> None:
        set_gateway_context("sk-user-123", "user-1")
        kwargs = gateway_call_kwargs()
        self.assertEqual(kwargs["extra_headers"], {"Authorization": "Bearer sk-user-123"})
        self.assertEqual(kwargs["user"], "user-1")

    def test_user_tracked_even_without_virtual_key(self) -> None:
        # Fallback master key (llm_key None) van gan user cho spend log gateway.
        set_gateway_context(None, "user-9")
        kwargs = gateway_call_kwargs()
        self.assertNotIn("extra_headers", kwargs)
        self.assertEqual(kwargs["user"], "user-9")

    def test_is_budget_error_detects_litellm_budget_messages(self) -> None:
        self.assertTrue(is_budget_error(Exception("Budget has been exceeded! Current cost: 0.5")))
        self.assertTrue(is_budget_error(Exception("ExceededBudget: crossed spend limit")))
        self.assertFalse(is_budget_error(Exception("connection timeout")))
        self.assertFalse(is_budget_error(Exception("max_budget field set")))

    def test_is_budget_error_uses_structured_error_body(self) -> None:
        # openai.APIStatusError co .body = {"error": {...}} do LiteLLM tra ve.
        # Nhan dien theo type/code truoc, khong phu thuoc wording cua message.
        exc = Exception("Error code: 400")
        exc.body = {"error": {"type": "budget_exceeded", "message": "denied"}}
        self.assertTrue(is_budget_error(exc))

        exc2 = Exception("Error code: 429")
        exc2.body = {"error": {"code": "ExceededBudget", "message": "denied"}}
        self.assertTrue(is_budget_error(exc2))

        exc3 = Exception("Error code: 400")
        exc3.body = {"error": {"type": "invalid_request_error", "message": "bad input"}}
        self.assertFalse(is_budget_error(exc3))

    def test_raise_if_budget_exceeded(self) -> None:
        with self.assertRaises(GatewayBudgetExceededError):
            raise_if_budget_exceeded(Exception("Budget has been exceeded"))
        # Loi khong phai budget thi khong nem (de call site tu fallback).
        raise_if_budget_exceeded(Exception("some other error"))


class GatewaySettingsTests(unittest.TestCase):
    def test_master_key_enables_llm_via_gateway(self):
        settings = Settings(
            litellm_base_url="http://localhost:4000",
            litellm_master_key="sk-local-dev",
        )
        self.assertEqual(settings.llm_base_url, "http://localhost:4000")
        self.assertEqual(settings.llm_api_key, "sk-local-dev")
        self.assertTrue(settings.use_llm)
        self.assertTrue(settings.use_llm_answer)

    def test_without_master_key_disables_llm(self):
        settings = Settings(litellm_master_key=None)
        self.assertFalse(settings.use_llm)
        self.assertFalse(settings.use_llm_answer)

    def test_llm_answer_requires_enable_flag(self):
        settings = Settings(litellm_master_key="sk-local-dev", enable_llm_answer=False)
        self.assertTrue(settings.use_llm)
        self.assertFalse(settings.use_llm_answer)


class ProviderMappingTests(unittest.TestCase):
    def test_openai_aliases(self):
        self.assertEqual(provider_and_pricing_model("gpt-4o-mini"), ("openai", "gpt-4o-mini"))
        self.assertEqual(provider_and_pricing_model("gpt-4.1-mini"), ("openai", "gpt-4.1-mini"))

    def test_groq_aliases_map_to_pricing_model(self):
        self.assertEqual(
            provider_and_pricing_model("groq-llama-8b"),
            ("groq", "llama-3.1-8b-instant"),
        )
        self.assertEqual(
            provider_and_pricing_model("groq-llama-70b"),
            ("groq", "llama-3.3-70b-versatile"),
        )

    def test_underlying_provider_prefixed_name(self):
        # LiteLLM may return "groq/llama-3.1-8b-instant" in response.model
        self.assertEqual(
            provider_and_pricing_model("groq/llama-3.1-8b-instant"),
            ("groq", "llama-3.1-8b-instant"),
        )

    def test_unknown_model_defaults_to_openai(self):
        self.assertEqual(provider_and_pricing_model("some-new-model"), ("openai", "some-new-model"))


class PricingTests(unittest.TestCase):
    def test_matches_spring_model_pricing_seed(self):
        # Giá trị khớp bảng model_pricing của Spring (V1 seed) -> cost trong response
        # khớp cost Spring lưu vào usage_logs.
        self.assertEqual(estimate_cost_usd("openai", "gpt-4.1-mini", 5109, 352), 0.002607)
        self.assertEqual(estimate_cost_usd("openai", "gpt-4o-mini", 4216, 110), 0.000698)

    def test_provider_and_model_case_insensitive(self):
        self.assertEqual(
            estimate_cost_usd("OpenAI", "GPT-4o-mini", 4216, 110), 0.000698
        )

    def test_unknown_or_missing_returns_zero(self):
        self.assertEqual(estimate_cost_usd("openai", "unknown-model", 100, 100), 0.0)
        self.assertEqual(estimate_cost_usd(None, None, 100, 100), 0.0)

    def test_negative_tokens_clamped(self):
        self.assertEqual(estimate_cost_usd("openai", "gpt-4.1-mini", -10, -10), 0.0)


class ApplyEstimatedCostTests(unittest.TestCase):
    def test_mutates_usage_from_provider_model(self):
        payload = {
            "llm_provider": "openai",
            "llm_model": "gpt-4.1-mini",
            "usage": {"input_tokens": 5109, "output_tokens": 352, "estimated_cost_usd": 0},
        }
        apply_estimated_cost(payload)
        self.assertEqual(payload["usage"]["estimated_cost_usd"], 0.002607)

    def test_no_usage_is_safe_noop(self):
        payload = {"llm_provider": "openai", "llm_model": "gpt-4.1-mini"}
        apply_estimated_cost(payload)  # không có 'usage' -> không lỗi
        self.assertNotIn("usage", payload)

    def test_unknown_model_leaves_zero(self):
        payload = {
            "llm_provider": "openai",
            "llm_model": "mystery",
            "usage": {"input_tokens": 100, "output_tokens": 50, "estimated_cost_usd": 0},
        }
        apply_estimated_cost(payload)
        self.assertEqual(payload["usage"]["estimated_cost_usd"], 0.0)


class CallLlmTests(unittest.IsolatedAsyncioTestCase):
    async def test_without_gateway_raises_without_network(self):
        with mock.patch("llm.client.get_settings", return_value=Settings(litellm_master_key=None)):
            with self.assertRaises(LlmCallError):
                await call_llm(stage="s", model="m", system_prompt="p", user_payload={})

    def test_parse_json_object_accepts_fenced_json(self):
        self.assertEqual(parse_json_object('```json\n{"a": 1}\n```', stage="s"), {"a": 1})

    def test_parse_json_object_rejects_non_object(self):
        with self.assertRaises(LlmCallError):
            parse_json_object("[1, 2]", stage="s")


class UsageTests(unittest.TestCase):
    def test_combine_usage_adds_token_counts_and_cost(self):
        usage = combine_usage(
            {"input_tokens": 10, "output_tokens": 2, "estimated_cost_usd": 0.1},
            None,
            {"input_tokens": 5, "output_tokens": 1, "estimated_cost_usd": 0.2},
        )
        self.assertEqual(usage["input_tokens"], 15)
        self.assertEqual(usage["output_tokens"], 3)
        self.assertAlmostEqual(usage["estimated_cost_usd"], 0.3)


class ModelSelectTests(unittest.TestCase):
    def setUp(self):
        self.settings = Settings(model_simple="cheap", model_complex="strong")
        patcher = mock.patch("llm.model_select.get_settings", return_value=self.settings)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_single_data_step_uses_simple_model(self):
        choice = select_answer_model(step_count=1, explain=False, all_patients=False, quota_used_ratio=0)
        self.assertEqual((choice.model, choice.complexity, choice.source), ("cheap", "simple", "plan"))

    def test_multi_step_explain_or_all_patients_use_complex_model(self):
        for kwargs in (
            {"step_count": 2, "explain": False, "all_patients": False},
            {"step_count": 1, "explain": True, "all_patients": False},
            {"step_count": 1, "explain": False, "all_patients": True},
        ):
            choice = select_answer_model(quota_used_ratio=0, **kwargs)
            self.assertEqual((choice.model, choice.complexity), ("strong", "complex"), kwargs)

    def test_quota_pressure_downgrades_complex_turn(self):
        choice = select_answer_model(step_count=3, explain=True, all_patients=False, quota_used_ratio=0.9)
        self.assertEqual((choice.model, choice.complexity, choice.source), ("cheap", "complex", "quota_downgrade"))


if __name__ == "__main__":
    unittest.main()
