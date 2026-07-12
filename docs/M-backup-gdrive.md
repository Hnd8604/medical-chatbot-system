# Sao lưu CSDL lên Google Drive (tự động 02:00 + thủ công)

Sao lưu **app DB** (`medical_chatbot_app`) và **HAPI FHIR DB** (`hapi`) bằng
`pg_dump` → nén gzip → lưu local (`logs/backups/`) → upload lên **Google Drive**
qua **rclone**. Giữ lại 7 ngày ở cả local và Drive.

Hai đường kích hoạt:

| Cách | Cơ chế |
|---|---|
| **Tự động 02:00 hằng ngày** | Windows Task Scheduler → `infra/scripts/backup.ps1 -Trigger auto` → `backup.sh` |
| **Thủ công** | Nút **"Backup ngay"** ở Admin UI (`/admin/backup`) → `POST /api/admin/backup` → Spring chạy `backup.sh` ở luồng nền |

## Thành phần

| File | Vai trò |
|---|---|
| `infra/scripts/backup.sh` | Script chính: pg_dump 2 DB, gzip, upload rclone, retention, xuất `BACKUP_RESULT_JSON=...` |
| `infra/scripts/backup.ps1` | Wrapper Windows: định vị Git Bash rồi chạy `backup.sh` (dùng cho Task Scheduler) |
| `infra/scripts/register-backup-task.ps1` | Đăng ký/gỡ Scheduled Task `MedicalChatbotBackup` chạy 02:00 |
| `backup_history` (Flyway V6) | Bảng log mỗi lần backup: trigger, status, size, đích, lỗi |
| `AdminBackupController` / `BackupService` | `POST /api/admin/backup`, `GET /api/admin/backup/history` (chỉ ADMIN) |
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

`backup.sh` đọc `spring-backend/.env` (xem `spring-backend/.env.example`):

```env
BACKUP_UPLOAD=true                 # false = chỉ backup local, bỏ qua upload
RCLONE_REMOTE=gdrive               # tên remote đã tạo ở bước 1
RCLONE_DEST=medical-chatbot-backups
```

Phía Spring (nút Admin UI), cấu hình trong `application.yml` (đều có mặc định):

```yaml
backup:
  enabled: true
  script-path: ../infra/scripts/backup.sh   # tương đối theo thư mục chạy Spring (spring-backend/)
  bash-path: bash                            # cần Git Bash trong PATH; hoặc trỏ tuyệt đối
  timeout-minutes: 10
```

## 3. Chạy thử thủ công (CLI)

```powershell
# Từ gốc repo, với Docker stack đang chạy:
powershell -ExecutionPolicy Bypass -File infra\scripts\backup.ps1 -Trigger manual
# Kết quả: file trong logs/backups/, log ở logs/backup_cron.log, và trên gdrive:medical-chatbot-backups
```

## 4. Đăng ký lịch tự động 02:00

Mở **PowerShell với quyền Administrator**:

```powershell
powershell -ExecutionPolicy Bypass -File infra\scripts\register-backup-task.ps1
# Đổi giờ:      ... register-backup-task.ps1 -Time 03:30
# Gỡ bỏ:        ... register-backup-task.ps1 -Unregister

# Kiểm tra / chạy thử:
Get-ScheduledTask -TaskName MedicalChatbotBackup
Start-ScheduledTask -TaskName MedicalChatbotBackup
```

## 5. Nút "Backup ngay" trên Admin UI

Đăng nhập tài khoản **ADMIN** → menu **Sao lưu** (`/admin/backup`) → **Backup ngay**.
Bảng lịch sử tự cập nhật (poll 4s) đến khi trạng thái chuyển `Thành công` / `Một phần` / `Thất bại`.

## 6. Phục hồi (restore)

```powershell
# Tải bản sao từ Drive (nếu không còn local):
rclone copy gdrive:medical-chatbot-backups/app_db_backup_YYYY-MM-DD_HH-MM-SS.sql.gz .\logs\backups\

# Restore app DB:
gunzip -c logs/backups/app_db_backup_...sql.gz | docker exec -i medical-chatbot-app-postgres psql -U app_user -d medical_chatbot_app

# Restore HAPI DB:
gunzip -c logs/backups/hapi_db_backup_...sql.gz | docker exec -i medical-chatbot-hapi-postgres psql -U admin -d hapi
```

> Dump tạo bằng `pg_dump --clean --if-exists` nên restore sẽ drop + tạo lại đối tượng.
> HAPI nên được restart sau khi restore DB của nó.

## Ghi chú an toàn

- Bản sao chứa **dữ liệu bệnh nhân** — Google Drive dùng để lưu phải thuộc tổ chức, hạn chế chia sẻ,
  ưu tiên scope `drive.file`. Cân nhắc mã hóa (`rclone crypt`) nếu cần.
- Khi backup lỗi, `backup.sh` gửi cảnh báo Telegram (nếu cấu hình `TELEGRAM_BOT_TOKEN`/`CHAT_ID`),
  giống cơ chế alert hiện có.
