"""Cửa LLM duy nhất của chatbot-service — mọi call đều đi qua LiteLLM gateway.

Gateway chạy không trạng thái (không database, không virtual key): nó chỉ chuyển call tới
provider và báo chi phí của đúng call đó. Một client ``AsyncOpenAI`` dùng chung (master
key); mỗi call gửi kèm ``user=<user_id>`` và ``extra_body.metadata.stage`` (router /
planner / answer / summary...) để log của gateway đọc được.

Chi phí của từng call lấy từ header ``x-litellm-response-cost`` của gateway và được ghi
vào sổ call của request (``start_call_log``/``recorded_calls``). Sổ này đi kèm cả response
thành công lẫn response lỗi (403/502), để Spring ghi nhận tiền đã tiêu kể cả khi lượt
chat hỏng giữa pipeline. Spring là sổ chi phí duy nhất và là nơi chặn hạn mức theo user.

Mọi lỗi của một call — kể cả gateway từ chối vì chạm trần chi tiêu của provider
(``provider_budget_config``) — thành ``LlmCallError`` để nơi gọi tự chọn đường lui.
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
from llm.models import provider_and_model
from llm.usage import parse_cost, usage_from_response, zero_usage


log = logging.getLogger(__name__)


# user_id (end-user) gửi kèm mỗi call để log của gateway tách được theo người dùng.
current_end_user: contextvars.ContextVar[str | None] = contextvars.ContextVar(
    "current_end_user", default=None
)


# Sổ call LLM của request hiện tại (list dùng chung: task con như rolling summary copy
# context nhưng vẫn trỏ cùng list). None => không ghi sổ (vd test gọi thẳng call_llm).
current_call_log: contextvars.ContextVar[list[dict[str, Any]] | None] = contextvars.ContextVar(
    "current_call_log", default=None
)

_COST_HEADER = "x-litellm-response-cost"
_CALL_ID_HEADER = "x-litellm-call-id"


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
    usage: dict[str, Any] = field(default_factory=zero_usage)
    call_id: str | None = None


def start_call_log() -> list[dict[str, Any]]:
    """Mở sổ call mới cho request hiện tại; gọi trước khi chạy agent."""
    calls: list[dict[str, Any]] = []
    current_call_log.set(calls)
    return calls


def recorded_calls() -> list[dict[str, Any]]:
    return list(current_call_log.get() or [])


def llm_unavailable_reason() -> str | None:
    """Lý do request này không được gọi LLM; None khi được gọi."""
    if not get_settings().use_llm:
        return "gateway_not_configured"
    return None


def set_gateway_context(end_user: str | None) -> None:
    current_end_user.set(end_user or None)


def gateway_call_kwargs() -> dict[str, Any]:
    """Kwargs bổ sung cho ``client.chat.completions.create(...)`` theo request hiện tại."""
    kwargs: dict[str, Any] = {}
    user = current_end_user.get()
    if user:
        kwargs["user"] = user
    return kwargs


@lru_cache
def _client():
    from openai import AsyncOpenAI

    settings = get_settings()
    return AsyncOpenAI(
        api_key=settings.llm_api_key,
        base_url=settings.llm_base_url,
        timeout=settings.llm_request_timeout_seconds,
        # SDK mặc định tự thử lại 2 lần; lần thử bị bỏ dở vẫn có thể bị tính tiền mà sổ
        # call không thấy. Thử lại (nếu cần) là việc của gateway.
        max_retries=0,
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
    về đường lui tất định.
    """
    if llm_unavailable_reason():
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
        raw = await _client().chat.completions.with_raw_response.create(
            model=model,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": content},
            ],
            temperature=temperature,
            **kwargs,
        )
    except Exception as exc:
        log.warning("LLM stage %s lỗi: %s", stage, exc)
        raise LlmCallError(stage, str(exc)) from exc

    # Gateway đã trả response nghĩa là call đã bị tính tiền: đọc cost từ header trước, để
    # body hỏng (parse lỗi) vẫn ghi được call vào sổ.
    headers = getattr(raw, "headers", None) or {}
    cost_usd = parse_cost(headers.get(_COST_HEADER))
    call_id = headers.get(_CALL_ID_HEADER) or None
    if cost_usd is None:
        # Spring ghi call này với cost 0 + nguồn "unavailable"; log để phát hiện
        # gateway/version không trả header.
        log.warning("Gateway không trả %s cho stage %s", _COST_HEADER, stage)
    try:
        response = raw.parse()
    except Exception as exc:
        log.warning("LLM stage %s trả body không đọc được: %s", stage, exc)
        _record_call(
            stage,
            LlmResult(content="", model=model, usage=usage_from_response(None, cost_usd), call_id=call_id),
        )
        raise LlmCallError(stage, str(exc)) from exc

    result = LlmResult(
        content=_first_message_content(response),
        model=getattr(response, "model", None) or model,
        usage=usage_from_response(response, cost_usd),
        call_id=call_id,
    )
    _record_call(stage, result)
    return result


def _record_call(stage: str, result: LlmResult) -> None:
    calls = current_call_log.get()
    if calls is None:
        return
    provider, model = provider_and_model(result.model)
    calls.append(
        {
            "stage": stage,
            "provider": provider,
            "model": model,
            "input_tokens": result.usage.get("input_tokens", 0),
            "output_tokens": result.usage.get("output_tokens", 0),
            "cost_usd": result.usage.get("cost_usd"),
            "call_id": result.call_id,
        }
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
