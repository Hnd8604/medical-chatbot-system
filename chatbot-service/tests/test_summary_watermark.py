"""Test cho mốc rolling summary (summarized_message_count do Spring lưu).

Ngoài test từng trường hợp, có một bài mô phỏng nhiều lượt: Spring gửi tin theo đúng luật
của ChatApplicationService (ít nhất 8, đủ mọi tin chưa tóm tắt, trần 24), summary lỡ ngẫu
nhiên, và kiểm tra không tin nào trước mốc bị bỏ sót.
"""

import random
import unittest
from unittest import mock

from agent.memory.summary import summary_context_for_turn
from api.schemas import ConversationContext, RecentMessage
from app.config import Settings


SPRING_MIN_MESSAGES = 8
SPRING_MAX_MESSAGES = 24


def spring_context(history: list[str], *, watermark: int | None, summary: str | None = "tóm tắt") -> ConversationContext:
    """Dựng conversation_context như Spring gửi (xem ChatApplicationService.contextMessageLimit)."""
    total = len(history)
    if watermark is None:
        limit = SPRING_MIN_MESSAGES
    else:
        limit = max(SPRING_MIN_MESSAGES, min(total - watermark, SPRING_MAX_MESSAGES))
    recent = history[-limit:] if limit else []
    return ConversationContext(
        memory_summary=summary,
        recent_messages=[
            RecentMessage(role="user" if index % 2 == 0 else "assistant", content=content)
            for index, content in enumerate(recent)
        ],
        total_message_count=total,
        summarized_message_count=watermark,
    )


def history_of(count: int) -> list[str]:
    return [f"tin {i}" for i in range(1, count + 1)]


def contents(summary_context) -> list[str]:
    return [message["content"] for message in summary_context["recent_messages"]]


class SummaryWatermarkTests(unittest.TestCase):
    def setUp(self):
        patcher = mock.patch("agent.memory.summary.get_settings", return_value=Settings(_env_file=None))
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_short_conversation_is_not_summarized(self):
        self.assertIsNone(summary_context_for_turn(spring_context(history_of(6), watermark=0), low_cost=False))

    def test_first_summary_sees_every_message(self):
        context = summary_context_for_turn(spring_context(history_of(8), watermark=0), low_cost=False)
        self.assertEqual(contents(context), history_of(8))

    def test_only_unsummarized_messages_are_sent(self):
        context = summary_context_for_turn(spring_context(history_of(20), watermark=18), low_cost=False)
        self.assertEqual(contents(context), ["tin 19", "tin 20"])
        self.assertEqual(context["memory_summary"], "tóm tắt")

    def test_missed_turns_are_recovered_next_time(self):
        # Mốc đứng ở 10 sau ba lượt lỡ: lượt này phải thấy cả 6 tin còn thiếu.
        context = summary_context_for_turn(spring_context(history_of(16), watermark=10), low_cost=False)
        self.assertEqual(contents(context), [f"tin {i}" for i in range(11, 17)])

    def test_low_cost_waits_for_bigger_batch(self):
        self.assertIsNone(summary_context_for_turn(spring_context(history_of(20), watermark=14), low_cost=True))
        context = summary_context_for_turn(spring_context(history_of(22), watermark=14), low_cost=True)
        self.assertEqual(contents(context), [f"tin {i}" for i in range(15, 23)])

    def test_nothing_new_means_no_summary(self):
        self.assertIsNone(summary_context_for_turn(spring_context(history_of(12), watermark=12), low_cost=False))
        # Mốc lớn hơn tổng (dữ liệu lệch) cũng không được gửi toàn bộ lịch sử.
        self.assertIsNone(summary_context_for_turn(spring_context(history_of(12), watermark=40), low_cost=False))

    def test_old_spring_without_watermark_keeps_window_of_eight(self):
        context = summary_context_for_turn(spring_context(history_of(20), watermark=None), low_cost=False)
        self.assertEqual(contents(context), [f"tin {i}" for i in range(13, 21)])

    def test_random_misses_never_lose_messages(self):
        for low_cost in (False, True):
            for seed in range(30):
                self._simulate(random.Random(seed), low_cost=low_cost)

    def _simulate(self, rng: random.Random, *, low_cost: bool) -> None:
        history: list[str] = []
        watermark = 0
        seen: set[str] = set()
        for _ in range(60):
            context = summary_context_for_turn(spring_context(history, watermark=watermark), low_cost=low_cost)
            # Lỡ ngẫu nhiên, nhưng không quá trần 24 tin tồn (giới hạn có chủ đích của Spring).
            backlog_full = len(history) - watermark >= SPRING_MAX_MESSAGES - 2
            missed = context is not None and rng.random() < 0.5 and not backlog_full
            if context is not None and not missed:
                seen.update(contents(context))
                watermark = len(history)  # Spring: markSummarizedThrough(totalMessageCount)
            history.extend([f"tin {len(history) + 1}", f"tin {len(history) + 2}"])

        missing = [message for message in history[:watermark] if message not in seen]
        self.assertEqual(missing, [], f"low_cost={low_cost}")


if __name__ == "__main__":
    unittest.main()
