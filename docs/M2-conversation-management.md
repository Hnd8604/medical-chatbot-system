# M2. Conversation Management

Quản lý phiên hội thoại (chat session) giữa user và chatbot y tế: tạo session, gửi câu hỏi, nhận câu trả lời, tiếp tục hội thoại cũ và xem danh sách hội thoại.

## M2.1 - Tạo phiên hội thoại

**Mục tiêu:** Tạo phiên chat mới khi người dùng bắt đầu cuộc trò chuyện.

**Yêu cầu:**
- Mỗi session thuộc về một user.
- Session có `title`, trạng thái, thời gian tạo/cập nhật.
- Có thể gắn `active_patient_id` nếu staff đang chọn bệnh nhân.

**Hành vi:**
- Khi `POST /api/chat` không có `session_id`, hệ thống tạo session mới (entity `ChatSession`, bảng `chat_sessions`).
- Title được sinh từ câu hỏi đầu tiên.
- `active_patient_id` được lưu vào session memory nếu có.

**Tiêu chí hoàn thành:**
- Chat mới tạo đúng một session.
- Session hiển thị được trong lịch sử hội thoại.

## M2.2 - Gửi câu hỏi

**Mục tiêu:** Nhận câu hỏi tự nhiên từ frontend và chuyển qua pipeline xử lý.

**Endpoint:** `POST /api/chat`

**Request:**
- `message` (bắt buộc, không rỗng).
- `session_id` (tùy chọn).
- `patient_id` (tùy chọn).

**Hành vi:**
- Validate `message` không rỗng.
- Kiểm tra session thuộc user hiện tại.
- Nếu không có `patient_id`, dùng `active_patient_id` trong session memory nếu phù hợp.

**Tiêu chí hoàn thành:**
- User gửi được câu hỏi mới.
- User hỏi tiếp trong session cũ vẫn giữ context.

## M2.3 - Nhận và hiển thị câu trả lời

**Mục tiêu:** Nhận câu trả lời từ chatbot-service và trả về frontend.

**Response (`ChatResponse`):**
- `answer`, `intent`, `tool_name`, `evidence`, `usage`.
- Nếu cần chọn bệnh nhân: `needs_patient_selection` và `patient_candidates`.

**Hành vi:**
- Frontend render answer, evidence, usage.
- Frontend render candidate cards khi bệnh nhân mơ hồ (nhiều bệnh nhân khớp).

**Tiêu chí hoàn thành:**
- Câu trả lời tiếng Việt hiển thị đúng.
- Evidence hiển thị được để staff kiểm chứng.

## M2.4 - Tiếp tục hội thoại cũ

**Mục tiêu:** Cho phép user mở lại một session và hỏi tiếp.

**Endpoint:** `GET /api/chat/sessions/{sessionId}/messages`

**Yêu cầu:**
- Load được messages theo session.
- Session phải thuộc user hiện tại.
- Giữ memory của session.

**Hành vi:**
- Frontend click vào lịch sử để load message.
- Khi gửi tiếp, frontend gửi kèm `session_id`.

**Tiêu chí hoàn thành:**
- Mở session cũ hiển thị đúng lịch sử.
- Câu hỏi tiếp theo dùng đúng context session.

## M2.5 - Danh sách hội thoại

**Mục tiêu:** Hiển thị danh sách các cuộc hội thoại gần đây.

**Endpoint:** `GET /api/chat/sessions`

**Thông tin hiển thị mỗi session:**
- Title.
- Thời gian cập nhật.
- Số message.
- Preview message cuối.
- Active patient (nếu session đang gắn bệnh nhân).

## Luồng chương trình

### Luồng tổng quan (gửi 1 câu hỏi)

