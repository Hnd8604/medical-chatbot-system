# M — AI Gateway bằng LiteLLM

**Mục tiêu:** Đặt một lớp trung gian (AI Gateway) giữa `chatbot-service` và các LLM provider (OpenAI, Groq) để tập trung **API key, logging, retry/fallback, multi-provider**, mở đường cho cost-aware routing và đo lường token/cost/latency.

Bổ trợ cho [M16/M17 (Model Routing & Retry/Fallback)](M16-M17-model-routing-retry-fallback.md) và [M8 (Cost Management)](M8-cost-management.md).

---

## Kiến trúc

```text
chatbot-service (AsyncOpenAI client)
  -> LiteLLM proxy  http://localhost:4000   (OpenAI-compatible)
       -> OpenAI   (gpt-4o-mini, gpt-4.1-mini)
       -> Groq     (llama-3.1-8b-instant, llama-3.3-70b-versatile)
```

- LiteLLM expose API **OpenAI-compatible**, nên client `AsyncOpenAI` chỉ cần đổi `base_url` + `api_key`. Call site `client.chat.completions.create(...)` **giữ nguyên**.
- **DB-backed** (cập nhật): gateway gắn Postgres (database `litellm` trên instance app-postgres) để bật **virtual key theo user, per-key budget, spend logs, Admin UI** `/ui`. LiteLLM tự chạy Prisma migrate tạo bảng `LiteLLM_*` lúc boot.
- **Cost là native ở gateway**: mỗi user Spring có 1 virtual key với `max_budget` = `daily_cost_limit_usd` (reset `1d`). Gateway chặn khi vượt budget. `QuotaService` đọc spend/budget cost từ gateway (`/key/info`) làm **source of truth cho cost**; token + request vẫn từ `usage_logs`. `usage_logs` giữ lại làm audit/history/analytics (không dùng làm chuẩn cost để tránh double-count).
- Tên model trong code (`model_simple`, `model_complex`) được khai làm **alias** trong LiteLLM nên không phải đổi code router.

### Luồng virtual key theo user

```text
Spring /api/chat
  -> LlmGatewayKeyService.resolveUserKey(user)   (lazy: cấp key lần đầu, budget = quota policy)
     -> LiteLLMAdminClient POST /key/generate {user_id, max_budget, budget_duration:"1d", models}
  -> ChatbotChatRequest.llm_key  ──► chatbot-service /chat
       -> set_gateway_context(llm_key, user_id)  (contextvar)
       -> mỗi call LLM: extra_headers Authorization=Bearer <virtual_key> + user=<user_id>
       -> gateway vượt budget => HTTP 429 => Spring passthrough 429 (như quota exceeded)
QuotaService.status  <- LiteLLMSpendService.getSpendForUser  <- /key/info {spend, max_budget}
```

## 4 trụ cột của AI Gateway

Gateway hiện thực đủ 4 vai trò của một lớp trung gian AI: **API Key, Logging, Cost Tracking, Routing**.

```text
Spring (8081)                 chatbot-service (8000)             LiteLLM Gateway (4000)
─────────────                 ──────────────────────             ──────────────────────
cấp virtual key/user ──llm_key──► contextvar per-request ──────►  auth theo virtual key
đọc spend về quota   ◄──/key/info────────────────────────────────  enforce budget, spend log
                                                                       │
                                                                 OpenAI / Groq
                                                                 (provider key chỉ ở đây)
```

### 1. API Key — 3 tầng key, provider key không rời gateway

| Tầng | Key | Phạm vi |
|---|---|---|
| Provider | `OPENAI_API_KEY`, `GROQ_API_KEY` | **Chỉ** trong `infra/litellm/.env` — Spring/chatbot-service/frontend không bao giờ thấy |
| Quản trị | `LITELLM_MASTER_KEY` | Gateway + Spring (gọi API quản trị `/key/*`) + chatbot-service (fallback) |
| Per-user | Virtual key `sk-...` | Spring cấp **lazy** khi user chat lần đầu (`LlmGatewayKeyService` → `POST /key/generate`), lưu mapping `llm_virtual_keys` (migration V2) |

