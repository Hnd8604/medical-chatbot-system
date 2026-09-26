# M12. Database Design

Thiết kế schema database ứng dụng (tách hoàn toàn khỏi HAPI FHIR database), quản lý bằng Flyway migration và JPA entity với `ddl-auto=validate`. Đây là nền tảng lưu trữ cho toàn hệ thống.

> Lịch sử migration V1→V17 cũ đã được **gộp (squash) thành baseline `V1`**.
> Schema hiện tại được tiến hóa tiếp bằng migration V2→V9; không sửa lại baseline.
> ERD chi tiết (Mermaid + DBML): [backend/docs/m12-database-erd.md](../backend/docs/m12-database-erd.md).

## M12.1 - Core application schema

**Mục tiêu:** Schema app database tách khỏi HAPI database.

**Hành vi:**
- App DB chứa: user, session, message, usage, quota, audit, pricing, alert,
  notification, feedback, user-patient link, LLM virtual key và lịch sử
  backup/restore. Semantic cache nằm trong Qdrant, không nằm trong app DB.
- Dùng **UUID** làm khóa chính (`gen_random_uuid()`), mọi bảng có timestamp (`created_at`/`updated_at` kiểu `timestamptz`). Ngoại lệ: `llm_virtual_keys` dùng `user_id` làm khóa chính (quan hệ 1–1 với `app_users`).
- Quản lý bằng **Flyway** (`V1`→`V9`); Hibernate `ddl-auto=validate`
  (entity phải khớp schema migration, không tự tạo bảng).

**Tiêu chí hoàn thành:** Spring start được và validate schema pass.

## M12.2 - User/role schema

- Bảng `app_users` (username/email unique — có unique index `lower()`, role mặc định `USER`, `display_name`, `password_hash` bcrypt, `status`, `token_version`, `quota_policy_id` FK → `quota_policies`).
- Bảng liên kết `app_user_patient_links` (user ↔ FHIR Patient ID, `relationship` check `SELF|DEPENDENT|CAREGIVER`, `is_primary` — giới hạn bệnh nhân mà USER được xem — [M4.7](M4-medical-data-query.md)).
- Entity `User`, repository `UserRepository`.
- **Tiêu chí:** lấy được user hiện tại + role/quota policy.

## M12.3 - Conversation/message schema

- `chat_sessions`, `chat_messages` (role check `user|assistant|system`, `metadata_json` JSONB).
- Memory fields trong session: `active_patient_id`, `memory_summary`, `last_intent`, `last_tool_name`, `last_resource_type/id` — [M15](M15-context-management.md).
- **Index:** `idx_chat_sessions_user_id`, `idx_chat_messages_session_id_created_at` → query history nhanh.
- **Tiêu chí:** History API nhanh với dữ liệu demo.

## M12.4 - Usage/cost/quota schema

- `usage_logs` (`request_count`, `input_tokens`, `output_tokens`, `estimated_cost_usd numeric(12,6)`, provider/model, `operation`, `status` check `success|failed|fallback|blocked`, `latency_ms`, cache observability: `answer_source`, `saved_tokens`, `saved_cost_usd`).
- `quota_policies` (`daily_request_limit`, `daily_token_limit`, `daily_cost_limit_usd`, `rate_limit_per_minute`).
- `model_pricing` (giá input/output per 1M token, currency, `active` + unique index partial theo provider/model active).
- `llm_virtual_keys` (`V2`): ánh xạ `app_user` → virtual key LiteLLM (`key_alias`, `virtual_key`, `max_budget_usd`, `budget_duration`) — gateway là nơi chặn budget token/cost ([M-litellm-gateway](M-litellm-gateway.md)).
- **Index:** `idx_usage_logs_user_id_created_at`, `idx_usage_logs_session_id_created_at`, `idx_usage_logs_model_created_at`, `idx_usage_logs_status_created_at`; `numeric` precision cho cost.
- **Tiêu chí:** Quota/cost summary chạy đúng ([M7](M7-usage-tracking.md), [M8](M8-cost-management.md), [M9](M9-quota-management.md)).

## M12.5 - Audit, cảnh báo và vận hành

- `audit_logs` (action/resource_type/resource_id/user/session + `metadata_json` JSONB) — [M18](M18-M19-audit-alert.md).
- `alerts` (source/alert_type/severity/status + resolved_at/by), `notifications` (type/title/content/is_read), `message_feedback` (rating 1–5 + comment).
- `backup_history` và `restore_history` lưu trạng thái, thời gian, kết quả từng
  database và lỗi của tác vụ backup/restore.
- `cache_entries` từng được tạo trong baseline nhưng bị drop ở `V5`; semantic
  cache hiện lưu tại Qdrant ([M14](M14-cache-management.md)).
- **Tiêu chí:** Tra cứu được audit, alert và lịch sử backup/restore.

## Danh sách migration (Flyway)

| Version | Nội dung |
|---|---|
| V1 | **Baseline** (gộp V1→V17 cũ): toàn bộ 12 bảng + index + seed — 3 quota policy, 13 demo user (USER/DOCTOR/ADMIN, mật khẩu bcrypt), bảng giá 4 model (openai + groq), liên kết user ↔ FHIR Patient (SELF/CAREGIVER) |
| V2 | `llm_virtual_keys` — mapping user → LiteLLM virtual key + budget |
| V3 | Đổi tên 3 quota policy theo role (`free`→`user_standard`, `pro`→`doctor_standard`, `enterprise`→`admin`) và gán mỗi user về gói khớp role |
| V4 | Đồng bộ `display_name` của user demo với hồ sơ FHIR đã liên kết |
| V5 | Xóa `cache_entries`; cache runtime dùng Qdrant/Redis thay vì app PostgreSQL |
| V6 | Thêm `backup_history` |
| V7 | Thêm `restore_history` và liên kết tùy chọn tới bản backup nguồn |
| V8 | Dọn feedback trùng và thêm unique index `(message_id, user_id)` |
| V9 | Vô hiệu hóa credential demo trong migration production |

Profile `dev` nạp thêm repeatable migration `db/devmigration/R__enable_demo_accounts.sql`
để bật lại ba tài khoản demo cục bộ. Profile production chỉ chạy `db/migration`.

> Baseline áp dụng trên database rỗng; các bước backfill cost lịch sử của
> migration cũ được bỏ qua vì `usage_logs` khởi tạo rỗng.

## Luồng chương trình

```
Khởi động Spring Boot
   ▼
Flyway chạy migration V1..V9 (theo thứ tự version) trên app PostgreSQL
   ▼
Hibernate ddl-auto=validate
   ├─ entity ⟷ schema khớp  → app start OK
   └─ lệch                  → fail-fast khi startup (phát hiện sai schema sớm)
   ▼
Repository (Spring Data JPA) đọc/ghi qua entity:
   User, ChatSession, ChatMessage, UsageLog, QuotaPolicy, ModelPricing,
   AuditLog, Alert, Notification, MessageFeedback, UserPatientLink,
   LlmVirtualKey, BackupHistory, RestoreHistory
```

App DB hoàn toàn tách biệt HAPI FHIR DB (hai PostgreSQL khác nhau — xem [M5](M5-fhir-integration.md)).

## Thành phần liên quan trong mã nguồn

| Vai trò | File / thư mục |
|---|---|
| Migrations | `backend/src/main/resources/db/migration/` (V1→V9), `db/devmigration/` (dev-only) |
| Entities | `backend/.../entity/*.java` |
| Repositories | `backend/.../repository/*.java` |
| ERD chi tiết | `backend/docs/m12-database-erd.md` |
