# CLAUDE.md

Medical chatbot mono-repo. Người dùng hỏi bằng ngôn ngữ tự nhiên (tiếng Việt),
hệ thống lấy dữ liệu y tế có cấu trúc qua **FHIR REST** rồi LLM sinh câu trả lời.

## Architecture map

| Service | Thư mục | Port | Vai trò |
|---|---|---|---|
| Frontend (React + Vite) | `frontend-react` | 5174 | Chat UI, lịch sử hội thoại, panel bệnh nhân. Không gọi FHIR trực tiếp. |
| Spring Boot backend | `spring-backend` | 8081 | Backend chính: auth, quota, session/message, audit/usage, gọi chatbot-service. |
| chatbot-service (FastAPI) | `chatbot-service` | 8000 | Hiểu intent, áp policy theo role, gọi FHIR, sinh câu trả lời LLM, semantic cache. |
| HAPI FHIR | `infra/hapi-fhir` | 8080 | Nguồn sự thật dữ liệu y tế (REST `/fhir`). |
| Hạ tầng | `infra/` | — | Postgres app `5433`, HAPI Postgres `5434`, Redis, Qdrant — qua Docker Compose. |

Luồng: Frontend → Spring `/api/chat` → chatbot-service `/chat` → FHIR → LLM (tiếng Việt).

## Lệnh build / test / run

| Việc | Lệnh | Thư mục |
|---|---|---|
| Bật cả stack | `.\run-dev.ps1` | gốc |
| Tắt cả stack | `.\run-dev.ps1 -Stop` | gốc |
| Test Spring | `.\mvnw.cmd test` | `spring-backend` |
| Run Spring | `.\mvnw.cmd spring-boot:run` | `spring-backend` |
| Test chatbot | `python -m unittest discover tests` | `chatbot-service` |
| Run chatbot | `uvicorn app.main:app --reload --port 8000` | `chatbot-service` |
| Typecheck frontend | `npm run typecheck` | `frontend-react` |
| Run frontend | `npm run dev` | `frontend-react` |

> **JAVA_HOME bắt buộc** cho mọi lệnh `mvnw.cmd`: `C:\Program Files\Java\jdk-21.0.10`
> (Java 21). `.claude/settings.json` đã đặt sẵn env này.

## Quy ước bắt buộc

1. **FHIR REST cho dữ liệu bệnh nhân** — KHÔNG sinh SQL vào bảng nội bộ HAPI
   (`hfj_*`). Mọi truy vấn y tế đi qua REST `/fhir`.
2. **Flyway sở hữu schema** app DB; Hibernate chạy `ddl-auto: validate`. Đổi schema
   = thêm migration `VN__...sql`, không sửa trực tiếp.
3. **LLM chỉ trả lời từ evidence đã normalize**, không bịa dữ liệu/chẩn đoán; câu
   trả lời cuối là **tiếng Việt**.
4. Chạy **test của service liên quan sau mỗi thay đổi** (xem bảng trên).
5. Hoàn thành milestone → **cập nhật `MILESTONES.md`** theo định dạng có sẵn.

## Tài liệu sâu hơn (import khi cần)

- @docs/product-spec.md — spec sản phẩm + quy tắc FHIR/usage/cache đầy đủ.
- @docs/optimization-direction.md — hướng chuyên sâu: tối ưu token/quota/cache/routing/gateway + chỉ số đánh giá.
- `docs/` — thiết kế từng module (vd `docs/M2-conversation-management.md`).

> Mỗi thư mục con có `CLAUDE.md` riêng (Claude tự nạp khi làm việc trong đó):
> `spring-backend/CLAUDE.md`, `chatbot-service/CLAUDE.md`,
> `infra/hapi-fhir/CLAUDE.md`, `infra/app-postgres/CLAUDE.md`.
