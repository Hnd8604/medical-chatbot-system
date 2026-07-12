import unittest

from agents.pricing import estimate_cost_usd
from chat.response_builder import _apply_estimated_cost


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
        _apply_estimated_cost(payload)
        self.assertEqual(payload["usage"]["estimated_cost_usd"], 0.002607)

    def test_no_usage_is_safe_noop(self):
        payload = {"llm_provider": "openai", "llm_model": "gpt-4.1-mini"}
        _apply_estimated_cost(payload)  # không có 'usage' -> không lỗi
        self.assertNotIn("usage", payload)

    def test_unknown_model_leaves_zero(self):
        payload = {
            "llm_provider": "openai",
            "llm_model": "mystery",
            "usage": {"input_tokens": 100, "output_tokens": 50, "estimated_cost_usd": 0},
        }
        _apply_estimated_cost(payload)
        self.assertEqual(payload["usage"]["estimated_cost_usd"], 0.0)


if __name__ == "__main__":
    unittest.main()
