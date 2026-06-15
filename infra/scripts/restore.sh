#!/bin/bash

# Bật strict mode
set -euo pipefail

if [ -z "${1:-}" ] || [ -z "${2:-}" ]; then
    echo "Sử dụng: bash infra/scripts/restore.sh <đường_dẫn_file_backup.sql.gz> <loại_db: app hoặc hapi>"
    exit 1
fi

BACKUP_FILE=$1
DB_TYPE=$2

if [ "$DB_TYPE" == "app" ]; then
    CONTAINER_NAME="medical-chatbot-app-postgres"
    DB_USER="app_user"
    DB_NAME="medical_chatbot_app"
elif [ "$DB_TYPE" == "hapi" ]; then
    CONTAINER_NAME="medical-chatbot-hapi-postgres"
    DB_USER="admin"
    DB_NAME="hapi"
else
    echo "❌ Loại DB không hợp lệ. Chỉ chấp nhận 'app' hoặc 'hapi'."
    exit 1
fi

if [ ! -f "$BACKUP_FILE" ]; then
    echo "❌ Không tìm thấy file backup tại: $BACKUP_FILE"
    exit 1
fi

# 1. Kiểm tra tính toàn vẹn của file GZIP trước khi làm bất cứ điều gì
echo "🔎 Đang kiểm tra tính toàn vẹn của file backup..."
if ! gzip -t "$BACKUP_FILE"; then
    echo "❌ LỖI: Backup file bị hỏng hoặc không đúng định dạng!"
    exit 1
fi

# 2. Kiểm tra container
if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "❌ LỖI: Container '${CONTAINER_NAME}' không tồn tại hoặc chưa được bật."
    exit 1
fi

echo "⚠️  CẢNH BÁO: Dữ liệu hiện tại trong DB '$DB_NAME' sẽ bị xóa sạch và ghi đè hoàn toàn."
read -p "Bạn có chắc chắn muốn tiếp tục? (y/n): " confirm
if [ "$confirm" != "y" ]; then
    echo "Đã hủy thao tác phục hồi."
    exit 0
fi

echo "🔄 Đang giải nén và phục hồi dữ liệu vào $DB_TYPE DB..."

# 3. Dùng ON_ERROR_STOP=1 để ngưng ngay lập tức nếu SQL có vấn đề
gunzip -c "$BACKUP_FILE" | docker exec -i "$CONTAINER_NAME" psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME"

echo "✅ Phục hồi dữ liệu thành công!"