create table if not exists notifications (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id) on delete cascade,
    type varchar(50) not null,
    title varchar(255) not null,
    content text not null,
    is_read boolean not null default false,
    created_at timestamptz not null default now()
);

create index if not exists idx_notifications_user_read on notifications(user_id, is_read);
