# M20 / M22 — System Configuration & Feedback

- **M20 — System Configuration:** quản lý cấu hình LLM / FHIR / quota-cache-retry (chủ yếu qua env, có admin config cho quota & pricing).
- **M22 — Feedback:** cho người dùng đánh giá câu trả lời và lưu để cải thiện hệ thống.

---

# M20. System Configuration

## M20.1 - LLM configuration

**Mục tiêu:** Quản lý cấu hình LLM.

**Hành vi:**
- Cấu hình qua env trong `app/config.py`: `llm_provider`, `model_simple`, `model_complex`, `openai_api_key`, `openai_base_url`, `llm_request_timeout_seconds`, `enable_llm_answer`.
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
- **Rate limit**: window 60s + limit theo policy/user (`V6`, [M10](M10-rate-limiting.md)).
- **Pricing**: DB `model_pricing` + admin API ([M8](M8-cost-management.md)).
- Có admin config UI (commit `feat: add configuration system UI`) cho quota & pricing.

**Tiêu chí hoàn thành:** Sai config được phát hiện sớm (pydantic `Settings` + Hibernate `validate` lúc startup — [M12](M12-database-design.md)).

---

# M22. Feedback

## M22.1 - Feedback UI

**Mục tiêu:** Cho người dùng đánh giá câu trả lời.

**Hành vi:**
- Nút feedback (useful/not useful + comment tùy chọn) trên mỗi assistant message.
- API `POST /api/chat/messages/{messageId}/feedback` (body: `rating`, `comment?`).
- Frontend `ChatPage.submitFeedback()` gọi API rồi cập nhật state message tại chỗ.

**Tiêu chí hoàn thành:** Feedback gắn đúng message.

## M22.2 - Feedback storage

**Mục tiêu:** Lưu feedback để cải thiện hệ thống.

**Hành vi:**
- Bảng `message_feedback` (`V9`), entity `MessageFeedback`: message id, user id, rating, comment, created_at.
- `FeedbackService.submitFeedback()`: nếu user đã feedback message đó → **update** (upsert theo `message_id + user_id`), ngược lại tạo mới.
- Kiểm tra message tồn tại (404 nếu không) và gắn user hiện tại.

**Tiêu chí hoàn thành:** Admin xem được feedback (join vào message — đã hiển thị trong lịch sử qua `findMessagesForSession`, [M3](M3-message-history.md)).

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
   spring-backend: quota_policies, model_pricing (DB) + admin API
        Hibernate ddl-auto=validate + pydantic validation → fail-fast khi sai config

M22 feedback:
   Assistant message (UI nút đánh giá)
        │ POST /api/chat/messages/{messageId}/feedback { rating, comment? }
        ▼
   FeedbackService.submitFeedback()
        findById(messageId)            → 404 nếu không có
        requireCurrentUser()
        findByMessage_IdAndUser_Id     → có: update() | không: save() (upsert)
        ▼
   message_feedback (rating, comment, created_at)
        ▼
   hiển thị lại trong lịch sử qua findMessagesForSession() (subquery feedback mới nhất)
```

## Luồng trong code

- **Config (Python):** [config.py](chatbot-service/app/config.py) — `Settings` + `get_settings()`.
- **Config admin (Spring):** `QuotaPolicyAdminService`, `ModelPricingAdminService`.
- **Feedback API:** `ChatbotController.submitFeedback()` ([ChatbotController.java:136-142](spring-backend/src/main/java/com/medicalchatbot/backend/controller/ChatbotController.java#L136-L142)).
- **Feedback service (upsert):** [FeedbackService.java:26-46](spring-backend/src/main/java/com/medicalchatbot/backend/service/FeedbackService.java#L26-L46).
- **Feedback UI:** `ChatPage.submitFeedback()` ([ChatPage.tsx:342-353](frontend-react/src/routes/ChatPage.tsx#L342-L353)).

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| Config chatbot-service | `chatbot-service/app/config.py` |
| Quota/pricing admin (Spring) | `spring-backend/.../service/{QuotaPolicyAdminService,ModelPricingAdminService}.java` |
| Feedback service | `spring-backend/.../service/FeedbackService.java` |
| Feedback entity/migration | `spring-backend/.../entity/MessageFeedback.java`, `db/migration/V1__baseline_schema_and_seed.sql` (bảng `message_feedback`) |
| Feedback UI | `frontend-react/src/routes/ChatPage.tsx` |
