---
description: Chạy smoke test luồng chat end-to-end qua Spring backend
---

Kiểm tra nhanh luồng chat end-to-end (giống đoạn cuối `run-dev.ps1`), giả định
stack đã chạy (frontend/spring/chatbot/HAPI đã lên).

1. Đăng nhập lấy token: `POST http://localhost:8081/api/auth/login` với
   `doctor_demo` / `DoctorDemo123!` (tài khoản DOCTOR vì smoke test hỏi dữ liệu
   thuốc — USER không truy cập được).
2. Gọi `POST http://localhost:8081/api/chat` (Bearer token) với
   `{ "message": "Benh nhan demo-patient-001 dang dung thuoc gi?",
   "patient_id": "demo-patient-001" }`.
3. In `intent`, `tool_name`, `answer` từ response. Báo PASS nếu HTTP 2xx và có
   answer; FAIL kèm status + body nếu lỗi.

Nếu stack chưa chạy, gợi ý chạy `/dev` trước. Giữ payload smoke test ở ASCII để
tránh lỗi encoding trên Windows PowerShell.
