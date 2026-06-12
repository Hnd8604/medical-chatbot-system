ALTER TABLE quota_policies ADD COLUMN rate_limit_per_minute INT DEFAULT 20;


UPDATE quota_policies SET rate_limit_per_minute = 9999 WHERE name = 'free_demo';

INSERT INTO quota_policies (id, name, daily_request_limit, daily_token_limit, daily_cost_limit_usd, rate_limit_per_minute)
VALUES (
    '00000000-0000-0000-0000-000000000102',
    'test_rate_limit',
    500,       
    500000,
    5.0000,
    5
);