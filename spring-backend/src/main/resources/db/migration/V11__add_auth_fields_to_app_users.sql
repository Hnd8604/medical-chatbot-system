create extension if not exists pgcrypto;

alter table app_users
    add column if not exists display_name varchar(255);

alter table app_users
    add column if not exists password_hash varchar(255);

alter table app_users
    add column if not exists status varchar(50);

alter table app_users
    add column if not exists token_version integer not null default 0;

update app_users
set display_name = coalesce(display_name, username),
    status = coalesce(status, 'ACTIVE')
where display_name is null
   or status is null;

update app_users
set username = 'user_demo',
    email = 'user_demo@medical-chatbot.local',
    display_name = 'Demo User',
    password_hash = crypt('UserDemo123!', gen_salt('bf')),
    status = 'ACTIVE',
    role = 'USER',
    quota_policy_id = coalesce(quota_policy_id, '00000000-0000-0000-0000-000000000101'),
    token_version = coalesce(token_version, 0),
    updated_at = now()
where id = '00000000-0000-0000-0000-000000000201'
   or username = 'demo_user'
   or username = 'user_demo';

insert into app_users (
    id,
    username,
    email,
    display_name,
    password_hash,
    status,
    role,
    token_version,
    quota_policy_id
)
values
    (
        '00000000-0000-0000-0000-000000000202',
        'doctor_demo',
        'doctor_demo@medical-chatbot.local',
        'Demo Doctor',
        crypt('DoctorDemo123!', gen_salt('bf')),
        'ACTIVE',
        'DOCTOR',
        0,
        '00000000-0000-0000-0000-000000000101'
    ),
    (
        '00000000-0000-0000-0000-000000000203',
        'admin_demo',
        'admin_demo@medical-chatbot.local',
        'Demo Admin',
        crypt('AdminDemo123!', gen_salt('bf')),
        'ACTIVE',
        'ADMIN',
        0,
        '00000000-0000-0000-0000-000000000101'
    )
on conflict (username) do update set
    email = excluded.email,
    display_name = excluded.display_name,
    password_hash = excluded.password_hash,
    status = excluded.status,
    role = excluded.role,
    token_version = excluded.token_version,
    quota_policy_id = excluded.quota_policy_id,
    updated_at = now();

update app_users
set password_hash = coalesce(password_hash, crypt(username || '-Password123!', gen_salt('bf'))),
    display_name = coalesce(display_name, username),
    status = coalesce(status, 'ACTIVE'),
    token_version = coalesce(token_version, 0)
where password_hash is null
   or display_name is null
   or status is null;

alter table app_users
    alter column display_name set not null;

alter table app_users
    alter column password_hash set not null;

alter table app_users
    alter column status set not null;
