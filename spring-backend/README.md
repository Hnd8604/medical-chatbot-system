# Spring Backend

Main backend for the Medical Chatbot web application.

This Spring Boot service is the frontend-facing backend. It proxies selected patient/FHIR read APIs to the FastAPI chatbot service at `http://localhost:8000` and persists demo chat sessions, messages, and usage logs in the app PostgreSQL database.

## Runtime

- Java target: 21
- Spring Boot: 3.5.9
- Local port: `8081`
- Chatbot service base URL: `http://localhost:8000`
- App PostgreSQL: `localhost:5433/medical_chatbot_app`
- Auth: JWT Bearer tokens with `USER`, `DOCTOR`, and `ADMIN` roles.

Local demo accounts:

| Username | Password | Role |
|---|---|---|
| `user_demo` | `UserDemo123!` | `USER` |
| `doctor_demo` | `DoctorDemo123!` | `DOCTOR` |
| `admin_demo` | `AdminDemo123!` | `ADMIN` |

## Run

From this folder:

```powershell
.\mvnw.cmd spring-boot:run
```

If your shell still points to another JDK, use JDK 21 explicitly before running:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.11"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

## Test

```powershell
.\mvnw.cmd test
```

## Endpoints

- `GET /api/health`
- `GET /api/chatbot/status`
- `POST /api/auth/login`
- `GET /api/auth/me`
- `POST /api/auth/logout`
- `GET /api/admin/users?page=0&size=20`
- `PATCH /api/admin/users/{id}/status`
- `PATCH /api/admin/users/{id}/role`
- `GET /api/patients?name=Nguyen&phone=0900000001&birth_date=2003-01-01&identifier=DEMO-001&limit=20`
- `GET /api/patients/{patientId}`
- `GET /api/patients/{patientId}/observations?limit=5`
- `GET /api/patients/{patientId}/conditions?limit=20`
- `GET /api/patients/{patientId}/medications?limit=20`
- `POST /api/chat`

Demo:

```text
http://localhost:8081/api/patients/demo-patient-001
```

Chat demo:

```powershell
$body = @{
  message = "What medications is Patient/demo-patient-001 taking?"
  patient_id = "demo-patient-001"
} | ConvertTo-Json

Invoke-RestMethod -Uri "http://localhost:8081/api/chat" -Method Post -ContentType "application/json" -Body $body
```

## Architecture Rule

The frontend should call this Spring Boot backend. This backend calls the FastAPI chatbot service. It should not query HAPI PostgreSQL internal tables directly.

## Package Layout

```text
src/main/java/com/medicalchatbot/backend/
  config/       application configuration and HTTP client beans
  controller/   REST controllers
  dto/          request and response payloads
  entity/       reserved for future persistence/domain entities
  enums/        shared enum values
  exception/    global exception handlers
  mapper/       reserved for future DTO/domain mappers
  repository/   app PostgreSQL queries and writes
  service/      business flow and external service coordination
```

## App Database

Spring Boot connects to the app database, not the HAPI database:

```text
Host: localhost
Port: 5433
Database: medical_chatbot_app
User: app_user
Password: app_password
```

Flyway migration creates the minimum app tables:

```text
app_users
quota_policies
chat_sessions
chat_messages
usage_logs
cache_entries
```

The migrations create local demo users `user_demo`, `doctor_demo`, `admin_demo`, and a `free_demo` quota policy.
