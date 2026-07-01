# M18 / M19 — Audit Log & Alert Management

- **M18 — Audit Log:** ghi lại ai truy cập dữ liệu bệnh nhân / thao tác admin, cho phép tra cứu.
- **M19 — Alert Management:** tạo và quản lý cảnh báo sự cố (quota, lỗi service, security...).

---

# M18. Audit Log

## M18.1 - Audit truy cập dữ liệu bệnh nhân

**Mục tiêu:** Ghi lại ai xem dữ liệu bệnh nhân nào.

**Hành vi:**
- Mỗi lượt chat ghi `AuditLog` qua `saveAuditLog()`: user id, patient/resource id, action, time, metadata (intent + `question_intent`, tool, latency, usage).
- `tool_name` được map sang action cụ thể: `VIEW_OBSERVATIONS`, `VIEW_MEDICATIONS`, `VIEW_CONDITIONS`, `VIEW_ENCOUNTERS`, `VIEW_PATIENT_DETAIL`, `SEARCH_PATIENTS`, `VIEW_FROM_CACHE`, `ASK_UNSUPPORTED`, mặc định `CHAT_COMPLETED`.
- Metadata lưu cả `intent` và `question_intent` (bản sao của intent) — `question_intent` là nguồn cho intent analytics ([M24.1](M24-M26-analytics-backup.md)).

**Tiêu chí hoàn thành:** Truy vết được lịch sử truy cập patient data.

## M18.2 - Audit thao tác admin

**Mục tiêu:** Ghi lại thay đổi cấu hình quan trọng.

**Hành vi:**
- Audit cho quota changes, user status/role changes, export (`EXPORT_CONVERSATION` — [M23](M21-M23-M25-utilities.md)), quota block (`QUOTA_BLOCKED` — [M9](M9-quota-management.md)).
- Metadata lưu chi tiết (vd quota status cũ/mới).

**Tiêu chí hoàn thành:** Mỗi thay đổi admin có audit record.

## M18.3 - Audit search/filter

**Mục tiêu:** Cho Admin tra cứu audit log.

**Hành vi:**
- `AuditLogService.searchAuditLogs()` lọc theo user, action, resourceType, resourceId, date range, có **pagination**.
- API `GET /api/audit-logs` (`AuditLogController`).
- UI: trang **Audit Logs** (`AdminAuditLogsPage`) — chọn user (`/api/admin/users`), lọc + phân trang qua `/api/audit-logs` ([M13.4](M13-admin-dashboard.md)).

**Tiêu chí hoàn thành:** Admin tìm được audit event cần thiết.

---

# M19. Alert Management

## M19.1 - Alert threshold

**Mục tiêu:** Cấu hình ngưỡng cảnh báo.

**Hành vi (ngưỡng đang phát alert):**
- Quota vượt hạn (`QUOTA_EXCEEDED`), spam API (`RATE_LIMIT_VIOLATION`), chatbot-service 5xx (`CHATBOT_SERVICE_500`), chatbot-service down (`CHATBOT_UNAVAILABLE`), lỗi hệ thống (`FATAL_ERROR_500`), backup thất bại (`BACKUP_ERROR` — [M26](M24-M26-analytics-backup.md)).

## M19.2 - Alert generation

**Mục tiêu:** Tạo cảnh báo khi điều kiện xảy ra.

**Hành vi:**
- `AlertService.triggerAlert(source, alertType, severity, message, metadata)` lưu `Alert` (entity, `V8`) với `severity` (WARNING/CRITICAL), `status` (OPEN/RESOLVED), `source`.
- **Chống spam alert:** nếu cùng `alertType` đã có alert OPEN trong **15 phút** gần đây → bỏ qua (`existsByAlertTypeAndStatusAndCreatedAtAfter`).
- Alert WARNING/CRITICAL còn gửi **Telegram** (nếu cấu hình `telegram.bot.token`/`chat.id`) — async, không chặn luồng chính.

**Tiêu chí hoàn thành:** Alert được lưu và hiển thị.

## M19.3 - Alert dashboard

**Mục tiêu:** Admin xem và xử lý cảnh báo.

**Hành vi:**
- `AlertService.searchAlerts()` lọc theo status/severity/source/alertType/date range + pagination.
- `resolveAlert(alertId, resolvedBy)` đánh dấu RESOLVED (ghi `resolved_at`, `resolved_by`).
- API `GET /api/admin/alerts` (`AdminAlertController`); hiển thị trên Admin Dashboard ([M13](M13-admin-dashboard.md)).

**Tiêu chí hoàn thành:** Admin xử lý được cảnh báo.

## Luồng chương trình

```
M18 Audit (mỗi lượt chat / thao tác):
   ChatApplicationService.saveAuditLog()
      map tool_name → action (VIEW_OBSERVATIONS, SEARCH_PATIENTS, ...)
      auditLogRepository.save(user, session, action, resourceType, resourceId, metadata)
   QuotaService → QUOTA_BLOCKED | ExportService → EXPORT_CONVERSATION
   ▼
   audit_logs (JSONB metadata)
   ▼
   GET /api/audit-logs (filter user/action/resource/date + page) → AuditLogService

M19 Alert (khi sự cố):
   nguồn: ApiExceptionHandler (5xx/down/fatal/rate-limit), QuotaService (quota), backup.sh
   ▼
   AlertService.triggerAlert(source, type, severity, message, metadata)
      cùng type đang OPEN trong 15' ? → SUPPRESS
      else → lưu Alert(OPEN) + log
              severity ∈ {WARNING, CRITICAL} → gửi Telegram (async)
   ▼
   GET /api/admin/alerts (filter) → Admin Dashboard
   resolveAlert(id, by) → status=RESOLVED
```

## Luồng trong code

- **Audit ghi:** `saveAuditLog()` + map tool→action ([ChatApplicationService.java:232-320](spring-backend/src/main/java/com/medicalchatbot/backend/service/ChatApplicationService.java#L232-L320)).
- **Audit search:** [AuditLogService.java:21-42](spring-backend/src/main/java/com/medicalchatbot/backend/service/AuditLogService.java#L21-L42); API `AuditLogController` (`/api/audit-logs`).
- **Alert trigger + chống spam + Telegram:** [AlertService.java:39-125](spring-backend/src/main/java/com/medicalchatbot/backend/service/AlertService.java#L39-L125).
- **Nguồn phát alert:** [ApiExceptionHandler.java](spring-backend/src/main/java/com/medicalchatbot/backend/exception/ApiExceptionHandler.java#L94-L179), [QuotaService.java:139-145](spring-backend/src/main/java/com/medicalchatbot/backend/service/QuotaService.java#L139-L145).

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| Audit ghi | `spring-backend/.../service/ChatApplicationService.java` (`saveAuditLog`) |
| Audit search/API | `spring-backend/.../service/AuditLogService.java`, `.../controller/AuditLogController.java` |
| Audit logs UI | `frontend-react/src/routes/AdminAuditLogsPage.tsx` |
| Alert service | `spring-backend/.../service/AlertService.java` |
| Alert API | `spring-backend/.../controller/AdminAlertController.java` |
| Entity/migration | `.../entity/{AuditLog,Alert}.java`, `db/migration/V3` (audit), `V8` (alert) |
