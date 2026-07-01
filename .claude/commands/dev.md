---
description: Bật hoặc tắt toàn bộ dev stack qua run-dev.ps1
---

Điều khiển dev stack bằng script `run-dev.ps1` ở thư mục gốc.

- Nếu `$ARGUMENTS` chứa `stop`: chạy `.\run-dev.ps1 -Stop` để tắt sạch các
  service và container (volumes giữ nguyên).
- Ngược lại: chạy `.\run-dev.ps1` để bật cả stack (Docker infra → HAPI FHIR →
  seed data nếu cần → chatbot-service `8000` → spring-backend `8081` → frontend
  `5174`), kèm smoke chat-flow ở cuối.
  - Cho phép truyền thêm cờ trong `$ARGUMENTS`, vd `-SkipInstall`, `-SkipSeed`,
    `-SkipFlowCheck`.

Đây là lệnh chạy lâu và khởi động nhiều process nền — chạy ở chế độ nền và báo
lại khi stack sẵn sàng, kèm bảng URL (8080/8000/8081/5174). Nếu một service không
lên, đọc log tương ứng trong `logs/` để chẩn đoán.
