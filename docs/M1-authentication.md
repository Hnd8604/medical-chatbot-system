# M1. Authentication & Access Control

Thay thế `demo_user` cố định bằng đăng nhập thật (JWT stateless), phân quyền theo role (ADMIN / DOCTOR / USER), và liên kết tài khoản USER với hồ sơ bệnh nhân FHIR để giới hạn phạm vi dữ liệu. Là nền tảng cho mọi module có kiểm soát truy cập (xem [M9 Quota](M9-quota-management.md), [M18/M19 Audit](M18-M19-audit-alert.md)).

## M1.1 - Đăng ký tài khoản (register)

**Mục tiêu:** Người dùng tự tạo tài khoản USER.

**Hành vi:**
- `POST /api/auth/register` (permitAll) → `AuthService.register()`.
- Validate: `display_name` 2–100 ký tự; `username` `^[a-z0-9._-]{3,30}$`; email regex; mật khẩu 8–72 byte, phải có ≥1 chữ + ≥1 số; xác nhận mật khẩu khớp.
- Chuẩn hóa username/email về lowercase; chặn trùng (`existsByUsernameIgnoreCase` / `existsByEmailIgnoreCase`) → HTTP 409.
- Tài khoản mới: `role=USER`, `status=ACTIVE`, `token_version=0`, mật khẩu
  BCrypt, gắn quota policy `user_standard`.
- Unique index `lower(username)` / `lower(email)` trong baseline V1 chống
  race-condition → bắt `DataIntegrityViolationException` trả 409.
- Ghi audit `REGISTER_SUCCESS`.

**Tiêu chí hoàn thành:** User đăng ký xong có thể login; username/email trùng bị từ chối.

## M1.2 - Đăng nhập (login + JWT)

**Mục tiêu:** Cấp JWT cho phiên làm việc stateless.

**Hành vi:**
- `POST /api/auth/login` (permitAll) → `AuthService.login()`; tra cứu bằng `findByUsernameOrEmailIgnoreCase`.
- So khớp mật khẩu BCrypt; sai → 401 chung (`invalidCredentials`, không lộ user tồn tại).
- `status=LOCKED` → 403 "Tài khoản đã bị khóa"; `status=DISABLED` → 403 "vô hiệu hóa".
- Trả `AuthLoginResponse` gồm token, `token_type=Bearer`, `expires_in`, và `AuthUserResponse`.
- Token mang `token_version` để hỗ trợ thu hồi (xem M1.5).
- Ghi audit `LOGIN_SUCCESS` / `LOGIN_FAILED`.

**Tiêu chí hoàn thành:** Login đúng trả JWT dùng được cho các endpoint `authenticated()`.

## M1.3 - Phân quyền theo role (RBAC)

**Mục tiêu:** Giới hạn endpoint theo vai trò.

**Hành vi (`SecurityConfig`, session STATELESS):**
- permitAll: health/Swagger, `/api/chatbot/status`, login/register/refresh,
  ba endpoint quên mật khẩu và `OPTIONS /**`.
- `hasRole("ADMIN")`: `/api/admin/**`, `/api/audit-logs`, `/api/metrics/cache`.
- `hasAnyRole("DOCTOR","ADMIN")`: `/api/patients/**` (tra cứu tự do hồ sơ bất kỳ).
- `authenticated()`: `/api/chat/**`, `/api/auth/me|logout|change-password|link-patient`,
  `/api/me/**`, quota, cost summary, notifications, model pricing và feedback.
- `JwtAuthenticationFilter` đặt trước `UsernamePasswordAuthenticationFilter`; lỗi 401/403 trả JSON thống nhất qua `SecurityErrorWriter`.

**Tiêu chí hoàn thành:** USER không gọi được API admin; chỉ DOCTOR/ADMIN tra cứu `/api/patients/**`.

## M1.4 - Onboarding: liên kết hồ sơ bệnh nhân (USER scope)

**Mục tiêu:** Tài khoản USER chỉ xem được hồ sơ FHIR của chính mình.

**Hành vi:**
- `POST /api/auth/link-patient` → `AuthService.linkPatient()` ủy quyền cho
  `PatientLinkService.linkCurrentUser()` (chỉ role USER).
- Xác minh 3 yếu tố khớp resource FHIR: `patient_id` + `birth_date` + `phone`
  (chuẩn hóa +84/84 → 0). Lấy patient qua adapter
  `integration/client/ChatbotServiceClient`; không khớp → 422.
- Một USER chỉ liên kết 1 hồ sơ `SELF`; 1 hồ sơ `SELF` không thuộc 2 tài khoản
  (`existsSelfLinkForOtherUser`, unique index trong baseline V1) → 409.
- Lưu `app_user_patient_links` (`relationship=SELF`, `primary_link=true`); audit `LINK_PATIENT_SUCCESS`.
- `onboardingRequired` = role USER và chưa có link nào → `AuthUserResponse.onboardingRequired=true` để FE chuyển sang `OnboardingPage`.
- Khi chat: `UserPatientScopeService.resolve()` chặn USER truy vấn `patient_id` ngoài danh sách đã liên kết (403); STAFF (DOCTOR/ADMIN) không bị giới hạn scope.

