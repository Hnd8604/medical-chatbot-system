-- Lịch sử khôi phục (restore) CSDL từ một bản backup.
-- Mỗi lần admin bấm "Khôi phục" trên Admin UI sẽ tạo một bản ghi ở đây.
create table if not exists restore_history (
    id uuid primary key default gen_random_uuid(),
    backup_id uuid references backup_history(id) on delete set null,  -- bản backup nguồn
    status varchar(20) not null default 'RUNNING',   -- RUNNING | SUCCESS | PARTIAL | FAILED
    triggered_by varchar(100),                        -- username admin
    started_at timestamptz not null default now(),
    finished_at timestamptz,
    items_json jsonb not null default '[]'::jsonb,    -- [{db,file,ok,error}]
    error_message text,
    created_at timestamptz not null default now()
);

create index if not exists idx_restore_history_created_at on restore_history (created_at desc);
