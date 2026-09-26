# Auth: Quên mật khẩu (OTP email) & Đổi mật khẩu

Tài liệu mô tả hai luồng mật khẩu của `backend`:

1. **Quên mật khẩu** (public): người dùng nhập email → nhận **mã OTP 6 số** qua email →
   xác thực mã lấy **reset ticket** ngắn hạn → đặt lại mật khẩu mới. Mã và ticket lưu trong
   **Redis** (hash SHA-256, TTL), **không cần Flyway migration**.
2. **Đổi mật khẩu** (đã đăng nhập): người dùng cung cấp **mật khẩu hiện tại** + mật khẩu mới
   qua một endpoint yêu cầu xác thực — xem [§10](#10-đổi-mật-khẩu-khi-đã-đăng-nhập).

Bổ sung cho [M1 Authentication](M1-authentication.md) và [Refresh Token](auth-refresh-token.md).

## 1. Tổng quan (quên mật khẩu)

Luồng **3 bước**, mỗi bước một endpoint public:

```text
B1  POST /api/auth/forgot-password    { email }                         → gửi mã OTP qua email
B2  POST /api/auth/verify-reset-code  { email, code }                   → trả reset_ticket
B3  POST /api/auth/reset-password     { reset_ticket, new_password, ... } → đổi mật khẩu
```

- **Mã OTP**: 6 chữ số (`SecureRandom`), Redis chỉ lưu **hash SHA-256** + số lần nhập sai.
- **Reset ticket**: opaque `{jti}.{secret}`, cấp sau khi xác thực mã đúng, **dùng một lần**.
  Tách bước xác thực khỏi bước đổi mật khẩu nên không phải mang lại mã OTP ở B3 và giảm
  brute-force.
- Sau khi đổi mật khẩu: `token_version++` + `revokeAllForUser()` ⇒ **mọi phiên cũ hết hiệu lực**
  (đăng xuất khỏi mọi thiết bị).
- Gửi email dùng SMTP thật; **nếu chưa cấu hình SMTP thì dev fallback**: log mã ra console.

## 2. Endpoint

| Method | Path | Auth | Mô tả |
|---|---|---|---|
| POST | `/api/auth/forgot-password` | public | Gửi mã OTP tới email của tài khoản |
| POST | `/api/auth/verify-reset-code` | public | Xác thực mã, trả `reset_ticket` |
| POST | `/api/auth/reset-password` | public | Dùng ticket đặt lại mật khẩu |

### `POST /api/auth/forgot-password`
Request:
```json
{ "email": "user_demo@medical-chatbot.local" }
```
Response `200`:
```json
{ "message": "Đã gửi mã đặt lại mật khẩu tới email của bạn." }
```
Lỗi: `404` nếu email không tồn tại; `403` nếu tài khoản không `ACTIVE` (LOCKED/DISABLED).
Gửi lại quá nhanh (trong `resend-cooldown-seconds`) sẽ không phát mã mới nhưng vẫn trả `200`.

### `POST /api/auth/verify-reset-code`
Request:
```json
{ "email": "user_demo@medical-chatbot.local", "code": "482913" }
```
Response `200`:
```json
{ "reset_ticket": "<jti>.<secret>", "message": "Mã xác thực hợp lệ." }
```
Lỗi: `400` nếu mã sai / hết hạn. Sai quá `max-attempts` lần thì mã bị vô hiệu.

### `POST /api/auth/reset-password`
Request:
```json
{
  "reset_ticket": "<jti>.<secret>",
  "new_password": "NewPass123",
  "password_confirmation": "NewPass123"
}
```
Response `200`:
```json
{ "message": "Đặt lại mật khẩu thành công. Vui lòng đăng nhập lại." }
```
Lỗi: `400` nếu mật khẩu không đạt (8–72 byte, ≥1 chữ + ≥1 số, xác nhận khớp) hoặc ticket
sai / hết hạn / đã dùng. Mật khẩu được **kiểm tra trước khi tiêu thụ ticket** để mật khẩu lỗi
không làm mất ticket.

## 3. Lưu trữ Redis

| Key | Kiểu | Giá trị | TTL |
|---|---|---|---|
| `password_reset:otp:{email}` | string | `{attempts}\|{sha256(code)}` | `otp-ttl-minutes` |
| `password_reset:cooldown:{email}` | string | `1` | `resend-cooldown-seconds` |
| `password_reset:ticket:{jti}` | string | `{sha256(secret)}\|{email}` | `ticket-ttl-minutes` |

- Email được chuẩn hóa `lower(strip(...))` trước khi làm khóa.
- Chỉ lưu hash ⇒ lộ Redis cũng không tái tạo được mã/ticket. So sánh **constant-time**
  (`MessageDigest.isEqual`). Mỗi key tự hết hạn ⇒ **không cần job dọn dẹp**.
- Tái dùng nguyên tắc của [RefreshTokenService](../backend/src/main/java/com/medicalchatbot/backend/service/RefreshTokenService.java)
  (`StringRedisTemplate`, hash, TTL).

## 4. Luồng trong code

```text
forgot-password → PasswordResetService.forgotPassword
    findByEmailIgnoreCase → 404 nếu không có; 403 nếu không ACTIVE
    otpService.issueCode(email)  (bỏ qua nếu đang cooldown)
    emailService.sendPasswordResetCode(email, code)   (SMTP hoặc log dev)
    audit PASSWORD_RESET_REQUEST

verify-reset-code → PasswordResetService.verifyResetCode
    otpService.verifyCode(email, code):
        so hash constant-time; sai → attempts++ (giữ TTL), chạm max → xóa mã, 400
        đúng → xóa mã, cấp ticket {jti}.{secret} (lưu hash secret + email)
    trả reset_ticket

reset-password → PasswordResetService.resetPassword
    PasswordPolicy.validate(new_password, confirmation)   (trước, để không mất ticket)
    otpService.consumeTicket(ticket) → email (xóa ticket, dùng một lần)
    findByEmailIgnoreCase(email) → 400 nếu không có
    passwordHash = BCrypt(new_password); incrementTokenVersion(); save
    refreshTokenService.revokeAllForUser(id)    → thu hồi mọi refresh token
    audit PASSWORD_RESET_SUCCESS
```

Luật mật khẩu được tách vào [PasswordPolicy](../backend/src/main/java/com/medicalchatbot/backend/service/PasswordPolicy.java)
và dùng chung với `AuthService.register()`.

## 5. Gửi email & dev fallback

[EmailService](../backend/src/main/java/com/medicalchatbot/backend/service/EmailService.java)
nhận `ObjectProvider<JavaMailSender>`:

- `spring.mail.host` có giá trị ⇒ Spring Boot tạo bean `JavaMailSender` ⇒ gửi `SimpleMailMessage`
  (from/subject/body tiếng Việt, nêu thời hạn mã).
- `spring.mail.host` **rỗng** (mặc định) ⇒ không có bean ⇒ log
  `[DEV] ... Ma dat lai mat khau cho {email}: {code}` để test local.
- Lỗi gửi mail được nuốt và chỉ log nội bộ, không làm hỏng luồng.

## 6. Cấu hình

`application.yml` (`backend`):
```yaml
spring:
  mail:
    host: ${SPRING_MAIL_HOST:}          # rỗng => dev fallback (log mã)
    port: ${SPRING_MAIL_PORT:587}
    username: ${SPRING_MAIL_USERNAME:}
    password: ${SPRING_MAIL_PASSWORD:}
    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true
password-reset:
  otp-ttl-minutes: ${PASSWORD_RESET_OTP_TTL_MINUTES:10}
  ticket-ttl-minutes: ${PASSWORD_RESET_TICKET_TTL_MINUTES:10}
  max-attempts: ${PASSWORD_RESET_MAX_ATTEMPTS:5}
  resend-cooldown-seconds: ${PASSWORD_RESET_RESEND_COOLDOWN_SECONDS:60}
  from-address: ${PASSWORD_RESET_FROM_ADDRESS:no-reply@medical-chatbot.local}
  from-name: ${PASSWORD_RESET_FROM_NAME:Medical Chatbot}
```
Redis đã có sẵn (`spring-boot-starter-data-redis`); chỉ thêm `spring-boot-starter-mail`.
Biến môi trường SMTP/`PASSWORD_RESET_*` được ghi (comment) trong `backend/.env.example`.
Endpoint được whitelist `permitAll()` trong `SecurityConfig` cùng nhóm `login/register/refresh`.

## 7. Frontend (đã triển khai)

- [ForgotPasswordPage](../frontend/src/pages/ForgotPasswordPage.tsx): một trang quản lý
  **3 bước nội bộ** bằng state (`email` → `code` → `password`), giữ `email` và `reset_ticket`
  trong bộ nhớ (không để trên URL). Gọi `apiPost` từ `services/api.ts`, bắt lỗi qua `ApiError.detail`.
  Bước nhập mã có nút "Gửi lại mã". Thành công → điều hướng `/login` kèm thông báo.
- [App.tsx](../frontend/src/App.tsx): route công khai `/forgot-password`.
- [LoginPage](../frontend/src/pages/LoginPage.tsx): link "Quên mật khẩu?".
- Chuỗi tiếng Việt trong `lib/constants.ts` (`TEXT`).

## 8. Thành phần code

| File | Vai trò |
|---|---|
| [PasswordResetOtpService](../backend/src/main/java/com/medicalchatbot/backend/service/PasswordResetOtpService.java) | issueCode / verifyCode / consumeTicket; OTP + ticket trong Redis |
| [PasswordResetService](../backend/src/main/java/com/medicalchatbot/backend/service/PasswordResetService.java) | điều phối 3 bước; đổi hash + revoke phiên; audit |
| [EmailService](../backend/src/main/java/com/medicalchatbot/backend/service/EmailService.java) | gửi SMTP / dev fallback log |
| [PasswordPolicy](../backend/src/main/java/com/medicalchatbot/backend/service/PasswordPolicy.java) | luật mật khẩu dùng chung với register |
| [PasswordResetProperties](../backend/src/main/java/com/medicalchatbot/backend/config/PasswordResetProperties.java) | config `password-reset.*` |
| [AuthController](../backend/src/main/java/com/medicalchatbot/backend/controller/AuthController.java) | 3 endpoint mới |
| [SecurityConfig](../backend/src/main/java/com/medicalchatbot/backend/config/SecurityConfig.java) | whitelist 3 path |
| [UserRepository](../backend/src/main/java/com/medicalchatbot/backend/repository/UserRepository.java) | `findByEmailIgnoreCase` |
| DTO | `ForgotPasswordRequest`, `VerifyResetCodeRequest`, `ResetPasswordRequest`; `ForgotPasswordResponse`, `VerifyResetCodeResponse`, `ResetPasswordResponse` |
| Frontend | `pages/ForgotPasswordPage.tsx`, `App.tsx`, `pages/LoginPage.tsx`, `lib/constants.ts` |

## 9. Kiểm thử

`backend`, chạy `.\mvnw.cmd test` (JAVA_HOME = JDK 21):

- [PasswordResetOtpServiceTest](../backend/src/test/java/com/medicalchatbot/backend/service/PasswordResetOtpServiceTest.java):
  issueCode lưu hash 6 số + cooldown; đang cooldown → empty; round-trip verify→consume ticket;
  không có mã → 400; mã sai tăng attempts giữ TTL; chạm max-attempts xóa mã; ticket sai/thiếu → 400.
- [PasswordResetServiceTest](../backend/src/test/java/com/medicalchatbot/backend/service/PasswordResetServiceTest.java):
  email không tồn tại → 404 (không gửi mã); user LOCKED → 403; verify trả ticket; reset đổi hash
  + `token_version++` + `revokeAllForUser`; mật khẩu yếu không tiêu thụ ticket; ticket đúng nhưng
  user biến mất → 400.

Test đổi mật khẩu nằm trong `AuthServiceTest` — xem [§10](#kiểm-thử-đổi-mật-khẩu).

Sau mỗi thay đổi, chạy `./mvnw.cmd test` trong `backend` và `npm run typecheck` trong `frontend`.

## 10. Đổi mật khẩu (khi đã đăng nhập)

Khác với luồng quên mật khẩu, đây là thao tác cho **người dùng đã đăng nhập** biết mật khẩu
hiện tại — **không dùng OTP/email/Redis**, chỉ một endpoint **yêu cầu xác thực**.

### `POST /api/auth/change-password`

| Method | Path | Auth | Mô tả |
|---|---|---|---|
| POST | `/api/auth/change-password` | **authenticated** (Bearer) | Đổi mật khẩu cho tài khoản đang đăng nhập |

Request:
```json
{
  "current_password": "OldPass123",
  "new_password": "NewPass456",
  "password_confirmation": "NewPass456"
}
```
Response `200`:
```json
{ "message": "Đổi mật khẩu thành công. Vui lòng đăng nhập lại." }
```
Lỗi:
- `401` nếu chưa đăng nhập.
- `400` nếu **mật khẩu hiện tại không đúng** (audit `PASSWORD_CHANGE_FAILURE`).
- `400` nếu mật khẩu mới không đạt luật (8–72 byte, ≥1 chữ + ≥1 số, xác nhận khớp) hoặc
  **trùng mật khẩu hiện tại**.

### Luồng trong code

```text
change-password → AuthService.changePassword
    currentUserService.requireCurrentUser()                → 401 nếu chưa đăng nhập
    passwordEncoder.matches(current, hash) == false        → 400 + audit PASSWORD_CHANGE_FAILURE
    PasswordPolicy.validate(new_password, confirmation)    → 400 nếu yếu / xác nhận lệch
    passwordEncoder.matches(new_password, hash) == true    → 400 (mật khẩu mới phải khác cũ)
    passwordHash = BCrypt(new_password); incrementTokenVersion(); save
    refreshTokenService.revokeAllForUser(id)               → thu hồi mọi refresh token
    audit PASSWORD_CHANGE_SUCCESS
```

Giống bước cuối của reset-password: `token_version++` + `revokeAllForUser()` ⇒ **mọi phiên cũ
hết hiệu lực** (kể cả thiết bị đang thao tác), nên frontend đăng xuất và yêu cầu đăng nhập lại.
Luật mật khẩu dùng chung [PasswordPolicy](../backend/src/main/java/com/medicalchatbot/backend/service/PasswordPolicy.java).
Endpoint nằm trong nhóm `.authenticated()` của `SecurityConfig` — **bắt buộc** vì cấu hình có
`anyRequest().permitAll()`, bỏ sót sẽ mở công khai.

### Frontend

- [ChangePasswordPage](../frontend/src/pages/ChangePasswordPage.tsx): form 3 trường
  (mật khẩu hiện tại / mới / xác nhận). Thành công → `logout()` (dọn token cục bộ đã bị thu hồi)
  → điều hướng `/login` kèm thông báo.
- [App.tsx](../frontend/src/App.tsx): route **được bảo vệ** `/change-password` (ProtectedRoute).
- Điểm truy cập: nút chìa khóa ở footer [HistorySidebar](../frontend/src/components/chat/HistorySidebar.tsx)
  (user) và link ở [DashboardLayout](../frontend/src/components/dashboard/DashboardLayout.tsx) (admin).
- Chuỗi tiếng Việt `changePassword*` trong `lib/constants.ts` (`TEXT`).

### Thành phần code (đổi mật khẩu)

| File | Vai trò |
|---|---|
| [AuthService.changePassword](../backend/src/main/java/com/medicalchatbot/backend/service/AuthService.java) | xác thực mật khẩu hiện tại; đổi hash + revoke phiên; audit |
| [AuthController](../backend/src/main/java/com/medicalchatbot/backend/controller/AuthController.java) | endpoint `POST /api/auth/change-password` |
| [SecurityConfig](../backend/src/main/java/com/medicalchatbot/backend/config/SecurityConfig.java) | thêm path vào nhóm `.authenticated()` |
| DTO | `ChangePasswordRequest`, `ChangePasswordResponse` |
| Frontend | `pages/ChangePasswordPage.tsx`, `App.tsx`, `components/chat/HistorySidebar.tsx`, `components/dashboard/DashboardLayout.tsx`, `lib/constants.ts` |

### Kiểm thử (đổi mật khẩu)

[AuthServiceTest](../backend/src/test/java/com/medicalchatbot/backend/service/AuthServiceTest.java):
đổi thành công (hash mới + `token_version++` + `revokeAllForUser`); sai mật khẩu hiện tại → 400;
mật khẩu mới trùng mật khẩu cũ → 400.

> **Lưu ý audit thất bại**: `PASSWORD_CHANGE_FAILURE` được ghi trong cùng transaction rồi throw
> nên bị rollback cùng (giống `logLoginFailure` hiện có). Muốn giữ lại bản ghi thất bại thì tách
> transaction riêng (`REQUIRES_NEW`) — áp dụng chung cho cả hai chỗ.

## 11. Giới hạn hiện tại / hướng mở rộng

- Endpoint không giới hạn tần suất theo IP (ngoài cooldown theo email); có thể gắn thêm
  `RateLimitInterceptor` để chống dò/spam.
- Email dạng plain-text (`SimpleMailMessage`); muốn HTML template thì chuyển `MimeMessage`.
- Không có chống dò email tồn tại (theo yêu cầu): `forgot-password` trả 404 rõ ràng khi email
  không tồn tại. Nếu cần bảo mật hơn, đổi lại thành thông điệp chung cho mọi email.
