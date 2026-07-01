# Auth: Access Token + Refresh Token (rotation, Redis)

Tài liệu này mô tả cơ chế xác thực JWT của `spring-backend`: access token ngắn hạn +
refresh token có **rotation**, lưu trong **Redis**. Áp dụng cho dữ liệu y tế nên ưu tiên
TTL ngắn và khả năng thu hồi phiên.

## 1. Tổng quan

| Loại token | Dạng | Nơi xác thực | TTL mặc định | Thu hồi |
|---|---|---|---|---|
| **Access token** | JWT (HMAC, jjwt) | `JwtAuthenticationFilter` mỗi request | 30 phút (`jwt.expiration-minutes`) | qua `token_version` |
| **Refresh token** | Opaque `{jti}.{secret}` | `RefreshTokenService` khi gọi `/refresh` | 7 ngày (`jwt.refresh-expiration-minutes`) | xóa khỏi Redis / `token_version` |

- Access token mang claim: `sub`=userId, `username`, `token_version`, `exp`. Không đụng tới
  flow validate sẵn có ([JwtTokenService](../spring-backend/src/main/java/com/medicalchatbot/backend/service/JwtTokenService.java),
  [JwtAuthenticationFilter](../spring-backend/src/main/java/com/medicalchatbot/backend/config/JwtAuthenticationFilter.java)).
- Refresh token **không phải JWT** — là chuỗi ngẫu nhiên opaque, Redis chỉ lưu **hash SHA-256**
  của phần secret.

## 2. Endpoint

| Method | Path | Auth | Mô tả |
|---|---|---|---|
| POST | `/api/auth/login` | public | Trả `access_token` + `refresh_token` |
| POST | `/api/auth/refresh` | public | Nhận `refresh_token`, trả cặp token mới (rotation) |
| POST | `/api/auth/logout` | bearer | Tăng `token_version` + thu hồi mọi refresh token của user |

### `POST /api/auth/login`
Request:
```json
{ "username_or_email": "doctor_demo", "password": "DoctorDemo123!" }
```
Response:
```json
{
  "access_token": "<jwt>",
  "refresh_token": "<jti>.<secret>",
  "token_type": "Bearer",
  "expires_in_seconds": 1800,
  "user": { "username": "doctor_demo", "role": "DOCTOR", "...": "..." }
}
```

### `POST /api/auth/refresh`
Request:
```json
{ "refresh_token": "<jti>.<secret>" }
```
Response:
```json
{
  "access_token": "<jwt-moi>",
  "refresh_token": "<jti-moi>.<secret-moi>",
  "token_type": "Bearer",
  "expires_in_seconds": 1800
}
```
Lỗi: `401` nếu refresh token sai định dạng / đã bị xoay / hết hạn / `token_version` lệch;
`403` nếu tài khoản `LOCKED`/`DISABLED`.

## 3. Lưu trữ Redis

| Key | Kiểu | Giá trị | TTL |
|---|---|---|---|
| `refresh_token:{jti}` | string | `{userId}\|{tokenVersion}\|{sha256(secret)}` | = refresh TTL |
| `refresh_token:user:{userId}` | set | tập các `jti` đang sống của user | = refresh TTL |

- Mỗi key tự hết hạn theo TTL ⇒ **không cần job dọn dẹp**.
- Chỉ lưu hash của secret ⇒ lộ Redis cũng không dùng lại được token.
- Set theo user phục vụ "đăng xuất mọi thiết bị" (`revokeAllForUser`) và có thể mở rộng
  liệt kê/thu hồi theo từng phiên.

## 4. Rotation (xoay vòng refresh token)

### 4.1 Vấn đề rotation giải quyết

Refresh token sống lâu (7 ngày) nên nó là "chìa khóa dài hạn": nếu bị lộ (XSS, log, máy
nhiễm mã độc), kẻ tấn công có thể tạo access token mới suốt cả tuần mà không bị phát hiện.
Access token ngắn (30 phút) ít rủi ro hơn nhiều.

**Ý tưởng rotation**: mỗi refresh token chỉ dùng được **đúng một lần**. Mỗi lần `/refresh`,
server hủy token cũ và cấp token mới. Nhờ đó token cũ "chết" ngay, và — quan trọng hơn —
việc một token cũ bị dùng lại trở thành **tín hiệu phát hiện đánh cắp** (mục 4.4).

### 4.2 Không rotation vs Có rotation

