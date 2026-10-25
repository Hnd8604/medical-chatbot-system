-- ------------------------------------------------------------
-- Moc rolling summary: memory_summary hien tai bao phu toi tin nhan thu may cua session.
-- chatbot-service dung moc nay de chi tom tat cac tin CHUA tom tat, va Spring gui du cac
-- tin do (khong co dinh 8 tin) -> summary lo mot luot (cache hit, LLM loi, low-cost) khong
-- lam mat tin nao.
-- ------------------------------------------------------------

alter table chat_sessions
    add column if not exists memory_summary_message_count integer not null default 0;

-- Session cu da co summary: truoc day summary chay moi luot va luot gan nhat bao phu toi
-- truoc luot do (so tin - 2). Gia tri hoi thap chi khien vai tin duoc tom tat lai, an toan
-- hon gia tri cao (lam mat tin).
update chat_sessions s
set memory_summary_message_count = greatest(c.message_count - 2, 0)
from (
    select session_id, count(*) as message_count
    from chat_messages
    group by session_id
) c
where c.session_id = s.id
  and s.memory_summary is not null
  and s.memory_summary <> '';