**Tiêu chí hoàn thành:** USER chưa liên kết bị buộc onboarding; sau liên kết chỉ truy cập đúng hồ sơ của mình.

## M1.5 - Phiên & đăng xuất (logout / thu hồi token)

**Mục tiêu:** Vô hiệu hóa token đã cấp.

**Hành vi:**
- `POST /api/auth/logout` → `incrementTokenVersion()` + lưu user; audit `LOGOUT`.
- JWT cũ mang `token_version` cũ → `JwtAuthenticationFilter` từ chối (mismatch) → buộc đăng nhập lại.
- `GET /api/auth/me` trả thông tin user hiện tại (qua `CurrentUserService.requireCurrentUser()`).
- `PUT /api/auth/me` cập nhật display name/email; `GET /api/me/patient/profile`
  trả hồ sơ FHIR đã liên kết của USER.
- FE lưu token ở `sessionStorage`, tự khôi phục qua `/api/auth/me`, xóa phiên khi 401/403.

**Tiêu chí hoàn thành:** Sau logout, token cũ không còn dùng được.

## Luồng chương trình

```
Đăng ký:   POST /api/auth/register → validate → check trùng → User(role=USER, user_standard) → audit REGISTER_SUCCESS
Đăng nhập: POST /api/auth/login → BCrypt match → check status → JWT(token_version) + AuthUserResponse
Mỗi request: Bearer JWT → JwtAuthenticationFilter → set SecurityContext → SecurityConfig authorize theo role
Onboarding: USER login → onboardingRequired? → POST /api/auth/link-patient (patient_id+birth_date+phone khớp FHIR) → link SELF
Chat (USER): UserPatientScopeService.resolve → chặn patient_id ngoài hồ sơ đã liên kết (403)
Đăng xuất: POST /api/auth/logout → incrementTokenVersion → token cũ vô hiệu
```

## Luồng trong code

- **Đăng nhập/đăng ký/profile/logout:** [AuthService.java](../backend/src/main/java/com/medicalchatbot/backend/service/AuthService.java).
- **Link hồ sơ:** [PatientLinkService.java](../backend/src/main/java/com/medicalchatbot/backend/service/PatientLinkService.java); HTTP adapter: `integration/client/ChatbotServiceClient.java`.
- **Audit auth và mapping user:** `service/AuthAuditService.java`, `mapper/AuthUserMapper.java`.
- **Endpoint:** [AuthController.java](../backend/src/main/java/com/medicalchatbot/backend/controller/AuthController.java) và `SelfPatientController.java`.
- **Phân quyền:** [SecurityConfig.java](../backend/src/main/java/com/medicalchatbot/backend/config/SecurityConfig.java); xác thực token: `config/JwtAuthenticationFilter.java`, `config/JwtProperties.java`, `service/JwtTokenService.java`.
- **Scope hồ sơ USER:** [UserPatientScopeService.java](../backend/src/main/java/com/medicalchatbot/backend/service/UserPatientScopeService.java); link repo: `repository/UserPatientLinkRepository.java`.
- **User hiện tại:** `service/CurrentUserService.java`.
- **FE:** `pages/LoginPage.tsx`, `pages/RegisterPage.tsx`,
  `pages/OnboardingPage.tsx`, `pages/ProfilePage.tsx`, `hooks/useAuth.tsx`.

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| Auth service/controller | `service/AuthService.java`, `service/AuthAuditService.java`, `controller/AuthController.java` |
| Liên kết/self profile | `service/PatientLinkService.java`, `service/SelfPatientService.java`, `controller/SelfPatientController.java` |
| JWT + security | `config/SecurityConfig.java`, `config/JwtAuthenticationFilter.java`, `config/JwtProperties.java`, `config/SecurityErrorWriter.java`, `service/JwtTokenService.java` |
| Scope hồ sơ USER | `service/UserPatientScopeService.java`, `repository/UserPatientLinkRepository.java` |
| Entity/enum | `entity/User.java`, `entity/UserPatientLink.java`, `enums/UserRole.java`, `enums/UserStatus.java` |
| DTO | `dto/request/AuthLoginRequest.java`, `AuthRegisterRequest.java`, `AuthLinkPatientRequest.java`; `dto/response/AuthLoginResponse.java`, `AuthRegisterResponse.java`, `AuthLinkPatientResponse.java`, `AuthUserResponse.java` |
| Migration | `V1__baseline_schema_and_seed.sql` (auth fields trên `app_users`, bảng `app_user_patient_links`, unique username/email) |
| Frontend | `pages/{LoginPage,RegisterPage,OnboardingPage,ProfilePage}.tsx`, `hooks/useAuth.tsx` |

## Trạng thái

- Done: register, login (JWT), RBAC, onboarding liên kết hồ sơ + scope USER, logout/thu hồi token, refresh token ([auth-refresh-token.md](auth-refresh-token.md)), quên mật khẩu qua email OTP ([password-reset.md](password-reset.md)).
- Chưa: OAuth2, xác minh email khi đăng ký, đăng ký DOCTOR/ADMIN tự phục vụ (hiện cấp thủ công).
