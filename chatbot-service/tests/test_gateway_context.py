import unittest

from agents.gateway_context import (
    GatewayBudgetExceededError,
    gateway_call_kwargs,
    is_budget_error,
    raise_if_budget_exceeded,
    set_gateway_context,
)


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


if __name__ == "__main__":
    unittest.main()
