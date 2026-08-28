# M-LG — Thiết kế Agent (LangGraph) cho medical-agent-system

> Thiết kế + trạng thái triển khai của agent trong `chatbot-service`: chuyển từ pipeline
> **single-shot intent → 1 tool → answer** sang **multi-agent graph**
> (router → planner → validator → executor → answer), tham chiếu bản
> `Medical-Chatbot-develop/chatbot-service/langgraph_agent/`.
>
> **Trạng thái: M-LG1–M-LG3 đã code, 280 test xanh.** Endpoint `POST /chat/langgraph`
> sau cờ `ENABLE_LANGGRAPH_AGENT` (mặc định tắt). Chi tiết ở §2; việc còn lại ở §5.
>
> Nguyên tắc: **giữ nguyên các thế mạnh riêng của repo này** (LiteLLM gateway, semantic
> cache, pricing, rolling summary chạy song song, terminology 3 nguồn) — bản develop **không có**
> hoặc **làm mất** những thứ đó khi chạy qua LangGraph.

---

## 1. Bản develop có gì (kết quả đọc code)

`langgraph_agent/` — 23 file, ~6.9k dòng, expose ở endpoint **riêng** `POST /chat/langgraph`
(endpoint `/chat` cũ vẫn giữ nguyên → pattern migration an toàn, nên học theo).

### 1.1 Graph

```text
prepare_context → memory_load → request_route_agent
                                   ├─ conversation_meta ─┐
                                   ├─ general_chat_agent ─┤
                                   ├─ unsupported_agent ──┤
                                   └─ planner_agent       │
                                        → validate_plan   │
                                        → execute_plan    │
                                        → terminology_enrichment
                                        → build_planned_response
                                                          │
                              checkpoint_memory_update ←──┘
                                        → memory_store_update → END
```

### 1.2 Vai trò từng node

| Node | Loại | Việc |
|---|---|---|
| `prepare_context` | thuần | Chuẩn hoá `allowed_patient_ids`, `explicit_patient_id`, merge checkpoint memory vào request; chặn sớm nếu chưa bật LLM |
| `memory_load` | I/O | Đọc long-term preference của user từ LangGraph Store (`answer_style`, `language`, `preferred_examples`) |
| `request_route_agent` | **LLM #1** | Chọn 1 trong 4 route: `general_chat` / `conversation_meta` / `fhir` / `unsupported`. Trả JSON kèm `resource_hint`, `safety_flag`, `reason` |
| `planner_agent` | **LLM #2** | Sinh **plan nhiều bước**: `{plan_type, answer_mode, steps[{id, tool, args, depends_on}]}`; hỗ trợ biến `"$resolve_patient.patient_id"` |
| `validate_plan` | thuần | Allowlist tool, clamp `limit`, normalize alias args, **enforce role policy** (USER không `list_patients` / `search_patients` / `get_all_patient_*`, `patient_id` phải ∈ allowed) |
| `execute_plan` | I/O | Chạy tuần tự từng step; tự resolve bệnh nhân khi thiếu `patient_id`; trả sớm `clarification_needed` / `patient_selection_needed` / `tool_error` |
| `terminology_enrichment` | I/O | Chỉ chạy khi `answer_mode = explain_with_external_knowledge` → gắn `external_knowledge` |
| `build_planned_response` | **LLM #3** | Sinh câu trả lời cuối từ evidence (bỏ qua nếu payload là clarification/no_data) |
| `checkpoint_memory_update` | **LLM #4 (đôi khi)** | Ghi `recent_messages` (6 gần nhất) + tóm tắt phần tràn ra → checkpoint memory |
| `memory_store_update` | I/O | Ghi preference dài hạn nếu user nói rõ ("trả lời ngắn gọn hơn") |

### 1.3 Điểm mạnh đáng mang về

1. **Tách 2 tầng quyết định LLM**: route (rẻ, guardrail) rồi mới plan (đắt). Prompt router cấm
   tuyệt đối `clarification_needed` — clarification là **kết quả của executor**, không phải route.
   Đây đúng là chỗ pipeline hiện tại hay sai: intent extractor phải vừa phân loại vừa chọn tool.