```
Frontend (ChatPage.tsx)
   │  POST /api/chat { message, session_id?, patient_id? }
   ▼
ChatbotController.chat()
   ▼
ChatApplicationService.chat(request)
   │
   ├─ 1. requireCurrentUser()              → lấy user đang đăng nhập
   ├─ 2. quotaService.assertQuotaAvailable() → chặn nếu user hết quota
   ├─ 3. Nếu có session_id → xác minh ownership, load ChatSession + memory đã lưu
   │     Nếu không có      → giữ session/memory rỗng ở bước này
   ├─ 4. userPatientScopeService.resolve()  → xác định patient_id hiệu lực
   │     (ưu tiên patient_id trong request, fallback active_patient_id trong memory,
   │      đồng thời áp giới hạn theo quyền user — patient scope)
   ├─ 5. Nếu chưa có session → tạo mới (title = câu hỏi đầu tiên), sau đó lấy
   │     recent messages (tối đa 8) và tổng số message để build ConversationContext
   ├─ 6. Lưu user message vào chat_messages
   ├─ 7. integration/client/ChatbotServiceClient.chat(...)
   │       → gọi sang chatbot-service (Python, /chat)
   │       payload: user_id, role, session_id, message, patient_id,
   │                allowed_patient_ids, patient_scope, conversation_context
   ├─ 8. Nhận response từ chatbot-service: answer, intent, tool_name, evidence,
   │       patient_candidates / needs_patient_selection, usage, memory_update...
   ├─ 9. Lưu assistant message vào chat_messages
   ├─ 10. ChatbotResponseMapper tính memory/metadata; ChatMapper + repository
   │        cập nhật session rồi map ChatResponse
   ├─ 11. ChatInteractionRecorder.record()
   │       → ghi UsageLog (token, cost, latency) + AuditLog
   ▼
ChatResponse trả về Controller → trả về Frontend
   ▼
Frontend render answer, evidence, usage; nếu needs_patient_selection=true
   → hiển thị patient_candidates để user chọn bệnh nhân, rồi gửi lại request
     với patient_id đã chọn.
```

### Luồng tạo session mới vs. tiếp tục session cũ

- **Không có `session_id`:** `ChatApplicationService.chat()` resolve patient scope
  với memory rỗng, sau đó tạo `ChatSession` mới trước khi lưu message và gọi
  chatbot-service.
- **Có `session_id`:** `requireSessionForUser()` xác nhận session thuộc user hiện tại (nếu không → 404). Memory của session (`active_patient_id`, `last_intent`, `last_tool_name`, `last_resource_type/id`, `memory_summary`) được nạp lại và dùng làm context, đồng thời lịch sử message gần nhất (tối đa 8) được đính kèm trong `ConversationContext` gửi sang chatbot-service.

### Luồng xem lịch sử / danh sách hội thoại

```
Frontend (HistorySidebar.tsx)
   │ GET /api/chat/sessions?query=&limit=
   ▼
ChatApplicationService.sessions() → ChatSessionRepository
   → trả title, updated_at, số message, preview message cuối, active_patient_id

Khi user click vào 1 session:
   │ GET /api/chat/sessions/{sessionId}/messages
   ▼
ChatApplicationService.sessionMessages()
   → requireSessionForUser() kiểm tra quyền sở hữu
   → trả toàn bộ messages của session
   → Frontend load lại khung chat, các câu hỏi tiếp theo sẽ gửi kèm session_id này.
```

### Lưu ý nghiệp vụ trong luồng

- Mỗi lượt chat đều ghi đồng thời 2 bản ghi: `UsageLog` (chi phí/token/latency phục vụ M20 cấu hình & quản lý chi phí) và `AuditLog` (hành động truy cập dữ liệu FHIR, phục vụ truy vết/an toàn dữ liệu).
- `tool_name` trả về từ chatbot-service được map sang hành động audit cụ thể (`VIEW_OBSERVATIONS`, `VIEW_MEDICATIONS`, `SEARCH_PATIENTS`, `VIEW_FROM_CACHE`, `ASK_UNSUPPORTED`, ...).
- Toàn bộ luồng `chat()` chạy trong 1 transaction (`@Transactional`) — nếu lỗi giữa đường, user/assistant message và memory update sẽ rollback cùng nhau.

## Luồng trong code (chi tiết theo file & hàm)

### 1. Frontend — `ChatPage.tsx`

`submitMessage(message, options)` ([ChatPage.tsx](../frontend/src/pages/ChatPage.tsx)):

1. Đẩy ngay 1 message "user" + 1 message "assistant" tạm (`pending: true`, nội dung "Đang xử lý câu hỏi...") vào state `messages` để UI phản hồi tức thì.
2. Build `payload: ChatRequestBody = { session_id: currentSessionId, patient_id, message }`.
   - `patient_id` chỉ gửi khi role là `DOCTOR`/`ADMIN` (`isStaff`), lấy từ `options.patientIdOverride` hoặc `selectedPatient?.id`.
