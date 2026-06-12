# Medical Chatbot Milestones

This file tracks project progress. Update it whenever a meaningful feature, integration, test, or architecture decision is completed.

## Current Snapshot

Last updated: 2026-06-10

The project currently has an end-to-end demo flow:

```text
Frontend
  -> Spring Boot backend
  -> FastAPI chatbot-service
  -> LLM intent extraction
  -> FHIR REST retrieval
  -> HAPI FHIR
  -> normalized evidence
  -> LLM Vietnamese answer generation
  -> Spring checks daily quota before calling AI
  -> Spring estimates AI cost from model pricing
  -> Spring persists chat history, usage logs, and audit logs through JPA repositories
  -> Staff Demo Dashboard displays selected patient context, chat history, answer, and evidence
```

Core rule still applies:

```text
Use FHIR REST API for structured patient data.
Do not query HAPI FHIR PostgreSQL internal tables directly.
```

## Completed Milestones

### 0. Business Module Decomposition

Status: Done

Completed:

- Read `module_nghiep_vu_chatbot_y_te_moscow.md`.
- Created detailed module breakdown in `module_nghiep_vu_chatbot_y_te_moscow_chi_tiet.md`.
- Exported the detailed module breakdown to `outputs/module-breakdown/module_nghiep_vu_chatbot_y_te_moscow_chi_tiet.xlsx`.
- Broke MoSCoW modules into smaller implementation-oriented modules.
- Added requirements, concrete work items, and completion criteria for each submodule.

Verified:

- Markdown and Excel documentation files created for planning and backlog refinement.

### 1. HAPI FHIR Infrastructure

Status: Done

Completed:

- Created isolated `infra/hapi-fhir` folder.
- Added Docker Compose stack for HAPI FHIR JPA Server and PostgreSQL.
- Exposed HAPI FHIR at `http://localhost:8080/fhir`.
- Published HAPI PostgreSQL on host port `5434`.
- Added seed scripts and connection-check scripts.
- Seeded demo FHIR resources:
  - Patient
  - Encounter
  - Observation
  - Condition
  - MedicationRequest

Verified:

- `GET http://localhost:8080/fhir/metadata`
- Patient lookup by fixed demo IDs.
- Observation, Condition, MedicationRequest, and Encounter retrieval through FHIR REST API.

### 2. Chatbot Service

Status: Done

Completed:

- Created `chatbot-service` FastAPI service.
- Added health and FHIR status endpoints.
- Added FHIR client module.
- Added FHIR normalizers for:
  - Patient
  - Encounter
  - Observation
  - Condition
  - MedicationRequest
- Added `POST /chat`.
- Added LLM intent extraction with rule-based fallback.
- Added LLM answer generation from normalized FHIR evidence.
- Added detailed evidence payloads in chat responses.
- Added Vietnamese answer behavior.
- Added patient search by:
  - name
  - phone
  - birth date
  - identifier
- Added flexible patient-name search so Vietnamese full names can resolve even when HAPI matches by name token.
- Changed patient display names to Vietnamese order:
  - `Nguyen Van A`
  - `Tran Thi B`
  - instead of `Van A Nguyen`, `Thi B Tran`.

Verified:

- `python -m unittest discover tests`
- Latest result: 52 tests passed.
- `GET http://localhost:8000/patients/demo-patient-002` returns `Tran Thi B`.
- `GET http://localhost:8000/patients?name=Tran%20Thi%20B&limit=5` returns `demo-patient-002`.
- Chat request `tim benh nhan Tran Thi B` returns matching Patient evidence.
- Chat request `thuoc cua benh nhan Thi B Tran` resolves to `demo-patient-002` before retrieving medications.

### 3. Spring Boot Backend

Status: Done

Completed:

- Created Java 21 Spring Boot backend in `spring-backend`.
- Spring runs as the main frontend-facing backend on port `8081`.
- Added structure:
  - `config`
  - `controller`
  - `dto`
  - `entity`
  - `enums`
  - `exception`
  - `mapper`
  - `repository`
  - `service`
