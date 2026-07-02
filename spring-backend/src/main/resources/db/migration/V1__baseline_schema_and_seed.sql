-- ============================================================
-- V1 (baseline): Gộp toàn bộ migration V1 -> V17 thành trạng thái
--                cuối cùng của app DB (schema + seed data).
--
-- Áp dụng trên database rỗng. Các bước backfill cost trong lịch sử
-- (V5/V8/V13) chỉ tác động lên usage_logs có sẵn nên được bỏ qua ở
-- baseline (bảng usage_logs khởi tạo rỗng).
-- ============================================================

create extension if not exists pgcrypto;

-- ------------------------------------------------------------
-- 1. Schema
-- ------------------------------------------------------------

create table if not exists quota_policies (
    id uuid primary key default gen_random_uuid(),
    name varchar(100) not null unique,
    daily_request_limit integer not null,
    daily_token_limit integer not null,
    daily_cost_limit_usd numeric(10, 4) not null,
    created_at timestamptz not null default now(),
    rate_limit_per_minute integer default 20
);

create table if not exists app_users (
    id uuid primary key default gen_random_uuid(),
    username varchar(100) not null unique,
    email varchar(255) unique,
    role varchar(50) not null default 'USER',
    quota_policy_id uuid references quota_policies(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    display_name varchar(255) not null,
    password_hash varchar(255) not null,
    status varchar(50) not null,
    token_version integer not null default 0
);

create table if not exists chat_sessions (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id) on delete cascade,
    title varchar(255),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    active_patient_id varchar(100),
    memory_summary text,
    last_intent varchar(100),
    last_tool_name varchar(100),
    last_resource_type varchar(100),
    last_resource_id varchar(100)
);

create table if not exists chat_messages (
    id uuid primary key default gen_random_uuid(),
    session_id uuid not null references chat_sessions(id) on delete cascade,
    role varchar(30) not null,
    content text not null,
    created_at timestamptz not null default now(),
    metadata_json jsonb not null default '{}'::jsonb,
    constraint chat_messages_role_check check (role in ('user', 'assistant', 'system'))
);

create table if not exists usage_logs (
    id uuid primary key default gen_random_uuid(),
    user_id uuid references app_users(id) on delete set null,
    session_id uuid references chat_sessions(id) on delete set null,
    request_count integer not null default 1,
    input_tokens integer not null default 0,
    output_tokens integer not null default 0,
    estimated_cost_usd numeric(12, 6) not null default 0,
    created_at timestamptz not null default now(),
    llm_provider varchar(50),
    llm_model varchar(100),
    operation varchar(50) not null default 'chat',
    status varchar(30) not null default 'success',
    latency_ms integer,
    error_message text,
    answer_source varchar(50) default 'llm',
    saved_tokens integer default 0,
    saved_cost_usd numeric(10, 6) default 0.0,
    constraint usage_logs_status_check check (status in ('success', 'failed', 'fallback', 'blocked')),
    constraint usage_logs_latency_ms_check check (latency_ms is null or latency_ms >= 0)
);

create table if not exists cache_entries (
    id uuid primary key default gen_random_uuid(),
    cache_key varchar(255) not null unique,
    value_json jsonb not null,
    expires_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists audit_logs (
    id uuid primary key default gen_random_uuid(),
    user_id uuid references app_users(id) on delete set null,
    session_id uuid references chat_sessions(id) on delete set null,
    action varchar(100) not null,
    resource_type varchar(100),
    resource_id varchar(100),
    metadata_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint audit_logs_action_not_blank check (length(trim(action)) > 0)
);

create table if not exists model_pricing (
    id uuid primary key default gen_random_uuid(),
    provider varchar(50) not null,
    model varchar(100) not null,
    input_price_per_1m_tokens numeric(12, 6) not null,
    output_price_per_1m_tokens numeric(12, 6) not null,
    currency varchar(3) not null default 'USD',
    active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint model_pricing_input_price_non_negative check (input_price_per_1m_tokens >= 0),
    constraint model_pricing_output_price_non_negative check (output_price_per_1m_tokens >= 0),
    constraint model_pricing_currency_upper check (currency = upper(currency))
);

create table if not exists alerts (
    id uuid primary key default gen_random_uuid(),
    source varchar(50) not null,
    alert_type varchar(100) not null,
    severity varchar(20) not null,
    status varchar(20) not null default 'OPEN',
    message text not null,
    metadata_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    resolved_at timestamptz,
    resolved_by varchar(100)
);

create table if not exists message_feedback (
    id uuid primary key default gen_random_uuid(),
    message_id uuid not null references chat_messages(id) on delete cascade,
    user_id uuid references app_users(id) on delete set null,
    rating smallint not null check (rating between 1 and 5),
    comment text,
    created_at timestamptz not null default now()
);

create table if not exists notifications (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id) on delete cascade,
    type varchar(50) not null,
    title varchar(255) not null,
    content text not null,
    is_read boolean not null default false,
    created_at timestamptz not null default now()
);

