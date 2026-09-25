-- Development-only seed activation. application-prod.yml never loads this location.
update app_users
set status = 'ACTIVE',
    updated_at = now()
where username in ('user_demo', 'doctor_demo', 'admin_demo');
