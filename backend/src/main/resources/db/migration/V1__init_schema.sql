-- ============================================================
-- V1: schema of the app database.
--
-- Every table has a UUID id and created_at/updated_at, matching the entities' BaseEntity
-- (timestamps are filled by JPA auditing; the defaults only cover rows written by SQL).
-- Reference data needed in every environment (quota policies) is seeded here; demo
-- accounts live in db/devmigration, which only the dev profile loads.
-- ============================================================

-- ------------------------------------------------------------
-- 1. Accounts and quotas
-- ------------------------------------------------------------

create table quota_policies (
    id                    uuid primary key default gen_random_uuid(),
    name                  varchar(100) not null unique,
    daily_request_limit   integer not null,
    daily_token_limit     integer not null,
    daily_cost_limit_usd  numeric(10, 4) not null,
    -- Chat requests per minute; null falls back to the application default.
    rate_limit_per_minute integer,
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now()
);

create table app_users (
    id              uuid primary key default gen_random_uuid(),
    username        varchar(100) not null unique,
    email           varchar(255) unique,
    display_name    varchar(255) not null,
    password_hash   varchar(255) not null,
    role            varchar(50) not null default 'USER',
    status          varchar(50) not null default 'ACTIVE',
    -- Bumped on logout/password change: every access and refresh token issued before is revoked.
    token_version   integer not null default 0,
    quota_policy_id uuid references quota_policies(id),
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    constraint app_users_role_check check (role in ('USER', 'DOCTOR', 'ADMIN')),
    constraint app_users_status_check check (status in ('ACTIVE', 'LOCKED', 'DISABLED'))
);

create index idx_app_users_quota_policy_id on app_users(quota_policy_id);
create unique index ux_app_users_username_lower on app_users (lower(username));
create unique index ux_app_users_email_lower on app_users (lower(email)) where email is not null;

-- Which FHIR patient records an account may access (see docs/patient-link.md).
create table app_user_patient_links (
    id              uuid primary key default gen_random_uuid(),
    user_id         uuid not null references app_users(id) on delete cascade,
    fhir_patient_id varchar(100) not null,
    relationship    varchar(50) not null default 'SELF',
    is_primary      boolean not null default false,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    constraint app_user_patient_links_relationship_check
        check (relationship in ('SELF', 'DEPENDENT', 'CAREGIVER')),
    constraint app_user_patient_links_unique_patient_per_user
        unique (user_id, fhir_patient_id)
);

create index idx_app_user_patient_links_user on app_user_patient_links(user_id);
create unique index ux_app_user_patient_links_primary
    on app_user_patient_links(user_id) where is_primary;
-- A patient record belongs to at most one account.
create unique index ux_app_user_patient_links_self_patient_lower
    on app_user_patient_links (lower(fhir_patient_id)) where relationship = 'SELF';

-- ------------------------------------------------------------
-- 2. Conversations
-- ------------------------------------------------------------

create table chat_sessions (
    id                 uuid primary key default gen_random_uuid(),
    user_id            uuid not null references app_users(id) on delete cascade,
    title              varchar(255),
    -- Conversation memory sent back to chatbot-service on the next turn (ChatMemory).
    active_patient_id  varchar(100),
    memory_summary     text,
    last_intent        varchar(100),
    last_tool_name     varchar(100),
    last_resource_type varchar(100),
    last_resource_id   varchar(100),
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now()
);

create index idx_chat_sessions_user_id on chat_sessions(user_id);
create index idx_chat_sessions_user_active_patient on chat_sessions(user_id, active_patient_id);

create table chat_messages (
    id            uuid primary key default gen_random_uuid(),
    session_id    uuid not null references chat_sessions(id) on delete cascade,
    role          varchar(30) not null,
    content       text not null,
    metadata_json jsonb not null default '{}'::jsonb,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    constraint chat_messages_role_check check (role in ('user', 'assistant', 'system'))
);

create index idx_chat_messages_session_id_created_at on chat_messages(session_id, created_at);

create table message_feedback (
    id         uuid primary key default gen_random_uuid(),
    message_id uuid not null references chat_messages(id) on delete cascade,
    user_id    uuid references app_users(id) on delete set null,
    rating     smallint not null,
    comment    text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint message_feedback_rating_check check (rating between 1 and 5)
);

create index idx_message_feedback_message_id on message_feedback(message_id);
-- One rating per user and answer; a deleted user's ratings (user_id null) are kept apart.
create unique index ux_message_feedback_message_user
    on message_feedback (message_id, user_id) where user_id is not null;

-- ------------------------------------------------------------
-- 3. Usage and cost (quotas, cost reports)
-- ------------------------------------------------------------

-- One AI turn. Sessions and users may be deleted; their usage stays for quotas and reports.
create table usage_logs (
    id             uuid primary key default gen_random_uuid(),
    user_id        uuid references app_users(id) on delete set null,
    session_id     uuid references chat_sessions(id) on delete set null,
    operation      varchar(50) not null default 'chat',
    status         varchar(30) not null default 'success',
    llm_provider   varchar(50),
    llm_model      varchar(100),
    latency_ms     integer,
    request_count  integer not null default 1,
    input_tokens   integer not null default 0,
    output_tokens  integer not null default 0,
    -- Total cost reported by the LLM gateway; Spring never estimates it.
    cost_usd       numeric(12, 6) not null default 0,
    cost_source    varchar(20) not null default 'none',
    trace_id       varchar(64),
    error_message  text,
    answer_source  varchar(50) default 'llm',
    -- What a semantic-cache hit saved (tokens and cost of the original answer).
    saved_tokens   integer not null default 0,
    saved_cost_usd numeric(12, 6) not null default 0,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    constraint usage_logs_status_check check (status in ('success', 'failed', 'fallback', 'blocked')),
    constraint usage_logs_latency_ms_check check (latency_ms is null or latency_ms >= 0),
    constraint usage_logs_cost_source_check check (cost_source in ('gateway', 'unavailable', 'none'))
);

