-- ============================================================
-- V5: Bỏ bảng cache_entries (không còn được backend sử dụng).
--
-- Bảng này được tạo ở V1 như khung cho exact-cache trong app DB,
-- nhưng caching thực tế dùng Redis/semantic cache, không có code
-- Java nào đọc/ghi cache_entries. Drop qua migration mới thay vì
-- sửa V1 (Flyway sở hữu schema; migration đã apply là bất biến).
-- ============================================================

drop index if exists idx_cache_entries_expires_at;
drop table if exists cache_entries;
