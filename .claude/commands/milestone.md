---
description: Triển khai milestone tiếp theo theo quy ước repo và cập nhật MILESTONES.md
---

Triển khai một milestone theo đúng nhịp làm việc hiện tại của repo.

1. Đọc `MILESTONES.md`, mục **"Next Recommended Milestones"**. Nếu `$ARGUMENTS`
   nêu rõ milestone (vd `M1 Authentication`, `M14 Cache Management`), làm milestone đó; nếu không,
   chọn mục đầu tiên trong "Next Recommended" và xác nhận lại với tôi trước khi code.
2. Lập kế hoạch ngắn: service nào bị ảnh hưởng, file nào sửa, có cần Flyway
   migration mới không. Tuân thủ quy ước trong `CLAUDE.md` (FHIR REST, Flyway sở
   hữu schema, trả lời tiếng Việt).
3. Định tuyến công việc theo tầng: dùng subagent `spring-backend` /
   `chatbot-service` / `frontend` khi thay đổi nằm gọn trong một tầng.
4. Triển khai thay đổi.
5. Chạy test của các service liên quan (`/test` hoặc lệnh tương ứng). Sửa cho
   xanh trước khi tiếp tục.
6. **Cập nhật `MILESTONES.md`**: thêm mục milestone mới ở "Completed Milestones"
   theo đúng định dạng có sẵn (Status / Completed / Verified), và dọn mục tương
   ứng ở "Next Recommended Milestones".

Không commit trừ khi tôi yêu cầu.