```text
KHÔNG rotation:
  login   → refresh_A
  refresh → access mới        (refresh_A vẫn sống)
  refresh → access mới        (vẫn refresh_A)
  ⇒ refresh_A dùng tới khi hết hạn. Lộ = nguy 7 ngày.

CÓ rotation (bản này):
  login      → refresh_A
  refresh(A) → access mới + refresh_B   (A bị xóa khỏi Redis)
  refresh(B) → access mới + refresh_C   (B bị xóa)
  ⇒ mỗi token chỉ dùng 1 lần.
```

### 4.3 Cài đặt trong `RefreshTokenService.rotate()`

Token gửi cho client có dạng `{jti}.{secret}` — `jti` làm khóa Redis, `secret` chỉ được
Redis lưu dưới dạng hash SHA-256. Các bước:

```text
1. Tách jti + secret. Sai định dạng → 401.
2. value = redis.get("refresh_token:" + jti)
       null → 401  (token đã bị xoay hoặc hết hạn)
3. Từ value lấy: userId | tokenVersion | storedHash
4. So sánh sha256(secret) với storedHash (constant-time) → lệch thì 401 (token giả mạo).
5. ROTATION: redis.delete(jti) + xóa jti khỏi set "refresh_token:user:{userId}".
6. return (userId, tokenVersion)
```

Bước 5 là rotation: xóa bản ghi Redis của token vừa dùng. Sau đó `AuthService.refresh()` gọi
`issue(user)` để cấp `jti` mới ⇒ cặp **delete-cũ + issue-mới** = "xoay". Token cũ tự chết vì
client chỉ giữ chuỗi `jti.secret`, mà bản ghi `refresh_token:{jti}` đã bị xóa → lần sau
`redis.get` trả `null` → 401.

### 4.4 Reuse detection — lợi ích lớn nhất

```text
- Client thật và kẻ trộm cùng có refresh_A.
- Client thật refresh(A) trước  → A bị xóa, nhận refresh_B.
- Kẻ trộm  refresh(A) sau        → A không còn trong Redis → 401.
  ⇒ "Token đã dùng mà còn xuất hiện lần nữa" = dấu hiệu bị đánh cắp.
```

Bản hiện tại từ chối (401) token đã xoay. Ngoài ra `AuthService.refresh()` còn gọi
`revokeAllForUser()` khi phát hiện bất thường (vd `token_version` lệch) để dọn sạch mọi
refresh token của user — phản ứng "nghi ngờ thì khóa hết". Mức nâng cao hơn (ngoài phạm vi):
khi bắt được reuse thì revoke ngay cả chuỗi token đang sống; set `refresh_token:user:{userId}`
đã có sẵn để mở rộng.

### 4.5 Vì sao phải dùng Redis (stateful)

Rotation **bắt buộc** server phải nhớ "token nào còn sống" → cần trạng thái. Vì vậy refresh
token là chuỗi opaque lưu Redis, **không** dùng JWT thuần:

- JWT thuần là *stateless* → không thu hồi được một token cụ thể ⇒ không rotation thật được.
- Redis lưu `refresh_token:{jti}` với TTL = hạn refresh → hết hạn tự xóa (không cần job dọn),
  và `delete` cho phép hủy tức thì khi xoay.

### 4.6 Quan hệ với `token_version`

Hai cơ chế thu hồi bổ trợ nhau:

| Cơ chế | Phạm vi | Kích hoạt khi |
|---|---|---|
| **Rotation** (xóa `jti`) | từng refresh token | mỗi lần `/refresh` |
| **token_version** (`++` trong DB) | toàn bộ token của user | logout, đổi mật khẩu |

Khi `logout`: `token_version++` (giết mọi access token) **và** `revokeAllForUser()` (xóa mọi
refresh token Redis) ⇒ sạch hoàn toàn.

### 4.7 Tóm tắt luồng

```text
login                → issue access + issue refresh (lưu jti_1 vào Redis)
refresh(jti_1.sec)   → rotate: xóa jti_1, cấp jti_2 ; trả access+refresh mới
refresh(jti_1.sec)   → jti_1 không còn trong Redis ⇒ 401 (token cũ đã dùng lại)
logout               → token_version++ ; revokeAllForUser ⇒ mọi access & refresh hết hiệu lực
```

## 5. Cấu hình

