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

Created the database container and persistent volume.

Spring Boot now owns Flyway migrations for the app tables.
Spring Boot also seeds a demo user and quota policy for local chat testing.

## Verification Result

Last checked on 2026-05-31:

```text
docker compose config: passed
app-postgres container: healthy
host port mapping: 0.0.0.0:5433->5432/tcp verified
psql current_user/current_database check: passed
Spring Flyway migration V1: passed
Spring Flyway migration V2: passed
Spring Flyway migration V3: passed
Created app tables: app_users, quota_policies, chat_sessions, chat_messages, usage_logs, cache_entries, audit_logs
usage_logs enhanced fields: llm_provider, llm_model, operation, status, latency_ms, error_message
POST /api/chat persistence check: 1 session, 1 user message, 1 assistant message, 1 enhanced usage log, 1 audit log
```

## Rule

Use this database for application data:

- users
- chat sessions
- chat messages
- usage logs
- quota policies
- audit logs

Do not store this application data inside the HAPI FHIR PostgreSQL database.
