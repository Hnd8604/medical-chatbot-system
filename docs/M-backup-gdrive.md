# Sao lưu CSDL lên Google Drive (tự động 02:00 + thủ công)

Sao lưu **app DB** (`medical_chatbot_app`) và **HAPI FHIR DB** (`hapi`) bằng
`pg_dump` → nén gzip → lưu local (`logs/backups/`) → upload lên **Google Drive**
qua **rclone**. Giữ lại 7 ngày ở cả local và Drive.

Hai đường kích hoạt:

| Cách | Cơ chế |
|---|---|
| **Tự động 02:00 hằng ngày** | **Backend tự xử lý**: Spring `@Scheduled` (cron `0 0 2 * * *`, TZ `Asia/Ho_Chi_Minh`) → `BackupService.scheduledBackup()` → `backup.sh` ở luồng nền |
| **Thủ công** | Nút **"Backup ngay"** ở Admin UI (`/admin/backup`) → `POST /api/admin/backup` → Spring chạy `backup.sh` ở luồng nền |

> Backend phải đang chạy lúc 02:00 để lịch kích hoạt (không dùng Windows Task Scheduler).
> Cả 2 cách đều lưu **2 nơi**: local `logs/backups/` và Google Drive, giữ **7 ngày** ở mỗi nơi.

## Thành phần

| File | Vai trò |
|---|---|
| `infra/scripts/backup.sh` | Script chính: pg_dump 2 DB, gzip, upload rclone, retention, xuất `BACKUP_RESULT_JSON=...` |
| `infra/scripts/backup.ps1` | Wrapper Windows: định vị Git Bash rồi chạy `backup.sh` (dùng khi chạy tay bằng CLI) |
| `infra/scripts/restore.sh` | Khôi phục TƯƠNG TÁC (có xác nhận) — dùng chạy tay 1 DB theo đường dẫn file |
| `infra/scripts/restore-auto.sh` | Khôi phục KHÔNG tương tác app+hapi (tải từ Drive nếu thiếu, stop/start HAPI) — backend gọi khi bấm nút Khôi phục |
| `backup_history` (Flyway V6) / `restore_history` (Flyway V7) | Bảng log mỗi lần backup / khôi phục |
| `SpringBackendApplication` (`@EnableScheduling`) + `BackupService.scheduledBackup()` | Lịch tự động 02:00 do backend xử lý |
| `AdminBackupController` / `BackupService` | `POST /api/admin/backup`, `GET /api/admin/backup/history`, `POST /api/admin/backup/{id}/restore`, `GET /api/admin/backup/restore-history` (chỉ ADMIN) |
| `AdminBackupPage.tsx` | Nút Backup + nút Khôi phục (modal xác nhận) + lịch sử backup/restore |
| `AdminBackupPage.tsx` | Trang Admin: nút backup + bảng lịch sử (poll khi đang chạy) |

## 1. Cài & cấu hình rclone (một lần)

1. Cài rclone: https://rclone.org/downloads/ (đảm bảo `rclone` có trong PATH — chạy `rclone version`).
2. Tạo remote Google Drive:
   ```powershell
   rclone config
   # n) New remote
   # name> gdrive
   # Storage> drive        (Google Drive)
   # client_id/secret> để trống (Enter) cho mặc định, hoặc điền của bạn
   # scope> 1 (Full access) hoặc 3 (drive.file — chỉ file do app tạo)
   # Trình duyệt mở ra để đăng nhập Google và cấp quyền
   ```
3. Kiểm tra:
   ```powershell
   rclone lsd gdrive:
   rclone mkdir gdrive:medical-chatbot-backups
   ```

> **Server headless (không có trình duyệt):** dùng `rclone authorize "drive"` trên máy có
> trình duyệt rồi dán token, hoặc dùng service account (`--drive-service-account-file`).

## 2. Biến môi trường

`backup.sh` đọc `backend/.env` (xem `backend/.env.example`):

```env
BACKUP_UPLOAD=true                 # false = chỉ backup local, bỏ qua upload
RCLONE_REMOTE=gdrive               # tên remote đã tạo ở bước 1
RCLONE_DEST=medical-chatbot-backups
```

Phía Spring (nút Admin UI), cấu hình trong `application.yml` (đều có mặc định):

