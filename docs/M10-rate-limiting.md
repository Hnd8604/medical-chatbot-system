# M10. Rate Limiting

Chống spam request trong thời gian ngắn bằng giới hạn số request/cửa sổ thời gian, áp dụng trước business logic. Khác với [M9 (Quota)](M9-quota-management.md) (hạn mức theo ngày) — rate limit chống burst trong 60 giây.

## M10.1 - Chính sách rate limit

**Mục tiêu:** Chống spam request trong thời gian ngắn.

**Hành vi:**
- Storage: **Redis** (`StringRedisTemplate` + Lua script `INCR`+`EXPIRE`).
- Window cố định **60 giây** (`windowInSeconds = 60`).
- Limit theo **user** (`rate_limit:user:{username}`) khi đã đăng nhập; theo **IP** (`rate_limit:ip:{ip}`) khi chưa đăng nhập.
- Limit lấy từ quota policy của user (`getRateLimitForUser`, mặc định **5**/phút; anonymous = 5).

**Tiêu chí hoàn thành:** Gửi quá nhiều request liên tục bị chặn.

## M10.2 - Enforcement layer

**Mục tiêu:** Áp rate limit trước business logic.

**Hành vi:**
- `RateLimitInterceptor` (Spring `HandlerInterceptor`) chạy ở `preHandle`, **chỉ áp cho POST** (vd `/api/chat`) → chặn trước khi gọi chatbot-service.
- Lua script atomic: `INCR` key, lần đầu set `EXPIRE 60s`; nếu count > limit → ném `RateLimitExceededException` (không ghi usage log thành công).
- Lỗi rate limit còn tạo **alert** `RATE_LIMIT_VIOLATION` (severity WARNING) — [M19](M18-M19-audit-alert.md).

**Tiêu chí hoàn thành:** Request vượt rate limit trả **429**.

## M10.3 - UI thông báo rate limit

**Mục tiêu:** Người dùng biết vì sao bị chặn.

**Hành vi:**
- Response 429 với message thân thiện tiếng Việt: *"Bạn thao tác quá nhanh, vui lòng thử lại sau ít phút."*
- Frontend bắt lỗi và hiển thị message tại chỗ (message role `error`) — không crash layout.

**Tiêu chí hoàn thành:** UI không crash khi bị rate limit.

## Luồng chương trình

```
POST request (vd /api/chat)
   ▼
RateLimitInterceptor.preHandle()   (trước controller / business logic)
   nếu không phải POST → bỏ qua
   username = currentUser hoặc null
   maxLimit = quotaService.getRateLimitForUser(username)   (mặc định 5)
   key = username ? "rate_limit:user:{username}" : "rate_limit:ip:{clientIp}"
   ▼
   Redis Lua: current = INCR(key); nếu current==1 → EXPIRE(key, 60s); return current
   ▼
   current > maxLimit ?
     ├─ có → throw RateLimitExceededException
     │         → ApiExceptionHandler: alert RATE_LIMIT_VIOLATION + HTTP 429 (message VN)
     └─ không → tiếp tục tới controller
```

## Luồng trong code

- **Interceptor + Lua script:** [RateLimitInterceptor.java:30-79](spring-backend/src/main/java/com/medicalchatbot/backend/config/RateLimitInterceptor.java#L30-L79); lấy IP ([L82-92](spring-backend/src/main/java/com/medicalchatbot/backend/config/RateLimitInterceptor.java#L82-L92)).
- **Limit theo user:** `QuotaService.getRateLimitForUser()` ([QuotaService.java:150-157](spring-backend/src/main/java/com/medicalchatbot/backend/service/QuotaService.java#L150-L157)) — `@Cacheable("rateLimitConfig")`.
- **429 + alert:** `ApiExceptionHandler.rateLimitExceeded()` ([L147-159](spring-backend/src/main/java/com/medicalchatbot/backend/exception/ApiExceptionHandler.java#L147-L159)).
- **Đăng ký interceptor:** `WebConfig`.

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| Rate limit interceptor | `spring-backend/.../config/RateLimitInterceptor.java` |
| Đăng ký interceptor | `spring-backend/.../config/WebConfig.java` |
| Limit theo user | `spring-backend/.../service/QuotaService.java` (`getRateLimitForUser`) |
| Exception + 429 | `spring-backend/.../exception/RateLimitExceededException.java`, `ApiExceptionHandler.java` |
| Migration rate limit | `db/migration/V6__add_rate_limit_to_quota.sql` |
