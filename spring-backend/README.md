# Spring Backend

Backend chính của hệ thống Medical Chatbot. Đây là lớp mà frontend gọi trực tiếp:
xử lý xác thực (JWT), quota/rate limit, phiên chat & tin nhắn, usage/audit log,
thông báo realtime (SSE), và ủy quyền phần hiểu câu hỏi + truy vấn FHIR cho
chatbot-service (FastAPI). Backend **không** truy vấn trực tiếp bảng nội bộ của
HAPI FHIR — mọi dữ liệu y tế đi qua chatbot-service → FHIR REST.

```text
Frontend (5174) → Spring Backend (8081) → chatbot-service (8000) → HAPI FHIR (8080)
                        ↓
        App PostgreSQL (5433) · Redis (6379) · LiteLLM Gateway (4000)
```

## Runtime

| Thành phần | Giá trị |
|---|---|
| Java | 21 (`JAVA_HOME = C:\Program Files\Java\jdk-21.0.10`) |
| Spring Boot | 3.5.9 |
| Port | `8081` |
| App PostgreSQL | `localhost:5433/medical_chatbot_app` (user `app_user` / `app_password`) |
| Redis | `localhost:6379` — refresh token, OTP reset mật khẩu, rate limit |
| chatbot-service | `http://localhost:8000` |
| LiteLLM Gateway | `http://localhost:4000` — cấp virtual key LLM theo user |
| Swagger UI | `http://localhost:8081/swagger-ui/index.html` |

Tài khoản demo (chỉ active ở profile `dev`; profile `prod` vô hiệu hóa các
credential công khai này):

| Username | Password | Role |
|---|---|---|
| `user_demo` | `UserDemo123!` | `USER` |
| `doctor_demo` | `DoctorDemo123!` | `DOCTOR` |
| `admin_demo` | `AdminDemo123!` | `ADMIN` |

## Chạy & test

Chạy từ thư mục `spring-backend` (cần đặt `JAVA_HOME` trỏ JDK 21 trước):

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

