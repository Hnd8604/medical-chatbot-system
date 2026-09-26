# M24 / M26 — Advanced Analytics & Backup/Restore

- **M24 — Advanced Analytics:** phân tích nâng cao về câu hỏi, lỗi và hiệu năng.
- **M26 — Backup & Restore:** sao lưu và phục hồi database (app + HAPI).

---

# M24. Advanced Analytics

`AdminAnalyticsController` expose `/api/admin/analytics/*`;
`AnalyticsService` chuẩn hóa input và map response, còn custom SQL/`JdbcTemplate`
nằm trong `repository/jdbc/JdbcAnalyticsQueryRepository`. Mọi endpoint nhận
`from`/`to` (tùy chọn; mặc định 7 ngày gần nhất, **tối đa 90 ngày**); ba endpoint
danh sách `/intents`, `/errors`, `/performance` nhận thêm `limit` (tối đa 20).

## M24.1 - Question analytics

**Mục tiêu:** Biết người dùng hỏi nhiều về chủ đề nào.

**Hành vi:**
- `GET /api/admin/analytics/intents` → top intent theo ngày.
- Nguồn: `audit_logs` nơi `metadata_json ->> 'operation' = 'chat'`, lấy intent từ
  `metadata_json ->> 'question_intent'` (fallback `intent`). Metadata này được
  `ChatInteractionRecorder` ghi qua `ChatbotResponseMapper.audit()` ([M18](M18-M19-audit-alert.md)).
- SQL: CTE `top_intents` lấy N intent nhiều nhất rồi đếm theo từng ngày → trả `{date, intent, count}`.

**Tiêu chí hoàn thành:** Admin thấy top question categories.

## M24.2 - Error analytics

**Mục tiêu:** Theo dõi chất lượng vận hành.

**Hành vi:**
- `GET /api/admin/analytics/errors` → top lỗi theo service + error type theo ngày.
- Nguồn: `alerts` (`source` → service, `alert_type` → error type) ([M19](M18-M19-audit-alert.md)).
- SQL: CTE `top_errors` (service, error_type) nhiều nhất → đếm theo ngày → `{date, service, errorType, count}`.

**Tiêu chí hoàn thành:** Phát hiện service hay lỗi.

## M24.3 - Performance analytics

**Mục tiêu:** Theo dõi latency.

**Hành vi:**
- `GET /api/admin/analytics/performance` → latency theo model.
- Nguồn: `usage_logs` (`status='success'`, `latency_ms` not null) — đo trong `ChatApplicationService` ([M7.1](M7-usage-tracking.md)).
- Tính **AVG**, **P95**, **P99** bằng `percentile_cont(...) WITHIN GROUP (ORDER BY latency_ms)`, group theo `llm_model` → `{model, avgLatency, p95Latency, p99Latency}`.

**Tiêu chí hoàn thành:** Biết request nào chậm.

## M24.4 - Request summary

- `GET /api/admin/analytics/requests` → tổng `request_count` từ `usage_logs` (`operation='chat'`) trong khoảng ngày → `{from, to, count}`.
- Dùng làm KPI "tổng request" trên Admin Dashboard ([M13](M13-admin-dashboard.md)).

> Ghi chú: range được chuẩn hóa trong `normalizeRange()` (mặc định 7 ngày, chặn > 90 ngày, lỗi 400 nếu `from > to`); `limit` chuẩn hóa về [1, 20].

---

# M26. Backup & Restore

## M26.1 - Backup policy

**Mục tiêu:** Định nghĩa dữ liệu cần backup.

**Hành vi:**
- Backup cả **app DB** (`medical_chatbot_app`) và **HAPI DB** (`hapi`).
- File backup lưu tại `logs/backups/`, định dạng `*.sql.gz`; **không** backup secrets vào repo (đọc credential từ `backend/.env`).
- Retention **7 ngày** (xóa file cũ hơn tự động).

**Tiêu chí hoàn thành:** Có lịch backup rõ ràng.

## M26.2 - Backup automation

**Mục tiêu:** Tự động backup theo lịch.

**Hành vi:**
- Script `infra/scripts/backup.sh` dùng `docker exec ... pg_dump --clean --if-exists | gzip`.
- Script dùng `set -uo pipefail` và kiểm tra lỗi từng pipeline để vẫn tổng hợp
  được kết quả `SUCCESS`/`PARTIAL`/`FAILED`; log nằm ở `logs/backup_cron.log`.
- **Alert khi fail:** nếu `pg_dump` lỗi → xóa file hỏng + gửi Telegram `BACKUP_ERROR` (CRITICAL) (cùng kênh alert M19).
- Spring `@Scheduled` gọi `BackupService.scheduledBackup()` (mặc định 02:00,
  `Asia/Ho_Chi_Minh`); manual backup dùng `/api/admin/backup`.

