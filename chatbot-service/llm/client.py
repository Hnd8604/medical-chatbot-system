"""Cửa LLM duy nhất của chatbot-service — mọi call đều đi qua LiteLLM gateway.

Một client ``AsyncOpenAI`` dùng chung (master key), còn thông tin theo request được ghi
đè ở từng call:

- ``extra_headers={"Authorization": "Bearer <virtual_key>"}`` để gateway chặn budget
  đúng người (virtual key do Spring cấp).
- ``user=<user_id>`` để spend log của gateway tách theo end-user.
- ``extra_body.metadata.stage`` để spend log tách chi phí theo stage (router / planner /
  answer / summary...) mà không phải tự cộng token.

Lỗi vượt budget nổi lên thành ``GatewayBudgetExceededError`` (route trả 429); mọi lỗi
khác thành ``LlmCallError`` để nơi gọi tự chọn đường lui.
"""

from __future__ import annotations

import contextvars
import json
import logging
import re
from dataclasses import dataclass, field
from functools import lru_cache
from typing import Any

from app.config import get_settings
from llm.usage import usage_from_response, zero_usage


log = logging.getLogger(__name__)


# Virtual key của user cho request hiện tại. None => dùng master key mặc định của client.
current_llm_key: contextvars.ContextVar[str | None] = contextvars.ContextVar(
    "current_llm_key", default=None
)
# user_id (end-user) cho spend tracking ở gateway.
current_end_user: contextvars.ContextVar[str | None] = contextvars.ContextVar(
    "current_end_user", default=None
)


class GatewayBudgetExceededError(Exception):
    """Gateway từ chối vì virtual key đã vượt budget token/cost trong kỳ."""


class LlmCallError(Exception):
    """LLM lỗi/timeout/parse fail ở một stage. Nơi gọi tự quyết định đường lui."""

    def __init__(self, stage: str, detail: str) -> None:
        super().__init__(f"[{stage}] {detail}")
        self.stage = stage
        self.detail = detail


@dataclass(frozen=True)
class LlmResult:
    content: str
    model: str | None = None
    usage: dict[str, int | float] = field(default_factory=zero_usage)


def set_gateway_context(llm_key: str | None, end_user: str | None) -> None:
    current_llm_key.set(llm_key or None)
    current_end_user.set(end_user or None)


def gateway_call_kwargs() -> dict[str, Any]:
    """Kwargs bổ sung cho ``client.chat.completions.create(...)`` theo request hiện tại."""
    kwargs: dict[str, Any] = {}
    user = current_end_user.get()
    if user:
        kwargs["user"] = user
    key = current_llm_key.get()
    if key:
        # Ghi đè header Authorization của client (master key) bằng virtual key của user.
        kwargs["extra_headers"] = {"Authorization": f"Bearer {key}"}
    return kwargs


_BUDGET_ERROR_MARKERS = ("exceededbudget", "budget_exceeded")


def _looks_like_budget_text(text: str) -> bool:
    if any(marker in text for marker in _BUDGET_ERROR_MARKERS):
        return True
    return "budget" in text and ("exceed" in text or "crossed" in text or "over" in text)


def is_budget_error(exc: Exception) -> bool:
    """Nhận biết lỗi vượt budget từ LiteLLM.

    Ưu tiên tín hiệu có cấu trúc từ openai SDK: ``APIStatusError.body`` mang
    ``error.type/code/message`` do LiteLLM trả về (vd type ``budget_exceeded``,
    message ``ExceededBudget: ...``). Khớp chuỗi trên message chỉ là lớp phủ vì
    wording có thể đổi theo version LiteLLM.
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
    if is_budget_error(exc):
        raise GatewayBudgetExceededError(str(exc)) from exc


@lru_cache
def _client():
    from openai import AsyncOpenAI

    settings = get_settings()
    return AsyncOpenAI(
        api_key=settings.llm_api_key,
        base_url=settings.llm_base_url,
        timeout=settings.llm_request_timeout_seconds,
    )


async def call_llm(
    *,
    stage: str,
    model: str,
    system_prompt: str,
    user_payload: Any,
    max_output_tokens: int | None = None,
    temperature: float = 0.0,
) -> LlmResult:
    """Gọi gateway cho một stage.

    Không có master key ⇒ raise ``LlmCallError`` ngay (không gọi mạng), để mọi stage rơi
    về đường lui tất định — service vẫn chạy được khi demo không có gateway.
    """
    if not get_settings().use_llm:
        raise LlmCallError(stage, "LLM gateway chưa được cấu hình (thiếu LITELLM_MASTER_KEY).")

    content = user_payload if isinstance(user_payload, str) else json.dumps(user_payload, ensure_ascii=False)
    kwargs: dict[str, Any] = dict(gateway_call_kwargs())
    metadata: dict[str, Any] = {"stage": stage}
    end_user = current_end_user.get()
    if end_user:
        metadata["end_user"] = end_user
    kwargs["extra_body"] = {"metadata": metadata}
    if max_output_tokens:
        kwargs["max_tokens"] = max_output_tokens

    try:
        response = await _client().chat.completions.create(
            model=model,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": content},
            ],
            temperature=temperature,
            **kwargs,
        )
    except Exception as exc:
        # Budget phải nổi lên trên; các lỗi còn lại để nơi gọi fallback.
        raise_if_budget_exceeded(exc)
        log.warning("LLM stage %s lỗi: %s", stage, exc)
        raise LlmCallError(stage, str(exc)) from exc

    return LlmResult(
        content=_first_message_content(response),
        model=getattr(response, "model", None) or model,
        usage=usage_from_response(response),
    )


def _first_message_content(response: Any) -> str:
    choices = getattr(response, "choices", None) or []
    if not choices:
        return ""
    message = getattr(choices[0], "message", None)
    return str(getattr(message, "content", "") or "")


def parse_json_object(content: str, *, stage: str) -> dict[str, Any]:
    """Đọc JSON object từ output LLM, chấp nhận cả khi bị bọc trong ```json."""
    text = (content or "").strip()
    if not text:
        raise LlmCallError(stage, "LLM trả về nội dung rỗng.")

    fenced = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, flags=re.DOTALL)
    if fenced:
        text = fenced.group(1)
    elif not text.startswith("{"):
        match = re.search(r"\{.*\}", text, flags=re.DOTALL)
        if match is None:
            raise LlmCallError(stage, "LLM không trả về JSON.")
        text = match.group(0)

    try:
        value = json.loads(text)
    except json.JSONDecodeError as exc:
        raise LlmCallError(stage, f"JSON không hợp lệ: {exc}") from exc
    if not isinstance(value, dict):
        raise LlmCallError(stage, "JSON phải là object.")
    return value


def clean_text(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    text = value.strip()
    if not text or text.lower() in {"null", "none"}:
        return None
    return text
