#!/bin/bash

# Bật strict mode: Dừng ngay nếu có lỗi, biến không tồn tại, hoặc lỗi trong pipe
set -uo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKUP_DIR="$PROJECT_ROOT/logs/backups"
LOG_FILE="$PROJECT_ROOT/logs/backup_cron.log"
DATE=$(date +"%Y-%m-%d_%H-%M-%S")
RETENTION_DAYS=7

# Nguồn kích hoạt: "auto" (Task Scheduler 2h sáng) hoặc "manual" (nút Admin UI).
TRIGGER="${1:-auto}"

ENV_FILE="$PROJECT_ROOT/spring-backend/.env"
if [ -f "$ENV_FILE" ]; then
    set -a
    source "$ENV_FILE"
    set +a
fi

# ---- Cấu hình Google Drive qua rclone ----
# BACKUP_UPLOAD=false để tắt upload (vd rclone chưa cấu hình).
BACKUP_UPLOAD="${BACKUP_UPLOAD:-true}"
RCLONE_REMOTE="${RCLONE_REMOTE:-gdrive}"           # tên remote đã tạo bằng `rclone config`
RCLONE_DEST="${RCLONE_DEST:-medical-chatbot-backups}"  # thư mục đích trên Drive
UPLOAD_TARGET="${RCLONE_REMOTE}:${RCLONE_DEST}"

mkdir -p "$BACKUP_DIR"

STARTED_AT=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

echo "==========================================" >> "$LOG_FILE"
echo "[$(date)] Bắt đầu tiến trình sao lưu hệ thống (trigger=$TRIGGER)..." >> "$LOG_FILE"

# Mảng gom kết quả từng DB để dựng JSON ở cuối.
ITEMS_JSON=""
OVERALL_OK=true
ANY_OK=false

# Trả về true nếu rclone khả dụng và bật upload.
upload_enabled() {
    [ "$BACKUP_UPLOAD" = "true" ] && command -v rclone >/dev/null 2>&1
}

append_item() {
    local json="$1"
    if [ -n "$ITEMS_JSON" ]; then
        ITEMS_JSON="${ITEMS_JSON},${json}"
    else
        ITEMS_JSON="${json}"
    fi
}

perform_backup() {
    local DB_TYPE=$1
    local CONTAINER_NAME=$2
    local DB_USER=$3
    local DB_NAME=$4
    local FILE_NAME="${DB_TYPE}_db_backup_${DATE}.sql.gz"
    local OUTPUT_FILE="$BACKUP_DIR/${FILE_NAME}"
    local size_bytes=0
    local uploaded=false
    local ok=false

    echo "[$(date)] Đang sao lưu $DB_TYPE DB..." >> "$LOG_FILE"

    # Chạy backup, nhờ pipefail nên nếu pg_dump sập thì rơi vào nhánh else.
    if docker exec "$CONTAINER_NAME" pg_dump --clean --if-exists -U "$DB_USER" "$DB_NAME" | gzip > "$OUTPUT_FILE"; then
        ok=true
        ANY_OK=true
        size_bytes=$(wc -c < "$OUTPUT_FILE" 2>/dev/null | tr -d ' ')
        [ -z "$size_bytes" ] && size_bytes=0
        echo "[$(date)] ✅ Sao lưu thành công: $OUTPUT_FILE (${size_bytes} bytes)" >> "$LOG_FILE"

        # Upload lên Google Drive.
        if upload_enabled; then
            if rclone copy "$OUTPUT_FILE" "${UPLOAD_TARGET}/" --log-level ERROR >> "$LOG_FILE" 2>&1; then
                uploaded=true
                echo "[$(date)] ☁️  Đã upload lên $UPLOAD_TARGET/$FILE_NAME" >> "$LOG_FILE"
            else
                OVERALL_OK=false
                echo "[$(date)] ⚠️  Upload $DB_TYPE DB lên Google Drive thất bại!" >> "$LOG_FILE"
                send_alert "Upload $DB_TYPE DB ($DB_NAME) lên Google Drive thất bại lúc $(date)."
            fi
        fi
    else
        OVERALL_OK=false
        echo "[$(date)] ❌ LỖI: Sao lưu $DB_TYPE DB thất bại!" >> "$LOG_FILE"
        # Xóa ngay file rác/file hỏng
        rm -f "$OUTPUT_FILE"
        send_alert "Quá trình sao lưu $DB_TYPE DB ($DB_NAME) thất bại lúc $(date). Vui lòng kiểm tra server!"
    fi

    append_item "{\"db\":\"${DB_TYPE}\",\"file\":\"${FILE_NAME}\",\"size_bytes\":${size_bytes},\"ok\":${ok},\"uploaded\":${uploaded}}"
}

send_alert() {
    local detail="$1"
    if [ -n "${TELEGRAM_BOT_TOKEN:-}" ] && [ -n "${TELEGRAM_CHAT_ID:-}" ]; then
        local MSG="🚨 *[DATABASE BACKUP FAILED]*%0A*Mức độ:* CRITICAL%0A*Loại:* BACKUP_ERROR%0A*Chi tiết:* ${detail}"
        curl -s -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
             -d chat_id="${TELEGRAM_CHAT_ID}" \
             -d text="${MSG}" \
             -d parse_mode="Markdown" > /dev/null 2>&1 || true
    fi
}

perform_backup "app" "medical-chatbot-app-postgres" "app_user" "medical_chatbot_app"
perform_backup "hapi" "medical-chatbot-hapi-postgres" "admin" "hapi"

# Dọn dẹp bản sao cũ ở local.
echo "[$(date)] Dọn dẹp các bản sao lưu local cũ hơn $RETENTION_DAYS ngày..." >> "$LOG_FILE"
find "$BACKUP_DIR" -type f -name "*.sql.gz" -mtime +$RETENTION_DAYS -exec rm {} \; 2>/dev/null || true

# Dọn dẹp bản sao cũ trên Google Drive.
if upload_enabled; then
    echo "[$(date)] Dọn dẹp bản sao trên $UPLOAD_TARGET cũ hơn $RETENTION_DAYS ngày..." >> "$LOG_FILE"
    rclone delete --min-age "${RETENTION_DAYS}d" "$UPLOAD_TARGET" --log-level ERROR >> "$LOG_FILE" 2>&1 || true
fi

# Xác định trạng thái tổng thể.
if [ "$ANY_OK" != "true" ]; then
    STATUS="failed"
elif [ "$OVERALL_OK" = "true" ]; then
    STATUS="success"
else
    STATUS="partial"
fi

FINISHED_AT=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
UPLOAD_STR="null"
if upload_enabled; then
    UPLOAD_STR="\"${UPLOAD_TARGET}\""
fi

# Dòng JSON kết quả để Spring parse (phải nằm trên stdout, một dòng duy nhất).
echo "BACKUP_RESULT_JSON={\"trigger\":\"${TRIGGER}\",\"status\":\"${STATUS}\",\"started_at\":\"${STARTED_AT}\",\"finished_at\":\"${FINISHED_AT}\",\"upload_target\":${UPLOAD_STR},\"items\":[${ITEMS_JSON}]}"

echo "[$(date)] Hoàn tất tiến trình sao lưu (status=$STATUS)." >> "$LOG_FILE"

# Exit 0 kể cả khi partial/failed để Spring luôn đọc được JSON; trạng thái nằm trong JSON.
exit 0
