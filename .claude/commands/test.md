---
description: Chạy toàn bộ test 3 service và báo cáo pass/fail tổng hợp
---

Chạy lần lượt 3 bộ kiểm thử của dự án, mỗi bộ trong đúng thư mục của nó. Không
dừng ở bộ đầu bị lỗi — chạy hết rồi tổng hợp.

1. **chatbot-service**: `python -m unittest discover tests` (cwd `chatbot-service`).
2. **backend**: `.\mvnw.cmd test` (cwd `backend`, JAVA_HOME đã set
   trong `.claude/settings.json` = `C:\Program Files\Java\jdk-21.0.10`).
3. **frontend**: `npm run typecheck` (cwd `frontend`).

Sau khi chạy xong, in một bảng tóm tắt: service | PASS/FAIL | số test hoặc lỗi
chính. Nếu có FAIL, trích đoạn lỗi liên quan và đề xuất hướng sửa; không tự sửa
trừ khi tôi yêu cầu.

Nếu `$ARGUMENTS` chỉ định một service (vd `spring`, `chatbot`, `frontend`), chỉ
chạy bộ test của service đó.