3. Gọi `apiJson<ChatResponse>("/api/chat", { method: "POST", body: JSON.stringify(payload) })`.
4. Khi có response: cập nhật `currentSessionId = response.session_id` (quan trọng cho lần gửi tiếp theo), `lastResponse`, thay nội dung message tạm bằng `response.answer`.
5. Nếu `response.patient_id` tồn tại và là staff → gọi `loadPatientProfile()` để nạp lại panel bệnh nhân bên phải.
6. Luôn refetch `loadSessions()`, `loadUsage()`, `loadNotifications()` song song để đồng bộ sidebar/quota.
7. Nếu lỗi (network/4xx/5xx) → message tạm chuyển role `"error"` hiển thị lỗi tại chỗ.

`selectPatientCandidate(candidate, pendingQuestion)` ([ChatPage.tsx](../frontend/src/pages/ChatPage.tsx)): khi `ChatResponse.needs_patient_selection = true`, UI hiển thị `patient_candidates`; user click 1 candidate → gọi lại `submitMessage(pendingQuestion, { patientIdOverride: candidate.id })`, tức gửi lại đúng câu hỏi cũ kèm `patient_id` đã chốt.

`selectSession(session)` trong
[`ChatPage.tsx`](../frontend/src/pages/ChatPage.tsx) đặt `currentSessionId`, gọi
`GET /api/chat/sessions/{id}/messages` và map response qua `mapHistoryMessage()`
vào state `messages`.

### 2. Spring Backend — nhận request, điều phối

`ChatbotController.chat()` trong
[`ChatbotController.java`](../backend/src/main/java/com/medicalchatbot/backend/controller/ChatbotController.java)
chỉ forward `ChatRequest` (đã `@Valid`) sang
`chatApplicationService.chat(request)` — không chứa logic nghiệp vụ.

`ChatApplicationService.chat()` trong
[`ChatApplicationService.java`](../backend/src/main/java/com/medicalchatbot/backend/service/ChatApplicationService.java)
là nơi điều phối chính, chạy trong `@Transactional`:

```java
User user = currentUserService.requireCurrentUser();
quotaService.assertQuotaAvailable(userId);                 // chặn nếu hết quota/ngày
if (request.sessionId() != null) {
    session = requireSessionForUser(...);                  // 404 nếu không thuộc user
    sessionMemory = session.memory();
}
PatientScope patientScope = userPatientScopeService.resolve(user, request.patientId(), sessionMemory);
if (session == null) {
    session = chatSessionRepository.create(user, titleFromMessage(request.message()));
}
List<ChatContextMessage> recentMessages = chatSessionRepository.findRecentMessagesForContext(sessionId, userId, 8);
ConversationContext conversationContext = conversationContext(scopedSessionMemory, recentMessages);
chatMessageRepository.save(session, USER, request.message(), ...);   // lưu user message trước

JsonNode chatbotResponse = chatbotServiceClient.chat(new ChatbotChatRequest(
    userId, role, sessionId, message, effectivePatientId,
    allowedPatientIds, patientScope, conversationContext));          // gọi sang Python service

chatMessageRepository.saveAndReturn(session, ASSISTANT, answer, ...); // lưu assistant message
ChatSessionMemory nextMemory = chatbotResponseMapper.nextSessionMemory(...);
chatSessionRepository.updateMemory(session, chatMapper.toDomain(nextMemory));
chatInteractionRecorder.record(...);                                  // usage + audit
return chatbotResponseMapper.toResponse(...);                          // API DTO
```

[`integration/client/ChatbotServiceClient`](../backend/src/main/java/com/medicalchatbot/backend/integration/client/ChatbotServiceClient.java)
thực hiện `POST {chatbot-service}/chat` qua `RestClient`, body là
`ChatbotChatRequest`; đây là outbound HTTP adapter, không chứa nghiệp vụ.

`ChatbotResponseMapper.nextSessionMemory()` trong
[`ChatbotResponseMapper.java`](../backend/src/main/java/com/medicalchatbot/backend/mapper/ChatbotResponseMapper.java)
đọc `memory_update` để tính `active_patient_id`, `last_intent`, `last_tool_name`,
`last_resource_type/id`, `memory_summary` mới. `ChatMapper` chuyển giá trị này
thành domain model trước khi repository cập nhật session.

### 3. Chatbot-service (Python/FastAPI) — xử lý ngôn ngữ tự nhiên & truy vấn FHIR

Endpoint `POST /chat` trong `chat_routes.py` ([chat_routes.py](../chatbot-service/api/chat_routes.py)):

