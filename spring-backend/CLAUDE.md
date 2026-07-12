# Spring Backend Notes

This folder contains the main Spring Boot backend for the web application.
Claude tự động nạp file này khi làm việc trong `spring-backend/`.

Implemented:

- Java 21 Spring Boot project generated with Maven Wrapper.
- Spring Boot runs on port `8081`.
- `ChatbotServiceClient` calls the FastAPI chatbot service at `http://localhost:8000`.
- Frontend-facing REST endpoints under `/api`.
- Patient search proxy endpoint for chatbot-service FHIR search.
- Chat response passthrough for ambiguous patient selection fields.
- `POST /api/chat` creates or reuses an app chat session, stores user/assistant messages, calls `chatbot-service`, stores an enhanced usage log, and writes an audit log.
- Chat history read APIs for the demo user:
  - `GET /api/chat/sessions?limit=20`
  - `GET /api/chat/sessions/{sessionId}/messages`
- Basic exception handling for chatbot-service errors.
- App PostgreSQL datasource configuration.
- Package layout now follows `config`, `controller`, `dto`, `entity`, `enums`, `exception`, `mapper`, `repository`, `service`.
- JDBC SQL access is isolated in repository classes instead of `ChatApplicationService`.
- Flyway migrations for minimum app tables, demo user/quota seed data, enhanced usage logging, and audit logs.
- Controller tests for health, patient proxy, validation, and chat response mapping.
- Controller/service tests for chat session history and session ownership checks.

Not implemented yet:

- Authentication and authorization.
- Frontend integration.
- Real LLM orchestration; current chatbot-service response is rule-based demo logic.

## Main Rule

The Spring Boot backend should not query HAPI PostgreSQL internal tables. It should call the chatbot service or HAPI FHIR REST APIs through controlled service layers.

## Package Layout

```text
src/main/java/com/medicalchatbot/backend/
  config/
  controller/
  dto/
  entity/
  enums/
  exception/
  mapper/
  repository/
  service/
```

## Local Run

```powershell
cd spring-backend
.\mvnw.cmd spring-boot:run
```

> **JAVA_HOME bắt buộc** cho mọi lệnh `mvnw.cmd`: `C:\Program Files\Java\jdk-21.0.10` (Java 21).

## Verified Endpoints

Last checked on 2026-05-31:

```text
GET http://localhost:8081/api/health
GET http://localhost:8081/api/chatbot/status
GET http://localhost:8081/api/patients?name=Nguyen&limit=5
GET http://localhost:8081/api/patients/BN2026-00001
GET http://localhost:8081/api/patients/BN2026-00001/observations?limit=5
GET http://localhost:8081/api/patients/BN2026-00001/conditions
GET http://localhost:8081/api/patients/BN2026-00001/medications
GET http://localhost:8081/api/chat/sessions?limit=3
GET http://localhost:8081/api/chat/sessions/{sessionId}/messages
POST http://localhost:8081/api/chat
```

## Verification Result

```text
.\mvnw.cmd test: passed
Java 21 runtime check: passed with C:\Program Files\Java\jdk-21.0.11
Spring package refactor: passed
Spring Boot app start: passed
Spring Boot dev server: http://localhost:8081
Spring -> chatbot-service -> HAPI FHIR integration: passed
Patient search proxy endpoint: passed
Ambiguous patient selection fields passthrough: passed
Chat history session/message APIs: passed
App PostgreSQL migration V1..V5: passed
Created app tables: app_users, quota_policies, chat_sessions, chat_messages, usage_logs, audit_logs
(cache_entries đã bị drop ở V5 — không dùng; caching qua Redis/semantic cache)
POST /api/chat persisted 1 chat session, 1 user message, 1 assistant message, 1 enhanced usage log, and 1 audit log
```
