-- Known demo credentials must never remain usable in environments that only
-- load the production migration location.
update app_users
set status = 'DISABLED',
    token_version = token_version + 1,
    updated_at = now()
where username in ('user_demo', 'doctor_demo', 'admin_demo')
  and status <> 'DISABLED';
