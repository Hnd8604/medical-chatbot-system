# Chính Sách Sao Lưu & Phục Hồi Dữ Liệu (M26)

## 1. M26.1 - Backup Policy (Chính sách sao lưu)

- **Phạm vi dữ liệu:**
  - `medical_chatbot_app` (App DB - lưu lịch sử chat, user, quota, audit log, cost).
  - `hapi` (HAPI FHIR DB - lưu dữ liệu y tế mô phỏng gốc).
- **Vị trí lưu trữ:** File sao lưu được nén định dạng `.sql.gz` và lưu tại thư mục `logs/backups/` trên máy chủ Host.
- **Bảo mật:** Không commit thư mục backup và file secret `.env` lên repository (cần định nghĩa trong `.gitignore`).
- **Lịch trình (Schedule):** Chạy tự động vào lúc `02:00 AM` mỗi ngày.
- **Chính sách lưu giữ (Retention):** Giữ lại các bản sao lưu trong vòng `7 ngày`. Tự động xóa file cũ để giải phóng ổ cứng.

## 2. M26.2 - Backup Automation (Tự động hóa)

Quá trình tự động được xử lý qua kịch bản shell.

- **Cấu hình Cron Job trên Server (Linux):**
  Thêm dòng sau vào crontab (`crontab -e`):
  `0 2 * * * /bin/bash /đường/dẫn/tới/dự_án/infra/scripts/backup.sh`
- **Cảnh báo (Alert):** Kịch bản tự động đọc Token từ file `.env` và bắn cảnh báo về nhóm Telegram của Dev team nếu có bất kỳ lỗi nào xảy ra trong lúc sao lưu (ví dụ: sập container, hết dung lượng).

## 3. M26.3 - Restore Procedure (Quy trình khôi phục)

Trong trường hợp cần rollback dữ liệu, thực hiện từ thư mục gốc dự án:

**Bước 1:** Tìm bản sao lưu mong muốn trong thư mục `logs/backups/`.

**Bước 2:** Chạy lệnh khôi phục:

```bash
bash infra/scripts/restore.sh logs/backups/tên_file_backup.sql.gz app
```

(Thay app thành hapi nếu khôi phục HAPI FHIR Database).

**Bước 3:** Nhấn y để xác nhận và chờ tiến trình ghi đè hoàn tất.

---

### ⚠️ Thao Tác Bắt Buộc Cuối Cùng Dành Cho Máy Windows

1. Mở file `backup.sh` trong VS Code hoặc IntelliJ.
2. Nhìn xuống góc dưới cùng bên phải cửa sổ code, tìm chữ **CRLF** và đổi nó thành **LF**.
3. Làm tương tự với file `restore.sh`.
4. Mở terminal dạng **Git Bash** (đứng tại thư mục gốc dự án) và chạy lệnh test thử:
   ```bash
   bash infra/scripts/backup.sh
   ```