2. **Plan nhiều bước + biến giữa các bước** → trả lời được câu cần 2+ resource
   ("bệnh nhân X đang dùng thuốc gì và có chẩn đoán gì") mà `/chat` hiện tại không làm được.
3. **`validate_plan` là lớp enforcement thuần tuý** — LLM đề xuất, code quyết định. Prompt được
   viết để LLM *vẫn* chọn tool bị cấm cho đúng intent, để validator trả 403 rõ ràng thay vì
   LLM tự lách sang tool khác (khôn hơn cách hiện tại).
4. **Response status có ngữ nghĩa**: `answered | no_data | clarification_needed | patient_selection_needed | tool_error`.
5. **Checkpointer bọc redaction** (`RedactingAsyncCheckpointer`) — không ghi evidence thô/PHI vào
   bảng checkpoint, chỉ giữ ref `{resource_type, resource_id, summary}`.
6. **Model routing theo stage** (`AgentStage`: router / planner / context / general / summarizer /
   answer_simple / answer_complex / rag) — mịn hơn một chiều SIMPLE/COMPLEX hiện tại.
7. **`fhir/tools.py` + `tool_registry.py`**: lớp tool thuần data trả `{ok, data} | {ok:false, error_code}`,
   tách khỏi lớp sinh payload (`response_adapter.py`). Repo này đang trộn 2 việc trong `chat/resource_answerers.py`.

### 1.4 Điểm yếu — **không bê nguyên**

| # | Vấn đề trong bản develop | Hệ quả |
|---|---|---|
| 1 | `cache_node.py` viết xong nhưng **không nối vào graph** | Semantic cache bị bypass hoàn toàn. Với đề tài tối ưu chi phí thì đây là lỗi chí mạng |
| 2 | `runtime.create_chat_model()` gọi thẳng `ChatOpenAI(api_key=settings.openai_api_key)` | **Bỏ qua LiteLLM gateway** → mất virtual key theo user, mất budget enforcement, mất spend log |
| 3 | `estimated_cost_usd` hardcode `0` ở mọi `_usage_from_message()` | Usage log sai; repo này đã có `agents/pricing.py` |
| 4 | 3–4 LLM call/lượt (route + plan + answer + summary) so với 2 hiện tại | Tăng cost & p95 latency — ngược mục tiêu đề tài nếu không bù bằng cache |
| 5 | `checkpoint_memory_update` chạy **đồng bộ trước khi trả response** | Repo này đang chạy rolling summary **song song** (`asyncio.create_task`). Bê nguyên = regression latency |
| 6 | Không có retry/fallback: mọi exception LLM → 503 `AI_UNAVAILABLE` | Mâu thuẫn M17 |
| 7 | `execute_plan` chạy step **tuần tự** kể cả khi độc lập | Bỏ phí `asyncio.gather` cho các step cùng `patient_id` |
| 8 | Prompt v1/v2/v3/v4 nằm lẫn trong module, có `return` chết + block comment khổng lồ (`request_router.py`, `planner.py`) | Không review / A-B test prompt được |
| 9 | `_conversation_context_for_prompt()` + `_safe_recent_metadata()` **copy nguyên văn** ở `planner.py` và `request_router.py` | Repo này đã có `agents/context_payload.py` |
| 10 | `LangGraphState` ~35 key, nhiều key chết (`selector_usage`, `tool_call`, `fhir_tool_result`, `query_scope_status`, `all_patient_tool_call`) | Rác từ graph đời trước; state càng to checkpoint càng nặng |
| 11 | `query_scope.py` (keyword all-patients) không còn node nào gọi | Dead module |

---

## 2. Đã triển khai gì trong repo này

> Trạng thái: **M-LG1 → M-LG3 đã code và có test** (280 test của `chatbot-service` xanh).
> M-LG4 (checkpointer Postgres) và M-LG6 (chạy số liệu thật) chưa làm — xem §5.

### 2.1 Graph thực tế