`application.yml` (`spring-backend`):
```yaml
jwt:
  secret: ${JWT_SECRET:...}                 # KHÔNG commit secret thật
  expiration-minutes: ${JWT_EXPIRATION_MINUTES:30}            # access token
  refresh-expiration-minutes: ${JWT_REFRESH_EXPIRATION_MINUTES:10080}  # refresh = 7 ngày
```
Phụ thuộc Redis đã có sẵn (`spring-boot-starter-data-redis`, `spring.data.redis.*`), tái dùng
`StringRedisTemplate` — **không thêm hạ tầng mới, không cần Flyway migration**.

## 6. Thành phần code

| File | Vai trò |
|---|---|
| [RefreshTokenService](../spring-backend/src/main/java/com/medicalchatbot/backend/service/RefreshTokenService.java) | issue / rotate / revokeAllForUser; hash SHA-256; Redis |
| [JwtProperties](../spring-backend/src/main/java/com/medicalchatbot/backend/config/JwtProperties.java) | thêm `refreshExpirationMinutes` |
| [AuthService](../spring-backend/src/main/java/com/medicalchatbot/backend/service/AuthService.java) | `login` (cấp 2 token), `refresh` (rotation), `logout` (revoke all) |
| [AuthController](../spring-backend/src/main/java/com/medicalchatbot/backend/controller/AuthController.java) | thêm `POST /api/auth/refresh` |
| [SecurityConfig](../spring-backend/src/main/java/com/medicalchatbot/backend/config/SecurityConfig.java) | whitelist `/api/auth/refresh` |
| DTO | `AuthLoginResponse` (+`refresh_token`), `AuthRefreshRequest`, `AuthRefreshResponse` |

## 7. Frontend (đã triển khai)

`frontend-react` dùng `fetch` (không Axios). Logic refresh nằm tập trung trong
[services/api.ts](../frontend-react/src/services/api.ts):

- **Lưu token**: cả access và refresh token lưu trong `sessionStorage`
  (`ACCESS_TOKEN_KEY`, `REFRESH_TOKEN_KEY`). Các helper `getAccessToken`/`setTokens`/
  `clearTokens` là nguồn sự thật duy nhất.
- **Tự refresh khi 401**: `fetchWithAuth` gắn `Authorization`, nếu nhận `401` và còn refresh
  token thì gọi `POST /api/auth/refresh`, lưu cặp token mới rồi **thử lại request gốc** một lần.
- **Single-flight**: nhiều request 401 đồng thời chỉ kích hoạt **một** lần gọi `/refresh`
  (biến `refreshPromise`), tránh "refresh storm" và việc rotation hủy nhầm token mới.
- **Phiên hết hiệu lực**: nếu `/refresh` cũng thất bại → `clearTokens()` và phát sự kiện
  `SESSION_EXPIRED_EVENT`. [hooks/useAuth.tsx](../frontend-react/src/hooks/useAuth.tsx) lắng nghe
  sự kiện này để xóa `user`/`token` ⇒ router tự đưa về trang đăng nhập.
- **Login**: lưu cả `access_token` + `refresh_token` (`AuthLoginResponse.refresh_token`).
- **Logout**: gọi `POST /api/auth/logout` rồi `clearTokens()`.

> `/api/auth/login` và `/api/auth/refresh` được loại khỏi vòng auto-refresh để tránh đệ quy.

## 8. Kiểm thử

`spring-backend`, chạy `./mvnw.cmd test` (JAVA_HOME = JDK 21):

- [RefreshTokenServiceTest](../spring-backend/src/test/java/com/medicalchatbot/backend/service/RefreshTokenServiceTest.java):
  issue lưu hash; rotate trả đúng chủ sở hữu + xóa token cũ; dùng lại token đã xoay → 401;
  token sai định dạng → 401; secret bị giả mạo → 401.
- [AuthServiceTest](../spring-backend/src/test/java/com/medicalchatbot/backend/service/AuthServiceTest.java):
  login trả cả refresh token; refresh xoay token cho user active; refresh bị từ chối khi
  `token_version` lệch (kèm revoke); refresh bị từ chối khi tài khoản LOCKED; logout revoke all.

Kết quả gần nhất: **63 test pass, BUILD SUCCESS**.

## 9. Giới hạn hiện tại / hướng mở rộng

- Logout hiện là "đăng xuất mọi thiết bị" (vì `token_version` toàn cục). Muốn thu hồi theo
  từng thiết bị: dùng set `refresh_token:user:{userId}` để xóa đúng `jti` của phiên đó.
- Chưa gắn metadata thiết bị/IP vào refresh token (có thể thêm vào value Redis để audit phiên).
- Đổi mật khẩu nên gọi `token_version++` để vô hiệu mọi phiên cũ (khi triển khai chức năng này).
