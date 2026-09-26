# Medical Chatbot Service

FastAPI orchestration service for chatbot, FHIR retrieval, semantic cache, and the
optional LangGraph agent.

This service sits behind the Spring Boot backend. It calls HAPI FHIR through REST
APIs only and must not query HAPI PostgreSQL tables directly. Authentication and
session ownership are enforced by Spring; the FastAPI request receives the resolved
user role, patient scope, conversation context, and LiteLLM virtual key.

## Run

From the repository root:

```powershell
cd chatbot-service
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

Default HAPI FHIR URL:

```text
http://localhost:8080/fhir
```

Override it with:

```powershell
$env:FHIR_BASE_URL="http://localhost:8080/fhir"
```

## LLM Intent And Answer Generation

The chat endpoint extracts a FHIR tool plan before calling HAPI FHIR.

LLM calls go through the LiteLLM gateway only—the service does not call OpenAI/Groq
directly and holds no provider API key. Copy `.env.example` to `.env`, then configure
at least these variables when enabling LLM features:

```env
LLM_PROVIDER=openai
MODEL_SIMPLE=gpt-4o-mini
MODEL_COMPLEX=gpt-4.1-mini
LITELLM_BASE_URL=http://localhost:4000
LITELLM_MASTER_KEY=sk-local-dev
LLM_REQUEST_TIMEOUT_SECONDS=20
ENABLE_LLM_ANSWER=true

# LLM Router: dùng model rẻ phân loại câu hỏi SIMPLE/COMPLEX (hybrid với keyword).
# Mặc định false → chỉ dùng keyword classifier.
ENABLE_LLM_ROUTER=false
MODEL_ROUTER=gpt-4o-mini
```

Provider API keys (`OPENAI_API_KEY`, `GROQ_API_KEY`) live only in `infra/litellm/.env`.
If `LITELLM_MASTER_KEY` is missing, the service automatically uses a local rule-based extractor and template answer fallback so demos still run.

Supported tool plans:

```text
fhir_status
get_patient_by_id
get_resource_by_id
search_patients
get_encounters
get_observations
get_conditions
get_medication_requests
get_all_patient_encounters
get_all_patient_observations
get_all_patient_conditions
get_all_patient_medication_requests
explain_concept
unsupported_question
```

After FHIR retrieval, `agents/answer_generator.py` can call the LLM again with only the normalized `evidence.data` payload. The LLM does not query FHIR or PostgreSQL directly.

## Endpoints

- `GET /health`
- `GET /fhir/status`
- `GET /patients?name=Nguyen&phone=0900000001&birth_date=2003-01-01&identifier=BN2026-00001&limit=20`
- `GET /patients/{patient_id}`
- `GET /patients/{patient_id}/encounters?limit=5`
- `GET /patients/{patient_id}/observations?limit=5`
- `GET /patients/{patient_id}/conditions`
- `GET /patients/{patient_id}/medications`
- `POST /chat`
- `POST /chat/langgraph` when `ENABLE_LANGGRAPH_AGENT=true` and LiteLLM is configured
- `POST /cache/invalidate/{patient_id}` for internal cache invalidation after FHIR data changes

Demo patient:

```text
BN2026-00001
```

Demo chat request:

```powershell
$body = @{
  user_id = "00000000-0000-0000-0000-000000000001"
  user_role = "USER"
  message = "What medications is Patient/BN2026-00001 taking?"
  patient_id = "BN2026-00001"
  allowed_patient_ids = @("BN2026-00001")
} | ConvertTo-Json

Invoke-RestMethod -Uri "http://localhost:8000/chat" -Method Post -ContentType "application/json" -Body $body
```

The response includes `tool_name`, `intent_source`, `answer_source`,
`answer_usage`, and combined `usage`. `answer_source` is `llm` when a model behind
LiteLLM writes the final answer; otherwise it is a template variant. Cache hits do
not call the FHIR or LLM stages.

## Tests

```powershell
cd chatbot-service
python -m unittest discover tests
```
