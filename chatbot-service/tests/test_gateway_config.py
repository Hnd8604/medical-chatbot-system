import unittest

from app.config import Settings
from chat.response_builder import _provider_and_pricing_model


class GatewaySettingsTests(unittest.TestCase):
    def test_gateway_enabled_uses_litellm_base_url_and_key(self):
        settings = Settings(
            use_gateway=True,
            litellm_base_url="http://localhost:4000",
            litellm_master_key="sk-local-dev",
            openai_api_key="sk-openai",
            openai_base_url="https://api.openai.com/v1",
        )
        self.assertEqual(settings.llm_base_url, "http://localhost:4000")
        self.assertEqual(settings.llm_api_key, "sk-local-dev")
        self.assertTrue(settings.use_llm)

    def test_gateway_enabled_without_master_key_disables_llm(self):
        settings = Settings(
            use_gateway=True,
            litellm_master_key=None,
            openai_api_key="sk-openai",
        )
        self.assertFalse(settings.use_llm)

    def test_gateway_disabled_falls_back_to_openai(self):
        settings = Settings(
            use_gateway=False,
            litellm_base_url="http://localhost:4000",
            litellm_master_key="sk-local-dev",
            openai_api_key="sk-openai",
            openai_base_url="https://api.openai.com/v1",
        )
        self.assertEqual(settings.llm_base_url, "https://api.openai.com/v1")
        self.assertEqual(settings.llm_api_key, "sk-openai")
        self.assertTrue(settings.use_llm)

    def test_gateway_disabled_without_openai_key_disables_llm(self):
        settings = Settings(use_gateway=False, openai_api_key=None)
        self.assertFalse(settings.use_llm)


class ProviderMappingTests(unittest.TestCase):
    def test_openai_aliases(self):
        self.assertEqual(_provider_and_pricing_model("gpt-4o-mini"), ("openai", "gpt-4o-mini"))
        self.assertEqual(_provider_and_pricing_model("gpt-4.1-mini"), ("openai", "gpt-4.1-mini"))

    def test_groq_aliases_map_to_pricing_model(self):
        self.assertEqual(
            _provider_and_pricing_model("groq-llama-8b"),
            ("groq", "llama-3.1-8b-instant"),
        )
        self.assertEqual(
            _provider_and_pricing_model("groq-llama-70b"),
            ("groq", "llama-3.3-70b-versatile"),
        )

    def test_underlying_provider_prefixed_name(self):
        # LiteLLM may return "groq/llama-3.1-8b-instant" in response.model
        self.assertEqual(
            _provider_and_pricing_model("groq/llama-3.1-8b-instant"),
            ("groq", "llama-3.1-8b-instant"),
        )

    def test_unknown_model_defaults_to_openai(self):
        self.assertEqual(_provider_and_pricing_model("some-new-model"), ("openai", "some-new-model"))


if __name__ == "__main__":
    unittest.main()
