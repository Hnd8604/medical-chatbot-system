# App PostgreSQL Notes

## What Was Built

This folder contains a separate PostgreSQL database for the web application layer.

It is intentionally separate from the HAPI FHIR PostgreSQL database.

## Service

```text
Service: app-postgres
Container: medical-chatbot-app-postgres
Image: postgres:16-alpine
Host port: 5433
Container port: 5432
Database: medical_chatbot_app
User: app_user
Password: app_password
```

## Current Scope

- This folder owns only the PostgreSQL container and persistent volume.
- Spring Boot owns the application schema through Flyway `V1` through `V9` in
  `backend/src/main/resources/db/migration/`.
- `backend/src/main/resources/db/devmigration/` contains development-only repeatable
  migration logic for demo accounts.
- `cache_entries` was removed by `V5`; semantic response caching is stored in Qdrant.
- The current schema also includes LiteLLM virtual keys and backup/restore history.

## Rule

Use this database for application data:

- users
- chat sessions
- chat messages
- usage logs
- quota policies
- audit logs
- alerts and notifications
- feedback and model pricing
- LiteLLM virtual keys
- backup and restore history

Do not store this application data inside the HAPI FHIR PostgreSQL database.

When the schema changes, add a new forward-only Flyway migration. Do not rewrite a
migration that may already have been applied.
