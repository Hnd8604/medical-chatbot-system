-- ============================================================
-- V16: Thay thế quota policies demo bằng các gói chính thức
--      free / pro / enterprise
-- ============================================================

-- 1. Cập nhật users đang dùng free_demo hoặc test_rate_limit sang policy "free" mới
--    (cần tạo free trước để có ID tham chiếu)

INSERT INTO quota_policies (id, name, daily_request_limit, daily_token_limit, daily_cost_limit_usd, rate_limit_per_minute)
VALUES
    ('00000000-0000-0000-0000-000000000110', 'free',       30,     50000,   0.50,   10),
    ('00000000-0000-0000-0000-000000000111', 'pro',       200,    500000,   5.00,   30)
ON CONFLICT (name) DO UPDATE SET
    daily_request_limit   = EXCLUDED.daily_request_limit,
    daily_token_limit     = EXCLUDED.daily_token_limit,
    daily_cost_limit_usd  = EXCLUDED.daily_cost_limit_usd,
    rate_limit_per_minute = EXCLUDED.rate_limit_per_minute;

-- 2. Chuyển tất cả user đang gắn free_demo hoặc test_rate_limit sang "free"
UPDATE app_users
SET    quota_policy_id = '00000000-0000-0000-0000-000000000110',
       updated_at      = now()
WHERE  quota_policy_id IN (
    SELECT id FROM quota_policies WHERE name IN ('free_demo', 'test_rate_limit')
);

-- 3. Xóa các policy cũ
DELETE FROM quota_policies WHERE name IN ('free_demo', 'test_rate_limit');