- Added `ChatbotServiceClient` to call `chatbot-service`.
- Added `POST /api/chat`.
- Added patient/FHIR proxy endpoints.
- Added patient search proxy endpoint:
  - `GET /api/patients?name=&phone=&birth_date=&identifier=&limit=`
- Added app PostgreSQL database for application data.
- Added minimum app tables through Flyway:
  - `app_users`
  - `quota_policies`
  - `chat_sessions`
  - `chat_messages`
  - `usage_logs`
  - `cache_entries`
- Persisted chat sessions, user messages, assistant messages, and usage logs.
- Upgraded `usage_logs` with LLM provider/model, operation, status, latency, and error fields.
- Added `audit_logs` for important application events.

Verified:

- `.\mvnw.cmd test -q`
- Spring backend starts with Java 21.
- `GET http://localhost:8081/api/patients?name=Nguyen&limit=5`.
- `POST http://localhost:8081/api/chat` routes through Spring -> chatbot-service -> HAPI FHIR.

### 4. Frontend Demo UI

Status: Replaced by Staff Demo Dashboard milestone

Completed:

- Added simple local frontend for testing chat flow.
- Sends chat requests through Spring backend.
- Displays:
  - answer
  - intent
  - tool name
  - answer source
  - evidence

Remaining:

- Continue improving visual polish and production UX after auth/access control decisions.

### 5. Ambiguous Patient Handling

Status: Done

Completed:

- Added explicit ambiguous-patient response metadata from `chatbot-service`:
  - `needs_patient_selection`
  - `patient_candidates`
  - `pending_question`
- Kept ambiguous patient answers on template output so the LLM does not guess which patient to use.
- Added Spring passthrough fields in `ChatResponse`.
- Added frontend candidate cards with a `Chọn` button.
- When a candidate is selected, the frontend resends the pending question with the chosen `patient_id`.
- Added backend logic so a selected `patient_id` clears old name/phone/birth-date search criteria before retrieving Patient, Observation, Encounter, Condition, or MedicationRequest data.
- Added `demo-patient-006` with the same family name `Nguyen` to FHIR seed data so ambiguity can be tested locally.

Verified:

- `python -m unittest discover tests`
- Latest result: 52 tests passed.
- `.\mvnw.cmd test -q` with Java 21.
- `node --check frontend/app.js`.
- `python infra/hapi-fhir/scripts/seed_fhir_data.py`.
- Live ambiguous-patient smoke test through Spring:
  - message `so dien thoai cua Nguyen`
  - returned `needs_patient_selection=true`
  - returned candidates `demo-patient-001` and `demo-patient-006`.
- Live selected-patient smoke test through Spring:
  - `POST http://localhost:8081/api/chat`
  - message `so dien thoai cua Nguyen`
  - `patient_id=demo-patient-006`
  - routed to `get_patient_by_id`.

### 6. App Usage And Audit Logging

Status: Done for current demo scope

Completed:

- Added Flyway migration `V3__upgrade_usage_logs_and_add_audit_logs.sql`.
- Upgraded `usage_logs` with:
  - `llm_provider`
  - `llm_model`
  - `operation`
  - `status`
  - `latency_ms`
  - `error_message`
- Added `audit_logs` with:
  - `user_id`
  - `session_id`
  - `action`
  - `resource_type`
  - `resource_id`
  - `metadata_json`
  - `created_at`
- Added `AuditLogRepository`.
- Updated `UsageLogRepository` and `ChatApplicationService` so each successful `POST /api/chat` stores enhanced usage data and a `CHAT_COMPLETED` audit event.
- Added `llm_provider` and `llm_model` metadata to chatbot-service chat responses so Spring can persist the actual LLM configuration.

Verified:

- `python -m unittest discover tests`
- `.\mvnw.cmd test -q` with Java 21.
- Flyway migration V3 applied successfully to `medical_chatbot_app`.
- Live Spring smoke test inserted a new `usage_logs` row with `openai`, `gpt-4o-mini`, `chat`, `success`, and `latency_ms`.
- Live Spring smoke test inserted a new `audit_logs` row with action `CHAT_COMPLETED`.

### 7. Staff Demo Dashboard And Chat History

Status: Done for current demo scope

Completed:

