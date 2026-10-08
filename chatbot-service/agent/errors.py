"""Lỗi của agent có mã HTTP xác định; route trả nguyên trạng."""

from __future__ import annotations

from fastapi import HTTPException, status


class AgentError(Exception):
    def __init__(self, status_code: int, detail: str) -> None:
        super().__init__(detail)
        self.status_code = status_code
        self.detail = detail

    def as_http(self) -> HTTPException:
        return HTTPException(status_code=self.status_code, detail=self.detail)


class PolicyError(AgentError):
    """Vi phạm chính sách role. Chỉ validator (và guard runtime của executor) raise lỗi này."""

    def __init__(self, detail: str) -> None:
        super().__init__(status.HTTP_403_FORBIDDEN, detail)


class PlanError(AgentError):
    """Plan không hợp lệ và không sửa được.

    Không tới được HTTP: node validate bắt lỗi này và graph rẽ sang ``clarify``
    (``clarification_needed``, HTTP 200) — plan hỏng là lỗi của hệ thống, không phải của
    người hỏi.
    """

    def __init__(self, detail: str) -> None:
        super().__init__(status.HTTP_400_BAD_REQUEST, detail)