```text
prepare ─ cache_lookup ──hit──> END                            (0 LLM call)
             │miss
             └─ route ──┬─ general_chat ──────┐
                        ├─ conversation_meta ─┤─ finalize_simple ─ END
                        ├─ unsupported ───────┘
                        └─ plan ─ validate ─ execute ─ finalize_fhir ─ END

(song song từ đầu lượt: summary_task — rolling summary, await ở bước finalize)
```

Định nghĩa ở `chatbot-service/langgraph_agent/graph.py` (`AgentGraph._build`).

### 2.2 File đã thêm

| File | Vai trò | Dòng |
|---|---|---|
| `fhir/tool_registry.py` | 13 tool, mỗi tool là wrapper mỏng quanh answerer sẵn có | 335 |
| `langgraph_agent/graph.py` | StateGraph + 9 node + `run_agent()` | 549 |
| `langgraph_agent/state.py` | `AgentState` — 18 key, hằng số route/status | 70 |
| `langgraph_agent/prompts.py` | Toàn bộ prompt, có version | 147 |
| `langgraph_agent/llm.py` | Cửa LLM duy nhất (gateway + `metadata.stage`) | 163 |
| `langgraph_agent/request_router.py` | LLM #1 + parse + LRU cache route | 209 |
| `langgraph_agent/planner.py` | LLM #2 + parse + fallback từ `IntentPlan` | 188 |
| `langgraph_agent/plan_validator.py` | **Điểm enforce chính sách duy nhất**, thuần tuý | 217 |
| `langgraph_agent/plan_executor.py` | Chạy step (song song), gộp payload, guard runtime | 393 |
| `langgraph_agent/evidence_budget.py` | Cắt evidence tất định trước prompt answer | 132 |
| `langgraph_agent/plan_cache.py` | Cache plan (không PHI, TTL dài, dùng chung user) | 239 |
| `langgraph_agent/simple_answers.py` | 3 nhánh không chạm FHIR | 146 |
| `langgraph_agent/finalize.py` | Cầu nối sang `_finalize_chat_response` + fast-path | 144 |
| `langgraph_agent/errors.py` | `PolicyError` (403), `PlanError` (400), `AgentLlmError` | 44 |
| `tests/test_langgraph_agent.py` | 52 test | 821 |
| `tests/eval/questions.jsonl` | 42 câu có nhãn | — |
| `tests/eval/run_eval.py` | Bộ đo before/after | 345 |

Sửa: `app/config.py` (14 setting mới), `app/main.py` (init plan cache), `api/chat_routes.py`
(endpoint `/chat/langgraph`), `requirements.txt`, `.env.example`.

**Không sửa** `chat/resource_answerers.py`, `chat/response_builder.py`, `fhir/client.py`,
`fhir/normalizer.py`, `agents/*`, `terminology/*`, `services/semantic_cache.py` — agent
dùng lại nguyên các module này, nên `/chat` không có bất kỳ thay đổi hành vi nào.

### 2.3 Bốn quyết định kiến trúc

**(a) Không dùng `langchain-openai`.** Bản develop dựng `ChatOpenAI(api_key=settings.openai_api_key)`,
tức bỏ qua LiteLLM gateway → mất virtual key theo user, mất budget enforcement, mất spend log.
Repo này đã có đường LLM chuẩn (`openai` AsyncOpenAI + `agents/gateway_context.py`); thêm client
thứ hai nghĩa là nhân đôi logic đó. LangGraph chỉ cần node là `async def`, **không** bắt buộc
dùng LangChain model — nên `langgraph_agent/llm.py` là cửa duy nhất và nó gọi qua gateway:

```python
kwargs = gateway_call_kwargs()                       # virtual key + end-user
kwargs["extra_body"] = {"metadata": {"stage": stage}}  # spend log tách theo stage
```

Phụ thuộc mới vì vậy chỉ có `langgraph>=1.2,<2`, không thêm provider key nào.

