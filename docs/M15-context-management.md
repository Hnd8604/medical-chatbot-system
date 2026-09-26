# M15. Context Management

Quản lý ngữ cảnh hội thoại để chatbot trả lời được các câu hỏi nối tiếp mà không phải gửi toàn bộ lịch sử vào LLM: gồm recent messages context, session memory, tóm tắt hội thoại dài và token budget guard. Module này là phần "trí nhớ" phía sau [M2](M2-conversation-management.md) và [M3](M3-message-history.md).

## M15.1 - Recent messages context

**Mục tiêu:** Gửi một số message gần nhất để giữ ngữ cảnh ngắn hạn.

**Hành vi:**
- Limit cố định: `RECENT_CONTEXT_MESSAGE_LIMIT = 8` trong `ChatApplicationService`.
- `findRecentMessagesForContext()` lấy 8 message mới nhất rồi **sắp xếp lại ASC** (subquery `order by created_at desc limit :limit`, ngoài cùng `order by createdAt asc`) để LLM đọc đúng trình tự thời gian.
- Spring đóng gói tối đa 8 message vào `ConversationContext.recentMessages`;
  `chatbot-service/agents/context_payload.py` tiếp tục compact còn tối đa 6 message,
  mỗi nội dung tối đa 400 ký tự, trước khi đưa vào prompt.

**Tiêu chí hoàn thành:** Chatbot hiểu câu hỏi nối tiếp ngắn (vd "còn thuốc thì sao?").

## M15.2 - Session memory

**Mục tiêu:** Lưu memory nhẹ cho phiên chat (không cần đọc lại toàn bộ message).

**Các trường memory** (cột trên bảng `chat_sessions`, ánh xạ qua domain value
object `ChatSessionMemoryState` và response DTO `ChatSessionMemory`):
- `active_patient_id`, `memory_summary`, `last_intent`, `last_tool_name`, `last_resource_type`, `last_resource_id`.

**Hành vi:**
- Cập nhật sau **mỗi** lượt chat qua
  `ChatbotResponseMapper.nextSessionMemory()` → `ChatMapper.toDomain()` →
  `chatSessionRepository.updateMemory()`.
- **Không overwrite active patient khi hỏi all-patients**: `nextSessionMemory()` chỉ cập nhật `active_patient_id`/`last_resource_*` khi `concretePatientResponse = !allPatients && !needsPatientSelection`.

**Tiêu chí hoàn thành:** Refresh trang / mở session cũ vẫn hỏi tiếp được (memory được nạp lại từ DB).

## M15.3 - Long conversation summary

**Mục tiêu:** Tóm tắt hội thoại để tiết kiệm token, không cần gửi full history.

**Hành vi (V2 - LLM rolling summary, thay thế hoàn toàn V1 rule-based):**
- Chatbot-service sinh `memory_summary` bằng **LLM rolling summary**
  (`agents/summary_generator.py`): summary cũ + tối đa 6 message sau compact +
  câu hỏi mới → summary mới ≤120 từ tiếng Việt.
- Mặc định chỉ trigger khi `total_message_count >= 8` (Spring đếm trước khi lưu
  câu hỏi hiện tại); chạy **song song** với answer generation nên không cộng tuần
  tự toàn bộ latency. Do Spring gửi 8 message nhưng prompt chỉ giữ 6, lần summary
  đầu tiên có thể chưa bao phủ hai message cũ nhất; xem phần giới hạn hiện tại
  trong [M-context-rolling-summary.md](M-context-rolling-summary.md).
- Summary được trả về trong `memory_update.summary` kèm `summary_usage`, Spring lưu vào `chat_sessions.memory_summary` và gửi lại ở lượt sau qua `ConversationContext.memorySummary`.
- `memory_summary` + `recent_messages` được inject vào prompt của intent extractor và answer generator — câu follow-up ("cái đó", "thuốc đó") do LLM tự resolve, keyword matching cũ đã gỡ bỏ.
- Chi tiết: [M-context-rolling-summary.md](M-context-rolling-summary.md).

**Tiêu chí hoàn thành:** Không cần gửi full history vào LLM.

## M15.4 - Token budget guard

**Mục tiêu:** Tránh prompt vượt token limit.

