# M3. Message History

Lưu trữ và truy xuất toàn bộ tin nhắn (user / assistant / system) của các phiên hội thoại, đảm bảo đúng thứ tự, đúng quyền sở hữu và có chính sách cho dữ liệu y tế nhạy cảm. Module này là tầng lưu trữ phía sau [M2 - Conversation Management](M2-conversation-management.md).

## M3.1 - Lưu user message

**Mục tiêu:** Lưu đầy đủ câu hỏi của người dùng.

**Yêu cầu:**
- Message có role `user`, gắn với một session, có thời gian tạo và metadata.

**Hành vi:**
- Entity `ChatMessage` (bảng `chat_messages`); role lưu dạng chuỗi qua `ChatMessageRole.databaseValue()`.
- User message được lưu **trước** khi gọi chatbot-service (xem `ChatApplicationService.chat()` bước 6).
- Metadata (`metadata_json`, kiểu JSONB) lưu `request_patient_id` và `effective_patient_id` qua `userMessageMetadata()`.

**Tiêu chí hoàn thành:** Database có đủ user message sau mỗi lượt chat.

## M3.2 - Lưu assistant message

**Mục tiêu:** Lưu câu trả lời của chatbot.

**Yêu cầu:**
- Message có role `assistant`, cùng session; metadata lưu intent / tool / evidence refs / memory update.

**Hành vi:**
- Lưu answer sau khi chatbot-service trả về, qua `chatMessageRepository.saveAndReturn()`.
- `assistantMessageMetadata()` ghi `intent`, `tool_name`, `patient_id`, `answer_source`, `evidence_refs`, `memory_update` vào JSONB.
- Chỉ lưu **evidence refs** (resource_type / resource_id / summary), **không** lưu raw FHIR bundle để tránh phình dữ liệu.

**Tiêu chí hoàn thành:** Mở lại session thấy đủ user và assistant messages.

## M3.3 - System message và metadata

**Mục tiêu:** Hỗ trợ message hệ thống để sau này thông báo trạng thái/lỗi.

**Hành vi:**
- Enum `ChatMessageRole` hỗ trợ giá trị `system` bên cạnh `user`/`assistant`.
- `metadata_json` là JSONB nên lưu được structured JSON tùy ý.
- Hiện chưa lạm dụng system message cho log kỹ thuật (log kỹ thuật đi qua `AuditLog`/`UsageLog`).

**Tiêu chí hoàn thành:** Có thể lưu thông báo hệ thống trong hội thoại mà không phá UI.

## M3.4 - Truy xuất lịch sử đúng thứ tự

**Mục tiêu:** Trả message theo thứ tự thời gian.

**Hành vi:**
- `ChatSessionRepository.findMessagesForSession(sessionId, userId)` query native `where s.id = :sessionId and s.user_id = :userId order by m.created_at asc`.
- Ràng buộc `user_id` trong câu query đảm bảo **không đọc được session của user khác** (cùng cơ chế với `requireSessionForUser()` trả 404).
- Query này còn lấy kèm feedback gần nhất của mỗi message (subquery vào `message_feedback`).

**Tiêu chí hoàn thành:** UI render đúng thứ tự chat (frontend map qua `mapHistoryMessage()`).

## M3.5 - Retention và dữ liệu nhạy cảm

**Mục tiêu:** Quy tắc lưu/xóa message chứa dữ liệu y tế.

**Hành vi hiện tại:**
- Metadata **không** lưu raw medical record — chỉ giữ evidence refs (loại + id + tóm tắt ngắn).
- Mọi thao tác `EXPORT_CONVERSATION` đều ghi `AuditLog` (xem [M23](M21-M23-M25-utilities.md)).
- Truy xuất luôn ràng buộc theo `user_id` (cô lập dữ liệu giữa các user).

**Tiêu chí hoàn thành:** Có chính sách rõ ràng cho dữ liệu hội thoại chứa thông tin y tế.

## Luồng chương trình

```
Ghi (trong mỗi lượt /api/chat — ChatApplicationService.chat, @Transactional):
   lưu USER message (trước khi gọi chatbot-service)
        │  metadata: request_patient_id, effective_patient_id
        ▼
   gọi chatbot-service → nhận answer + intent/tool/evidence/memory_update
        ▼
   lưu ASSISTANT message
        │  metadata: intent, tool_name, patient_id, answer_source,
        │            evidence_refs, memory_update
        ▼
   (chỉ lưu evidence refs, KHÔNG lưu raw FHIR bundle)

Đọc (mở lại session — GET /api/chat/sessions/{id}/messages):
   requireSessionForUser(sessionId, userId)   → 404 nếu không thuộc user
        ▼
   findMessagesForSession(sessionId, userId)  → ORDER BY created_at ASC
        │  + lấy feedback gần nhất mỗi message
        ▼
   ChatMessagesResponse → frontend map qua mapHistoryMessage()
```

## Luồng trong code

- **Ghi message:** `ChatApplicationService.chat()` gọi `ChatMessageRepository.save`
  cho USER rồi `saveAndReturn` cho ASSISTANT. Hai helper đều gọi `session.touch()`.
- **Metadata builders:** `ChatbotResponseMapper.userMessageMetadata()`,
  `assistantMessageMetadata()` và `evidenceRefs()` rút gọn evidence thành
  `{resource_type, resource_id, summary}` thay vì lưu raw FHIR bundle.
- **Entity & repository:** [ChatMessage.java](../backend/src/main/java/com/medicalchatbot/backend/entity/ChatMessage.java),
  [ChatMessageRepository.java](../backend/src/main/java/com/medicalchatbot/backend/repository/ChatMessageRepository.java).
- **Query đọc:** `ChatSessionRepository.findMessageItemViewsForSession()` trả
  `ChatMessageProjection`; `ChatMapper.toMessageItems()` chuyển projection thành API DTO.
- **Frontend render:** `mapHistoryMessage()` và `selectSession()` trong
  [ChatPage.tsx](../frontend/src/pages/ChatPage.tsx).

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| Entity message | `backend/.../entity/ChatMessage.java` |
| Enum role (user/assistant/system) | `backend/.../enums/ChatMessageRole.java` |
| Repository message | `backend/.../repository/ChatMessageRepository.java` |
| Query đọc + sắp xếp | `backend/.../repository/ChatSessionRepository.java` |
| Projection + mapper | `backend/.../repository/projection/ChatMessageProjection.java`, `.../mapper/ChatMapper.java` |
| Ghi message trong luồng chat | `backend/.../service/ChatApplicationService.java` |
| Metadata message | `backend/.../mapper/ChatbotResponseMapper.java` |
| Frontend render lịch sử | `frontend/src/pages/ChatPage.tsx` |