Key chảy per-request: Spring gắn `llm_key` vào request → chatbot-service đặt vào contextvar (`gateway_context.py`) → 3 call site LLM override header `Authorization: Bearer <virtual_key>` qua `gateway_call_kwargs()`. Gateway mã hoá key trong DB bằng `LITELLM_SALT_KEY`. Thiếu key → fallback master key; gateway tắt → rơi về rule-based/template, không vỡ luồng chat.

**Vì sao 3 tầng (nguyên tắc least privilege + chặn blast radius):** mỗi tầng xuống thì **quyền ít hơn, thiệt hại khi lộ nhỏ hơn**.

| Tầng | Quyền | Nếu lộ thì mất gì | Thu hồi |
|---|---|---|---|
| **1. Provider** (`OPENAI_API_KEY`, `GROQ_API_KEY`) | Toàn quyền tài khoản provider (đốt tiền vô hạn) | **Cả tài khoản** | Xoay key ở provider |
| **2. Master** (`LITELLM_MASTER_KEY`) | Admin gateway: sinh/thu hồi key, đọc mọi spend — **không** chạm tài khoản provider | Toàn bộ gateway | Đổi master key |
| **3. Virtual** (`sk-...`, per-user) | Chỉ gọi LLM trong `max_budget` + `models` cho phép | **1 user, 1 ngày budget** | `/key/block` hoặc `/key/delete` |

- **Tầng 1 — cô lập bí mật lớn nhất:** provider key là "chìa khóa két tiền", nhốt ở **một chỗ duy nhất** (`infra/litellm/.env` → gateway) chính là lý do tồn tại của AI Gateway. Spring/chatbot/frontend không bao giờ thấy → xoay key = sửa 1 file.
- **Tầng 2 — admin nhưng đóng khung trong gateway:** Spring cần master key để cấp/sửa/thu hồi virtual key (`LiteLLMAdminClient`: `/key/generate`, `/key/update`, `/key/info`, `/key/block`). Mạnh nhưng chỉ trong phạm vi gateway, **chỉ server-side, không ra frontend**.
- **Tầng 3 — least privilege per-user:** mỗi user 1 key, `max_budget = daily_cost_limit_usd`, `budget_duration: "1d"`, `models` giới hạn, gắn `user_id` để spend log tách đúng người. Lộ 1 key → thiệt hại tối đa = budget/ngày của **đúng user đó**, thu hồi được mà không đụng ai khác.

**Fallback theo tầng (không vỡ luồng chat):** client chatbot-service mặc định cầm **master key** (`config.py` `llm_api_key = litellm_master_key`); virtual key chỉ **ghi đè header per-request**. Nên thiếu virtual key (gateway lỗi cấp key) → dùng master key, chat vẫn chạy nhưng mất enforcement budget per-user; không có master key luôn → `use_llm=false` → rule-based/template (demo offline).

**Vì sao chọn virtual key thay vì 1 key chung:** đây là pattern công nghiệp của các AI Gateway (LiteLLM, Portkey, Cloudflare AI Gateway, Kong) và cũng là nguyên tắc chung của IAM/API gateway (Stripe restricted keys, AWS IAM roles). So với 2 nấc đơn giản hơn:

| Phương án | Được | Mất |
|---|---|---|
| A. 1 provider key trong backend, không gateway | Đơn giản nhất | Không per-user budget, key nằm trong app, không failover |
| B. Gateway nhưng mọi người xài chung master key | Có logging + failover | Không tách spend/budget theo user |
| **C. 3-tier virtual key (dự án chọn)** | Least-privilege, budget + attribution per-user, thu hồi được | Nặng hơn: thêm DB `litellm`, key lifecycle, network hop |

Lý do chọn **C**: bài toán **đa người dùng có quota/cost per-user** (AGENTS.md §7) → buộc phải có credential per-user để chặn tiền đúng người; phần lớn là **cấu hình tool có sẵn** (LiteLLM lo mã hóa/thu hồi key) chứ không code tay. Đánh đổi thành thật: đây là lựa chọn **trên mức phạm vi demo tối thiểu** (AGENTS.md §17) một cách có chủ đích để hiện thực đầy đủ trụ cột gateway, đổi lấy chút overhead vận hành — không bắt buộc cho demo 1 người dùng. Lập luận bảo vệ mạnh nhất khi bị hỏi *"sao không dùng 1 key cho gọn"*: **least privilege + blast radius** — lộ key 1 user chỉ mất budget/ngày của người đó và thu hồi được, còn 1-key-cho-tất-cả thì lộ là mất sạch.

