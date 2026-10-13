-- ------------------------------------------------------------
-- Chi phi AI chi lay tu LiteLLM gateway (header x-litellm-response-cost); Spring khong
-- giu bang gia va khong tu uoc tinh. usage_logs giu tong cua mot luot chat;
-- llm_call_usage giu tung LLM call (stage/model/cost) de bao cao theo model dung va
-- khong mat tien cua luot loi giua pipeline (vd router da goi xong thi planner bi
-- gateway chan budget).
-- ------------------------------------------------------------

-- Nguon cua usage_logs.estimated_cost_usd (giu ten cot cu):
--   gateway         : moi call deu co cost that tu gateway
--   unavailable     : co call gateway khong bao cost (ghi 0) -> tong bi thieu
--   none            : khong co LLM call nao (cache hit, template, ...)
--   legacy_estimate : dong truoc V10, cost do Spring uoc tinh theo bang gia cu
alter table usage_logs
    add column if not exists cost_source varchar(20) not null default 'legacy_estimate',
    add column if not exists trace_id varchar(64);

-- Dong moi luon ghi cost_source tuong minh; default chi de gan nhan cho dong cu.
alter table usage_logs
    alter column cost_source set default 'none';

alter table usage_logs
    drop constraint if exists usage_logs_cost_source_check;
alter table usage_logs
    add constraint usage_logs_cost_source_check
        check (cost_source in ('gateway', 'unavailable', 'none', 'legacy_estimate'));

create table if not exists llm_call_usage (
    id uuid primary key default gen_random_uuid(),
    usage_log_id uuid not null references usage_logs(id) on delete cascade,
    stage varchar(50) not null,
    llm_provider varchar(50),
    llm_model varchar(100),
    input_tokens integer not null default 0,
    output_tokens integer not null default 0,
    cost_usd numeric(12, 6) not null default 0,
    cost_source varchar(20) not null,
    gateway_call_id varchar(100),
    created_at timestamptz not null default now(),
    constraint llm_call_usage_cost_source_check
        check (cost_source in ('gateway', 'unavailable')),
    constraint llm_call_usage_tokens_check
        check (input_tokens >= 0 and output_tokens >= 0)
);

create index if not exists idx_llm_call_usage_usage_log_id on llm_call_usage(usage_log_id);

-- Bang gia khong con dung: gateway la nguon chi phi duy nhat.
drop table if exists model_pricing;

-- Trang thai dong bo budget giua quota policy (Spring) va virtual key (gateway).
-- Gateway la noi chan cost, nen lech budget phai nhin thay duoc va tu sua lai.
alter table llm_virtual_keys
    add column if not exists budget_sync_status varchar(20) not null default 'synced',
    add column if not exists budget_synced_at timestamptz,
    add column if not exists budget_sync_error varchar(500);

alter table llm_virtual_keys
    drop constraint if exists llm_virtual_keys_budget_sync_status_check;
alter table llm_virtual_keys
    add constraint llm_virtual_keys_budget_sync_status_check
        check (budget_sync_status in ('synced', 'pending', 'failed'));
