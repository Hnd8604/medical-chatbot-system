# Context Compression — Rolling Summary (LLM)

> Nâng cấp M15.3 từ V1 (template rule-based) lên V2 (LLM rolling summary).
> Thay toàn bộ cơ chế memory rule-based cũ: summary do LLM sinh, câu follow-up
> do LLM intent extractor tự hiểu từ ngữ cảnh được inject vào prompt.

## Vấn đề

Trước thay đổi này:

- `memory_summary` sinh bằng template rule-based (`_memory_summary()` cũ) — chỉ là
  chuỗi ghép `"Da xem Observation/obs-1 cho Patient/..."`, không tích lũy nội dung
  nhiều lượt.
- Câu follow-up ("cái đó", "thuốc đó", "chỉ số đó"…) xử lý bằng keyword matching
  trong `chat/context_memory.py` — dễ vỡ với cách diễn đạt mới.
- `conversation_context.memory_summary` + `recent_messages` được Spring gửi sang
  chatbot-service nhưng **không được đưa vào bất kỳ prompt LLM nào**.

## Kiến trúc mới

```text
Spring (ChatApplicationService)
  ├─ ConversationContext: memory_summary, recent_messages (8), total_message_count
  ▼
chatbot-service POST /chat
  ├─ compact_conversation_for_llm() — giữ tối đa 6 message × 400 ký tự
  ├─ summary_task = asyncio.create_task(SummaryGenerator.summarize(...))   ◄ SONG SONG
  ├─ intent extractor (LLM): prompt có conversation → tự resolve follow-up
  ├─ FHIR retrieval → answer generator (LLM): prompt có conversation
  └─ _finalize_chat_response:
       ├─ await summary_task → memory_update.summary + summary_usage
       └─ usage tổng = intent + answer + router + summary
  ▼
Spring merge memory_update → chat_sessions.memory_summary (firstNonBlank)
```

### LlmSummaryGenerator (async thuần)

`agents/summary_generator.py` — `LlmSummaryGenerator`:

```text
summarize(context, question, patient_id, total_message_count)
  ├─ _should_summarize(...) == False  ──► SummaryResult(source="skipped")   (0 token)
  └─ _should_summarize(...) == True   ──► 1 lệnh gọi LLM ──► SummaryResult(source="llm")
```

- Đây là một quyết định trigger + đúng một lệnh gọi LLM, nên viết bằng `async`
  thuần — không cần graph engine. (Trước đây bọc trong LangGraph StateGraph 1 node;
  đã gỡ vì không dùng checkpointer/multi-node/cycle nào — chỉ là một câu `if`.)
- Gọi `AsyncOpenAI` qua **LiteLLM gateway** với `gateway_call_kwargs()` (virtual
  key + end-user per request — giống 3 call site LLM còn lại).
- **Persist là việc của Spring** (`chat_sessions.memory_summary`); module này không
  giữ state giữa các lượt.
- Input: `previous_summary + recent_messages + latest_question` (KHÔNG có answer
  của lượt hiện tại vì summary chạy song song với answer generation — "trễ một
  lượt", không mất thông tin vì lượt mới nhất luôn nằm trong recent_messages của
  lượt sau).

### Trigger

- Spring gửi `total_message_count` = số message của session **trước khi** lưu
  message hiện tại (đếm cùng thời điểm với `findRecentMessagesForContext`).
- Chỉ gọi LLM khi `total_message_count >= SUMMARY_TRIGGER_MESSAGE_COUNT` (mặc
  định 8 = `RECENT_CONTEXT_MESSAGE_LIMIT` phía Spring).
- Lưu ý hiện trạng: Spring đọc 8 message nhưng
  `compact_conversation_for_llm()` chỉ giữ 6 message cuối. Vì vậy ở lần summary
  đầu tiên, nếu chưa có `previous_summary`, hai message cũ nhất trong nhóm 8
  không đi vào prompt. Muốn bảo đảm không có khoảng trống phải đồng bộ hai hằng
  số này trong code.
- Dưới ngưỡng: `recent_messages` đã đủ ngữ cảnh, summary rỗng, **0 token**.

## Quyết định thiết kế

| Quyết định | Lý do |
|---|---|
| Summary chạy song song với answer (asyncio task) | Latency cộng thêm ≈ 0, giữ mục tiêu p50/p95 của đề tài |
| Cache hit KHÔNG gọi summary | Cache phải rẻ (~0 chi phí); summary rỗng → Spring giữ summary cũ |
| Lỗi summary (kể cả budget) nuốt + log warning | Summary là việc phụ; fail cả /chat để vứt answer đã sinh là tệ hơn. Lượt sau vẫn bị chặn 429 ở intent/answer |
| Giữ `_patient_id_hint` (active_patient_id) | Structural hint cho patient scoping/cache, không phải keyword logic |
| Model `gpt-4o-mini` | Alias sẵn trong gateway + pricing map 2 phía, tiếng Việt tốt, rẻ |
| Bỏ toàn bộ keyword follow-up logic | LLM extractor nhận `conversation` trong prompt và tự resolve "cái đó"/"thuốc đó" |

### Trade-off đã chấp nhận

- **Chế độ không LLM** (không có `LITELLM_MASTER_KEY`): RuleBasedIntentExtractor +
  NoopSummaryGenerator — follow-up "cái đó" degrade thành câu hỏi làm rõ, chỉ còn
  hint `active_patient_id`. Demo không LLM vẫn chạy nhưng không hiểu tham chiếu.
- **Token mỗi lượt tăng so với hiện trạng cũ** (trước đây không gửi ngữ cảnh nào
  vào prompt). Phép đo đúng của context compression là so **"gửi nguyên văn toàn bộ
  lịch sử" vs "summary + cửa sổ prompt 6 tin nhắn"** — ở hội thoại dài, rolling summary
  giữ input token gần như hằng số thay vì tăng tuyến tính.

## Cấu hình

```env
# chatbot-service/.env
ENABLE_LLM_SUMMARY=true
MODEL_SUMMARY=gpt-4o-mini
SUMMARY_TRIGGER_MESSAGE_COUNT=8
```

## Chỉ số đo (cho báo cáo)

- `summary_usage` (response + `audit_logs.metadata.summary_usage`): tách chi phí
  context compression khỏi usage tổng.
- `usage` tổng đã gồm summary → `usage_logs` và cost estimation tự đúng.
- So sánh input token/lượt theo độ dài hội thoại: baseline "full history" vs
  "rolling summary + window".
- Latency lượt có summary vs không có (kỳ vọng chênh ≈ 0 vì chạy song song).
- Độ chính xác resolve follow-up ("chỉ số đó có cao không?") trước–sau.

## File liên quan

- `chatbot-service/agents/summary_generator.py` — `LlmSummaryGenerator` (async thuần) + factory.
- `chatbot-service/agents/context_payload.py` — nén conversation cho prompt.
- `chatbot-service/chat/response_builder.py` — await summary task, `summary_usage`, `memory_update.summary`.
- `chatbot-service/api/chat_routes.py` — tạo summary task song song, inject context vào intent extractor.
- `chatbot-service/agents/intent/llm_extractor.py`, `agents/answer_generator.py` — prompt nhận `conversation`.
- `backend/.../service/ChatApplicationService.java` — gửi `total_message_count`.
- `backend/.../mapper/ChatbotResponseMapper.java` và
  `service/ChatInteractionRecorder.java` — parse/log `summary_usage`, map và persist memory.
- Tests: `tests/test_summary_generator.py`, `tests/test_chat_routes.py`, `ChatApplicationServiceTest.java`.
