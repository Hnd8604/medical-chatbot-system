-- ------------------------------------------------------------
-- V3: Đổi tên gói quota theo role người dùng
--     free -> user_standard, pro -> doctor_standard, enterprise -> admin
-- Giữ nguyên id và hạn mức (chỉ đổi name) nên FK app_users.quota_policy_id
-- không đổi. Sau đó gán mỗi user về gói khớp role để tên gói phản ánh
-- đúng đối tượng sử dụng.
-- ------------------------------------------------------------

-- 1. Đổi tên 3 gói quota (id giữ nguyên)
update quota_policies set name = 'user_standard'   where id = '00000000-0000-0000-0000-000000000110';
update quota_policies set name = 'doctor_standard' where id = '00000000-0000-0000-0000-000000000111';
update quota_policies set name = 'admin'           where id = '00000000-0000-0000-0000-000000000112';

-- 2. Gán user theo role về gói tương ứng
update app_users set quota_policy_id = '00000000-0000-0000-0000-000000000110' where role = 'USER';
update app_users set quota_policy_id = '00000000-0000-0000-0000-000000000111' where role = 'DOCTOR';
update app_users set quota_policy_id = '00000000-0000-0000-0000-000000000112' where role = 'ADMIN';
