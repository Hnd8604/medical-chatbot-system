create table if not exists app_user_patient_links (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references app_users(id) on delete cascade,
    fhir_patient_id varchar(100) not null,
    relationship varchar(50) not null default 'SELF',
    is_primary boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint app_user_patient_links_relationship_check
        check (relationship in ('SELF', 'DEPENDENT', 'CAREGIVER')),
    constraint app_user_patient_links_unique_patient_per_user
        unique (user_id, fhir_patient_id)
);

create unique index if not exists ux_app_user_patient_links_primary
    on app_user_patient_links(user_id)
    where is_primary;

create index if not exists idx_app_user_patient_links_user
    on app_user_patient_links(user_id);

insert into app_user_patient_links (
    user_id,
    fhir_patient_id,
    relationship,
    is_primary
)
select
    id,
    'demo-patient-001',
    'SELF',
    true
from app_users
where username = 'user_demo'
on conflict (user_id, fhir_patient_id) do update set
    relationship = excluded.relationship,
    is_primary = excluded.is_primary,
    updated_at = now();
