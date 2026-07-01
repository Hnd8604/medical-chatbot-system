# Chức năng Liên kết User ↔ Bệnh nhân FHIR (`app_user_patient_links`)

Tài liệu mô tả bảng `app_user_patient_links` — "cây cầu" nối **tài khoản app**
(`app_users`) với **hồ sơ bệnh nhân FHIR** (`Patient/demo-patient-xxx` bên HAPI),
và cách nó được dùng để **kiểm soát quyền truy cập dữ liệu y tế**.

> **Vì sao cần?** Tài khoản đăng nhập và hồ sơ y tế là hai thứ tách rời. User
> `le_hoa` nằm trong Postgres app; còn dữ liệu khám bệnh của cô ấy nằm trên HAPI
> FHIR dưới ID `demo-patient-007`. Bảng này khẳng định: *user `le_hoa` được phép
> xem hồ sơ `demo-patient-007`*. Postgres app **không** lưu dữ liệu lâm sàng, chỉ
> giữ *liên kết* qua cột `fhir_patient_id` (xem [m12-database-erd.md](m12-database-erd.md)).

---

## 1. Schema

Định nghĩa tại [V12__create_user_patient_links.sql](../src/main/resources/db/migration/V12__create_user_patient_links.sql),
entity JPA tại [UserPatientLink.java](../src/main/java/com/medicalchatbot/backend/entity/UserPatientLink.java).

| Cột | Kiểu | Ý nghĩa |
|---|---|---|
| `id` | uuid | Khóa chính |
| `user_id` | uuid → `app_users(id)` | Tài khoản app nào (xóa user → xóa link, `on delete cascade`) |
| `fhir_patient_id` | varchar(100) | Hồ sơ FHIR nào, vd `demo-patient-007` |
| `relationship` | varchar(50) | Quan hệ: `SELF` \| `DEPENDENT` \| `CAREGIVER` (có `CHECK`) |
| `is_primary` | boolean | Hồ sơ mặc định khi user không nói rõ đang hỏi về ai |
| `created_at` / `updated_at` | timestamptz | Thời điểm tạo / cập nhật |

### Các ràng buộc quan trọng

- **`unique (user_id, fhir_patient_id)`** — một user không thể có 2 link trùng tới
  cùng một hồ sơ.
- **`ux_app_user_patient_links_primary`** (partial unique index `where is_primary`)
  — mỗi user chỉ được **đúng một** hồ sơ `is_primary = true`.
- **`ux_app_user_patient_links_self_patient_lower`** (V14, partial unique
  `where relationship = 'SELF'`) — một hồ sơ FHIR chỉ được **một** user nhận là
  `SELF`. Tức không thể có 2 tài khoản cùng khai "đây là hồ sơ của chính tôi".

### Ý nghĩa `relationship`

| Giá trị | Nghĩa | Ví dụ |
|---|---|---|
| `SELF` | Hồ sơ của chính user | `le_hoa` ↔ `demo-patient-007` |
| `DEPENDENT` | Người phụ thuộc | Phụ huynh xem hồ sơ con |
| `CAREGIVER` | Người chăm sóc / **theo dõi** người khác | Bác sĩ `dr_ngo` "theo dõi" `demo-patient-013` |

---

## 2. Vai trò access-control (điểm cốt lõi)

Logic nằm ở [UserPatientScopeService.java](../src/main/java/com/medicalchatbot/backend/service/UserPatientScopeService.java),
được gọi mỗi khi xử lý `POST /api/chat`.

```text
User hỏi chatbot
   ↓
UserPatientScopeService.resolve(user, requestedPatientId, sessionMemory)
   ↓
role == USER ?
   ├── CÓ  → chỉ cho phép các fhir_patient_id đã liên kết.
   │         Hỏi bệnh nhân ngoài danh sách → 403 FORBIDDEN.
   │         Không có link nào             → 403 FORBIDDEN.
   └── KHÔNG (DOCTOR/ADMIN/STAFF) → BỎ QUA kiểm tra, xem được mọi bệnh nhân.
```

> ⚠️ **Bảng này chỉ thực sự siết quyền với role `USER`.** Với DOCTOR/ADMIN, nhánh
> đầu hàm `resolve()` trả về scope `"STAFF"` và không tra link
> ([dòng 26-33](../src/main/java/com/medicalchatbot/backend/service/UserPatientScopeService.java#L26-L33)).
> Do đó link `CAREGIVER` của bác sĩ mang tính **dữ liệu demo / hiển thị** (vd trang
> admin cho biết bác sĩ phụ trách ai), **không** giới hạn họ.

**`is_primary`** quyết định "hồ sơ mặc định": nếu USER hỏi chung chung mà không chỉ
rõ bệnh nhân, hệ thống lấy hồ sơ primary (sắp xếp `primaryLink desc` trong
[`findPatientIdsForUser`](../src/main/java/com/medicalchatbot/backend/repository/UserPatientLinkRepository.java#L13-L19)).

---

## 3. Link được tạo khi nào?

1. **Lúc đăng ký / liên kết hồ sơ** — `AuthService.linkPatient(...)` tạo một link
   `SELF`, `is_primary = true`, kèm kiểm tra hồ sơ chưa thuộc về user khác
   ([AuthService.java:190-195](../src/main/java/com/medicalchatbot/backend/service/AuthService.java#L190-L195)).
2. **Seed migration** — dữ liệu demo:
   - [V12](../src/main/resources/db/migration/V12__create_user_patient_links.sql): `user_demo` ↔ `demo-patient-001` (SELF).
   - [V17 mục 3](../src/main/resources/db/migration/V17__seed_additional_users_and_enterprise_tier.sql#L45-L62): 8 user thường ↔ `demo-patient-007..016` (SELF, primary).
   - [V17 mục 4](../src/main/resources/db/migration/V17__seed_additional_users_and_enterprise_tier.sql#L64-L75): 2 bác sĩ `dr_ngo`/`dr_ly` "theo dõi" `demo-patient-013`/`014` (CAREGIVER, không primary).

> **`on conflict ... do update`** trong seed chỉ để migration chạy lại không lỗi
> (idempotent): link đã tồn tại thì cập nhật thay vì báo trùng khóa.

---

## 4. Hiển thị ở trang Admin

`AdminUserService` đọc link qua `findLinksForUser` / `findLinksForUsers` và map sang
[AdminUserPatientLinkResponse](../src/main/java/com/medicalchatbot/backend/dto/response/AdminUserPatientLinkResponse.java)
(`fhir_patient_id`, `relationship`, `is_primary`). Frontend dùng ở
[AdminUsersPage.tsx](../../frontend-react/src/pages/AdminUsersPage.tsx) để cho admin
thấy mỗi tài khoản đang gắn với hồ sơ FHIR nào.

---

## 5. Các truy vấn repository

[UserPatientLinkRepository.java](../src/main/java/com/medicalchatbot/backend/repository/UserPatientLinkRepository.java):

| Method | Dùng để |
|---|---|
| `findPatientIdsForUser` | Lấy danh sách hồ sơ user được phép (access-control khi chat) |
| `findSelfLinksForUser` | Kiểm tra user đã có link SELF chưa (lúc đăng ký) |
| `existsSelfLinkForOtherUser` | Chặn liên kết một hồ sơ đã thuộc user khác |
| `findLinksForUser` / `findLinksForUsers` | Hiển thị ở trang admin |