create table if not exists app_user_patient_links (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id) on delete cascade,
    fhir_patient_id varchar(100) not null,
    relationship varchar(50) not null default 'SELF',
    is_primary boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint app_user_patient_links_relationship_check
        check (relationship in ('SELF', 'DEPENDENT', 'CAREGIVER')),
    constraint app_user_patient_links_unique_patient_per_user
        unique (user_id, fhir_patient_id)
);

-- ------------------------------------------------------------
-- 2. Indexes
-- ------------------------------------------------------------

create index if not exists idx_app_users_quota_policy_id on app_users(quota_policy_id);
create unique index if not exists ux_app_users_username_lower on app_users (lower(username));
create unique index if not exists ux_app_users_email_lower on app_users (lower(email)) where email is not null;

create index if not exists idx_chat_sessions_user_id on chat_sessions(user_id);
create index if not exists idx_chat_sessions_user_active_patient on chat_sessions(user_id, active_patient_id);

create index if not exists idx_chat_messages_session_id_created_at on chat_messages(session_id, created_at);

create index if not exists idx_usage_logs_user_id_created_at on usage_logs(user_id, created_at);
create index if not exists idx_usage_logs_session_id_created_at on usage_logs(session_id, created_at);
create index if not exists idx_usage_logs_model_created_at on usage_logs(llm_provider, llm_model, created_at);
create index if not exists idx_usage_logs_status_created_at on usage_logs(status, created_at);

create index if not exists idx_cache_entries_expires_at on cache_entries(expires_at);

create index if not exists idx_audit_logs_user_id_created_at on audit_logs(user_id, created_at);
create index if not exists idx_audit_logs_session_id_created_at on audit_logs(session_id, created_at);
create index if not exists idx_audit_logs_action_created_at on audit_logs(action, created_at);

create unique index if not exists idx_model_pricing_active_provider_model
    on model_pricing(lower(provider), lower(model)) where active;

create index if not exists idx_alerts_status_created_at on alerts(status, created_at);
create index if not exists idx_alerts_severity_created_at on alerts(severity, created_at);
create index if not exists idx_alerts_type_status_created on alerts(alert_type, status, created_at);

create index if not exists idx_message_feedback_message_id on message_feedback(message_id);

create index if not exists idx_notifications_user_read on notifications(user_id, is_read);

create unique index if not exists ux_app_user_patient_links_primary
    on app_user_patient_links(user_id) where is_primary;
create index if not exists idx_app_user_patient_links_user on app_user_patient_links(user_id);
create unique index if not exists ux_app_user_patient_links_self_patient_lower
    on app_user_patient_links (lower(fhir_patient_id)) where relationship = 'SELF';

-- ------------------------------------------------------------
-- 3. Seed: quota policies (free / pro / enterprise)
-- ------------------------------------------------------------

insert into quota_policies (id, name, daily_request_limit, daily_token_limit, daily_cost_limit_usd, rate_limit_per_minute)
values
    ('00000000-0000-0000-0000-000000000110', 'free',       30,   50000,   0.50,  10),
    ('00000000-0000-0000-0000-000000000111', 'pro',       200,  500000,   5.00,  30),
    ('00000000-0000-0000-0000-000000000112', 'enterprise', 2000, 5000000, 50.00, 120)
on conflict (name) do update set
    daily_request_limit   = excluded.daily_request_limit,
    daily_token_limit     = excluded.daily_token_limit,
    daily_cost_limit_usd  = excluded.daily_cost_limit_usd,
    rate_limit_per_minute = excluded.rate_limit_per_minute;

-- ------------------------------------------------------------
-- 4. Seed: app users
--    Mật khẩu (bcrypt): user/doctor/admin_demo -> UserDemo123! / DoctorDemo123! / AdminDemo123!
--                       các user còn lại       -> <username>123!
-- ------------------------------------------------------------