**(b) Tool layer là wrapper, không phải lớp FHIR mới.** Bản develop viết lại `fhir/tools.py` +
`response_adapter.py` (~1.200 dòng) song song với answerer cũ — hai đường format dữ liệu khác
nhau và sẽ phân kỳ. Ở đây mỗi tool gọi thẳng `_answer_*` sẵn có và trả **đúng payload đó**:

```python
async def _tool_get_conditions(client, *, patient_id, limit=5):
    return await _answer_conditions(client, patient_id, limit)
```

Hệ quả tốt: agent thừa hưởng miễn phí câu trả lời template tiếng Việt (chính là fallback của
answer LLM, và là nền cho fast-path §6.2), shape `evidence`, và payload
`needs_patient_selection`/`patient_candidates`.

**(c) `_finalize_chat_response` được tái dùng nguyên vẹn.** Nó đã làm đủ: terminology
enrichment theo `plan.explain`, answer LLM, lưu semantic cache, chờ rolling summary, tính cost
bằng `agents/pricing.py`, dựng `memory_update`. Executor chỉ cần sinh `(payload, IntentPlan)`
rồi giao lại. Bản develop fork một `finalizer.py` riêng và **đánh mất cả cache lẫn cost**.

**(d) Chính sách USER — một điểm enforce, hai lớp kiểm.**
`plan_validator.py` là nơi **duy nhất** raise `PolicyError`; prompt chỉ gợi ý. Nhưng validator
không nhìn thấy `patient_id` đến từ `"$resolve_patient.patient_id"` (giá trị chỉ có lúc chạy),
nên `plan_executor._assert_patient_allowed()` kiểm lại lần hai sau khi resolve.
Có test riêng cho đúng lỗ hổng này (`test_user_cannot_reach_patient_resolved_at_runtime`).

Một khác biệt **vẫn đang chờ chốt**: repo này cấm USER dùng `get_resource_by_id` hoàn toàn;
bản develop cho phép rồi kiểm chủ sở hữu sau khi fetch. Cách develop tốt hơn về UX (USER mới
hỏi nối được "chỉ số này có ý nghĩa gì") và vẫn an toàn. Vì đây là **bề mặt bảo mật**, tôi
không tự đổi: cả hai đường đã code, chọn bằng cờ `AGENT_ALLOW_USER_RESOURCE_LOOKUP`
(mặc định `false` = giữ nguyên hành vi hiện tại).

### 2.4 Từng node

| Node | LLM? | Việc | Đường lui khi lỗi |
|---|---|---|---|
| `prepare` | không | Chuẩn hoá `allowed_patient_ids`, suy ra `provided_patient_id`, nén ngữ cảnh, bật `low_cost_mode` theo `quota_used_ratio` | — |
| `cache_lookup` | không | `get_cached_chat_payload()` — semantic cache đang có. Hit ⇒ huỷ `summary_task`, đi thẳng END | Lỗi Qdrant ⇒ coi như miss |
| `route` | **có** (rẻ) | Thứ tự: plan cache → low-cost mode → LLM router. Trả `route` + `resource_hint` + `safety_flag` | Mặc định route `fhir` (validator vẫn chặn quyền) |
| `plan` | **có** | Sinh plan nhiều bước; bỏ qua nếu plan cache đã cấp plan | `RuleBasedIntentExtractor` → plan 1 bước |
| `validate` | không | Allowlist tool, bỏ arg lạ, clamp limit, kiểm quyền, cắt số step | Raise 400/403 |
| `execute` | không | Chốt bệnh nhân trước, rồi `asyncio.gather` các step còn lại; gộp payload | Lỗi FHIR ⇒ payload `tool_error` (không 502) |
| `general_chat` / `conversation_meta` / `unsupported` | **có** (rẻ) | Một call trả `{"answer": ...}` | Template tĩnh |

### 2.5 Contract response

Giữ **nguyên** mọi trường mà Spring và frontend đang đọc:
`answer, intent, patient_id, evidence[], usage, saved_usage, tool_name, intent_source,
answer_source, answer_usage, llm_provider, llm_model, query_complexity, routing_source,
summary_usage, memory_update`.

Thêm (tuỳ chọn, Spring bỏ qua được):

