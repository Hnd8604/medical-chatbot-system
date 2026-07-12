-- ============================================================
-- V4: Đồng bộ display_name của tài khoản app với danh tính hồ sơ
--     FHIR được liên kết (app_user_patient_links, relationship=SELF).
--
-- Khi user hỏi "thông tin của tôi", chatbot lấy hồ sơ FHIR đã liên kết.
-- Tên trên hồ sơ FHIR (seed generator) nay có dấu tiếng Việt, nên tên
-- hiển thị của tài khoản phải trùng khớp để nhất quán.
--   user_demo  -> BN2026-00001 (Nguyễn Văn An)
--   le_hoa     -> BN2026-00007 (Lê Thị Hoa)      ... v.v.
-- Bác sĩ demo cũng đổi sang tên có dấu cho đồng bộ hiển thị.
-- ============================================================

update app_users set display_name = 'Nguyễn Văn An',  updated_at = now() where username = 'user_demo';
update app_users set display_name = 'Lê Thị Hoa',      updated_at = now() where username = 'le_hoa';
update app_users set display_name = 'Phạm Văn Cường',  updated_at = now() where username = 'pham_cuong';
update app_users set display_name = 'Võ Thị Lan',      updated_at = now() where username = 'vo_lan';
update app_users set display_name = 'Đặng Minh Tuấn',  updated_at = now() where username = 'dang_tuan';
update app_users set display_name = 'Bùi Thị Mai',     updated_at = now() where username = 'bui_mai';
update app_users set display_name = 'Đỗ Văn Hùng',     updated_at = now() where username = 'do_hung';
update app_users set display_name = 'Trương Thị Nga',  updated_at = now() where username = 'truong_nga';
update app_users set display_name = 'Phan Văn Khoa',   updated_at = now() where username = 'phan_khoa';
update app_users set display_name = 'BS. Ngô Thị Thu', updated_at = now() where username = 'dr_ngo';
update app_users set display_name = 'BS. Lý Văn Nam',  updated_at = now() where username = 'dr_ly';
