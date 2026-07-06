"""Ngu canh goi AI Gateway (LiteLLM) theo tung request.

Moi request chat mang theo virtual key cua user (do Spring cap) va user_id. Thay vi
dung lai client theo tung key, ta giu client singleton (dung master key mac dinh) va
ghi de per-call:

- ``extra_headers={"Authorization": "Bearer <virtual_key>"}`` de gateway chan budget
  dung nguoi.
- ``user=<user_id>`` de spend log cua gateway tach theo end-user.

Cac gia tri nay duoc dat vao contextvar o dau route ``/chat`` va doc lai o 3 call site
LLM (intent extractor, model router, answer generator) qua ``gateway_call_kwargs()``.
"""

from __future__ import annotations

import contextvars
from typing import Any


# Virtual key cua user cho request hien tai. None => dung master key mac dinh cua client.
current_llm_key: contextvars.ContextVar[str | None] = contextvars.ContextVar(
    "current_llm_key", default=None
)
# user_id (end-user) cho spend tracking o gateway.
current_end_user: contextvars.ContextVar[str | None] = contextvars.ContextVar(
    "current_end_user", default=None
)


class GatewayBudgetExceededError(Exception):
    """Gateway tu choi vi virtual key da vuot budget token/cost trong ky."""


def set_gateway_context(llm_key: str | None, end_user: str | None) -> None:
    current_llm_key.set(llm_key or None)
    current_end_user.set(end_user or None)


def gateway_call_kwargs() -> dict[str, Any]:
    """Kwargs bo sung cho ``client.chat.completions.create(...)`` theo request hien tai."""
    kwargs: dict[str, Any] = {}
    user = current_end_user.get()
    if user:
        kwargs["user"] = user
    key = current_llm_key.get()
    if key:
        # Ghi de header Authorization cua client (master key) bang virtual key cua user.
        kwargs["extra_headers"] = {"Authorization": f"Bearer {key}"}
    return kwargs


_BUDGET_ERROR_MARKERS = ("exceededbudget", "budget_exceeded")


def _looks_like_budget_text(text: str) -> bool:
    if any(marker in text for marker in _BUDGET_ERROR_MARKERS):
        return True
    return "budget" in text and ("exceed" in text or "crossed" in text or "over" in text)


def is_budget_error(exc: Exception) -> bool:
    """Nhan biet loi vuot budget tu LiteLLM.

    Uu tien tin hieu co cau truc tu openai SDK: ``APIStatusError.body`` mang
    ``error.type/code/message`` do LiteLLM tra ve (vd type ``budget_exceeded``,
    message ``ExceededBudget: ...``). Khop chuoi tren message chi la lop phu vi
    wording co the doi theo version LiteLLM.
    """
    body = getattr(exc, "body", None)
    error = body.get("error", body) if isinstance(body, dict) else None
    if isinstance(error, dict):
        structured = " ".join(
            str(error.get(key, "")) for key in ("type", "code", "message")
        ).lower()
        if _looks_like_budget_text(structured):
            return True
    text = f"{getattr(exc, 'message', '')} {exc}".lower()
    return _looks_like_budget_text(text)


def raise_if_budget_exceeded(exc: Exception) -> None:
    """Chuyen loi budget cua gateway thanh ``GatewayBudgetExceededError`` de route xu ly.

    Goi truoc khi cac call site roi ve rule-based/template, de loi budget khong bi nuot."""
    if is_budget_error(exc):
        raise GatewayBudgetExceededError(str(exc)) from exc
