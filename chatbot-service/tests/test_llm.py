"""Test cho llm/: gateway context, cost/sổ call, chọn model, settings."""

import unittest
from types import SimpleNamespace
from unittest import mock

from app.config import Settings
from llm.client import (
    LlmCallError,
    _client,
    call_llm,
    gateway_call_kwargs,
    llm_unavailable_reason,
    parse_json_object,
    set_gateway_context,
    start_call_log,
)
from llm.model_select import select_answer_model
from llm.models import provider_and_model
from llm.usage import combine_usage, parse_cost


class GatewayContextTests(unittest.TestCase):
    def tearDown(self) -> None:
        # Reset contextvars de khong ro ri sang test khac.
        set_gateway_context(None)

    def test_no_context_returns_empty_kwargs(self) -> None:
        set_gateway_context(None)
        self.assertEqual(gateway_call_kwargs(), {})

    def test_end_user_is_sent_with_every_call(self) -> None:
        set_gateway_context("user-9")
        # Mọi call dùng master key của client; không ghi đè Authorization theo user.
        self.assertEqual(gateway_call_kwargs(), {"user": "user-9"})


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
        self.assertEqual(provider_and_model("gpt-4o-mini"), ("openai", "gpt-4o-mini"))
        self.assertEqual(provider_and_model("gpt-4.1-mini"), ("openai", "gpt-4.1-mini"))

    def test_underlying_provider_prefixed_name(self):
        # LiteLLM may return "groq/llama-3.1-8b-instant" in response.model
        self.assertEqual(provider_and_model("groq/llama-3.1-8b-instant"), ("groq", "llama-3.1-8b-instant"))

    def test_unknown_model_defaults_to_openai(self):
        self.assertEqual(provider_and_model("some-new-model"), ("openai", "some-new-model"))


def _raw_response(*, headers, prompt_tokens=12, completion_tokens=3, model="gpt-4o-mini"):
    """Giả lập ``with_raw_response.create(...)`` của openai SDK."""
    parsed = SimpleNamespace(
        model=model,
        usage=SimpleNamespace(prompt_tokens=prompt_tokens, completion_tokens=completion_tokens),
        choices=[SimpleNamespace(message=SimpleNamespace(content="ok"))],
    )
    return SimpleNamespace(headers=headers, parse=lambda: parsed)


def _fake_client(raw=None, error=None):
    async def create(**kwargs):
        if error is not None:
            raise error
        return raw

    completions = SimpleNamespace(with_raw_response=SimpleNamespace(create=create))
    return SimpleNamespace(chat=SimpleNamespace(completions=completions))


class CallLlmTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.settings = Settings(litellm_master_key="sk-master")
        patcher = mock.patch("llm.client.get_settings", return_value=self.settings)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.addCleanup(set_gateway_context, None)

    async def _call(self, client):
        with mock.patch("llm.client._client", return_value=client):
            return await call_llm(stage="agent_answer", model="gpt-4o-mini", system_prompt="p", user_payload={})

    async def test_without_gateway_raises_without_network(self):
        with mock.patch("llm.client.get_settings", return_value=Settings(litellm_master_key=None)):
            with self.assertRaises(LlmCallError):
                await call_llm(stage="s", model="m", system_prompt="p", user_payload={})

    async def test_master_key_alone_is_enough_to_call(self):
        set_gateway_context("user-1")
        raw = _raw_response(headers={"x-litellm-response-cost": "0.0001"})
        result = await self._call(_fake_client(raw))
        self.assertEqual(result.content, "ok")
        self.assertIsNone(llm_unavailable_reason())

    def test_client_does_not_retry_on_its_own(self):
        # Lần thử lại ngầm của SDK có thể bị tính tiền mà sổ call không thấy.
        _client.cache_clear()
        self.addCleanup(_client.cache_clear)
        self.assertEqual(_client().max_retries, 0)

    async def test_gateway_cost_and_call_id_are_recorded(self):
        set_gateway_context("user-1")
        calls = start_call_log()
        raw = _raw_response(headers={"x-litellm-response-cost": "0.00042", "x-litellm-call-id": "call-1"})
        result = await self._call(_fake_client(raw))
        self.assertEqual(result.usage, {"input_tokens": 12, "output_tokens": 3, "cost_usd": 0.00042})
        self.assertEqual(
            calls,
            [
                {
                    "stage": "agent_answer",
                    "provider": "openai",
                    "model": "gpt-4o-mini",
                    "input_tokens": 12,
                    "output_tokens": 3,
                    "cost_usd": 0.00042,
                    "call_id": "call-1",
                }
            ],
        )

    async def test_missing_cost_header_is_unknown_not_zero(self):
        set_gateway_context("user-1")
        calls = start_call_log()
        result = await self._call(_fake_client(_raw_response(headers={})))
        self.assertIsNone(result.usage["cost_usd"])
        self.assertIsNone(calls[0]["cost_usd"])

    async def test_gateway_rejection_is_call_error_and_not_recorded(self):
        # Vd gateway chạm trần provider_budget_config (429): stage tự rơi về đường lui.
        set_gateway_context("user-1")
        calls = start_call_log()
        error = Exception("No deployments available - crossed budget: Exceeded budget for provider openai")
        with self.assertRaises(LlmCallError):
            await self._call(_fake_client(error=error))
        self.assertEqual(calls, [])

    async def test_unreadable_body_still_records_the_charged_call(self):
        set_gateway_context("user-1")
        calls = start_call_log()

        def broken_parse():
            raise ValueError("body không phải JSON")

        raw = SimpleNamespace(
            headers={"x-litellm-response-cost": "0.0003", "x-litellm-call-id": "call-9"},
            parse=broken_parse,
        )
        with self.assertRaises(LlmCallError):
            await self._call(_fake_client(raw))
        self.assertEqual(
            calls,
            [
                {
                    "stage": "agent_answer",
                    "provider": "openai",
                    "model": "gpt-4o-mini",
                    "input_tokens": 0,
                    "output_tokens": 0,
                    "cost_usd": 0.0003,
                    "call_id": "call-9",
                }
            ],
        )

    def test_parse_json_object_accepts_fenced_json(self):
        self.assertEqual(parse_json_object('```json\n{"a": 1}\n```', stage="s"), {"a": 1})

    def test_parse_json_object_rejects_non_object(self):
        with self.assertRaises(LlmCallError):
            parse_json_object("[1, 2]", stage="s")


class UsageTests(unittest.TestCase):
    def test_combine_usage_adds_token_counts_and_cost(self):
        usage = combine_usage(
            {"input_tokens": 10, "output_tokens": 2, "cost_usd": 0.1},
            None,
            {"input_tokens": 5, "output_tokens": 1, "cost_usd": 0.2},
        )
        self.assertEqual(usage["input_tokens"], 15)
        self.assertEqual(usage["output_tokens"], 3)
        self.assertAlmostEqual(usage["cost_usd"], 0.3)

    def test_unknown_cost_makes_total_unknown(self):
        usage = combine_usage(
            {"input_tokens": 10, "output_tokens": 2, "cost_usd": 0.1},
            {"input_tokens": 5, "output_tokens": 1, "cost_usd": None},
        )
        self.assertEqual(usage["input_tokens"], 15)
        self.assertIsNone(usage["cost_usd"])

    def test_no_calls_costs_known_zero(self):
        self.assertEqual(combine_usage(None)["cost_usd"], 0.0)

    def test_parse_cost(self):
        self.assertEqual(parse_cost("0.0012"), 0.0012)
        self.assertEqual(parse_cost("0"), 0.0)
        for bad in (None, "", "abc", "-1", "nan"):
            self.assertIsNone(parse_cost(bad), bad)


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