| Trường | Ý nghĩa |
|---|---|
| `agent_route` | `fhir` / `general_chat` / `conversation_meta` / `unsupported` / `cache` |
| `response_status` | `answered` / `no_data` / `clarification_needed` / `patient_selection_needed` / `tool_error` |
| `plan_steps[]` | Các step đã chạy: `{id, tool, args}` |
| `stage_usage` | Token theo từng stage: `agent_router`, `agent_planner`, `agent_chat`, `agent_model_router`, `agent_answer`, `agent_summary` |
| `evidence_pruning` | `{chars_before, chars_after, dropped_items, pruned_fields}` khi có cắt |
| `prompt_versions` | Version prompt đã dùng — để đối chiếu với kết quả eval |

`stage_usage` là thứ `docs/optimization-direction.md` §3 cần: nó trả lời được "planner tốn bao
nhiêu token so với answer" mà không phải suy đoán.

### 2.6 Kế toán usage

```text
usage = plan.usage(planner) + answer_usage + router_usage(route + model_router) + summary_usage
stage_usage = từng stage tách riêng
estimated_cost_usd = agents/pricing.py tính MỘT LẦN trên tổng token, theo model của answer
```

Cache hit: `usage = 0`, `saved_usage` = usage của lượt đã sinh câu trả lời gốc. Giống hệt `/chat`.

---

## 3. Chạy và cấu hình

```powershell
cd chatbot-service
pip install -r requirements.txt          # thêm langgraph>=1.2,<2
# .env
ENABLE_LANGGRAPH_AGENT=true
uvicorn app.main:app --reload --port 8000
```

Cờ tắt ⇒ `POST /chat/langgraph` trả 404; `POST /chat` không đổi. Cờ chỉ có tác dụng khi có
`LITELLM_MASTER_KEY` (`Settings.use_langgraph_agent`).

Toàn bộ setting mới nằm ở `chatbot-service/.env.example`, mục `---- LangGraph agent (M-LG) ----`.
Ba cái đáng chú ý:

| Setting | Mặc định | Vì sao |
|---|---|---|
| `AGENT_TEMPLATE_FAST_PATH` | `false` | Tiết kiệm 1 LLM call/lượt nhưng đổi chất lượng câu trả lời — bật sau khi A/B bằng eval |
| `AGENT_ALLOW_USER_RESOURCE_LOOKUP` | `false` | Bề mặt bảo mật, chờ chốt (§2.3d) |
| `ENABLE_PLAN_CACHE` | `true` | Không chứa PHI, và là đòn giảm cost lớn nhất |

### Test

```powershell
cd chatbot-service
python -m unittest discover tests            # 280 test, gồm 52 test agent
python -m unittest tests.test_langgraph_agent
```

Bộ eval (gọi LLM thật, **tốn tiền** — không đưa vào CI):

```powershell
python -m tests.eval.run_eval --endpoint both --verbose
python -m tests.eval.run_eval --endpoint langgraph --json out.json
```

In bảng: route accuracy, tool accuracy, LLM call/lượt, token, cost, p50/p95, cache hit — và
bảng so sánh `langgraph` với `legacy`. `tests/eval/questions.jsonl` có 42 câu (31 fhir,
5 unsupported, 3 general_chat, 3 conversation_meta, trong đó 3 case chính sách kỳ vọng HTTP 403),
mỗi câu có cả biến thể tiếng Việt có dấu và không dấu ở các nhóm hay sai.

---

## 4. Kiểm thử đã có

52 test trong `tests/test_langgraph_agent.py`, tập trung vào hai thứ dễ hỏng nhất của một
agent nhiều bước: **enforce chính sách** và **đường lui khi LLM lỗi**.

