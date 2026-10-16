-- ============================================================
-- Development-only demo data; application-prod.yml never loads db/devmigration.
-- Repeatable: rerun whenever this file changes, so every statement is an upsert.
--
-- Passwords (bcrypt): user_demo / doctor_demo / admin_demo -> UserDemo123! / DoctorDemo123! / AdminDemo123!
--                     other accounts                       -> <username>123!
-- Display names match the linked FHIR patient records of the HAPI seed.
-- ============================================================

create extension if not exists pgcrypto;

insert into app_users (id, username, email, display_name, password_hash, role, status, quota_policy_id)
values
    ('00000000-0000-0000-0000-000000000201', 'user_demo',   'user_demo@medical-chatbot.local',   'Nguyễn Văn An',   crypt('UserDemo123!', gen_salt('bf')),   'USER',   'ACTIVE', '00000000-0000-0000-0000-000000000110'),
    ('00000000-0000-0000-0000-000000000202', 'doctor_demo', 'doctor_demo@medical-chatbot.local', 'Demo Doctor',     crypt('DoctorDemo123!', gen_salt('bf')), 'DOCTOR', 'ACTIVE', '00000000-0000-0000-0000-000000000111'),
    ('00000000-0000-0000-0000-000000000203', 'admin_demo',  'admin_demo@medical-chatbot.local',  'Demo Admin',      crypt('AdminDemo123!', gen_salt('bf')),  'ADMIN',  'ACTIVE', '00000000-0000-0000-0000-000000000112'),
    ('00000000-0000-0000-0000-000000000204', 'le_hoa',      'le_hoa@medical-chatbot.local',      'Lê Thị Hoa',      crypt('le_hoa123!', gen_salt('bf')),     'USER',   'ACTIVE', '00000000-0000-0000-0000-000000000110'),
    ('00000000-0000-0000-0000-000000000205', 'pham_cuong',  'pham_cuong@medical-chatbot.local',  'Phạm Văn Cường',  crypt('pham_cuong123!', gen_salt('bf')), 'USER',   'ACTIVE', '00000000-0000-0000-0000-000000000110'),
    ('00000000-0000-0000-0000-000000000206', 'vo_lan',      'vo_lan@medical-chatbot.local',      'Võ Thị Lan',      crypt('vo_lan123!', gen_salt('bf')),     'USER',   'ACTIVE', '00000000-0000-0000-0000-000000000110'),
    ('00000000-0000-0000-0000-000000000207', 'dang_tuan',   'dang_tuan@medical-chatbot.local',   'Đặng Minh Tuấn',  crypt('dang_tuan123!', gen_salt('bf')),  'USER',   'ACTIVE', '00000000-0000-0000-0000-000000000110'),
    ('00000000-0000-0000-0000-000000000208', 'bui_mai',     'bui_mai@medical-chatbot.local',     'Bùi Thị Mai',     crypt('bui_mai123!', gen_salt('bf')),    'USER',   'ACTIVE', '00000000-0000-0000-0000-000000000110'),
    ('00000000-0000-0000-0000-000000000209', 'do_hung',     'do_hung@medical-chatbot.local',     'Đỗ Văn Hùng',     crypt('do_hung123!', gen_salt('bf')),    'USER',   'ACTIVE', '00000000-0000-0000-0000-000000000110'),
    ('00000000-0000-0000-0000-000000000210', 'dr_ngo',      'dr_ngo@medical-chatbot.local',      'BS. Ngô Thị Thu', crypt('dr_ngo123!', gen_salt('bf')),     'DOCTOR', 'ACTIVE', '00000000-0000-0000-0000-000000000111'),
    ('00000000-0000-0000-0000-000000000211', 'dr_ly',       'dr_ly@medical-chatbot.local',       'BS. Lý Văn Nam',  crypt('dr_ly123!', gen_salt('bf')),      'DOCTOR', 'ACTIVE', '00000000-0000-0000-0000-000000000111'),
    ('00000000-0000-0000-0000-000000000212', 'truong_nga',  'truong_nga@medical-chatbot.local',  'Trương Thị Nga',  crypt('truong_nga123!', gen_salt('bf')), 'USER',   'ACTIVE', '00000000-0000-0000-0000-000000000110'),
    ('00000000-0000-0000-0000-000000000213', 'phan_khoa',   'phan_khoa@medical-chatbot.local',   'Phan Văn Khoa',   crypt('phan_khoa123!', gen_salt('bf')),  'USER',   'ACTIVE', '00000000-0000-0000-0000-000000000110')
on conflict (username) do update set
    email           = excluded.email,
    display_name    = excluded.display_name,
    password_hash   = excluded.password_hash,
    role            = excluded.role,
    status          = excluded.status,
    quota_policy_id = excluded.quota_policy_id,
    updated_at      = now();

-- Patients own their record (SELF, primary).
insert into app_user_patient_links (user_id, fhir_patient_id, relationship, is_primary)
select u.id, m.fhir_patient_id, 'SELF', true
from (values
    ('user_demo',  'BN2026-00001'),
    ('le_hoa',     'BN2026-00007'),
    ('pham_cuong', 'BN2026-00008'),
    ('vo_lan',     'BN2026-00009'),
    ('dang_tuan',  'BN2026-00010'),
    ('bui_mai',    'BN2026-00011'),
    ('do_hung',    'BN2026-00012'),
    ('truong_nga', 'BN2026-00015'),
    ('phan_khoa',  'BN2026-00016')
) as m(username, fhir_patient_id)
join app_users u on u.username = m.username
on conflict (user_id, fhir_patient_id) do update set
    relationship = excluded.relationship,
    is_primary   = excluded.is_primary,
    updated_at   = now();

-- Doctors follow a patient (CAREGIVER, not primary).
insert into app_user_patient_links (user_id, fhir_patient_id, relationship, is_primary)
select u.id, m.fhir_patient_id, 'CAREGIVER', false
from (values
    ('dr_ngo', 'BN2026-00013'),
    ('dr_ly',  'BN2026-00014')
) as m(username, fhir_patient_id)
join app_users u on u.username = m.username
on conflict (user_id, fhir_patient_id) do update set
    relationship = excluded.relationship,
    is_primary   = excluded.is_primary,
    updated_at   = now();
