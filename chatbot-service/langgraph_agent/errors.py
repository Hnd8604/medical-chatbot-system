"""Lỗi của LangGraph agent, ánh xạ thẳng sang HTTP để route trả nguyên trạng."""

from __future__ import annotations

from fastapi import HTTPException, status


class AgentError(Exception):
    """Lỗi có mã HTTP xác định, do node trong graph raise."""

    def __init__(self, status_code: int, detail: str) -> None:
        super().__init__(detail)
        self.status_code = status_code
        self.detail = detail

    def as_http(self) -> HTTPException:
        return HTTPException(status_code=self.status_code, detail=self.detail)


class PolicyError(AgentError):
    """Vi phạm chính sách role. Chỉ ``plan_validator`` được raise lỗi này."""

    def __init__(self, detail: str) -> None:
        super().__init__(status.HTTP_403_FORBIDDEN, detail)


class PlanError(AgentError):
    """Plan của LLM không hợp lệ và không sửa được."""

    def __init__(self, detail: str) -> None:
        super().__init__(status.HTTP_400_BAD_REQUEST, detail)


class AgentLlmError(Exception):
    """LLM lỗi/timeout/parse fail ở một stage.

    KHÔNG map thẳng ra HTTP: mỗi stage có đường lui riêng (xem ``docs/M-langgraph-agent.md``
    §6.4), nên lỗi này được bắt tại node và chuyển sang fallback thay vì trả 503.
    """

    def __init__(self, stage: str, detail: str) -> None:
        super().__init__(f"[{stage}] {detail}")
        self.stage = stage
        self.detail = detail