### 2. Logging — 2 lớp

- **Gateway-native**: mỗi request LLM ghi `LiteLLM_SpendLogs` (DB `litellm`): model, token, cost, latency, **gắn đúng end-user** nhờ tham số `user=user_id` ở mỗi completion call. Xem trực quan tại Admin UI `/ui` (tab Logs/Usage).
- **App-side** (audit/analytics, có từ trước): Spring ghi `usage_logs` (token, cost ước tính, latency, `answer_source`) + `audit_logs` mỗi lượt chat (`ChatApplicationService.saveUsage/saveAuditLog`).

**Vì sao giữ 2 lớp (không gộp về 1):** hai lớp **không trùng lặp** — chúng ghi những chiều dữ liệu khác nhau, gộp lại sẽ mất năng lực chứ không tiết kiệm.

| | Gateway-native (`LiteLLM_SpendLogs`) | App-side (`usage_logs` + `audit_logs`) |
|---|---|---|
| Đơn vị ghi | **mỗi call LLM** (1 lượt chat = 2–3 call: intent, router, answer) | **mỗi lượt chat** (business turn) |
| Cost/token | Chính xác từ provider (source of truth) | Chỉ ước tính (analytics) |
| `answer_source` (llm/template/fallback) | ❌ không biết | ✅ |
| `session_id` / `message_id` / nội dung hỏi | ❌ không biết | ✅ |
| Lượt **không gọi LLM** (template, rule-based, gateway sập) | ❌ **không có log** | ✅ vẫn ghi |
| Audit an toàn (healthcare: ai / khi nào / hỏi gì) | ❌ | ✅ |

Điểm chí mạng: khi **gateway sập hoặc trả lời bằng template**, gateway **không sinh log nào**, nhưng lượt chat đó vẫn xảy ra và (bối cảnh y tế) **bắt buộc phải có audit** → chỉ giữ lớp gateway sẽ mất lịch sử các lượt này. Ngược lại, app-side cost chỉ là ước tính, còn gateway biết **model thật sau fallback** + token thật → gateway vẫn là source-of-truth cho cost.

**Phân vai (cách gọi đúng, tránh hiểu nhầm là duplicate):**
- **Gateway = observability/cost của LLM** — per-call, tự động, chính xác.
- **App = audit + analytics nghiệp vụ** — per-turn, gắn user/session, bao cả lượt không-LLM.

**Không có double-count — và không nên bỏ cột cost ước tính:** cost chỉ được đếm **một lần** cho enforcement. `QuotaService.effectiveUsedCost` ưu tiên gateway `/key/info`, chỉ **fallback** `usage_logs.estimated_cost` khi gateway sập; analytics dùng cột estimate là **view riêng có nhãn ước tính**, không cộng vào quota. Cột `estimated_cost_usd` đang gánh **2 việc** mà gateway không thay được:

1. **Analytics breakdown** — `CostManagementService` → cost theo model/ngày/provider (`summarizeCostByModel/ByDay`, `findMissingPricingModels`) → `AdminCostController` → dashboard frontend. Gateway `/key/info` **chỉ trả tổng spend/key**, không có breakdown → analytics này không lấy từ gateway được.
2. **Fallback enforcement** — khi gateway down, cost-quota rơi về `usage_logs.estimated_cost`; bỏ cột này thì lúc degradation cost tụt về `$0`, mất chặn chi phí.

→ **Phân vai đúng (đã có sẵn trong hệ thống, không cần thay đổi gì):**
- **Cost enforcement** (chặn tiền): gateway `/key/info` — chính xác, per-key, **source of truth**.
- **Cost analytics** (báo cáo theo model/ngày): `usage_logs.estimated_cost` — ước tính, có breakdown gateway không cấp; đồng thời là fallback enforcement khi gateway lỗi.

