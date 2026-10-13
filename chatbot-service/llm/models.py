"""Nhận diện (provider, model) chuẩn từ tên model mà gateway nhận hoặc trả về.

Dùng để Spring báo cáo chi phí theo model với tên ổn định (alias của LiteLLM và tên
gốc trong response.model gộp về một). Không hệ thống nào giữ bảng giá: chi phí của mỗi
call chỉ lấy từ header ``x-litellm-response-cost`` của LiteLLM (xem ``llm/client.py``).

Thêm provider/model mới: khai báo trong ``infra/litellm/config.yaml`` rồi thêm một dòng
alias ở đây.
"""

from __future__ import annotations


# Alias model (tên trong infra/litellm/config.yaml) và tên model gốc mà gateway có thể
# trả về trong response.model -> (provider, model) chuẩn.
_MODEL_ALIAS_MAP: dict[str, tuple[str, str]] = {
    "gpt-4o-mini": ("openai", "gpt-4o-mini"),
    "gpt-4.1-mini": ("openai", "gpt-4.1-mini"),
}


def provider_and_model(model: str | None) -> tuple[str, str]:
    key = (model or "").split("/")[-1].strip()
    if key in _MODEL_ALIAS_MAP:
        return _MODEL_ALIAS_MAP[key]
    provider = "groq" if "llama" in key.lower() or "groq" in key.lower() else "openai"
    return provider, key
