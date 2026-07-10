# M15. Context Management

Quản lý ngữ cảnh hội thoại để chatbot trả lời được các câu hỏi nối tiếp mà không phải gửi toàn bộ lịch sử vào LLM: gồm recent messages context, session memory, tóm tắt hội thoại dài và token budget guard. Module này là phần "trí nhớ" phía sau [M2](M2-conversation-management.md) và [M3](M3-message-history.md).

## M15.1 - Recent messages context

**Mục tiêu:** Gửi một số message gần nhất để giữ ngữ cảnh ngắn hạn.

**Hành vi:**
- Limit cố định: `RECENT_CONTEXT_MESSAGE_LIMIT = 8` trong `ChatApplicationService`.
- `findRecentMessagesForContext()` lấy 8 message mới nhất rồi **sắp xếp lại ASC** (subquery `order by created_at desc limit :limit`, ngoài cùng `order by createdAt asc`) để LLM đọc đúng trình tự thời gian.
- Không gửi toàn bộ lịch sử — chỉ phần gần nhất, đóng gói vào `ConversationContext.recentMessages`.

**Tiêu chí hoàn thành:** Chatbot hiểu câu hỏi nối tiếp ngắn (vd "còn thuốc thì sao?").

## M15.2 - Session memory

**Mục tiêu:** Lưu memory nhẹ cho phiên chat (không cần đọc lại toàn bộ message).

**Các trường memory** (cột trên bảng `chat_sessions`, ánh xạ qua `ChatSessionMemory`):
- `active_patient_id`, `memory_summary`, `last_intent`, `last_tool_name`, `last_resource_type`, `last_resource_id`.

**Hành vi:**
- Cập nhật sau **mỗi** lượt chat qua `nextSessionMemory()` → `chatSessionRepository.updateMemory()`.
- **Không overwrite active patient khi hỏi all-patients**: `nextSessionMemory()` chỉ cập nhật `active_patient_id`/`last_resource_*` khi `concretePatientResponse = !allPatients && !needsPatientSelection`.

**Tiêu chí hoàn thành:** Refresh trang / mở session cũ vẫn hỏi tiếp được (memory được nạp lại từ DB).

## M15.3 - Long conversation summary

**Mục tiêu:** Tóm tắt hội thoại để tiết kiệm token, không cần gửi full history.

**Hành vi (V2 - LLM rolling summary, thay thế hoàn toàn V1 rule-based):**
- Chatbot-service sinh `memory_summary` bằng **LLM rolling summary trên LangGraph** (`agents/summary_generator.py`): summary cũ + 6 recent messages + câu hỏi mới → summary mới ≤120 từ tiếng Việt.
- Chỉ trigger khi `total_message_count >= 6` (hội thoại vượt cửa sổ recent); chạy **song song** với answer generation nên latency cộng thêm ≈ 0.
- Summary được trả về trong `memory_update.summary` kèm `summary_usage`, Spring lưu vào `chat_sessions.memory_summary` và gửi lại ở lượt sau qua `ConversationContext.memorySummary`.
- `memory_summary` + `recent_messages` được inject vào prompt của intent extractor và answer generator — câu follow-up ("cái đó", "thuốc đó") do LLM tự resolve, keyword matching cũ đã gỡ bỏ.
- Chi tiết: [M-context-rolling-summary.md](M-context-rolling-summary.md).

**Tiêu chí hoàn thành:** Không cần gửi full history vào LLM.

## M15.4 - Token budget guard

**Mục tiêu:** Tránh prompt vượt token limit.

**Hành vi:**
- Giới hạn **recent messages** ở 6 (M15.1) — chặn trên cho phần history.
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
   │        → ChatbotServiceClient.chat(...)
   │
   └─ GHI context (sau khi chatbot trả lời)
       chatbotResponse.memory_update { summary, active_patient_id,
                                       last_intent, last_tool_name,
                                       last_resource_type/id, evidence_refs }
            ▼
       nextSessionMemory(current, effectivePatientId, chatbotResponse)
            │  nếu all_patients hoặc needs_patient_selection
            │      → GIỮ NGUYÊN active_patient_id & last_resource_*
            │  ngược lại → cập nhật từ memory_update / evidence
            ▼
       chatSessionRepository.updateMemory(session, nextMemory)  → lưu vào chat_sessions
```

## Luồng trong code

- **Recent messages:** `findRecentMessagesForContext()` / native query `findRecentMessageViewsForContext()` ([ChatSessionRepository.java:44-73](spring-backend/src/main/java/com/medicalchatbot/backend/repository/ChatSessionRepository.java#L44-L73)); hằng số limit ([ChatApplicationService.java:40](spring-backend/src/main/java/com/medicalchatbot/backend/service/ChatApplicationService.java#L40)).
- **Đóng gói context gửi đi:** `conversationContext()` ([ChatApplicationService.java:322-335](spring-backend/src/main/java/com/medicalchatbot/backend/service/ChatApplicationService.java#L322-L335)).
- **Tính memory mới (giữ active patient khi all-patients):** `nextSessionMemory()` ([ChatApplicationService.java:377-420](spring-backend/src/main/java/com/medicalchatbot/backend/service/ChatApplicationService.java#L377-L420)).
- **Đọc/ghi memory trên entity:** `ChatSession.memory()` / `applyMemory()` ([ChatSession.java:76-95](spring-backend/src/main/java/com/medicalchatbot/backend/entity/ChatSession.java#L76-L95)); persistence qua `updateMemory()` ([ChatSessionRepository.java:39-42](spring-backend/src/main/java/com/medicalchatbot/backend/repository/ChatSessionRepository.java#L39-L42)).
- **Sinh summary (rule-based) phía Python:** `_memory_summary()` và `_build_memory_update()` ([response_builder.py:141-159](chatbot-service/chat/response_builder.py#L141-L159)).
- **Dùng context để suy ra bệnh nhân khi câu hỏi mơ hồ:** `_apply_selected_patient_context()` / `_apply_context_reference_context()` trong [chat_routes.py](chatbot-service/api/chat_routes.py#L187-L190).

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| Điều phối đọc/ghi context | `spring-backend/.../service/ChatApplicationService.java` |
| Query recent messages | `spring-backend/.../repository/ChatSessionRepository.java` |
| Entity + trường memory | `spring-backend/.../entity/ChatSession.java` |
| DTO memory | `spring-backend/.../dto/response/ChatSessionMemory.java` |
| DTO context gửi đi | `spring-backend/.../dto/request/ConversationContext.java` |
| Migration session memory | `spring-backend/.../db/migration/V1__baseline_schema_and_seed.sql` (memory fields trên `chat_sessions`) |
| Sinh summary + memory_update | `chatbot-service/chat/response_builder.py` |
| Áp context suy luận bệnh nhân | `chatbot-service/chat/context_memory.py`, `chatbot-service/api/chat_routes.py` |
