# M12. Database Design

Thiết kế schema database ứng dụng (tách hoàn toàn khỏi HAPI FHIR database), quản lý bằng Flyway migration và JPA entity với `ddl-auto=validate`. Đây là nền tảng lưu trữ cho toàn hệ thống.

## M12.1 - Core application schema

**Mục tiêu:** Schema app database tách khỏi HAPI database.

**Hành vi:**
- App DB chứa: user, session, message, usage, quota, audit, cache, pricing, alert, notification, feedback, user-patient link.
- Dùng **UUID** làm khóa chính (`gen_random_uuid()`), mọi bảng có timestamp (`created_at`/`updated_at` kiểu `timestamptz`).
- Quản lý bằng **Flyway** (`V1`..`V12`); Hibernate `ddl-auto=validate` (entity phải khớp schema migration, không tự tạo bảng).

**Tiêu chí hoàn thành:** Spring start được và validate schema pass.

## M12.2 - User/role schema

- Bảng `app_users` (username unique, email, role mặc định `USER`, `quota_policy_id` FK → `quota_policies`).
- Auth fields bổ sung ở `V11`; bảng liên kết `user_patient_links` ở `V12` (giới hạn bệnh nhân mà USER được xem — [M4.7](M4-medical-data-query.md)).
- Entity `User`, repository `UserRepository`.
- **Tiêu chí:** lấy được user hiện tại + role/quota policy.

## M12.3 - Conversation/message schema

- `chat_sessions`, `chat_messages` (role check `user|assistant|system`, `metadata_json` JSONB ở các migration sau).
- Memory fields trong session thêm ở `V4` (`active_patient_id`, `memory_summary`, `last_intent`, `last_tool_name`, `last_resource_type/id` — [M15](M15-context-management.md)).
- **Index:** `idx_chat_sessions_user_id`, `idx_chat_messages_session_id_created_at` → query history nhanh.
- **Tiêu chí:** History API nhanh với dữ liệu demo.

## M12.4 - Usage/cost/quota schema

- `usage_logs` (`request_count`, `input_tokens`, `output_tokens`, `estimated_cost_usd numeric(12,6)`); nâng cấp ở `V3` (status/provider/model/latency), `V7` (cache observability).
- `quota_policies` (`daily_request_limit`, `daily_token_limit`, `daily_cost_limit_usd`); rate limit thêm ở `V6`.
- `model_pricing` thêm ở `V5` (giá input/output per 1M token, currency) + backfill cost.
- **Index:** `idx_usage_logs_user_id_created_at`, `idx_usage_logs_session_id_created_at`; `numeric` precision cho cost.
- **Tiêu chí:** Quota/cost summary chạy đúng ([M7](M7-usage-tracking.md), [M8](M8-cost-management.md), [M9](M9-quota-management.md)).

## M12.5 - Audit/cache schema

- `audit_logs` (action/resource_type/resource_id/user/session + `metadata_json` JSONB) — thêm ở `V3` ([M18](M18-M19-audit-alert.md)).
- `cache_entries` (`cache_key` unique, `value_json` JSONB, `expires_at`) + `idx_cache_entries_expires_at` ([M14](M14-cache-management.md)).
- Bổ sung: `alerts` (`V8`), `notifications` (`V10`), `message_feedback` (`V9`).
- **Tiêu chí:** Tra cứu được audit và cache entry.

## Danh sách migration (Flyway)

| Version | Nội dung |
|---|---|
| V1 | Bảng tối thiểu: `quota_policies`, `app_users`, `chat_sessions`, `chat_messages`, `usage_logs`, `cache_entries` + index |
| V2 | Seed demo user + quota policy |
| V3 | Nâng cấp `usage_logs` (status/provider/model/latency) + thêm `audit_logs` |
| V4 | Session memory fields |
| V5 | `model_pricing` + backfill cost |
| V6 | Rate limit vào quota |
| V7 | Cache observability fields trên `usage_logs` |
| V8 | Alert fields / `alerts` |
| V9 | `message_feedback` |
| V10 | `notifications` |
| V11 | Auth fields cho `app_users` |
| V12 | `user_patient_links` |
| V13 | Bổ sung giá Groq `llama-3.1-8b-instant` vào `model_pricing` |

## Luồng chương trình

```
Khởi động Spring Boot
   ▼
Flyway chạy migration V1..V12 (theo thứ tự version) trên app PostgreSQL
   ▼
Hibernate ddl-auto=validate
   ├─ entity ⟷ schema khớp  → app start OK
   └─ lệch                  → fail-fast khi startup (phát hiện sai schema sớm)
   ▼
Repository (Spring Data JPA) đọc/ghi qua entity:
   User, ChatSession, ChatMessage, UsageLog, QuotaPolicy, ModelPricing,
   AuditLog, CacheEntry, Alert, Notification, MessageFeedback, UserPatientLink
```

App DB hoàn toàn tách biệt HAPI FHIR DB (hai PostgreSQL khác nhau — xem [M5](M5-fhir-integration.md)).

## Thành phần liên quan trong mã nguồn

| Vai trò | File / thư mục |
|---|---|
| Migrations | `spring-backend/src/main/resources/db/migration/V1..V12*.sql` |
| Entities | `spring-backend/.../entity/*.java` |
| Repositories | `spring-backend/.../repository/*.java` |
| Tài liệu thiết kế DB | xem commit `docs: add database design document` |