```yaml
backup:
  enabled: true
  script-path: ../infra/scripts/backup.sh   # tương đối theo thư mục chạy Spring (backend/)
  bash-path: bash                            # cần Git Bash trong PATH; hoặc trỏ tuyệt đối
  timeout-minutes: 10
```

## 3. Chạy thử thủ công (CLI)

```powershell
# Từ gốc repo, với Docker stack đang chạy:
powershell -ExecutionPolicy Bypass -File infra\scripts\backup.ps1 -Trigger manual
# Kết quả: file trong logs/backups/, log ở logs/backup_cron.log, và trên gdrive:medical-chatbot-backups
```

## 4. Lịch tự động 02:00 (backend tự xử lý)

Không cần Windows Task Scheduler. Backend Spring bật `@EnableScheduling` và chạy
`BackupService.scheduledBackup()` theo cron. Chỉ cần **backend đang chạy** lúc 02:00.

Cấu hình trong `application.yml` (đều có thể override bằng env):

```yaml
backup:
  cron: "0 0 2 * * *"          # BACKUP_CRON — mặc định 02:00 hằng ngày
  timezone: "Asia/Ho_Chi_Minh"  # BACKUP_TIMEZONE
```

Ví dụ đổi giờ chạy sang 03:30: `BACKUP_CRON=0 30 3 * * *`. Muốn tắt hẳn (cả nút thủ công):
`BACKUP_ENABLED=false`. Khi tới giờ, nếu đang có một backup RUNNING thì lịch sẽ bỏ qua lần đó.

## 5. Nút "Backup ngay" trên Admin UI

Đăng nhập tài khoản **ADMIN** → menu **Sao lưu** (`/admin/backup`) → **Backup ngay**.
Bảng lịch sử tự cập nhật (poll 4s) đến khi trạng thái chuyển `Thành công` / `Một phần` / `Thất bại`.

## 6. Phục hồi (restore)

### Cách 1 — Trên Admin UI (khuyến nghị)

`/admin/backup` → tại mỗi bản backup **Thành công / Một phần**, bấm **Khôi phục** →
tích xác nhận "ghi đè dữ liệu" → **Khôi phục ngay**. Backend chạy `restore-auto.sh` ở
luồng nền: tự tải file từ Google Drive nếu local đã hết, **stop HAPI → nạp dump → start HAPI**.
Trạng thái hiện ở mục **Lịch sử khôi phục** (poll tự động). Ghi log vào bảng `restore_history`.

> Khi đang có backup hoặc restore chạy, hệ thống chặn khởi động thao tần thứ hai (409).

### Cách 2 — CLI thủ công (tương tác)

```powershell
# Tải bản sao từ Drive (nếu không còn local):
rclone copy gdrive:medical-chatbot-backups/app_db_backup_YYYY-MM-DD_HH-MM-SS.sql.gz .\logs\backups\

# Có xác nhận (y/n) trước khi ghi đè:
bash infra/scripts/restore.sh logs/backups/app_db_backup_....sql.gz app
bash infra/scripts/restore.sh logs/backups/hapi_db_backup_....sql.gz hapi
```

Hoặc trực tiếp bằng psql:

```bash
gunzip -c logs/backups/app_db_backup_...sql.gz | docker exec -i medical-chatbot-app-postgres psql -U app_user -d medical_chatbot_app
gunzip -c logs/backups/hapi_db_backup_...sql.gz | docker exec -i medical-chatbot-hapi-postgres psql -U admin -d hapi
```

> Dump tạo bằng `pg_dump --clean --if-exists` nên restore sẽ drop + tạo lại đối tượng.
> HAPI nên được restart sau khi restore DB của nó (bản UI/`restore-auto.sh` tự làm việc này).

## Ghi chú an toàn

- Bản sao chứa **dữ liệu bệnh nhân** — Google Drive dùng để lưu phải thuộc tổ chức, hạn chế chia sẻ,
  ưu tiên scope `drive.file`. Cân nhắc mã hóa (`rclone crypt`) nếu cần.
- Khi backup lỗi, `backup.sh` gửi cảnh báo Telegram (nếu cấu hình `TELEGRAM_BOT_TOKEN`/`CHAT_ID`),
  giống cơ chế alert hiện có.
