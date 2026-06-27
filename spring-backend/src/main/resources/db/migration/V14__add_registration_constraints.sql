create unique index if not exists ux_app_users_username_lower
    on app_users (lower(username));

create unique index if not exists ux_app_users_email_lower
    on app_users (lower(email))
    where email is not null;

create unique index if not exists ux_app_user_patient_links_self_patient_lower
    on app_user_patient_links (lower(fhir_patient_id))
    where relationship = 'SELF';
