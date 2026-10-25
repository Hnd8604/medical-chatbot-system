"""Lớp an toàn đầu tiên: câu mô tả tình huống cấp cứu được trả lời ngay, trước cả cache.

Đặt trước semantic cache và router vì: (1) không được để cache hay lỗi LLM che mất
hướng dẫn cấp cứu; (2) luật thuần nên 0 LLM call, chạy được cả ở low-cost mode.
"""

from __future__ import annotations

from typing import Any

from langchain_core.runnables import RunnableConfig

from agent.nodes.simple import finish_simple_turn
from agent.state import ROUTE_EMERGENCY, AgentState, get_deps
from llm.usage import zero_usage
from nlp.emergency import EMERGENCY_SELF_HARM, detect_emergency


SAFETY_FLAG_EMERGENCY = "emergency"

EMERGENCY_ANSWER = (
    "Những dấu hiệu bạn mô tả có thể là tình huống cấp cứu. "
    "Hãy gọi ngay 115 hoặc đến cơ sở y tế gần nhất, và báo cho người đang ở gần để được hỗ trợ. "
    "Nếu kịp, chuẩn bị sẵn thông tin bệnh nền và thuốc đang dùng để báo cho nhân viên y tế.\n\n"
    "Mình là chatbot tra cứu hồ sơ nên không thể đánh giá hay xử trí tình huống cấp cứu."
)
SELF_HARM_ANSWER = (
    "Mình rất tiếc vì bạn đang phải trải qua điều này. "
    "Nếu bạn đang có ý định làm hại bản thân hoặc đang gặp nguy hiểm, hãy gọi ngay 115 "
    "hoặc đến cơ sở y tế gần nhất, và nói với một người bạn tin tưởng đang ở gần. "
    "Bạn không phải một mình vượt qua chuyện này: bác sĩ và chuyên gia tâm lý có thể giúp bạn.\n\n"
    "Mình là chatbot tra cứu hồ sơ nên không thể hỗ trợ khủng hoảng, nhưng bạn xứng đáng được giúp đỡ ngay."
)


async def triage(state: AgentState, config: RunnableConfig) -> dict[str, Any]:
    kind = detect_emergency(state["request"].message)
    if kind is None:
        return {"emergency_kind": None}

    # Trả lời ngay: không chờ rolling summary.
    get_deps(config).cancel_summary()
    state = {
        **state,
        "route": ROUTE_EMERGENCY,
        "route_source": "rules",
        "route_safety_flag": SAFETY_FLAG_EMERGENCY,
    }
    payload = {
        "answer": SELF_HARM_ANSWER if kind == EMERGENCY_SELF_HARM else EMERGENCY_ANSWER,
        "intent": "emergency",
        "patient_id": None,
        "evidence": [],
        "answer_source": f"template_emergency_{kind}",
        "safety_flag": SAFETY_FLAG_EMERGENCY,
    }
    result = await finish_simple_turn(state, config, payload, zero_usage(), tool_name="emergency_guidance")
    return {
        **result,
        "emergency_kind": kind,
        "route": ROUTE_EMERGENCY,
        "route_source": "rules",
        "route_safety_flag": SAFETY_FLAG_EMERGENCY,
    }