| Nhóm | Ví dụ khẳng định |
|---|---|
| Router parsing | `clarification_needed` → `fhir`; route lạ → `AgentLlmError`; `conversation_meta` thiếu `meta_kind` → `current_context` |
| Route cache | Câu hỏi lặp (kể cả không dấu) không gọi LLM lần hai; đổi *hình thái* ngữ cảnh thì tách entry |
| Planner | Plan nhiều bước; `answer_mode` lạ → `data_only`; step trùng id được đặt lại; fallback từ `IntentPlan` |
| Validator | USER không `search_patients` / `get_all_patient_*` / bệnh nhân khác; arg lạ bị bỏ; `limit` bị clamp; `$ref` treo bị bỏ; số step bị cắt |
| Executor | Gộp evidence nhiều step; nhiều bệnh nhân khớp ⇒ dừng hỏi lại **trước khi** chạm dữ liệu; lỗi FHIR ⇒ `tool_error`; guard quyền sau khi resolve; step độc lập chạy song song |
| Evidence budget | Bỏ trường phụ trước; luôn giữ ít nhất 1 bản ghi và giữ bản mới nhất; `value` không bao giờ bị bỏ |
| Plan cache | `patient_id`/`name` → placeholder khi ghi; placeholder bị gỡ khi đọc |
| Graph e2e | Cache hit ⇒ **0 LLM call**; nhánh general_chat không chạm FHIR; planner lỗi ⇒ rule fallback, không 503; router lỗi ⇒ mặc định `fhir`; vi phạm quyền ⇒ `PolicyError`; lỗi budget nổi lên nguyên vẹn |
| Bộ eval | Nhãn hợp lệ: tool có trong registry, route hợp lệ, case USER khai `allowed_patient_ids`, mọi case dựng được `ChatRequest` |

---

## 5. Còn lại

| MS | Nội dung | Ghi chú |
|---|---|---|
| **M-LG4** | `persistence.py`: `AsyncPostgresSaver` + Store + `RedactingAsyncCheckpointer`, retention | Cần thêm `langgraph-checkpoint-postgres` + `psycopg`. State đã thiết kế sẵn cho việc này: deps đi qua `config["configurable"]`, không nằm trong state |
| **M-LG5** | `AgentStage` trong `agents/model_router.py` để route model theo stage bằng chính `ModelRouter` thay vì 3 setting rời | Fallback theo stage đã xong |
| **M-LG6** | Chạy `tests/eval/run_eval.py` thật, lấy bảng số liệu, chốt `AGENT_TEMPLATE_FAST_PATH` và `ROUTER_MODE` | Cần HAPI FHIR có dữ liệu + gateway sống |
| **M-LG7** | Streaming câu trả lời qua SSE | Lan sang Spring + frontend |

Chỉ cắt `/chat` sang graph sau khi M-LG6 cho thấy **cost/lượt không tăng** và **độ chính xác
tool tăng**. Trước đó hai endpoint chạy song song.

### Rủi ro còn mở

| Rủi ro | Trạng thái |
|---|---|
| Cost/latency tăng do thêm LLM call | Đã có 3 đòn giảm (route cache, plan cache, fast-path) nhưng **chưa đo thật** — M-LG6 |
| Prompt tiếng Việt dài làm phình token input | Đã tách ra `prompts.py` có version và đo được qua `stage_usage`; rút gọn sau khi có số liệu |
| Hai đường code phân kỳ | Giảm thiểu bằng cách agent **tái dùng** answerer + finalizer, không fork |
| `execute` chỉ song song hoá được các step sau bước chốt bệnh nhân | Đủ cho mọi hình dạng plan hiện tại (1 `search_patients` + N step dữ liệu); DAG tổng quát chưa cần |

---

## 6. Cải tiến vượt bản develop

§1.4 chỉ là *sửa lỗi* của bản develop. Mục này là những thứ **bản develop không có**, chọn theo
đúng trục đề tài: giảm token / giảm cost / giảm latency mà không giảm độ chính xác.

### 6.1 Plan cache — tách cache "ý định" khỏi cache "câu trả lời" ★

Semantic cache hiện tại là `câu hỏi → câu trả lời`, buộc phải TTL ngắn và scope theo user
vì answer chứa PHI (`product-spec.md` §8.3). Hệ quả: hit rate thấp, và **mọi miss đều trả giá
đủ 3 LLM call**.

Tách làm hai tầng:

| Tầng | Key | Value | Chứa PHI? | TTL | Scope |
|---|---|---|---|---|---|
| **Plan cache** (mới) | embedding(`normalize_text(question)`) + `user_role` | `{route, plan_type, answer_mode, steps[] với patient_id = placeholder}` | **Không** | dài (giờ/ngày) | **dùng chung mọi user** |
| Answer cache (đang có) | user + patient + question | answer | Có | ngắn | theo user |

"Bệnh nhân này đang dùng thuốc gì" và "thuốc của bệnh nhân đó là gì" cho **cùng một plan**
dù khác bệnh nhân, khác user. Plan cache hit ⇒ bỏ **cả LLM #1 và #2**, vẫn gọi FHIR nên dữ liệu
luôn tươi — đúng thứ hệ thống y tế cần: *intent lặp lại nhiều, dữ liệu thì không được cache lâu*.

Chỉ cache plan sau khi `validate_plan` chạy xong (cache plan đã hợp lệ, không cache output thô
của LLM). Placeholder được điền lại từ `provided_patient_id` / `resolved_patient_id` của lượt
hiện tại, rồi **vẫn chạy qua `validate_plan`** lần nữa để enforce role — plan cache không bao giờ
là đường vòng qua policy.

Triển khai: thêm collection Qdrant thứ hai, tái dùng `services/semantic_cache.py`
(fastembed + threshold + TTL đã có sẵn). Ước tính đây là đòn giảm cost lớn nhất trong cả danh sách.

### 6.2 Template answer cho câu hỏi tra cứu thuần ★

Bản develop luôn gọi LLM #3. Nhưng repo này đã có bộ template tốt trong
`chat/resource_answerers.py`. Điều kiện dùng template (0 LLM call):

```text
response_status == "answered"
  AND answer_mode == "data_only"
  AND len(plan.steps) == 1
  AND len(evidence) <= N        (N ~ 5, chỉnh được)
```

"Bệnh nhân BN2026-00001 có chẩn đoán gì" không cần LLM để đọc lại 3 dòng Condition.
Đặt sau cờ `TEMPLATE_ANSWER_FAST_PATH`, A/B trong M-LG6 — nếu chất lượng không giảm thì
loại query phổ biến nhất tụt từ 3 LLM call xuống 2 (và xuống **0** khi kết hợp 6.1).

### 6.3 Evidence budget trước khi vào answer LLM ★

Input token của answer node là khoản chi lớn nhất mỗi lượt, và plan nhiều bước làm nó
phình theo cấp số nhân (3 step × 10 record × full `data`). Thêm bước **thuần, tất định**
trước answer:

- Cap tổng evidence theo token, không theo số record.
- Ưu tiên record mới nhất (`effective_time` desc) khi phải cắt.
- Bỏ field không liên quan câu hỏi (vd `reference_range`, `note` khi hỏi "ngày khám gần nhất").
- Ghi lại `pruned_tokens` → có số liệu "giảm bao nhiêu token nhờ pruning" cho báo cáo.

Zero LLM cost, zero rủi ro bịa dữ liệu (chỉ bỏ bớt, không viết thêm).

### 6.4 Fallback dùng lại chính pipeline cũ

M17 trong bản develop là con số 0: mọi lỗi LLM → 503. Thiết kế fallback theo stage, tận dụng
code đang có thay vì viết mới:

| Stage lỗi | Fallback |
|---|---|
| `request_router` | Mặc định route `fhir` — an toàn vì `plan_validator` vẫn chặn; hoặc lấy route từ plan cache (6.1) |
| `planner` | Gọi **`agents/intent/rule_extractor.py`** (đang có sẵn) → plan 1 bước. Pipeline cũ trở thành đường lui của pipeline mới |
| `answer` | Template answer (6.2) |
| `summarizer` | Bỏ qua, giữ summary cũ (đã là hành vi hiện tại) |

Kết quả: graph **không có single point of failure LLM nào** trả 503.

### 6.5 Low-cost mode toàn lượt khi quota gần cạn