- Added Spring chat history APIs:
  - `GET /api/chat/sessions?limit=20`
  - `GET /api/chat/sessions/{sessionId}/messages`
- Added chat history DTOs and repository queries over `chat_sessions` and `chat_messages`.
- Added service ownership guard so session messages are only returned for the current demo user.
- Refactored frontend static UI into a staff dashboard:
  - ChatGPT-like chat history sidebar
  - selected patient header
  - chat area
  - compact patient search in the right detail panel
  - patient detail/evidence panel
- Patient search supports name, phone, birth date, identifier, and direct `demo-patient-*` lookup.
- Selecting a patient loads:
  - demographics
  - encounters
  - observations
  - conditions
  - medications
- Chat requests now automatically include the selected `patient_id`.
- Clicking a chat session loads stored user/assistant messages from the app database.
- Fixed chat history item layout so long session titles/previews no longer overlap in the scroll list.
- Moved chat history to the left sidebar, made the center chat panel fixed-height with its own message scroll, and changed patient search to show results only after a search.

Verified:

- `.\mvnw.cmd test -q` with Java 21.
- `node --check frontend/app.js`.
- Spring restarted on `http://localhost:8081`.
- Frontend static server available on `http://localhost:5173`.
- Live smoke test:
  - `GET /api/chat/sessions?limit=3` returned sessions.
  - `GET /api/chat/sessions/{sessionId}/messages` returned messages.
  - `GET /api/patients?name=Nguyen&limit=5` returned matching patients.
  - `POST /api/chat` with `patient_id=demo-patient-001` routed to `get_medication_requests`.
  - New chat persisted session/messages and wrote usage/audit rows.

### 8. Session Memory V1

Status: Done for current demo scope

Completed:

- Added lightweight session memory in the app database through Flyway V4:
  - `chat_sessions.active_patient_id`
  - `chat_sessions.memory_summary`
  - `chat_sessions.last_intent`
  - `chat_sessions.last_tool_name`
  - `chat_sessions.last_resource_type`
  - `chat_sessions.last_resource_id`
  - `chat_messages.metadata_json`
- Spring now builds `conversation_context` for chatbot-service from:
  - session memory
  - active patient
  - last FHIR resource reference
  - six recent messages
- Spring uses `active_patient_id` when the next message in the same session does not provide a patient id.
- chatbot-service now accepts `conversation_context`.
- chatbot-service returns `memory_update` with compact evidence refs instead of storing full raw FHIR evidence in memory.
- Follow-up questions such as `benh nhan do dang dung thuoc gi?` can reuse the active patient from the session.
- Follow-up questions such as `chi so do co cao khong?` can use `last_resource_type` and `last_resource_id`, then retrieve the FHIR resource again by REST API.
- Frontend restores the active patient profile when opening a chat session that has `active_patient_id`.

Verified:

- `python -m unittest discover tests`
- Latest result: 56 tests passed.
- `node --check frontend/app.js`
- `.\mvnw.cmd test -q` with Java 21 after setting `JAVA_HOME` to `C:\Program Files\Java\jdk-21.0.11`.

### 9. Quota Management V1

Status: Done for current demo scope

Completed:

- Added `QuotaService` in Spring.
- Added quota policy lookup from existing `quota_policies` and `app_users.quota_policy_id`.
- Added daily usage aggregation from existing `usage_logs`.
- Spring now checks quota before creating a chat session, saving a message, or calling `chatbot-service`.
- If the daily quota is exceeded, `POST /api/chat` returns HTTP `429 Too Many Requests`.
- Added `GET /api/quota/status` for the current `demo_user`.
- Added `QUOTA_BLOCKED` audit log event when a request is blocked by quota.
- Kept the V1 scope simple:
  - no new quota counter table
  - no Admin quota editing UI yet
  - no group-level quota enforcement yet

Verified:

- `.\mvnw.cmd test -q` with Java 21 after setting `JAVA_HOME` to `C:\Program Files\Java\jdk-21.0.11`.

Follow-up fix:

