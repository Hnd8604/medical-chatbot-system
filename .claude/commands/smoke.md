---
description: Chạy smoke test luồng chat end-to-end qua Spring backend
---

Kiểm tra nhanh luồng chat end-to-end (giống đoạn cuối `run-dev.ps1`), giả định
stack đã chạy (frontend/spring/chatbot/HAPI đã lên).

1. Đăng nhập lấy token: `POST http://localhost:8081/api/v1/auth/login` (body `{ "usernameOrEmail", "password" }`) với
   `doctor_demo` / `DoctorDemo123!` (tài khoản DOCTOR vì smoke test hỏi dữ liệu
   thuốc — USER không truy cập được).
2. Gọi `POST http://localhost:8081/api/v1/chat` (Bearer token) với
   `{ "message": "Benh nhan BN2026-00001 dang dung thuoc gi?",
   "patientId": "BN2026-00001" }`.
3. In `intent`, `toolName`, `answer` từ response. Báo PASS nếu HTTP 2xx và có
   answer; FAIL kèm status + body nếu lỗi.

Nếu stack chưa chạy, gợi ý chạy `/dev` trước. Giữ payload smoke test ở ASCII để
tránh lỗi encoding trên Windows PowerShell.