create index idx_usage_logs_user_id_created_at on usage_logs(user_id, created_at);
create index idx_usage_logs_session_id_created_at on usage_logs(session_id, created_at);
create index idx_usage_logs_model_created_at on usage_logs(llm_provider, llm_model, created_at);
create index idx_usage_logs_status_created_at on usage_logs(status, created_at);

-- Every LLM call of a turn (router, planner, answer...), for cost reports by the model really used.
create table llm_call_usage (
    id              uuid primary key default gen_random_uuid(),
    usage_log_id    uuid not null references usage_logs(id) on delete cascade,
    stage           varchar(50) not null,
    llm_provider    varchar(50),
    llm_model       varchar(100),
    input_tokens    integer not null default 0,
    output_tokens   integer not null default 0,
    cost_usd        numeric(12, 6) not null default 0,
    cost_source     varchar(20) not null,
    gateway_call_id varchar(100),
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    constraint llm_call_usage_cost_source_check check (cost_source in ('gateway', 'unavailable')),
    constraint llm_call_usage_tokens_check check (input_tokens >= 0 and output_tokens >= 0)
);

create index idx_llm_call_usage_usage_log_id on llm_call_usage(usage_log_id);

-- ------------------------------------------------------------
-- 4. Operations: audit trail, alerts, notifications, backups
-- ------------------------------------------------------------

create table audit_logs (
    id            uuid primary key default gen_random_uuid(),
    user_id       uuid references app_users(id) on delete set null,
    session_id    uuid references chat_sessions(id) on delete set null,
    action        varchar(100) not null,
    resource_type varchar(100),
    resource_id   varchar(100),
    metadata_json jsonb not null default '{}'::jsonb,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    constraint audit_logs_action_not_blank check (length(trim(action)) > 0)
);

create index idx_audit_logs_user_id_created_at on audit_logs(user_id, created_at);
create index idx_audit_logs_session_id_created_at on audit_logs(session_id, created_at);
create index idx_audit_logs_action_created_at on audit_logs(action, created_at);

create table alerts (
    id            uuid primary key default gen_random_uuid(),
    source        varchar(50) not null,
    alert_type    varchar(100) not null,
    severity      varchar(20) not null,
    status        varchar(20) not null default 'OPEN',
    message       text not null,
    metadata_json jsonb not null default '{}'::jsonb,
    resolved_at   timestamptz,
    resolved_by   varchar(100),
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create index idx_alerts_status_created_at on alerts(status, created_at);
create index idx_alerts_severity_created_at on alerts(severity, created_at);
create index idx_alerts_type_status_created on alerts(alert_type, status, created_at);

create table notifications (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid not null references app_users(id) on delete cascade,
    type       varchar(50) not null,
    title      varchar(255) not null,
    content    text not null,
    is_read    boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index idx_notifications_user_read on notifications(user_id, is_read);

-- Backups of the app and HAPI databases, run by infra/scripts/backup.sh.
create table backup_history (
    id               uuid primary key default gen_random_uuid(),
    trigger_type     varchar(20) not null,                -- AUTO | MANUAL
    status           varchar(20) not null default 'RUNNING',
    triggered_by     varchar(100),                        -- admin username, null when scheduled
    started_at       timestamptz not null default now(),
    finished_at      timestamptz,
    total_size_bytes bigint,
    upload_target    varchar(255),                        -- e.g. gdrive:medical-chatbot-backups
    items_json       jsonb not null default '[]'::jsonb,  -- [{db, file, size_bytes, ok, uploaded}]
    error_message    text,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    constraint backup_history_status_check check (status in ('RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED'))
);

create index idx_backup_history_created_at on backup_history (created_at desc);

create table restore_history (
    id            uuid primary key default gen_random_uuid(),
    backup_id     uuid references backup_history(id) on delete set null,
    status        varchar(20) not null default 'RUNNING',
    triggered_by  varchar(100),
    started_at    timestamptz not null default now(),
    finished_at   timestamptz,
    items_json    jsonb not null default '[]'::jsonb,     -- [{db, file, ok, error}]
    error_message text,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    constraint restore_history_status_check check (status in ('RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED'))
);

create index idx_restore_history_created_at on restore_history (created_at desc);

-- ------------------------------------------------------------
-- 5. Reference data: one quota policy per role
--    (self-registered accounts get user_standard, see QuotaPolicy.DEFAULT_USER_POLICY)
-- ------------------------------------------------------------

insert into quota_policies (id, name, daily_request_limit, daily_token_limit, daily_cost_limit_usd, rate_limit_per_minute)
values
    ('00000000-0000-0000-0000-000000000110', 'user_standard',     30,   50000,  0.50,  10),
    ('00000000-0000-0000-0000-000000000111', 'doctor_standard',  200,  500000,  5.00,  30),
    ('00000000-0000-0000-0000-000000000112', 'admin',           2000, 5000000, 50.00, 120);
