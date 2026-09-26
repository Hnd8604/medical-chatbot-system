# M9. Quota Management

Quản lý hạn mức sử dụng theo ngày cho mỗi user (request/token/cost), chặn request trước khi phát sinh chi phí khi vượt hạn mức. Dựa trên dữ liệu [M7 (Usage)](M7-usage-tracking.md).

## M9.1 - Quota policy

**Mục tiêu:** Định nghĩa hạn mức sử dụng.

**Hành vi:**
- Bảng `quota_policies` (`V1`), entity `QuotaPolicy`: `daily_request_limit`, `daily_token_limit`, `daily_cost_limit_usd`.
- Gắn policy cho user qua `app_users.quota_policy_id`; baseline V1 seed policy,
  V3 đổi tên theo role (`user_standard`, `doctor_standard`, `admin`).

**Tiêu chí hoàn thành:** User có quota policy trước khi chat (`statusForUser` ném 500 nếu thiếu policy).

## M9.2 - Kiểm tra quota trước request

**Mục tiêu:** Chặn request trước khi phát sinh chi phí nếu đã vượt hạn mức.

**Hành vi:**
- `QuotaService.assertQuotaAvailable(userId)` được gọi **đầu** `ChatApplicationService.chat()`.
- `statusForUser()` aggregate usage trong ngày (`summarizeSuccessfulUsage` theo `startOfDay`..`startOfNextDay`) và so với policy.
- `blockedReason()` chặn theo thứ tự: vượt request limit → token limit → cost limit.
- Nếu vượt → tạo alert `QUOTA_EXCEEDED`, ghi audit `QUOTA_BLOCKED`, ném `QuotaExceededException` → `ApiExceptionHandler` trả **HTTP 429**.

**Tiêu chí hoàn thành:** Khi request_count/token/cost vượt limit, chat bị chặn.

## M9.3 - Ghi nhận usage sau request

**Mục tiêu:** Cập nhật quota thông qua usage log.

**Hành vi:**
- **Không** có bảng quota counter riêng (V1) — `usage_logs` là nguồn tính quota.
- Mỗi chat thành công ghi usage ([M7](M7-usage-tracking.md)); lần check tiếp theo aggregate lại theo ngày.

**Tiêu chí hoàn thành:** Quota status thay đổi sau mỗi request.

## M9.4 - Quota theo nhóm

- Hiện: mỗi user gắn 1 `quota_policy` (có thể cấp policy khác nhau cho Doctor/Admin/User).
- Hướng mở rộng: default theo role + override theo user (priority: user > role > default).

## M9.5 - Lịch sử thay đổi quota

- Dùng `audit_logs` để truy vết (action liên quan quota); admin chỉnh policy qua `QuotaPolicyAdminService` ([M20](M20-M22-system-config-feedback.md)).
- **Tiêu chí:** Admin truy vết được thay đổi quota.

## Cảnh báo sớm (quota warning)

- `checkAndTriggerQuotaWarning()`: khi request/token usage ≥ **80%**, tạo `Notification` loại `QUOTA_WARNING` — **một lần/ngày** (`hasQuotaWarningBeenSentToday`) — xem [M25](M21-M23-M25-utilities.md).

## Luồng chương trình

```
POST /api/chat → ChatApplicationService.chat (đầu hàm)
   ▼
QuotaService.assertQuotaAvailable(userId)
   statusForUser(userId):
      policy = quota_policies theo user (500 nếu thiếu)
      usage  = summarizeSuccessfulUsage(userId, startOfDay, startOfNextDay)
      checkAndTriggerQuotaWarning()  → ≥80% → Notification QUOTA_WARNING (1 lần/ngày)
      blockedReason = request>limit ? : token>limit ? : cost>limit ? : null
   ├─ allowed (blockedReason == null) → tiếp tục pipeline
   └─ vượt:
        AlertService.triggerAlert(QUOTA_EXCEEDED, WARNING)
        auditLog QUOTA_BLOCKED
        throw QuotaExceededException → HTTP 429 (ApiExceptionHandler)
```

## Luồng trong code

- **Chặn quota:** `assertQuotaAvailable()` ([QuotaService.java](../backend/src/main/java/com/medicalchatbot/backend/service/QuotaService.java)).
- **Tính status:** `statusForUser()` ([QuotaService.java](../backend/src/main/java/com/medicalchatbot/backend/service/QuotaService.java)); `blockedReason()` ([QuotaService.java](../backend/src/main/java/com/medicalchatbot/backend/service/QuotaService.java)).
- **Cảnh báo 80%:** `checkAndTriggerQuotaWarning()` ([QuotaService.java](../backend/src/main/java/com/medicalchatbot/backend/service/QuotaService.java)).
- **429 response:** `ApiExceptionHandler.quotaExceeded()` ([ApiExceptionHandler.java](../backend/src/main/java/com/medicalchatbot/backend/exception/ApiExceptionHandler.java)).
- **API status:** `GET /api/quota/status` ([ChatbotController.java](../backend/src/main/java/com/medicalchatbot/backend/controller/ChatbotController.java)).

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| Quota service | `backend/.../service/QuotaService.java` |
| Exception 429 | `backend/.../exception/QuotaExceededException.java`, `ApiExceptionHandler.java` |
| Entity/policy admin | `backend/.../entity/QuotaPolicy.java`, `.../service/QuotaPolicyAdminService.java` |
| Migration | `V1__baseline_schema_and_seed.sql` (policy, seed, rate limit), `V3__rename_quota_policies_to_roles.sql` |