Cột estimate **không redundant** với gateway spend (breakdown vs tổng là hai thứ khác nhau). Kịch bản "trim" duy nhất hợp lý là trỏ analytics vào `LiteLLM_SpendLogs` (bảng này có breakdown) để có số *chính xác* thay vì estimate — nhưng đó là **refactor** thêm coupling vào DB nội bộ gateway, ngoài phạm vi hiện tại.

### 3. Cost Tracking — gateway là source of truth, enforce tại nguồn

- **Budget**: mỗi virtual key có `max_budget` = `daily_cost_limit_usd` của quota policy, reset `1d`. Admin sửa policy → `syncBudgetForPolicy` đẩy budget mới xuống gateway cho mọi user thuộc policy.
- **Enforce**: vượt budget → gateway trả 429 `budget_exceeded` → chatbot-service nhận diện có cấu trúc (`is_budget_error`, đọc `error.type/code` trước, khớp chuỗi làm lớp phụ) → HTTP 429 → Spring passthrough (không 500, không âm thầm fallback template).
- **Đọc về**: `LiteLLMSpendService` đọc `spend/max_budget` từ `/key/info` (cache 5s) → `QuotaService` dùng làm **cost chính thức** trong quota status; `usage_logs` + `model_pricing` chỉ còn phục vụ analytics/audit (tránh double-count).

### 4. Routing — 2 tầng

- **Tầng app** (chọn model theo độ khó + quota): `agents/model_router.py` — keyword classifier + LLM router lai (M16), cost-aware downgrade khi `quota_used_ratio` cao → chọn `model_simple`/`model_complex`.
- **Tầng gateway** (độ tin cậy + đa provider): `infra/litellm/config.yaml` — alias model tách tên trong code khỏi provider thật; `num_retries: 2`; `fallbacks` (`gpt-4.1-mini` → `gpt-4o-mini`, `groq-llama-70b` → `gpt-4o-mini`) — model chính lỗi thì gateway tự chuyển, app không cần biết. `response.model` phản ánh model thật đã dùng để log cost đúng.

**Vì sao 2 tầng (không gộp về 1):** hai tầng trả lời **hai câu hỏi khác nhau**, trigger khác nhau, nên vuông góc và bổ sung nhau chứ không trùng.

- **App-tier** = *"câu này nên hỏi model NÀO?"* — quyết định **trước** khi gọi, theo **độ khó + quota** (cost/chất lượng). Downgrade là **cost-driven** (chủ động khi `quota_used_ratio ≥ 0.8`).
- **Gateway-tier** = *"làm sao GIAO được model đó dù provider trục trặc?"* — xử lý **trong lúc** gọi bằng retry + fallback (độ tin cậy). Fallback là **failure-driven** (phản ứng khi lỗi).

| | App-tier (`model_router.py`) | Gateway-tier (`config.yaml`) |
|---|---|---|
| Câu hỏi simple/complex | ✅ | ❌ (chỉ thấy 1 model name) |
| Quota của user | ✅ | ❌ |
| Provider key thật | ❌ (không giữ key) | ✅ (key chỉ ở đây) |
| Provider đang lỗi/outage | ❌ | ✅ |

App-tier **không thể** làm provider failover (không giữ provider key, không nên biết provider nào đang chết); gateway **không thể** routing theo độ khó (không biết câu hỏi là gì hay quota còn bao nhiêu). Trace ráp 2 tầng:

```text
App-tier:   keyword "phan tich"+"xu huong" → COMPLEX → chọn "gpt-4.1-mini"
   │        (quota_used_ratio ≥ 0.8 → hạ xuống "gpt-4o-mini")
   ▼
Gateway:    nhận "gpt-4.1-mini" → openai/gpt-4.1-mini
   │          ├─ OK                    → response.model = "gpt-4.1-mini"
   │          └─ lỗi (hết 2 retries)   → fallback "gpt-4o-mini"
   ▼                                     → response.model = "gpt-4o-mini"
Answer gen: đọc response.model → log cost đúng model thật
```

