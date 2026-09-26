# Spring Backend Notes

Backend Spring Boot chính của Medical Chatbot. Frontend chỉ gọi backend này;
backend chịu trách nhiệm auth, phân quyền, quota/rate limit, lịch sử hội thoại,
usage/cost, audit/alert/notification và gọi các hệ thống ngoài qua adapter trong
`integration/`.

## Runtime và lệnh chính

- Java 21, Spring Boot 3.5.x, port mặc định `8081`.
- App PostgreSQL `5433`, Redis `6379`, chatbot-service `8000`, LiteLLM `4000`.
- Flyway sở hữu schema; Hibernate chạy `ddl-auto: validate`.

Chạy từ thư mục `backend`:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd spring-boot:run
.\mvnw.cmd test
```

## Kiến trúc package

```text
src/main/java/com/medicalchatbot/backend/
  config/       security, HTTP client configuration, OpenAPI, response advice
  controller/   ánh xạ HTTP và validation; không gọi adapter trực tiếp
  dto/          contract request/response của REST API
  domain/       value object nghiệp vụ độc lập với REST API
  entity/       JPA entity khớp schema Flyway
  enums/        enum nghiệp vụ
  exception/    AppException, ErrorCode và global exception handler
  integration/  outbound adapter: chatbot/LiteLLM, backup script, Telegram
  mapper/       chuyển domain/entity/projection sang API DTO
  repository/   Spring Data và query contract
    projection/ read model tối giản cho kết quả query
    jdbc/       implementation custom SQL/JdbcTemplate
  service/      orchestration use case và nghiệp vụ ứng dụng
  utils/        tiện ích dùng chung
```

Luồng phụ thuộc chính:

```text
controller → service → repository / integration
                         ↓
                  projection / entity
```

Các rule được khóa bằng `LayerDependencyTest`:

1. Entity/repository không phụ thuộc REST DTO.
2. Controller không gọi `integration` trực tiếp.
3. Service không chứa `JdbcTemplate` hoặc SQL implementation.
4. Service dùng `AppException` + `ErrorCode`, không dùng exception HTTP làm lỗi nghiệp vụ.
5. Outbound adapter không phụ thuộc ngược vào service.

## Quy tắc quan trọng

- Không query trực tiếp database hoặc bảng nội bộ HAPI (`hfj_*`). Dữ liệu y tế
  phải đi qua chatbot-service → FHIR REST API.
- Không sửa migration Flyway đã tồn tại; thay đổi schema phải thêm migration mới.
- Provider key LLM chỉ nằm trong LiteLLM Gateway. Backend chỉ quản lý virtual key.
- Không log token, secret, raw FHIR Bundle hoặc response body upstream chứa PHI.
- Sau khi đổi code, chạy `.\mvnw.cmd test`; test PostgreSQL thật cần `TEST_DB_URL`.

## API chính

- `/api/auth/**`: login, register, refresh rotation, profile, liên kết patient,
  đổi/quên mật khẩu và logout.
- `/api/chat`, `/api/chat/sessions/**`: chat, history, rename/delete, export, feedback.
- `/api/patients/**`: proxy dữ liệu bệnh nhân qua chatbot-service.
- `/api/quota/status`, `/api/usage/cost-summary`, `/api/model-pricing`.
- `/api/notifications/**`, `/api/metrics/cache`, `/api/audit-logs`.
- `/api/admin/**`: users, quota, pricing, costs, analytics, alerts, backup/restore.

Chi tiết runtime, cấu hình và endpoint nằm trong `README.md`; tài liệu luồng nghiệp
vụ nằm trong `../docs/`.