insert into app_users (
    id, username, email, display_name, password_hash, status, role, token_version, quota_policy_id
)
values
    ('00000000-0000-0000-0000-000000000201', 'user_demo',   'user_demo@medical-chatbot.local',   'Demo User',       crypt('UserDemo123!', gen_salt('bf')),   'ACTIVE', 'USER',   0, '00000000-0000-0000-0000-000000000110'),
    ('00000000-0000-0000-0000-000000000202', 'doctor_demo', 'doctor_demo@medical-chatbot.local', 'Demo Doctor',     crypt('DoctorDemo123!', gen_salt('bf')), 'ACTIVE', 'DOCTOR', 0, '00000000-0000-0000-0000-000000000110'),
    ('00000000-0000-0000-0000-000000000203', 'admin_demo',  'admin_demo@medical-chatbot.local',  'Demo Admin',      crypt('AdminDemo123!', gen_salt('bf')),  'ACTIVE', 'ADMIN',  0, '00000000-0000-0000-0000-000000000110'),
    ('00000000-0000-0000-0000-000000000204', 'le_hoa',      'le_hoa@medical-chatbot.local',      'Le Thi Hoa',      crypt('le_hoa123!', gen_salt('bf')),     'ACTIVE', 'USER',   0, '00000000-0000-0000-0000-000000000110'),
    ('00000000-0000-0000-0000-000000000205', 'pham_cuong',  'pham_cuong@medical-chatbot.local',  'Pham Van Cuong',  crypt('pham_cuong123!', gen_salt('bf')), 'ACTIVE', 'USER',   0, '00000000-0000-0000-0000-000000000110'),
    ('00000000-0000-0000-0000-000000000206', 'vo_lan',      'vo_lan@medical-chatbot.local',      'Vo Thi Lan',      crypt('vo_lan123!', gen_salt('bf')),     'ACTIVE', 'USER',   0, '00000000-0000-0000-0000-000000000111'),
    ('00000000-0000-0000-0000-000000000207', 'dang_tuan',   'dang_tuan@medical-chatbot.local',   'Dang Minh Tuan',  crypt('dang_tuan123!', gen_salt('bf')),  'ACTIVE', 'USER',   0, '00000000-0000-0000-0000-000000000111'),
    ('00000000-0000-0000-0000-000000000208', 'bui_mai',     'bui_mai@medical-chatbot.local',     'Bui Thi Mai',     crypt('bui_mai123!', gen_salt('bf')),    'ACTIVE', 'USER',   0, '00000000-0000-0000-0000-000000000110'),
    ('00000000-0000-0000-0000-000000000209', 'do_hung',     'do_hung@medical-chatbot.local',     'Do Van Hung',     crypt('do_hung123!', gen_salt('bf')),    'ACTIVE', 'USER',   0, '00000000-0000-0000-0000-000000000110'),
    ('00000000-0000-0000-0000-000000000210', 'dr_ngo',      'dr_ngo@medical-chatbot.local',      'Dr. Ngo Thi Thu', crypt('dr_ngo123!', gen_salt('bf')),     'ACTIVE', 'DOCTOR', 0, '00000000-0000-0000-0000-000000000112'),
    ('00000000-0000-0000-0000-000000000211', 'dr_ly',       'dr_ly@medical-chatbot.local',       'Dr. Ly Van Nam',  crypt('dr_ly123!', gen_salt('bf')),      'ACTIVE', 'DOCTOR', 0, '00000000-0000-0000-0000-000000000112'),
    ('00000000-0000-0000-0000-000000000212', 'truong_nga',  'truong_nga@medical-chatbot.local',  'Truong Thi Nga',  crypt('truong_nga123!', gen_salt('bf')), 'ACTIVE', 'USER',   0, '00000000-0000-0000-0000-000000000111'),
    ('00000000-0000-0000-0000-000000000213', 'phan_khoa',   'phan_khoa@medical-chatbot.local',   'Phan Van Khoa',   crypt('phan_khoa123!', gen_salt('bf')),  'ACTIVE', 'USER',   0, '00000000-0000-0000-0000-000000000112')
on conflict (username) do update set
    email           = excluded.email,
    display_name    = excluded.display_name,
    password_hash   = excluded.password_hash,
    status          = excluded.status,
    role            = excluded.role,
    token_version   = excluded.token_version,
    quota_policy_id = excluded.quota_policy_id,
    updated_at      = now();

-- ------------------------------------------------------------
-- 5. Seed: model pricing
-- ------------------------------------------------------------

insert into model_pricing (
    id, provider, model, input_price_per_1m_tokens, output_price_per_1m_tokens, currency, active
)
values
    ('00000000-0000-0000-0000-000000000701', 'openai', 'gpt-4.1-mini',             0.400000, 1.600000, 'USD', true),
    ('00000000-0000-0000-0000-000000000702', 'openai', 'gpt-4o-mini',              0.150000, 0.600000, 'USD', true),
    ('00000000-0000-0000-0000-000000000801', 'groq',   'llama-3.3-70b-versatile',  0.590000, 0.790000, 'USD', true),
    ('00000000-0000-0000-0000-000000001301', 'groq',   'llama-3.1-8b-instant',     0.050000, 0.080000, 'USD', true)
on conflict (id) do update set
    provider                   = excluded.provider,
    model                      = excluded.model,
    input_price_per_1m_tokens  = excluded.input_price_per_1m_tokens,
    output_price_per_1m_tokens = excluded.output_price_per_1m_tokens,
    currency                   = excluded.currency,
    active                     = excluded.active,
    updated_at                 = now();

-- ------------------------------------------------------------
-- 6. Seed: liên kết user <-> Patient FHIR
-- ------------------------------------------------------------

-- 6a. SELF (primary): bệnh nhân sở hữu chính hồ sơ của mình
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

-- 6b. CAREGIVER (không primary): bác sĩ theo dõi bệnh nhân
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
