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
- Context compression (M15.3 V2): LLM rolling summary (`agents/summary_generator.py`, plain async single LLM call) — runs in parallel with answer generation, triggers only when `conversation_context.total_message_count >= SUMMARY_TRIGGER_MESSAGE_COUNT` (default 8), returns `memory_update.summary` + `summary_usage` (added to combined `usage`). `memory_summary` + `recent_messages` are injected into the LLM intent extractor and answer generator prompts (`agents/context_payload.py`); the old keyword follow-up logic in `chat/context_memory.py` was removed (only `_patient_id_hint` remains). Summary errors (incl. budget) are swallowed with a warning; cache hits skip summarization. See `docs/M-context-rolling-summary.md`.
- Terminology enrichment (`terminology/`): explains medical codes via LOINC (`CodeSystem/$lookup` on `fhir.loinc.org`, Basic Auth), RxNorm (`rxnav.nlm.nih.gov`, no key), and MedlinePlus Connect (`connect.medlineplus.gov`, no key). Pure data-fetch layer (no LLM); the three sources fan out concurrently via `asyncio.gather`, each gated by its own flag (`LOINC_ENABLED`/`RXNORM_ENABLED`/`MEDLINEPLUS_ENABLED`), cached globally by code (not patient), capped at 5 requests/source, and degrading to `[]` on error. LOINC is skipped when `LOINC_USERNAME/PASSWORD` are absent (`has_credentials` gate). **Enrichment runs only for explanation-type questions**: the LLM intent extractor sets `IntentPlan.explain=true` (rule fallback uses keywords like "là gì", "ý nghĩa") when the user asks the meaning/purpose of retrieved data; `_finalize_chat_response` then attaches `external_knowledge` (top-level, beside `evidence`) which the answer generator summarizes into Vietnamese. Plain data questions skip terminology entirely (no external calls). Standalone concept questions ("HbA1c là gì", "Metformin dùng để làm gì") route to the new `explain_concept` tool/intent. See `docs/M-terminology-enrichment.md`.
- **LangGraph agent (M-LG)**: endpoint thứ hai `POST /chat/langgraph` sau cờ `ENABLE_LANGGRAPH_AGENT` (mặc định `false`). Graph: `prepare → cache_lookup → route → {general_chat | conversation_meta | unsupported | plan → validate → execute} → finalize`. Hai tầng quyết định LLM (router rẻ chọn route + `safety_flag`, rồi planner sinh **plan nhiều bước** với biến `"$resolve_patient.patient_id"`), `plan_validator` là **điểm enforce chính sách duy nhất** (thuần tuý, không I/O), `plan_executor` chạy các step độc lập bằng `asyncio.gather` và kiểm quyền lần hai sau khi resolve patient. Mọi LLM call đi qua `langgraph_agent/llm.py` → LiteLLM gateway, kèm `extra_body.metadata.stage` để spend log tách theo stage. **Tái dùng, không fork**: mỗi tool trong `fhir/tool_registry.py` là wrapper mỏng quanh `chat/resource_answerers._answer_*`, và bước cuối gọi thẳng `_finalize_chat_response` (nên thừa hưởng terminology, semantic cache, pricing, rolling summary, `memory_update`). Cải tiến riêng của bản này: **plan cache** (`langgraph_agent/plan_cache.py` — cache ý định đã validate, không chứa PHI, TTL dài, dùng chung mọi user → hit bỏ được cả router lẫn planner), **evidence budget** (`evidence_budget.py` — cắt tất định trước prompt answer), **template fast-path** (`AGENT_TEMPLATE_FAST_PATH`), **fallback theo stage** (planner lỗi → `RuleBasedIntentExtractor`; router lỗi → mặc định route `fhir`; chat lỗi → template — không stage nào trả 503), **low-cost mode** theo `quota_used_ratio`. Response giữ nguyên shape cũ, thêm `agent_route`, `response_status`, `plan_steps`, `stage_usage`, `evidence_pruning`, `prompt_versions`. Bộ eval 42 câu có nhãn ở `tests/eval/` (`python -m tests.eval.run_eval --endpoint both`). Xem `docs/M-langgraph-agent.md`.
- Central FHIR HTTP client using HAPI FHIR REST APIs.
- Normalizers that convert raw FHIR resources/Bundles into compact JSON for app and future LLM usage.
- Unit tests for normalizers, FHIR client behavior with mocked HTTP transport, intent extraction behavior, and terminology enrichment (foundation, 3-source fan-out, conditional gating).

