# Chatbot Service Setup Notes

## What Was Built

This folder contains the first FastAPI chatbot/FHIR service slice for the Medical Chatbot project.
Claude tự động nạp file này khi làm việc trong `chatbot-service/`.

Implemented:

- FastAPI service entrypoint.
- Health endpoint.
- FHIR status endpoint.
- Patient, Encounter, Observation, Condition, and MedicationRequest read endpoints.
- Patient search by FHIR REST criteria: name, phone, birth date, and identifier.
- Ambiguous patient handling with `needs_patient_selection`, `patient_candidates`, and `pending_question`.
- `POST /chat` endpoint for patient, observation, condition, and medication questions.
- Tool/function calling intent extraction via the LiteLLM gateway when `LITELLM_MASTER_KEY` is configured (provider keys live only in the gateway; no direct provider calls).
- Rule-based fallback intent extraction for local demos without an LLM gateway key.
- LLM final answer generation from normalized FHIR evidence when `ENABLE_LLM_ANSWER=true`.
- Template fallback answer generation when LLM answer generation is disabled or fails.
- Model routing (M16): keyword classifier + cost-aware quota downgrade, plus optional hybrid LLM Router (`ENABLE_LLM_ROUTER=true`, `MODEL_ROUTER`) that confirms SIMPLE cases with a cheap model via the LiteLLM gateway, runs in parallel with intent extraction, caches classifications, and falls back to the keyword result on errors. Response exposes `query_complexity` and `routing_source`; router token usage is added to the combined `usage`. See `docs/M16-M17-model-routing-retry-fallback.md`.
- Context compression (M15.3 V2): LLM rolling summary via LangGraph (`agents/summary_generator.py`) — runs in parallel with answer generation, triggers only when `conversation_context.total_message_count >= SUMMARY_TRIGGER_MESSAGE_COUNT` (default 6), returns `memory_update.summary` + `summary_usage` (added to combined `usage`). `memory_summary` + `recent_messages` are injected into the LLM intent extractor and answer generator prompts (`agents/context_payload.py`); the old keyword follow-up logic in `chat/context_memory.py` was removed (only `_patient_id_hint` remains). Summary errors (incl. budget) are swallowed with a warning; cache hits skip summarization. See `docs/M-context-rolling-summary.md`.
- Central FHIR HTTP client using HAPI FHIR REST APIs.
- Normalizers that convert raw FHIR resources/Bundles into compact JSON for app and future LLM usage.
- Unit tests for normalizers, FHIR client behavior with mocked HTTP transport, and intent extraction behavior.

Not implemented yet:

- Real cost estimation from token usage.
- Authentication/access control.

## Main Rule

Chatbot service code must use FHIR REST endpoints for structured medical data. Do not query HAPI PostgreSQL tables directly.

## Local Run

```powershell
cd chatbot-service
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

## HAPI FHIR Dependency

Expected local FHIR base URL:

```text
http://localhost:8080/fhir
```

The HAPI stack is managed in:

```text
infra/hapi-fhir
```

## Intent Extraction

The chat route first extracts a tool plan:

```text
message + optional patient_id
  -> IntentExtractor
  -> tool_name + patient_id/search criteria + optional limit/observation_type
  -> FHIR retrieval function
```

When configured, the OpenAI extractor asks the model to select one of these tools:

```text
fhir_status
get_patient_by_id
get_resource_by_id
search_patients
get_encounters
get_observations
get_conditions
get_medication_requests
get_all_patient_observations
get_all_patient_encounters
get_all_patient_conditions
get_all_patient_medication_requests
unsupported_question
```

Notes:

- `fhir_status` checks HAPI FHIR availability via `GET /fhir/metadata`; if the server is down the chat answer reports it instead of failing.
- `get_resource_by_id` fetches one resource by `resource_type` + `resource_id` (whitelist: Patient, Encounter, Observation, Condition, MedicationRequest). USER role is always denied for this tool.
- The four `get_all_patient_*` tools are normalized to their base tool with `all_patients=True` inside `plan_from_tool_call`, so they share the existing multi-patient answerers and role policy (USER role denied).

Without `LITELLM_MASTER_KEY`, `RuleBasedIntentExtractor` keeps local demo behavior working
(including `fhir_status` keywords and explicit `Encounter|Observation|Condition|MedicationRequest/{id}` references).

## Answer Generation

After FHIR retrieval, `agents/answer_generator.py` receives:

```text
question + intent + tool_name + patient_id + evidence.data + fallback_answer
```

If OpenAI answer generation is enabled, the model writes the final Vietnamese answer using only the normalized evidence. It must not invent patient data, create new diagnoses, or query any data source. If the LLM call fails or evidence is empty, the service returns the template answer.

Relevant response fields:

```text
answer_source: llm | template | template_fallback | template_no_evidence | template_patient_selection
answer_usage: token usage for answer generation only
usage: combined intent + answer usage
llm_provider: configured LLM provider, used by Spring usage logging
llm_model: configured LLM model, used by Spring usage logging
```

## Evidence Shape

Chat responses now keep both a compact summary and detailed normalized FHIR data:

```json
{
  "resource_type": "Observation",
  "id": "OBS-2026-00002",
  "summary": "HbA1c",
  "data": {
    "status": "final",
    "effective_time": "2026-05-24T14:18:00+07:00",
    "encounter": "Encounter/ENC-2026-00004",
    "value": {"value": 7.2, "unit": "%"},
    "interpretation": [{"text": "High"}],
    "reference_range": [{"text": "Non-diabetes reference threshold."}],
    "note": ["Demo value for testing interpretation and reference range formatting."]
  }
}
```

The normalizer preserves detailed fields for Patient, Encounter, Observation, Condition, and MedicationRequest while keeping the older summary fields compatible.

## Verification Result

Last checked on 2026-05-30:

```text
python -m unittest discover tests: 52 tests passed
python -m py_compile agents\intent_extractor.py api\chat_routes.py api\fhir_routes.py fhir\client.py agents\answer_generator.py: passed
app import: passed
GET /health: passed
GET /fhir/status: passed
GET /patients?name=Nguyen&limit=5: passed
GET /patients/BN2026-00001: passed
GET /patients/BN2026-00001/observations?limit=5: passed
GET /patients/BN2026-00001/conditions: passed
GET /patients/BN2026-00001/medications: passed
POST /chat medication demo: passed
LLM/tool-call intent extraction fallback: passed
Chatbot service dev server: http://localhost:8000
Detailed evidence passthrough via POST /chat: passed
LLM final answer via POST /chat: passed
Spring passthrough of answer_source and answer_usage: passed
Encounter direct endpoint and chat flow: passed
Patient search direct endpoint and chat flow: passed
Ambiguous patient candidate payload unit test: passed
Selected patient_id clears search criteria before resource retrieval: passed
```