- Restarted Spring backend so runtime loads the new quota endpoint and enforcement code.
- Verified `GET /api/quota/status` returns current `demo_user` quota status.
- Verified `POST /api/chat` returns HTTP `429` when `daily_request_limit = 1` has already been reached.
- Fixed quota blocked reason strings in `QuotaService` so HTTP responses return readable Vietnamese instead of mojibake.

### 10. Cost Management V1

Status: Done for current demo scope

Completed:

- Added Flyway migration `V5__add_model_pricing_and_cost_backfill.sql`.
- Added `model_pricing` table with active OpenAI demo pricing:
  - `gpt-4.1-mini`
  - `gpt-4o-mini`
- Backfilled existing `usage_logs.estimated_cost_usd` where provider, model, and token counts matched pricing.
- Added `CostEstimationService` in Spring to calculate cost from input/output tokens and model pricing.
- Updated `ChatApplicationService` so Spring calculates cost before saving `usage_logs`.
- Kept `usage_logs` as the V1 per-request cost log; no separate `cost_logs` table yet.
- Added cost APIs:
  - `GET /api/model-pricing`
  - `GET /api/usage/cost-summary?from=YYYY-MM-DD&to=YYYY-MM-DD`
- Cost summary returns totals, model breakdown, daily breakdown, and missing-pricing models.
- Frontend Staff Demo Dashboard now shows a small `Chi phí AI` panel with today's tokens, cost, cost limit, remaining cost, and top model.
- `daily_cost_limit_usd` in quota now works with real estimated cost values.

Verified:

- `node --check frontend/app.js`
- `.\mvnw.cmd test -q` with Java 21 after setting `JAVA_HOME` to `C:\Program Files\Java\jdk-21.0.11`.
- Spring restarted and Flyway V5 applied successfully.
- `GET http://localhost:8081/api/model-pricing` returned active model prices.
- `GET http://localhost:8081/api/usage/cost-summary?from=2026-06-01&to=2026-06-01` returned today's request, token, and cost totals.

### 11. Spring JPA Flow Refactor

Status: Done for current demo scope

Completed:

- Replaced Spring JDBC repository implementation with Spring Data JPA repositories for the app database.
- Added entity mappings for:
  - `User`
  - `QuotaPolicy`
  - `ChatSession`
  - `ChatMessage`
  - `UsageLog`
  - `AuditLog`
  - `ModelPricing`
  - `CacheEntry`
- Java entity `User` maps to the existing database table `app_users`; the database table was not renamed.
- Kept Flyway as the schema owner and configured Hibernate with `ddl-auto: validate`.
- Disabled Open Session In View with `spring.jpa.open-in-view=false`.
- Kept complex reporting queries as JPA native projections for:
  - chat session history previews
  - quota usage summary
  - cost totals
  - cost by model
  - cost by day
  - missing model pricing
- Updated Spring services to use `User` and `ChatSession` entities while keeping public REST API contracts unchanged.
- Removed `AppUserRepository` and replaced it with `UserRepository`.

Verified:

- `.\mvnw.cmd test -q` with Java 21 after setting `JAVA_HOME` to `C:\Program Files\Java\jdk-21.0.11`.
- Spring non-web startup connected to app PostgreSQL on port `5433`.
- Flyway validated 5 migrations and reported schema version 5.
- Hibernate EntityManagerFactory started successfully with `ddl-auto: validate`.

Follow-up fix:

- Fixed chat history native projection after the JPA refactor so PostgreSQL `timestamptz` values returned as `Instant` are converted back to `OffsetDateTime` DTO fields.
- Verified `GET /api/chat/sessions?limit=3` returns HTTP 200.
- Verified `GET /api/chat/sessions/{sessionId}/messages` returns HTTP 200.

Follow-up refactor:

- Split Spring DTO package into:
  - `dto.request`
  - `dto.response`
- Moved chat input/context payload DTOs to `dto.request`.
- Moved API response, summary, quota, pricing, and cost DTOs to `dto.response`.
- Updated controller, service, repository, entity, exception, and test imports.
- Verified `.\mvnw.cmd test -q` with Java 21.
- Verified Spring JPA startup against app PostgreSQL still succeeds.
- Restarted Spring backend and verified `GET /api/chat/sessions?limit=1` returns HTTP 200.
 
### 12. Rate Limiting & Security Foundation (M10)

