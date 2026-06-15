#!/bin/bash

# Bật strict mode: Dừng ngay nếu có lỗi, biến không tồn tại, hoặc lỗi trong pipe
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKUP_DIR="$PROJECT_ROOT/logs/backups"
LOG_FILE="$PROJECT_ROOT/logs/backup_cron.log"
DATE=$(date +"%Y-%m-%d_%H-%M-%S")
RETENTION_DAYS=7


ENV_FILE="$PROJECT_ROOT/spring-backend/.env"
if [ -f "$ENV_FILE" ]; then
    set -a
    source "$ENV_FILE"
    set +a
fi

mkdir -p "$BACKUP_DIR"

echo "==========================================" >> "$LOG_FILE"
echo "[$(date)] Bắt đầu tiến trình sao lưu hệ thống..." >> "$LOG_FILE"

perform_backup() {
    local DB_TYPE=$1
    local CONTAINER_NAME=$2
    local DB_USER=$3
    local DB_NAME=$4
    local OUTPUT_FILE="$BACKUP_DIR/${DB_TYPE}_db_backup_${DATE}.sql.gz"

    echo "[$(date)] Đang sao lưu $DB_TYPE DB..." >> "$LOG_FILE"

    # Chạy backup, nhờ pipefail nên nếu pg_dump sập thì tiến trình sẽ nhảy vào nhánh else
    if docker exec "$CONTAINER_NAME" pg_dump --clean --if-exists -U "$DB_USER" "$DB_NAME" | gzip > "$OUTPUT_FILE"; then
        echo "[$(date)] ✅ Sao lưu thành công: $OUTPUT_FILE" >> "$LOG_FILE"
    else
        echo "[$(date)] ❌ LỖI: Sao lưu $DB_TYPE DB thất bại!" >> "$LOG_FILE"
        # Xóa ngay file rác/file hỏng
        rm -f "$OUTPUT_FILE"
        
        # Bắn Telegram Alert
        if [ -n "${TELEGRAM_BOT_TOKEN:-}" ] && [ -n "${TELEGRAM_CHAT_ID:-}" ]; then
            local MSG="🚨 *[DATABASE BACKUP FAILED]*%0A*Mức độ:* CRITICAL%0A*Loại:* BACKUP_ERROR%0A*Chi tiết:* Quá trình sao lưu $DB_TYPE DB ($DB_NAME) thất bại lúc $(date). Vui lòng kiểm tra server!"
            curl -s -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
                 -d chat_id="${TELEGRAM_CHAT_ID}" \
                 -d text="${MSG}" \
                 -d parse_mode="Markdown" > /dev/null
        fi
    fi
}

perform_backup "app" "medical-chatbot-app-postgres" "app_user" "medical_chatbot_app"
perform_backup "hapi" "medical-chatbot-hapi-postgres" "admin" "hapi"

echo "[$(date)] Dọn dẹp các bản sao lưu cũ hơn $RETENTION_DAYS ngày..." >> "$LOG_FILE"
find "$BACKUP_DIR" -type f -name "*.sql.gz" -mtime +$RETENTION_DAYS -exec rm {} \;

echo "[$(date)] Hoàn tất tiến trình sao lưu." >> "$LOG_FILE"