Not implemented yet:

- Authentication/access control.

Cost estimation: `usage.estimated_cost_usd` in the chat response is now filled by
`agents/pricing.py` (mirrors Spring's `model_pricing` seed; same token×price formula
over combined tokens at the answer model), so response/logs match what Spring stores in
`usage_logs`. Spring's `CostEstimationService` remains the source of truth and recomputes
from the admin-editable `model_pricing` table on save — keep `agents/pricing.py` in sync
when prices change. Cache hits report `usage` cost 0 (a hit costs ~0) with saved tokens in
`saved_usage`.

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

## LangGraph Agent (M-LG)

Đường thứ hai, chạy song song với `/chat`, chưa thay thế nó.

```text
POST /chat            -> intent extractor (1 tool) -> FHIR -> answer      (đường chính)
POST /chat/langgraph  -> router -> planner (N step) -> validator
                                -> executor (gather) -> answer            (sau cờ)
```

Bật:

```env
ENABLE_LANGGRAPH_AGENT=true    # cần cả LITELLM_MASTER_KEY
```

Ngân sách LLM call mỗi lượt (mục tiêu, cần M-LG6 xác nhận bằng số thật):

| Loại lượt | `/chat` | `/chat/langgraph` |
|---|---|---|
| Semantic cache hit | 0 | **0** |
| Plan cache hit | — | 1 (chỉ answer) |
| Chào hỏi / meta | 2 | 1–2 |
| Hỏi 1 loại dữ liệu | 2–3 | 2–3 |
| Hỏi nhiều loại dữ liệu | không làm được | 3 |

Điểm cần nhớ khi sửa code trong `langgraph_agent/`:

1. **`plan_validator.py` là nơi duy nhất raise `PolicyError`.** Đừng rải kiểm quyền vào
   prompt hay executor. Ngoại lệ đã có chủ đích: `plan_executor._assert_patient_allowed()`
   kiểm lại sau khi `$resolve_patient.patient_id` được điền — validator không thấy giá trị đó.
2. **Đừng fork `_finalize_chat_response`.** Nếu cần đổi cách sinh câu trả lời cho agent,
   truyền một `AnswerGenerator` khác vào (xem `_TemplateAnswerGenerator` trong `finalize.py`).
3. **Plan cache tuyệt đối không được chứa PHI.** `_redact_step()` thay mọi
   `patient_id/resource_id/name/phone/birth_date/identifier` bằng placeholder. Thêm tham số
   định danh mới thì phải thêm vào `_REDACTED_ARG_KEYS`.
4. **Prompt nằm hết trong `prompts.py` và có version.** Đổi prompt thì tăng version rồi chạy
   `tests/eval/run_eval.py` để có số liệu so sánh.
5. Tool mới: thêm vào `fhir/tool_registry.py` (planner đọc danh mục từ đó qua
   `tool_catalog_for_prompt()`, validator dùng `params` để lọc arg lạ) — không cần sửa prompt.

Quyết định bảo mật **đang chờ chốt**: `AGENT_ALLOW_USER_RESOURCE_LOOKUP` (mặc định `false`).
`false` giữ chính sách hiện tại (USER không được `get_resource_by_id`); `true` cho phép và
`plan_executor._enforce_resource_ownership()` loại resource không thuộc USER sau khi fetch.
Bật cái này thì USER mới hỏi nối được "chỉ số này có ý nghĩa gì".

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

LangGraph agent (M-LG), kiểm ngày 2026-08-28:

```text
python -m unittest discover tests: 280 tests passed (52 test agent)
app import + AgentGraph.compile(): passed
graph e2e voi LLM gia lap: fhir / general_chat / unsupported / cache hit: passed
fallback: planner loi -> rule extractor, router loi -> route fhir: passed
policy: USER bi chan search_patients / get_all_patient_* / benh nhan khac: passed
tests/eval/questions.jsonl: 42 case, nhan hop le voi tool registry: passed
CHUA chay: tests/eval/run_eval.py voi LLM that (can HAPI FHIR co du lieu + gateway)
CHUA lam: M-LG4 checkpointer Postgres, M-LG6 so lieu before/after
```
