-- ------------------------------------------------------------
-- AI Gateway (LiteLLM) DB-backed: anh xa app_user -> virtual key.
-- Gateway la nguon chan budget token/cost; bang nay chi luu mapping + budget.
-- ------------------------------------------------------------

create table if not exists llm_virtual_keys (
    user_id uuid primary key references app_users(id) on delete cascade,
    key_alias varchar(150) not null,
    virtual_key varchar(255) not null,
    max_budget_usd numeric(10, 4) not null,
    budget_duration varchar(20) not null default '1d',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
