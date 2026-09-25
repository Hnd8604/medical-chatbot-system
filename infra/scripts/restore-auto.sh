#!/bin/bash

# Khôi phục KHÔNG TƯƠNG TÁC app DB + HAPI DB — dùng bởi nút "Khôi phục" ở Admin UI
# (Spring gọi qua bash, xác nhận đã làm ở UI). CẨN THẬN: ghi đè dữ liệu hiện tại.
#
# Bản tương tác cho người dùng chạy tay là infra/scripts/restore.sh.
#
# Cách dùng: restore-auto.sh <app_file.sql.gz> <hapi_file.sql.gz>
#   - Tham số là TÊN FILE (basename) trong logs/backups/. Để "" nếu không restore DB đó.
#   - Nếu file không có ở local, tự tải từ Google Drive (rclone) khi BACKUP_UPLOAD=true.

set -uo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKUP_DIR="$PROJECT_ROOT/logs/backups"
LOG_FILE="$PROJECT_ROOT/logs/backup_cron.log"

APP_FILE="${1:-}"
HAPI_FILE="${2:-}"

ENV_FILE="$PROJECT_ROOT/backend/.env"
if [ -f "$ENV_FILE" ]; then
    set -a
    source "$ENV_FILE"
    set +a
fi

BACKUP_UPLOAD="${BACKUP_UPLOAD:-true}"
RCLONE_REMOTE="${RCLONE_REMOTE:-gdrive}"
RCLONE_DEST="${RCLONE_DEST:-medical-chatbot-backups}"
UPLOAD_TARGET="${RCLONE_REMOTE}:${RCLONE_DEST}"
# Binary rclone; đặt path tuyệt đối trong .env nếu không có trên PATH (xem backup.sh).
RCLONE_BIN="${RCLONE_BIN:-rclone}"

mkdir -p "$BACKUP_DIR"

echo "==========================================" >> "$LOG_FILE"
echo "[$(date)] Bắt đầu KHÔI PHỤC (app=$APP_FILE, hapi=$HAPI_FILE)..." >> "$LOG_FILE"

ITEMS_JSON=""
OVERALL_OK=true
ANY_OK=false

download_enabled() {
    [ "$BACKUP_UPLOAD" = "true" ] && command -v "$RCLONE_BIN" >/dev/null 2>&1
}

append_item() {
    local json="$1"
    if [ -n "$ITEMS_JSON" ]; then
        ITEMS_JSON="${ITEMS_JSON},${json}"
    else
        ITEMS_JSON="${json}"
    fi
}

# Đảm bảo file có ở local; nếu thiếu thì tải từ Drive. Trả 0 nếu sẵn sàng.
ensure_file() {
    local fname="$1"
    local path="$BACKUP_DIR/$fname"
    if [ -f "$path" ]; then
        return 0
    fi
    if download_enabled; then
        echo "[$(date)] File $fname không có local, tải từ $UPLOAD_TARGET..." >> "$LOG_FILE"
        if "$RCLONE_BIN" copy "${UPLOAD_TARGET}/${fname}" "$BACKUP_DIR/" --log-level ERROR >> "$LOG_FILE" 2>&1 && [ -f "$path" ]; then
            return 0
        fi
    fi
    return 1
}

restore_db() {
    local DB_TYPE=$1
    local CONTAINER_NAME=$2
    local DB_USER=$3
    local DB_NAME=$4
    local FILE_NAME=$5
    local HAPI_APP_CONTAINER=${6:-}   # container HAPI cần stop/start khi restore DB của nó
    local ok=false
    local err=""

    if [ -z "$FILE_NAME" ]; then
        return 0   # bỏ qua DB không được yêu cầu
    fi

    echo "[$(date)] Đang khôi phục $DB_TYPE DB từ $FILE_NAME..." >> "$LOG_FILE"

    if ! ensure_file "$FILE_NAME"; then
        OVERALL_OK=false
        err="Khong tim thay file backup (local lan Google Drive)."
        echo "[$(date)] ❌ $DB_TYPE: $err" >> "$LOG_FILE"
        append_item "{\"db\":\"${DB_TYPE}\",\"file\":\"${FILE_NAME}\",\"ok\":false,\"error\":\"${err}\"}"
        return 0
    fi

    local path="$BACKUP_DIR/$FILE_NAME"

    # Kiểm tra toàn vẹn file gzip trước khi động vào DB.
    if ! gzip -t "$path" >> "$LOG_FILE" 2>&1; then
        OVERALL_OK=false
        err="File backup hong hoac sai dinh dang gzip."
        echo "[$(date)] ❌ $DB_TYPE: $err" >> "$LOG_FILE"
        append_item "{\"db\":\"${DB_TYPE}\",\"file\":\"${FILE_NAME}\",\"ok\":false,\"error\":\"${err}\"}"
        return 0
    fi

    # Tắt HAPI trước khi restore DB của nó để tránh tranh chấp kết nối khi DROP bảng.
    if [ -n "$HAPI_APP_CONTAINER" ]; then
        docker stop "$HAPI_APP_CONTAINER" >> "$LOG_FILE" 2>&1 || true
    fi

    if gunzip -c "$path" | docker exec -i "$CONTAINER_NAME" psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" >> "$LOG_FILE" 2>&1; then
        ok=true
        ANY_OK=true
        echo "[$(date)] ✅ Khôi phục $DB_TYPE DB thành công." >> "$LOG_FILE"
    else
        OVERALL_OK=false
        err="Loi khi nap dump vao PostgreSQL."
        echo "[$(date)] ❌ Khôi phục $DB_TYPE DB thất bại!" >> "$LOG_FILE"
    fi

    # Bật lại HAPI dù thành công hay lỗi.
    if [ -n "$HAPI_APP_CONTAINER" ]; then
        docker start "$HAPI_APP_CONTAINER" >> "$LOG_FILE" 2>&1 || true
    fi

    append_item "{\"db\":\"${DB_TYPE}\",\"file\":\"${FILE_NAME}\",\"ok\":${ok},\"error\":\"${err}\"}"
}

restore_db "app"  "medical-chatbot-app-postgres"  "app_user" "medical_chatbot_app" "$APP_FILE"  ""
restore_db "hapi" "medical-chatbot-hapi-postgres" "admin"    "hapi"                "$HAPI_FILE" "medical-chatbot-hapi-fhir"

if [ "$ANY_OK" != "true" ]; then
    STATUS="failed"
elif [ "$OVERALL_OK" = "true" ]; then
    STATUS="success"
else
    STATUS="partial"
fi

FINISHED_AT=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
echo "RESTORE_RESULT_JSON={\"status\":\"${STATUS}\",\"finished_at\":\"${FINISHED_AT}\",\"items\":[${ITEMS_JSON}]}"

echo "[$(date)] Hoàn tất khôi phục (status=$STATUS)." >> "$LOG_FILE"
exit 0
