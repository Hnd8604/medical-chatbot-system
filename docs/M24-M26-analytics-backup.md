# M24 / M26 — Advanced Analytics & Backup/Restore

- **M24 — Advanced Analytics:** phân tích nâng cao về câu hỏi, lỗi và hiệu năng.
- **M26 — Backup & Restore:** sao lưu và phục hồi database (app + HAPI).

---

# M24. Advanced Analytics

Đã có triển khai riêng: `AnalyticsService` (query bằng `JdbcTemplate`) + `AdminAnalyticsController` expose tại `/api/admin/analytics/*`. Mọi endpoint nhận `from`/`to` (tùy chọn; mặc định 7 ngày gần nhất, **tối đa 90 ngày**) và `limit` (≤ 20).

## M24.1 - Question analytics

**Mục tiêu:** Biết người dùng hỏi nhiều về chủ đề nào.

**Hành vi:**
- `GET /api/admin/analytics/intents` → top intent theo ngày.
- Nguồn: `audit_logs` nơi `metadata_json ->> 'operation' = 'chat'`, lấy intent từ `metadata_json ->> 'question_intent'` (fallback `intent`). `intent`/`question_intent` được ghi trong `saveAuditLog()` ([M18](M18-M19-audit-alert.md)).
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
- Strict mode (`set -euo pipefail`); ghi log vào `logs/backup_cron.log`.
- **Alert khi fail:** nếu `pg_dump` lỗi → xóa file hỏng + gửi Telegram `BACKUP_ERROR` (CRITICAL) (cùng kênh alert M19).
- Chạy định kỳ qua cron/scheduled task.

**Tiêu chí hoàn thành:** Backup được tạo định kỳ.

## M26.3 - Restore procedure

**Mục tiêu:** Khôi phục dữ liệu khi cần.

**Hành vi:**
- Script `infra/scripts/restore.sh <file.sql.gz> <app|hapi>`.
- An toàn: kiểm tra tính toàn vẹn gzip (`gzip -t`), kiểm tra container tồn tại, **xác nhận (y/n)** trước khi ghi đè, dùng `psql -v ON_ERROR_STOP=1` để dừng ngay khi lỗi SQL.

**Tiêu chí hoàn thành:** Restore được database từ backup đã tạo.

## Luồng chương trình

```
M24 Analytics (AnalyticsService — JdbcTemplate, /api/admin/analytics/*):
   GET /intents      ← audit_logs (operation='chat', question_intent/intent) → top intent/ngày
   GET /errors       ← alerts (source, alert_type)                           → top lỗi/ngày
   GET /performance  ← usage_logs (latency_ms, status='success')             → AVG/P95/P99 theo model
   GET /requests     ← usage_logs (operation='chat', request_count)          → tổng request
        normalizeRange: mặc định 7 ngày, tối đa 90; limit ≤ 20
        → chart/KPI trên Admin Dashboard (M13)

M26 Backup:
   cron → backup.sh
      đọc credential từ backend/.env
      perform_backup "app"  → pg_dump medical_chatbot_app | gzip → logs/backups/app_db_*.sql.gz
      perform_backup "hapi" → pg_dump hapi               | gzip → logs/backups/hapi_db_*.sql.gz
         lỗi → xóa file hỏng + Telegram BACKUP_ERROR (CRITICAL)
      xóa backup > 7 ngày

M26 Restore:
   restore.sh <file.sql.gz> <app|hapi>
      gzip -t (toàn vẹn) → kiểm tra container → xác nhận (y/n)
      gunzip -c | psql -v ON_ERROR_STOP=1   → ghi đè DB
```

## Luồng trong code / script

- **Backup:** [infra/scripts/backup.sh](infra/scripts/backup.sh) — `perform_backup()` ([L25-51](infra/scripts/backup.sh#L25-L51)), retention ([L56-57](infra/scripts/backup.sh#L56-L57)).
- **Restore:** [infra/scripts/restore.sh](infra/scripts/restore.sh) — kiểm tra + xác nhận ([L32-50](infra/scripts/restore.sh#L32-L50)), phục hồi ([L55](infra/scripts/restore.sh#L55)).
- **Analytics service:** `AnalyticsService` — intent ([AnalyticsService.java:29-66](backend/src/main/java/com/medicalchatbot/backend/service/AnalyticsService.java#L29-L66)), error ([L68-104](backend/src/main/java/com/medicalchatbot/backend/service/AnalyticsService.java#L68-L104)), performance ([L106-130](backend/src/main/java/com/medicalchatbot/backend/service/AnalyticsService.java#L106-L130)), request summary ([L132-148](backend/src/main/java/com/medicalchatbot/backend/service/AnalyticsService.java#L132-L148)).
- **Analytics API:** [AdminAnalyticsController.java](backend/src/main/java/com/medicalchatbot/backend/controller/AdminAnalyticsController.java) (`/api/admin/analytics/*`).
- **Ghi `question_intent`:** [ChatApplicationService.java:294-307](backend/src/main/java/com/medicalchatbot/backend/service/ChatApplicationService.java#L294-L307).

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| Analytics service | `backend/.../service/AnalyticsService.java` |
| Analytics API | `backend/.../controller/AdminAnalyticsController.java` |
| Analytics DTO | `backend/.../dto/response/{Intent,Error,Performance}AnalyticsResponse.java`, `RequestAnalyticsSummaryResponse.java` |
| Dashboard UI | `frontend/src/routes/AdminDashboardPage.tsx` |
| Backup script | `infra/scripts/backup.sh` |
| Restore script | `infra/scripts/restore.sh` |
| Latency/usage nguồn | `backend/.../service/ChatApplicationService.java`, `.../repository/UsageLogRepository.java` |
| Error nguồn (alerts) | `backend/.../service/AlertService.java` |
| Dashboard analytics | `frontend/src/routes/{AdminDashboardPage,UsagePage}.tsx` |