Status: Done

Completed:

- Added Redis infrastructure to `infra/redis/docker-compose.yml`.
- Implemented `RateLimitInterceptor` using Redis Lua Scripts to guarantee atomic INCR and EXPIRE operations, preventing race conditions.
- Added support for Cloud environments by parsing the `X-Forwarded-For` header for IP-based rate limiting.
- Implemented `CurrentUserService` to decouple identity extraction, allowing smooth transition from `anonymousUser` (IP-based) to `demo_user` or real JWT users in the future.
- Implemented `QuotaPolicyService` with Spring `@Cacheable` to dynamically fetch `rate_limit_per_minute` from the `quota_policies` PostgreSQL table without degrading performance.
- Added temporary `SecurityConfig` (permitAll) to unblock API testing while waiting for the M1 Authentication module.

Verified:

- Redis container starts successfully.
- Spamming `POST /api/chat` correctly yields HTTP 429 after exceeding the dynamically assigned limit.

## Current Capabilities

The system can currently answer questions about:

- Patient list.
- Patient details.
- Patient phone number/contact information.
- Patient search by name, phone, birth date, and identifier.
- Encounters/visits.
- Observations/labs/vitals:
  - blood pressure
  - glucose
  - heart rate
  - cholesterol
  - HbA1c
- Conditions/diagnoses.
- Medication requests.
- Staff demo dashboard patient search.
- Staff demo dashboard selected-patient chat context.
- Chat session history loading.
- Session-level active patient memory.
- Compact evidence reference memory for follow-up questions.
- Daily AI quota status and quota blocking before AI calls.
- Daily AI cost estimate and model pricing status.

- Dynamic Redis-based rate limiting per IP or user package.

Example questions:

```text
tim benh nhan Tran Thi B
so dien thoai cua benh nhan Tran Thi B
thuoc cua benh nhan Tran Thi B
huyet ap cua benh nhan 001
lich su kham cua benh nhan 005
chan doan cua tat ca benh nhan
benh nhan do dang dung thuoc gi
chi so do co cao khong
```

## Important Decisions

- HAPI FHIR PostgreSQL is treated as internal storage only.
- The chatbot retrieves structured data only through FHIR REST APIs.
- App-specific data uses a separate PostgreSQL database.
- Spring Boot is the main backend for the webapp.
- FastAPI `chatbot-service` is the AI/FHIR orchestration service.
- Patient names are stored in FHIR using `family` and `given`, but displayed in Vietnamese order by the normalizer.
- LLM output must be grounded in normalized FHIR evidence.
- Session memory stores compact references and summaries; detailed structured data is re-read from FHIR when needed.
- Quota V1 uses app database `quota_policies` plus daily aggregation from `usage_logs`; it does not maintain a separate counter table.
- Cost V1 uses `model_pricing` plus `usage_logs.estimated_cost_usd`; it does not create a separate `cost_logs` table yet.
- Spring app database persistence now follows JPA entity/repository flow; HAPI FHIR internal tables are still not mapped as entities.

- Rate limiting limits are driven by database configurations (quota packages) rather than hardcoded values, cached via Spring Cache to protect PostgreSQL.

## Next Recommended Milestones

### 1. RAG For Medical Explanations

Status: Planned

Goal:

- Use FHIR for exact patient data.
- Use RAG for explanations such as:
  - what high HbA1c means
  - what hypertension means
  - what a medication is commonly used for

### 2. Admin Cost And Quota UI

Status: Partially done

Goal:

- Enforce daily request limit. Done for demo scope.
- Track token usage. Done at log level.
- Estimate cost by model. Done for demo scope.
- Show quota/cost status in Staff Demo Dashboard. Done in compact form.
- Add Admin Dashboard for editing quotas/pricing and viewing longer-range cost statistics.

### 3. Frontend Improvements

Status: Partially done

Goal:

- Patient search UI. Done for staff demo.
- Chat history. Done for staff demo.
- Improve evidence panel. Basic readable summaries done; production polish remains.

### 4. Authentication And Access Control

Status: Planned

Goal:

- Replace demo user with real login.
- Add user-level access checks before exposing patient data.
