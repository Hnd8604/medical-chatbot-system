# M20 / M22 — System Configuration & Feedback

- **M20 — System Configuration:** quản lý cấu hình LLM / FHIR / quota-cache-retry (chủ yếu qua env, có admin config cho quota & pricing).
- **M22 — Feedback:** cho người dùng đánh giá câu trả lời và lưu để cải thiện hệ thống.

---

# M20. System Configuration

## M20.1 - LLM configuration

**Mục tiêu:** Quản lý cấu hình LLM.

**Hành vi:**
- Cấu hình qua env trong `app/config.py`: `llm_provider`, `model_simple`,
  `model_complex`, `litellm_master_key`, `litellm_base_url`,
  `llm_request_timeout_seconds`, `enable_llm_answer`.
- Đổi model/provider không cần sửa logic nghiệp vụ (router + generator đọc từ settings — [M16](M16-M17-model-routing-retry-fallback.md), [M6](M6-ai-integration.md)).

**Tiêu chí hoàn thành:** Đổi model mà không sửa logic nghiệp vụ.

## M20.2 - FHIR configuration

**Mục tiêu:** Quản lý cấu hình FHIR server.

**Hành vi:**
- Env `fhir_base_url` (mặc định `http://localhost:8080/fhir`), `fhir_request_timeout_seconds` (mặc định 20s).
- Health check `GET /fhir/status` (proxy `GET /api/chatbot/status`) báo rõ trạng thái FHIR.

**Tiêu chí hoàn thành:** Chatbot-service báo rõ trạng thái FHIR.

## M20.3 - Quota/cache/retry configuration

**Mục tiêu:** Quản lý cấu hình vận hành.

**Hành vi:**
- **Cache** (env, `app/config.py`): `cache_ttl_seconds` (300), `cache_similarity_threshold` (0.94), `qdrant_url`, `cache_embedding_model`, `cache_vector_size` ([M14](M14-cache-management.md)).
- **Quota**: lưu trong DB `quota_policies` (gắn cho user); quản trị qua `QuotaPolicyAdminController` / `QuotaPolicyAdminService` ([M9](M9-quota-management.md)).
- **Rate limit**: window 60s + limit theo policy/user (schema baseline V1,
  [M10](M10-rate-limiting.md)).
- **Pricing**: DB `model_pricing` + admin API ([M8](M8-cost-management.md)).
- `frontend/src/pages/AdminConfigPage.tsx` cung cấp UI quản trị quota policy và
  model pricing.

**Tiêu chí hoàn thành:** Sai config được phát hiện sớm (pydantic `Settings` + Hibernate `validate` lúc startup — [M12](M12-database-design.md)).

---

# M22. Feedback

## M22.1 - Feedback UI

**Mục tiêu:** Cho người dùng đánh giá câu trả lời.

**Hành vi:**
- Bộ chọn 1–5 sao, comment tùy chọn và thao tác xóa trên mỗi assistant message.
- API `POST` để tạo, `PUT` để sửa và `DELETE` để xóa tại
  `/api/chat/messages/{messageId}/feedback`.
- Frontend `ChatPage.submitFeedback()` chọn `POST` hay `PUT` theo state hiện tại;
  `deleteFeedback()` xóa và cập nhật message tại chỗ.

**Tiêu chí hoàn thành:** Feedback gắn đúng message.

## M22.2 - Feedback storage

**Mục tiêu:** Lưu feedback để cải thiện hệ thống.

**Hành vi:**
- Bảng `message_feedback` (baseline V1), entity `MessageFeedback`: message id,
  user id, rating, comment, created_at; V8 bổ sung unique index cho mỗi cặp
  `(message_id, user_id)`.
- `FeedbackService.createFeedback()` chỉ tạo mới; nếu cặp
  `(message_id, user_id)` đã tồn tại thì trả `409 CONFLICT`.
- `updateFeedback()` và `deleteFeedback()` chỉ thao tác feedback thuộc user hiện
  tại; message phải là assistant message trong session của chính user.

**Tiêu chí hoàn thành:** Feedback được trả lại cùng lịch sử message qua
`findMessagesForSession` ([M3](M3-message-history.md)).

## M22.3 - Feedback analytics

**Mục tiêu:** Đo chất lượng câu trả lời.

**Hành vi hiện tại:**
- Feedback gắn message → message có `intent`/`tool_name` trong `metadata_json` → là cơ sở để group tỷ lệ helpful theo intent/tool.
- Báo cáo admin (tỷ lệ helpful, câu bị dislike nhiều) là hướng mở rộng trên dữ liệu `message_feedback` + metadata.

**Tiêu chí hoàn thành:** Có cơ sở ưu tiên cải thiện chatbot.

## Luồng chương trình

```
M20 config:
   chatbot-service: pydantic Settings (env/.env) → get_settings() (lru_cache)
        LLM / FHIR / cache config
   backend: quota_policies, model_pricing (DB) + admin API
        Hibernate ddl-auto=validate + pydantic validation → fail-fast khi sai config

M22 feedback:
   Assistant message (UI nút đánh giá)
        │ chưa có feedback: POST { rating, comment? }
        │ đã có feedback:  PUT  { rating, comment? }
        │ xóa feedback:    DELETE
        ▼
   FeedbackService
        createFeedback() → xác minh assistant message thuộc user → insert
        updateFeedback() → tìm feedback được phép sửa → update
        deleteFeedback() → tìm feedback được phép xóa → delete
        ▼
   message_feedback (rating, comment, created_at)
        ▼
   hiển thị lại trong lịch sử qua findMessagesForSession() (subquery feedback mới nhất)
```

## Luồng trong code

- **Config (Python):** [config.py](../chatbot-service/app/config.py) — `Settings` + `get_settings()`.
- **Config admin (Spring):** `QuotaPolicyAdminService`, `ModelPricingAdminService`.
- **Feedback API:** `ChatbotController.createFeedback()` / `updateFeedback()` /
  `deleteFeedback()` ([ChatbotController.java](../backend/src/main/java/com/medicalchatbot/backend/controller/ChatbotController.java)).
- **Feedback service:** [FeedbackService.java](../backend/src/main/java/com/medicalchatbot/backend/service/FeedbackService.java).
- **Feedback UI:** `ChatPage.submitFeedback()` / `deleteFeedback()`
  ([ChatPage.tsx](../frontend/src/pages/ChatPage.tsx)).

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| Config chatbot-service | `chatbot-service/app/config.py` |
| Quota/pricing admin (Spring) | `backend/.../service/{QuotaPolicyAdminService,ModelPricingAdminService}.java` |
| Feedback service | `backend/.../service/FeedbackService.java` |
| Feedback entity/migration | `backend/.../entity/MessageFeedback.java`, baseline V1 + unique constraint V8 |
| Feedback UI | `frontend/src/pages/ChatPage.tsx` |
