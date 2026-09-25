-- Keep the most recent feedback if concurrent requests created duplicates before
-- the database invariant existed. Feedback whose user was deleted remains
-- untouched because PostgreSQL intentionally treats NULL users as distinct.
with ranked_feedback as (
    select id,
           row_number() over (
               partition by message_id, user_id
               order by created_at desc, id desc
           ) as row_number
    from message_feedback
    where user_id is not null
)
delete from message_feedback feedback
using ranked_feedback ranked
where feedback.id = ranked.id
  and ranked.row_number > 1;

create unique index if not exists ux_message_feedback_message_user
    on message_feedback (message_id, user_id)
    where user_id is not null;
