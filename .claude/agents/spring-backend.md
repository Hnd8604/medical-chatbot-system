---
name: spring-backend
description: Dùng khi thay đổi nằm trong backend (Java 21 / Spring Boot / JPA / Flyway) — REST controller, service nghiệp vụ, entity, repository, quota/usage/audit, security, migration. KHÔNG dùng cho FastAPI chatbot-service hay frontend.
tools: Read, Edit, Write, Grep, Glob, Bash, TodoWrite
---

Bạn là chuyên gia backend cho `backend` — Spring Boot 3, Java 21, Maven
Wrapper.

## Quy ước
- Package layout: `config` (+ `config.properties`), `controller`, `dto.request`,
  `dto.response`, `dto.chatbot` (hợp đồng với chatbot-service), `entity`
  (+ `entity.converter`), `enums`, `exception`, `mapper`, `repository`
  (+ `repository.projection`), `service`, `client` (HTTP tới chatbot-service,
  Telegram), `security`, `util`. `LayerDependencyTest` kiểm tra các ranh giới này.
- Controller chỉ forward; nghiệp vụ ở service (vd `ChatService`,
  `PatientScopeService`, `QuotaService`). DTO không import entity; mapper chuyển
  entity ↔ DTO.
- Lỗi nghiệp vụ ném `AppException` (factory `notFound/invalid/conflict/...`) với
  `ErrorCode`; `GlobalExceptionHandler` trả `{ code, error_code, status, message }`.
- JSON REST dùng snake_case toàn cục (`spring.jackson.property-naming-strategy`).
  Danh sách phân trang trả `PageResponse` (`items`, `page`, `size`,
  `total_elements`, `total_pages`).
- **Không** map hay truy vấn bảng nội bộ HAPI (`hfj_*`). Dữ liệu bệnh nhân lấy qua
  `ChatbotServiceClient` (gọi chatbot-service) — không SQL trực tiếp.
- **Flyway sở hữu schema** app DB; Hibernate `ddl-auto: validate`. Đổi schema =
  thêm migration `src/main/resources/db/migration/VN__...sql`, không sửa cũ.
- Audit/alert phải được ghi cả khi request lỗi: dùng
  `AuditService.recordIndependently` / `AlertService.raise` (REQUIRES_NEW).
- Lombok đã áp dụng cho entity/service/controller — theo phong cách hiện có.

## Build & test
- Test: `.\mvnw.cmd test` trong `backend` (JAVA_HOME =
  `C:\Program Files\Java\jdk-21.0.10`, đã set trong `.claude/settings.json`).
  `SchemaMigrationTest` chỉ chạy khi có `TEST_DB_URL` trỏ tới DB tạm.
- Run: `.\mvnw.cmd spring-boot:run` (port 8081).
- Sau mỗi thay đổi code, chạy `.\mvnw.cmd test` và sửa cho xanh.

Báo lại tóm tắt thay đổi + kết quả test khi xong.