Chi tiết 3 kỹ thuật app-tier (keyword classifier + LLM router hybrid + cost-aware downgrade) và retry/fallback: xem [M16-M17](M16-M17-model-routing-retry-fallback.md).

## Thành phần

| File | Vai trò |
|---|---|
| `infra/litellm/config.yaml` | `model_list` (alias→provider), `num_retries`, `request_timeout`, `fallbacks`, `master_key`, `database_url`, `store_model_in_db` |
| `infra/litellm/docker-compose.yml` | Service `litellm` port `4000`, `DATABASE_URL`/`LITELLM_SALT_KEY`/`UI_*`, healthcheck `/health/liveliness` |
| `infra/litellm/.env.example` | `OPENAI_API_KEY`, `GROQ_API_KEY`, `LITELLM_MASTER_KEY`, `DATABASE_URL`, `LITELLM_SALT_KEY`, `LITELLM_UI_USERNAME/PASSWORD` (tạo `.env` thật, **không commit**) |
| `run-dev.ps1` | Tạo database `litellm` (idempotent) rồi khởi động/stop LiteLLM (khi `infra/litellm/.env` tồn tại) |
| `chatbot-service/app/config.py` | `litellm_base_url`/`litellm_master_key`; property `llm_base_url`/`llm_api_key`/`use_llm` (gateway là đường LLM duy nhất) |
| `chatbot-service/agents/gateway_context.py` | contextvar `current_llm_key`/`current_end_user`; `gateway_call_kwargs()` (extra_headers+user); `GatewayBudgetExceededError` |
| `agents/intent/llm_extractor.py`, `agents/answer_generator.py`, `agents/model_router.py` | 3 call site LLM: `**gateway_call_kwargs()` + `raise_if_budget_exceeded` |
| `chat/response_builder.py` | `AnswerResult.model` đọc `response.model`; map alias→(provider, pricing-model) cho analytics |
| Spring `service/LiteLLMAdminClient.java` | Quản virtual key qua master key: `/key/generate`, `/key/update`, `/key/info`, `/key/block` |
| Spring `service/LlmGatewayKeyService.java` | Cấp key lazy khi chat + `syncBudgetForPolicy` khi admin sửa quota |
| Spring `service/LiteLLMSpendService.java` | Đọc spend/budget cost từ `/key/info` (cache ngắn) cho `QuotaService` |
| Spring `entity/LlmVirtualKey.java` + migration `V2__llm_virtual_keys.sql` | Map `app_user` → virtual key + budget |

## Biến môi trường

**`infra/litellm/.env`** (không commit):
```
OPENAI_API_KEY=...
GROQ_API_KEY=...
LITELLM_MASTER_KEY=sk-local-dev
DATABASE_URL=postgresql://app_user:app_password@host.docker.internal:5433/litellm
LITELLM_SALT_KEY=sk-salt-local-dev   # KHÔNG đổi sau khi đã sinh key
LITELLM_UI_USERNAME=admin
LITELLM_UI_PASSWORD=admin
```

**`chatbot-service/.env`** (KHÔNG chứa key provider — chỉ master key của gateway):
```
LITELLM_BASE_URL=http://localhost:4000
LITELLM_MASTER_KEY=sk-local-dev   # phải khớp master_key của gateway
```

**`spring-backend/.env`** (Spring quản virtual key + đọc spend):
```
LITELLM_BASE_URL=http://localhost:4000
LITELLM_MASTER_KEY=sk-local-dev   # phải khớp master_key của gateway
LITELLM_GATEWAY_ENABLED=true      # false => bỏ qua gateway, dùng master key mặc định
```

- LiteLLM là **đường LLM duy nhất**: `llm_api_key = LITELLM_MASTER_KEY`, `llm_base_url = LITELLM_BASE_URL`. Không còn nhánh gọi OpenAI trực tiếp.
- Provider key (`OPENAI_API_KEY`, `GROQ_API_KEY`) chỉ tồn tại trong `infra/litellm/.env` — quản lý tập trung tại gateway.
- Thiếu `LITELLM_MASTER_KEY` → `use_llm=false` → chatbot dùng rule-based extractor + template answer (demo offline, không gọi LLM).