.\mvnw.cmd spring-boot:run   # chạy dev server
.\mvnw.cmd test              # chạy test
```

Hoặc bật cả stack (Docker infra + 3 service) từ thư mục gốc repo:

```powershell
.\run-dev.ps1
```

## Cấu hình

Cấu hình chung nằm trong `application.yml`; profile mặc định `dev` đọc
`application-dev.yml` và file `.env`. Khi deploy phải bật `prod`, dùng
`application-prod.yml` và cung cấp đầy đủ DB/JWT/CORS từ môi trường (không có
secret production mặc định). Mẫu tương ứng: `.env.example` và
`.env.prod.example`.

```env
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/medical_chatbot_app
SPRING_DATA_REDIS_HOST=localhost
CHATBOT_SERVICE_BASE_URL=http://localhost:8000
LITELLM_BASE_URL=http://localhost:4000
LITELLM_MASTER_KEY=                  # bỏ trống => bỏ qua gateway
JWT_SECRET=...                       # bắt buộc đổi khi deploy
JWT_EXPIRATION_MINUTES=30            # access token TTL ngắn
JWT_REFRESH_EXPIRATION_MINUTES=10080 # refresh token 7 ngày, lưu Redis + rotation
SPRING_MAIL_HOST=                    # bỏ trống => OTP quên mật khẩu chỉ log ra console
TELEGRAM_BOT_TOKEN=                  # tùy chọn: gửi cảnh báo admin qua Telegram
```

Không commit secret thật vào repo.

Build image backend (multi-stage, runtime non-root):

```powershell
docker build -t medical-backend .
docker run --env-file .env.prod -p 8081:8081 medical-backend
```

CI tại `.github/workflows/ci.yml` chạy unit test, kiểm tra Flyway trên PostgreSQL
rỗng, build frontend và kiểm tra Docker image.

## API chính

Mọi response JSON thành công dùng contract ổn định:

```json
{"code": 1000, "result": {}}
```

Lỗi giữ HTTP status phù hợp và trả `status`, `code`, `error_code`, `message`,
`detail`. SSE và nội dung tải xuống (PDF/CSV) không bị bọc. Frontend giải bọc
contract này tập trung tại `src/services/api.ts`.

Tài liệu đầy đủ (tham số, schema) xem tại Swagger UI. Nhóm endpoint:

### Auth — `/api/auth`

- `POST /login`, `POST /register`, `POST /logout`
- `POST /refresh` — cấp access token mới bằng refresh token (rotation)
- `GET /me`, `POST /link-patient` — liên kết tài khoản với FHIR Patient
- `POST /change-password`
- `POST /forgot-password` → `POST /verify-reset-code` → `POST /reset-password` (OTP qua email, hash lưu Redis)

### Chat & phiên hội thoại

- `POST /api/chat` — luồng chính: kiểm tra quota → tạo/tái sử dụng session → gọi chatbot-service → lưu tin nhắn + usage log + audit log
- `GET /api/chat/sessions` · `GET /api/chat/sessions/{id}/messages`
- `PATCH /api/chat/sessions/{id}` (đổi tiêu đề) · `DELETE /api/chat/sessions/{id}`
- `GET /api/chat/sessions/{id}/export` · `GET /api/chat/export` — xuất hội thoại
- `POST|PUT|DELETE /api/chat/messages/{id}/feedback` — đánh giá câu trả lời (1–5 sao)

### Proxy dữ liệu bệnh nhân (qua chatbot-service → FHIR)

- `GET /api/patients?name=&phone=&birth_date=&identifier=&limit=`
- `GET /api/patients/{id}` và các sub-resource: `/observations`, `/conditions`, `/encounters`, `/medications`

### Usage, quota, thông báo (người dùng)

- `GET /api/quota/status` — quota còn lại trong ngày (request/token/cost)
- `GET /api/usage/cost-summary` · `GET /api/model-pricing`
- `GET /api/notifications` · `GET /api/notifications/stream` (SSE realtime) · `POST /api/notifications/{id}/read`, `/read-all`
- `GET /api/metrics/cache` — chỉ số cache hit/miss

### Admin — `/api/admin/**` (role `ADMIN`)

- `users` — danh sách, đổi `status`/`role`
- `quota-policies`, `model-pricing` — CRUD chính sách quota & bảng giá model
- `quotas`, `costs` — tra cứu usage/chi phí theo user
- `analytics` — `/requests`, `/intents`, `/errors`, `/performance`
- `alerts` — cảnh báo hệ thống (resolve được), kèm `GET /api/audit-logs`

### Khác

- `GET /api/health` · `GET /api/chatbot/status`

Ví dụ gọi chat:

```powershell
$login = Invoke-RestMethod -Uri "http://localhost:8081/api/auth/login" -Method Post -ContentType "application/json" `
  -Body (@{ username = "user_demo"; password = "UserDemo123!" } | ConvertTo-Json)

Invoke-RestMethod -Uri "http://localhost:8081/api/chat" -Method Post -ContentType "application/json" `
  -Headers @{ Authorization = "Bearer $($login.result.access_token)" } `
  -Body (@{ message = "Bệnh nhân BN2026-00001 đang dùng thuốc gì?"; patient_id = "BN2026-00001" } | ConvertTo-Json)
```

## Database & Flyway

Flyway sở hữu schema (Hibernate chạy `ddl-auto: validate`). **Đổi schema = thêm
migration `VN__...sql`**, không sửa migration cũ hay sửa DB trực tiếp.

Bảng chính: `app_users`, `quota_policies`, `chat_sessions`, `chat_messages`,
`usage_logs`, `cache_entries`, `audit_logs`, `model_pricing`, `alerts`,
`message_feedback`, `notifications`, `app_user_patient_links`,
`llm_virtual_keys`.

Seed data gồm 3 user demo ở trên, quota policy mặc định, bảng giá model, và
liên kết `user_demo ↔ Patient BN2026-00001`. Migration production vô hiệu hóa
ba tài khoản demo; repeatable migration riêng của profile `dev` mới kích hoạt lại.

## Bố cục package

```text
src/main/java/com/medicalchatbot/backend/
  config/       security filter chain, JWT, rate limit interceptor, HTTP client, OpenAPI
  controller/   REST controller theo nhóm API ở trên
  dto/          request/response payload
  domain/       model nghiệp vụ độc lập với HTTP và persistence projection
  entity/       JPA entity (map với bảng Flyway)
  enums/        UserRole, UserStatus, AlertSeverity, ...
  exception/    global exception handler, QuotaExceeded, RateLimitExceeded
  integration/  outbound adapter: chatbot/LiteLLM client, backup script, notification provider
  mapper/       chuyển entity/projection/domain model sang API response
  repository/   Spring Data JPA và query contract; SQL/JDBC nằm trong repository/jdbc
  service/      orchestration và nghiệp vụ ứng dụng, không chứa SQL hay HTTP client trực tiếp
```

## Quy tắc kiến trúc

1. Luồng phụ thuộc là `controller → service → repository/integration`; entity và
   repository không phụ thuộc DTO của REST API.
2. Controller chỉ làm HTTP mapping/validation; việc gọi hệ thống ngoài phải đi
   qua application service rồi mới tới adapter trong `integration/`.
3. SQL và `JdbcTemplate` thuộc tầng repository; service chỉ xử lý use case và
   mapping kết quả. Các rule này được kiểm tra bởi `LayerDependencyTest`.
4. Frontend chỉ gọi backend này; backend gọi chatbot-service — **không** gọi
   thẳng HAPI PostgreSQL hay bảng `hfj_*`.
5. Provider key LLM chỉ nằm ở LiteLLM Gateway; backend quản lý virtual key theo
   user, không giữ key provider.
6. Chạy `.\mvnw.cmd test` sau mỗi thay đổi.
