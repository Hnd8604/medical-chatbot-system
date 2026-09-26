# App PostgreSQL

PostgreSQL database for the Spring Boot application layer.

This database is separate from the HAPI FHIR PostgreSQL database. Spring Boot owns
its schema through Flyway migrations in
[`backend/src/main/resources/db/migration`](../../backend/src/main/resources/db/migration/).

Current application data includes users/roles, patient links, chat sessions and
messages, usage/cost records, audit and alert records, model pricing, feedback,
LiteLLM virtual keys, and backup/restore history. Refresh tokens, reset-password
state, and rate-limit counters are kept in Redis. Medical resources remain in HAPI
FHIR and must be accessed through the FHIR REST API.

Flyway currently runs `V1` through `V9`. Development-only demo-account activation is
kept separately in
[`db/devmigration`](../../backend/src/main/resources/db/devmigration/).

## Connection

```text
Host: localhost
Port: 5433
Database: medical_chatbot_app
User: app_user
Password: app_password
```

## Run

From the repository root:

```powershell
docker compose -f infra/app-postgres/docker-compose.yml up -d
```

## Check

```powershell
docker compose -f infra/app-postgres/docker-compose.yml ps
docker exec medical-chatbot-app-postgres psql -U app_user -d medical_chatbot_app -c "select current_user, current_database();"
```

## Stop

```powershell
docker compose -f infra/app-postgres/docker-compose.yml down
```

To remove persisted data:

```powershell
docker compose -f infra/app-postgres/docker-compose.yml down -v
```

`down -v` deletes the local database volume. Use it only when a full local reset is
intended; Spring Boot will recreate the schema on the next startup.