**Hành vi:**
- Giới hạn **recent messages** ở 8 tại Spring và 6 trong prompt Python; mỗi
  message trong prompt bị cắt ở 400 ký tự.
- Giới hạn **evidence**: `evidenceRefs()` chỉ giữ `{resource_type, resource_id, summary}` thay vì raw bundle.
- `memory_summary` thay cho việc nhồi toàn bộ lịch sử.
- Model routing (M16) chọn model theo độ phức tạp, tránh lãng phí context cho câu hỏi đơn giản.

**Tiêu chí hoàn thành:** Không lỗi do prompt quá lớn trong các tình huống demo thường gặp.

## Luồng chương trình

```
Mỗi lượt /api/chat (ChatApplicationService.chat):
   ┌─ ĐỌC context (gửi sang chatbot-service)
   │   session.memory() → ChatSessionMemory
   │        active_patient_id, memory_summary, last_intent,
   │        last_tool_name, last_resource_type/id
   │   findRecentMessagesForContext(sessionId, userId, 8)  (ASC)
   │        ▼
   │   ConversationContext { memorySummary, activePatientId, lastIntent,
   │                         lastToolName, lastResourceType/Id, recentMessages }
   │        → ChatbotServiceClient.chat(...) → compact_conversation_for_llm(max 6)
   │
   └─ GHI context (sau khi chatbot trả lời)
       chatbotResponse.memory_update { summary, active_patient_id,
                                       last_intent, last_tool_name,
                                       last_resource_type/id, evidence_refs }
            ▼
       ChatbotResponseMapper.nextSessionMemory(current, effectivePatientId, response)
            │  nếu all_patients hoặc needs_patient_selection
            │      → GIỮ NGUYÊN active_patient_id & last_resource_*
            │  ngược lại → cập nhật từ memory_update / evidence
            ▼
       chatSessionRepository.updateMemory(session, nextMemory)  → lưu vào chat_sessions
```

## Luồng trong code

- **Recent messages:** `ChatSessionRepository.findRecentMessagesForContext()` trả
  `ChatContextMessageProjection`; `ChatMapper` chuyển sang request DTO. Hằng số
  limit nằm trong `ChatApplicationService`.
- **Đóng gói context gửi đi:** `ChatApplicationService.conversationContext()`.
- **Tính memory mới:** `ChatbotResponseMapper.nextSessionMemory()` giữ active
  patient khi response là all-patients hoặc đang chờ chọn bệnh nhân.
- **Đọc/ghi memory:** `ChatSession.memory()` / `applyMemory()` dùng
  `domain/model/ChatSessionMemoryState`; persistence qua repository `updateMemory()`.
- **Sinh summary phía Python:** `agents/summary_generator.py`; `response_builder.py`
  chờ task, cộng usage và đưa summary vào `memory_update`.
- **Dùng context:** `chat_routes.py` lấy structural patient hint qua
  `_patient_id_hint()` và truyền context đã compact vào `LLMIntentExtractor` /
  `LLMAnswerGenerator`. `RuleBasedIntentExtractor` không diễn giải hội thoại.

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| Điều phối đọc/ghi context | `backend/.../service/ChatApplicationService.java` |
| Query recent messages | `backend/.../repository/ChatSessionRepository.java` |
| Entity + trường memory | `backend/.../entity/ChatSession.java` |
| Domain memory | `backend/.../domain/model/ChatSessionMemoryState.java` |
| Mapper context/memory | `backend/.../mapper/ChatMapper.java`, `.../mapper/ChatbotResponseMapper.java` |
| Projection recent messages | `backend/.../repository/projection/ChatContextMessageProjection.java` |
| DTO memory | `backend/.../dto/response/ChatSessionMemory.java` |
| DTO context gửi đi | `backend/.../dto/request/ConversationContext.java` |
| Migration session memory | `backend/.../db/migration/V1__baseline_schema_and_seed.sql` (memory fields trên `chat_sessions`) |
| Sinh summary + memory_update | `chatbot-service/agents/summary_generator.py`, `chatbot-service/chat/response_builder.py` |
| Compact/patient hint | `chatbot-service/agents/context_payload.py`, `chatbot-service/chat/context_memory.py`, `chatbot-service/api/chat_routes.py` |
