"""Một cửa duy nhất để agent gọi LLM — luôn đi qua LiteLLM gateway.

Khác bản tham chiếu ``Medical-Chatbot-develop`` ở hai điểm quan trọng:

1. **Không dùng ``langchain_openai``.** Repo này đã có đường LLM chuẩn (``openai``
   AsyncOpenAI + ``agents/gateway_context.py``); thêm client thứ hai nghĩa là nhân đôi
   logic virtual key / budget / spend log. LangGraph chỉ cần node là ``async def``,
   không bắt buộc dùng LangChain model.
2. **Gắn ``metadata.stage`` vào mỗi call** để spend log của gateway tự tách chi phí
   theo stage (router / planner / chat) — không phải viết thêm code accounting.
"""

from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass, field
from functools import lru_cache
from typing import Any

from agents.gateway_context import (
    current_end_user,
    gateway_call_kwargs,
    raise_if_budget_exceeded,
)
from app.config import get_settings
from langgraph_agent.errors import AgentLlmError


log = logging.getLogger(__name__)

STAGE_ROUTER = "agent_router"
STAGE_PLANNER = "agent_planner"
STAGE_CHAT = "agent_chat"


def zero_usage() -> dict[str, int | float]:
    return {"input_tokens": 0, "output_tokens": 0, "estimated_cost_usd": 0}


@dataclass(frozen=True)
class LlmResult:
    content: str
    model: str | None = None
    usage: dict[str, int | float] = field(default_factory=zero_usage)


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
    """Gọi gateway cho một stage của agent.

    Raise ``GatewayBudgetExceededError`` khi virtual key vượt budget (route đổi thành
    HTTP 429), còn mọi lỗi khác thành ``AgentLlmError`` để node tự chọn đường lui.
    """
    content = user_payload if isinstance(user_payload, str) else json.dumps(user_payload, ensure_ascii=False)
    kwargs: dict[str, Any] = dict(gateway_call_kwargs())
    extra_body: dict[str, Any] = {"metadata": {"stage": stage}}
    end_user = current_end_user.get()
    if end_user:
        extra_body["metadata"]["end_user"] = end_user
    kwargs["extra_body"] = extra_body
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
        # Budget phải nổi lên trên; các lỗi còn lại để node fallback.
        raise_if_budget_exceeded(exc)
        log.warning("LLM stage %s lỗi: %s", stage, exc)
        raise AgentLlmError(stage, str(exc)) from exc

    return LlmResult(
        content=_first_message_content(response),
        model=getattr(response, "model", None) or model,
        usage=_usage_from_response(response),
    )


def _first_message_content(response: Any) -> str:
    choices = getattr(response, "choices", None) or []
    if not choices:
        return ""
    message = getattr(choices[0], "message", None)
    return str(getattr(message, "content", "") or "")


def _usage_from_response(response: Any) -> dict[str, int | float]:
    usage = getattr(response, "usage", None)
    if usage is None:
        return zero_usage()
    return {
        "input_tokens": _number(getattr(usage, "prompt_tokens", 0)),
        "output_tokens": _number(getattr(usage, "completion_tokens", 0)),
        # Cost thật do agents/pricing.py tính một lần ở _finalize_chat_response,
        # trên model của answer — không cộng dồn ước tính rời rạc ở đây.
        "estimated_cost_usd": 0,
    }


def _number(value: Any) -> int | float:
    return value if isinstance(value, (int, float)) and not isinstance(value, bool) else 0


def parse_json_object(content: str, *, stage: str) -> dict[str, Any]:
    """Đọc JSON object từ output LLM, chấp nhận cả khi bị bọc trong ```json."""
    text = (content or "").strip()
    if not text:
        raise AgentLlmError(stage, "LLM trả về nội dung rỗng.")

    fenced = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, flags=re.DOTALL)
    if fenced:
        text = fenced.group(1)
    elif not text.startswith("{"):
        match = re.search(r"\{.*\}", text, flags=re.DOTALL)
        if match is None:
            raise AgentLlmError(stage, "LLM không trả về JSON.")
        text = match.group(0)

    try:
        value = json.loads(text)
    except json.JSONDecodeError as exc:
        raise AgentLlmError(stage, f"JSON không hợp lệ: {exc}") from exc
    if not isinstance(value, dict):
        raise AgentLlmError(stage, "JSON phải là object.")
    return value


def clean_text(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    text = value.strip()
    if not text or text.lower() in {"null", "none"}:
        return None
    return text