`model_router.py` hiện chỉ hạ cấp *model của answer* khi `quota_used_ratio > 0.8`. Mở rộng thành
chế độ của cả graph:

```text
quota_used_ratio > LOW_COST_MODE_RATIO:
    router      → dùng plan cache, miss thì mặc định route fhir (0 LLM)
    planner     → rule_extractor (0 LLM)
    terminology → tắt (bỏ external call)
    summary     → tắt
    answer      → model rẻ, hoặc template nếu đủ điều kiện 6.2
```

User gần hết quota vẫn được phục vụ với chi phí ~1 LLM call thay vì bị chặn 429 —
tốt hơn cho cả trải nghiệm lẫn con số "throughput / concurrent users" của báo cáo.

### 6.6 Gắn `stage` vào spend log của gateway

Thay vì tự cộng token thủ công, đẩy nhãn stage xuống LiteLLM:

```python
extra_body={"user": end_user, "metadata": {"stage": "planner", "session_id": ...}}
```

Gateway tự tách spend theo stage — dashboard "planner chiếm bao nhiêu % chi phí" có được
mà không phải viết thêm code accounting nào. `stage_usage` trong state chỉ còn là bản đối chiếu.

### 6.7 Router: sequential hay speculative — đo rồi chốt

Thêm `router` trước `planner` cộng thẳng 1 roundtrip vào p50. Hai phương án, để sau cờ
`ROUTER_MODE` và **quyết bằng số liệu M-LG6**, không quyết bằng cảm tính:

| | `sequential` | `speculative` |
|---|---|---|
| Cách chạy | router xong mới planner | `asyncio.gather(router, planner)`, bỏ kết quả planner nếu route ≠ fhir |
| Latency | +1 roundtrip | ~0 |
| Cost | thấp hơn | phí token planner trên các lượt non-fhir (~20–30%) |

Nếu tỉ lệ route `fhir` thực đo > ~75% thì `speculative` gần như luôn thắng.

### 6.8 Bộ eval cố định — không có nó thì M-LG6 là nói suông

`chatbot-service/tests/eval/questions.jsonl`: ~40 câu tiếng Việt (có dấu + không dấu), mỗi câu
gán nhãn `expected_route`, `expected_tools[]`, `expected_scope`, `role`. Một script chạy cả hai
endpoint và in bảng:

```text
route accuracy | tool accuracy | LLM calls/lượt | tokens in/out | cost/lượt | p50 | p95 | cache hit %
```

Đây là thứ biến "đồ án tối ưu vận hành" thành số liệu bảo vệ được, và cũng là lưới an toàn
chống regression khi sửa prompt. **Nên làm ở M-LG1**, trước khi viết node nào — để có baseline
của `/chat` hiện tại đem so.

### 6.9 Streaming câu trả lời (tùy chọn, M-LG7)

LangGraph có `astream_events`; repo đã có hạ tầng SSE (`docs/notification-sse-stream.md`).
Stream riêng node answer làm **latency cảm nhận** giảm mạnh dù tổng token không đổi.
Phạm vi lan sang Spring + frontend nên tách milestone riêng, làm sau khi graph ổn định.

### Thứ tự ưu tiên

| Cải tiến | Tác động | Công | Gắn vào |
|---|---|---|---|
| 6.8 Bộ eval | (điều kiện để đo mọi thứ khác) | thấp | **M-LG1** |
| 6.1 Plan cache | **cost ↓↓** | trung bình | M-LG3 |
| 6.3 Evidence budget | **token ↓↓** | thấp | M-LG3 |
| 6.2 Template fast-path | cost ↓ | thấp | M-LG3 |
| 6.4 Fallback dùng pipeline cũ | độ ổn định ↑↑ | thấp | M-LG5 |
| 6.6 Stage vào spend log | quan sát ↑ | rất thấp | M-LG3 |
| 6.5 Low-cost mode | throughput ↑ | trung bình | M-LG5 |
| 6.7 Speculative router | latency ↓ | thấp | M-LG6 |
| 6.9 Streaming | latency cảm nhận ↓↓ | cao | M-LG7 |