**Tiêu chí hoàn thành:** Backup được tạo định kỳ.

## M26.3 - Restore procedure

**Mục tiêu:** Khôi phục dữ liệu khi cần.

**Hành vi:**
- Admin UI/API chạy `restore-auto.sh` qua `BackupJobRunner`: khôi phục app + HAPI,
  tải file từ Drive nếu local thiếu, stop/start HAPI và ghi `restore_history`.
- CLI thủ công dùng `infra/scripts/restore.sh <file.sql.gz> <app|hapi>`.
- Cả hai kiểm tra gzip/container và dùng `psql -v ON_ERROR_STOP=1`; bản CLI
  yêu cầu xác nhận `(y/n)`, còn bản admin đã xác nhận ở UI nên chạy không tương tác.

**Tiêu chí hoàn thành:** Restore được database từ backup đã tạo.

## Luồng chương trình

```
M24 Analytics (controller → service → AnalyticsQueryRepository/JDBC):
   GET /intents      ← audit_logs (operation='chat', question_intent/intent) → top intent/ngày
   GET /errors       ← alerts (source, alert_type)                           → top lỗi/ngày
   GET /performance  ← usage_logs (latency_ms, status='success')             → AVG/P95/P99 theo model
   GET /requests     ← usage_logs (operation='chat', request_count)          → tổng request
        normalizeRange: mặc định 7 ngày, tối đa 90; limit danh sách ≤ 20
        → KPI trên Admin Dashboard + biểu đồ trên AdminAnalyticsPage (M13)

M26 Backup:
   BackupService @Scheduled / Admin API → BackupJobRunner → backup.sh
      đọc credential từ backend/.env
      perform_backup "app"  → pg_dump medical_chatbot_app | gzip → logs/backups/app_db_*.sql.gz
      perform_backup "hapi" → pg_dump hapi               | gzip → logs/backups/hapi_db_*.sql.gz
         lỗi → xóa file hỏng + Telegram BACKUP_ERROR (CRITICAL)
      xóa backup > 7 ngày

M26 Restore:
   Admin API → BackupService → BackupJobRunner → restore-auto.sh appFile hapiFile
      tải file từ Drive nếu cần → stop/start HAPI → restore không tương tác
   CLI thủ công → restore.sh <file.sql.gz> <app|hapi> → có xác nhận y/n
```

## Luồng trong code / script

- **Backup:** [backup.sh](../infra/scripts/backup.sh); `BackupService` tạo job,
  `integration/backup/BackupJobRunner` chạy process và persist kết quả.
- **Restore UI/backend:** [restore-auto.sh](../infra/scripts/restore-auto.sh);
  **restore CLI:** [restore.sh](../infra/scripts/restore.sh).
- **Analytics service:**
  [AnalyticsService.java](../backend/src/main/java/com/medicalchatbot/backend/service/AnalyticsService.java).
- **SQL repository:**
  [JdbcAnalyticsQueryRepository.java](../backend/src/main/java/com/medicalchatbot/backend/repository/jdbc/JdbcAnalyticsQueryRepository.java)
  implements `AnalyticsQueryRepository`.
- **Analytics API:** [AdminAnalyticsController.java](../backend/src/main/java/com/medicalchatbot/backend/controller/AdminAnalyticsController.java).
- **Ghi `question_intent`:** `ChatbotResponseMapper.audit()` →
  `ChatInteractionRecorder` → `AuditLogRepository`.

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| Analytics service | `backend/.../service/AnalyticsService.java` |
| Analytics query/SQL | `backend/.../repository/AnalyticsQueryRepository.java`, `.../repository/jdbc/JdbcAnalyticsQueryRepository.java` |
| Analytics API | `backend/.../controller/AdminAnalyticsController.java` |
| Analytics DTO | `backend/.../dto/response/{Intent,Error,Performance}AnalyticsResponse.java`, `RequestAnalyticsSummaryResponse.java` |
| Dashboard UI | `frontend/src/pages/AdminDashboardPage.tsx` |
| Backup orchestration | `backend/.../service/BackupService.java`, `.../integration/backup/BackupJobRunner.java` |
| Backup/restore scripts | `infra/scripts/backup.sh`, `restore-auto.sh`, `restore.sh` |
| Latency/usage nguồn | `backend/.../service/ChatApplicationService.java`, `.../service/ChatInteractionRecorder.java`, `.../repository/UsageLogRepository.java` |
| Error nguồn (alerts) | `backend/.../service/AlertService.java` |
| Dashboard analytics | `frontend/src/pages/{AdminDashboardPage,AdminAnalyticsPage}.tsx` |
