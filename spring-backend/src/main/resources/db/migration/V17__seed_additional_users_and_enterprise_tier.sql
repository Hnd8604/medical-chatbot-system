-- ============================================================
-- V17: Seed thêm nhiều user demo + gói "enterprise"
--      Liên kết user bệnh nhân với các Patient FHIR 007-016
--      (xem infra/hapi-fhir/seed/additional-demo-data-transaction-bundle.json)
-- ============================================================

create extension if not exists pgcrypto;

-- 1. Bổ sung gói quota cao cấp "enterprise" (V16 mới có free + pro)
insert into quota_policies (id, name, daily_request_limit, daily_token_limit, daily_cost_limit_usd, rate_limit_per_minute)
values
    ('00000000-0000-0000-0000-000000000112', 'enterprise', 2000, 5000000, 50.00, 120)
on conflict (name) do update set
    daily_request_limit   = excluded.daily_request_limit,
    daily_token_limit     = excluded.daily_token_limit,
    daily_cost_limit_usd  = excluded.daily_cost_limit_usd,
    rate_limit_per_minute = excluded.rate_limit_per_minute;

-- 2. Seed thêm user demo trải đều free / pro / enterprise
--    Mật khẩu mặc định: <username>123! (đã hash bằng bcrypt)
insert into app_users (
    id, username, email, display_name, password_hash, status, role, token_version, quota_policy_id
)
values
    ('00000000-0000-0000-0000-000000000204', 'le_hoa',     'le_hoa@medical-chatbot.local',     'Le Thi Hoa',     crypt('le_hoa123!', gen_salt('bf')),     'ACTIVE', 'USER',   0, '00000000-0000-0000-0000-000000000110'),
    ('00000000-0000-0000-0000-000000000205', 'pham_cuong', 'pham_cuong@medical-chatbot.local', 'Pham Van Cuong', crypt('pham_cuong123!', gen_salt('bf')), 'ACTIVE', 'USER',   0, '00000000-0000-0000-0000-000000000110'),
    ('00000000-0000-0000-0000-000000000206', 'vo_lan',     'vo_lan@medical-chatbot.local',     'Vo Thi Lan',     crypt('vo_lan123!', gen_salt('bf')),     'ACTIVE', 'USER',   0, '00000000-0000-0000-0000-000000000111'),
    ('00000000-0000-0000-0000-000000000207', 'dang_tuan',  'dang_tuan@medical-chatbot.local',  'Dang Minh Tuan', crypt('dang_tuan123!', gen_salt('bf')),  'ACTIVE', 'USER',   0, '00000000-0000-0000-0000-000000000111'),
    ('00000000-0000-0000-0000-000000000208', 'bui_mai',    'bui_mai@medical-chatbot.local',    'Bui Thi Mai',    crypt('bui_mai123!', gen_salt('bf')),    'ACTIVE', 'USER',   0, '00000000-0000-0000-0000-000000000110'),
    ('00000000-0000-0000-0000-000000000209', 'do_hung',    'do_hung@medical-chatbot.local',    'Do Van Hung',    crypt('do_hung123!', gen_salt('bf')),    'ACTIVE', 'USER',   0, '00000000-0000-0000-0000-000000000110'),
    ('00000000-0000-0000-0000-000000000210', 'dr_ngo',     'dr_ngo@medical-chatbot.local',     'Dr. Ngo Thi Thu', crypt('dr_ngo123!', gen_salt('bf')),    'ACTIVE', 'DOCTOR', 0, '00000000-0000-0000-0000-000000000112'),
    ('00000000-0000-0000-0000-000000000211', 'dr_ly',      'dr_ly@medical-chatbot.local',      'Dr. Ly Van Nam',  crypt('dr_ly123!', gen_salt('bf')),     'ACTIVE', 'DOCTOR', 0, '00000000-0000-0000-0000-000000000112'),
    ('00000000-0000-0000-0000-000000000212', 'truong_nga', 'truong_nga@medical-chatbot.local', 'Truong Thi Nga', crypt('truong_nga123!', gen_salt('bf')), 'ACTIVE', 'USER',   0, '00000000-0000-0000-0000-000000000111'),
    ('00000000-0000-0000-0000-000000000213', 'phan_khoa',  'phan_khoa@medical-chatbot.local',  'Phan Van Khoa',  crypt('phan_khoa123!', gen_salt('bf')),  'ACTIVE', 'USER',   0, '00000000-0000-0000-0000-000000000112')
on conflict (username) do update set
    email           = excluded.email,
    display_name    = excluded.display_name,
    password_hash   = excluded.password_hash,
    status          = excluded.status,
    role            = excluded.role,
    token_version   = excluded.token_version,
    quota_policy_id = excluded.quota_policy_id,
    updated_at      = now();

-- 3. Liên kết các user bệnh nhân với Patient FHIR tương ứng (SELF, primary)
insert into app_user_patient_links (user_id, fhir_patient_id, relationship, is_primary)
select u.id, m.fhir_patient_id, 'SELF', true
from (values
    ('le_hoa',     'demo-patient-007'),
    ('pham_cuong', 'demo-patient-008'),
    ('vo_lan',     'demo-patient-009'),
    ('dang_tuan',  'demo-patient-010'),
    ('bui_mai',    'demo-patient-011'),
    ('do_hung',    'demo-patient-012'),
    ('truong_nga', 'demo-patient-015'),
    ('phan_khoa',  'demo-patient-016')
) as m(username, fhir_patient_id)
join app_users u on u.username = m.username
on conflict (user_id, fhir_patient_id) do update set
    relationship = excluded.relationship,
    is_primary   = excluded.is_primary,
    updated_at   = now();

-- 4. Bác sĩ theo dõi bệnh nhân của mình (CAREGIVER, không primary)
insert into app_user_patient_links (user_id, fhir_patient_id, relationship, is_primary)
select u.id, m.fhir_patient_id, 'CAREGIVER', false
from (values
    ('dr_ngo', 'demo-patient-013'),
    ('dr_ly',  'demo-patient-014')
) as m(username, fhir_patient_id)
join app_users u on u.username = m.username
on conflict (user_id, fhir_patient_id) do update set
    relationship = excluded.relationship,
    is_primary   = excluded.is_primary,
    updated_at   = now();