## Cost đúng khi đa provider

`response_builder._provider_and_pricing_model(alias)` map alias LiteLLM về đúng `(provider, model)` của bảng `model_pricing` Spring:

| Alias (LiteLLM / response.model) | provider | pricing model |
|---|---|---|
| `gpt-4o-mini` | openai | `gpt-4o-mini` |
| `gpt-4.1-mini` | openai | `gpt-4.1-mini` |
| `groq-llama-8b`, `groq/llama-3.1-8b-instant` | groq | `llama-3.1-8b-instant` |
| `groq-llama-70b`, `groq/llama-3.3-70b-versatile` | groq | `llama-3.3-70b-versatile` |

`LLMAnswerGenerator` đọc `response.model` (phản ánh cả khi gateway **fallback** sang model khác) → set `llm_model`/`llm_provider` thực tế → `CostEstimationService` tính cost cho analytics `usage_logs`. Việc **chặn budget** thì do gateway lo (per-key `max_budget`).

## Cách chạy

```powershell
# 1. Cấu hình key
copy infra\litellm\.env.example infra\litellm\.env   # điền OPENAI/GROQ key + master key
# đặt LITELLM_MASTER_KEY trong chatbot-service\.env khớp master key của gateway

# 2. Chạy stack (run-dev tự khởi động LiteLLM nếu có infra\litellm\.env)
.\run-dev.ps1
```

## Kiểm thử

- Unit: `python -m unittest discover tests` (gồm `tests/test_gateway_config.py`).
- Health: `GET http://localhost:4000/health/liveliness`.
- Smoke trực tiếp:
  ```
  curl http://localhost:4000/v1/chat/completions \
    -H "Authorization: Bearer sk-local-dev" \
    -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"ping"}]}'
  ```
- End-to-end: `POST http://localhost:8081/api/chat` → `answer_source=llm`, `usage` có token, `usage_logs` ghi cost.
- Provisioning: chat lần đầu → có row `llm_virtual_keys`; `GET /key/info` trả `max_budget` đúng theo quota policy; spend log ở `LiteLLM_SpendLogs` gắn đúng `user_id`.
- Budget: hạ `max_budget` key của user (qua `/key/update`) → chat vài lần → gateway chặn, Spring trả **429** (không 500).
- Quota status: `GET /api/quota/status` → `used_cost_usd` khớp spend trên Admin UI (trễ tối đa ~5s cache).
- Degradation: `docker stop medical-litellm` → chat vẫn trả lời bằng rule-based/template, không 500.
- Fallback model: cố tình để key sai cho `gpt-4.1-mini` → câu phức tạp vẫn trả lời nhờ fallback `gpt-4o-mini`.

## Ghi chú / mở rộng

- Proxy thêm 1 hop mạng → đo overhead latency khi smoke.
- Spend log ghi **bất đồng bộ** (flush theo lô sau vài giây) → `LiteLLMSpendService` cache ngắn + eventual consistency; `/key/info` có thể trễ vài giây so với call vừa xong.
- **Cửa sổ budget lệch với quota ngày lịch**: `budget_duration: "1d"` của LiteLLM là cửa sổ **rolling 24h** (tính từ lúc tạo/reset key), còn token/request trong `QuotaService` reset theo **ngày lịch** (`quotaZone`). Cost hiển thị/chặn có thể lệch vài giờ so với token/request — chấp nhận cho phạm vi hiện tại; nếu cần đồng bộ tuyệt đối, chuyển việc chặn cost về theo ngày lịch từ `usage_logs` hoặc reset key theo mốc ngày.
- `LITELLM_SALT_KEY` **không được đổi** sau khi đã sinh virtual key (sẽ không giải mã được key cũ).
- `DATABASE_URL` dùng `host.docker.internal:5433` (Docker Desktop/Windows). Linux thuần cần chung network hoặc `--add-host`.
- Team keys / RBAC nâng cao và Admin UI quản model (`store_model_in_db`) đã sẵn ở gateway, có thể mở rộng sau.
- Cost-aware multi-provider routing (chọn `groq-llama-8b` cho câu đơn giản) là milestone riêng, build trên gateway này.
