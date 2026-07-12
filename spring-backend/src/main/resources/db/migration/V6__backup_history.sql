-- Lịch sử sao lưu CSDL (app + HAPI) lên Google Drive.
-- Ghi lại mỗi lần backup tự động (02:00) hoặc thủ công (nút Admin UI).
create table if not exists backup_history (
    id uuid primary key default gen_random_uuid(),
    trigger_type varchar(20) not null,               -- AUTO | MANUAL
    status varchar(20) not null default 'RUNNING',   -- RUNNING | SUCCESS | PARTIAL | FAILED
    triggered_by varchar(100),                        -- username admin (null nếu tự động)
    started_at timestamptz not null default now(),
    finished_at timestamptz,
    total_size_bytes bigint,
    upload_target varchar(255),                       -- vd gdrive:medical-chatbot-backups
    items_json jsonb not null default '[]'::jsonb,    -- [{db,file,size_bytes,ok,uploaded}]
    error_message text,
    created_at timestamptz not null default now()
);

create index if not exists idx_backup_history_created_at on backup_history (created_at desc);