1. **Cache check** — `get_cached_chat_payload(request, cache_service)` ([chat_routes.py](../chatbot-service/api/chat_routes.py)): nếu câu hỏi khớp semantic cache, trả ngay kết quả đã cache (không gọi LLM/FHIR).
2. **Context + intent extraction** — `_patient_id_hint()` lấy patient từ request
   hoặc session memory; `compact_conversation_for_llm()` nén lịch sử rồi
   `intent_extractor.extract(...)` tạo `plan` gồm `tool_name`, `patient_id`,
   `observation_type`, `all_patients`, `limit`. LLM extractor đọc context để hiểu
   câu nối tiếp; rule fallback chỉ dùng patient hint.
3. **Áp policy theo role**:
   - `_apply_user_patient_scope()` ([chat_routes.py](../chatbot-service/api/chat_routes.py)): nếu `user_role == "USER"`, ép `patient_id` phải thuộc `allowed_patient_ids` của chính user đó, cấm `search_patients`/`all_patients`.
   - `_ensure_role_can_access_plan()` ([chat_routes.py](../chatbot-service/api/chat_routes.py)): chốt lại, ném `403` nếu vượt quyền.
4. **Model routing** — `model_router.route(message, quota_used_ratio)` chạy song
   song với intent extraction và chọn model theo độ phức tạp/quota.
5. **Dispatch theo `tool_name`** ([chat_routes.py](../chatbot-service/api/chat_routes.py)): mỗi nhánh gọi đúng resource answerer tương ứng (`_answer_patients`, `_answer_medications`, `_answer_observations`, `_answer_conditions`, `_answer_encounters`, `_answer_patient`), các answerer này gọi `FhirClient` để lấy dữ liệu FHIR thật.
   - Nếu thiếu `patient_id` cụ thể và có nhiều khả năng khớp → `_resolve_patient_id_for_tool()` trả về dict chứa `needs_patient_selection=true` + `patient_candidates` thay vì gọi FHIR.
6. **Sinh câu trả lời** — `_finalize_chat_response(...)` đưa evidence FHIR đã
   normalize qua `AnswerGenerator`, gắn `evidence`, cộng usage của các stage và
   build `memory_update` để Spring lưu vào session memory.
7. Nếu `FhirClientError` → trả `502` cho Spring backend (Spring sẽ propagate lỗi lên frontend).

### Tóm tắt trách nhiệm theo tầng

| Tầng | Trách nhiệm |
|---|---|
| Frontend (`ChatPage.tsx`) | UI state, optimistic update, gửi `session_id`/`patient_id`, hiển thị candidate khi mơ hồ |
| Spring Backend (`ChatApplicationService`) | Điều phối auth/quota/session/patient scope; mapper xử lý response/memory và recorder ghi usage/audit |
| Chatbot-service (Python) | Hiểu ý định câu hỏi (intent extraction), áp policy truy cập FHIR theo role, gọi FHIR, sinh câu trả lời bằng LLM, semantic cache |

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| API endpoints | `backend/src/main/java/com/medicalchatbot/backend/controller/ChatbotController.java` |
| Nghiệp vụ chính | `backend/src/main/java/com/medicalchatbot/backend/service/ChatApplicationService.java` |
| Gọi chatbot-service | `backend/src/main/java/com/medicalchatbot/backend/integration/client/ChatbotServiceClient.java` |
| Map response/memory | `backend/src/main/java/com/medicalchatbot/backend/mapper/ChatbotResponseMapper.java`, `mapper/ChatMapper.java` |
| Ghi usage/audit | `backend/src/main/java/com/medicalchatbot/backend/service/ChatInteractionRecorder.java` |
| Entity session | `backend/src/main/java/com/medicalchatbot/backend/entity/ChatSession.java` |
| Entity message | `backend/src/main/java/com/medicalchatbot/backend/entity/ChatMessage.java` |
| Repository session | `backend/src/main/java/com/medicalchatbot/backend/repository/ChatSessionRepository.java` |
| Repository message | `backend/src/main/java/com/medicalchatbot/backend/repository/ChatMessageRepository.java` |
| Session memory | `backend/src/main/resources/db/migration/V1__baseline_schema_and_seed.sql` (memory fields trên `chat_sessions`) |
| Frontend chat page | `frontend/src/pages/ChatPage.tsx` |
| Frontend history sidebar | `frontend/src/components/chat/HistorySidebar.tsx` |
