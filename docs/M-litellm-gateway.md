# M — AI Gateway bằng LiteLLM

**Mục tiêu:** Đặt một lớp trung gian (AI Gateway) giữa `chatbot-service` và các LLM provider (OpenAI, Groq) để:
- ✓ Tập trung **API key quản lý** (provider key chỉ ở gateway)
- ✓ **Logging 2 lớp**: gateway-native per-call + app-side per-turn
- ✓ **Cost tracking chính xác**: tính từ actual tokens × custom pricing (config.yaml)
- ✓ **Per-user budget enforcement**: virtual key per user, rolling 24h budget
- ✓ **Multi-provider support**: OpenAI + Groq (dễ thêm provider mới)

**Không làm ở gateway:** retry/fallback ← tách ra tầng app (model_router.py).

Bổ trợ cho [M16/M17 (Model Routing)](M16-M17-model-routing-retry-fallback.md) và [M8 (Cost Management)](M8-cost-management.md).

---

## Kiến trúc

```text
Spring Backend (8081)
  ├─ LiteLLMAdminClient: cấp key, sửa budget, đọc spend
  │
  └─ chatbot-service (8000)
     ├─ App-tier routing: keyword classifier + cost-aware downgrade
     └─ (AsyncOpenAI client)
        └─ LiteLLM Gateway Proxy (4000, OpenAI-compatible)
           ├─ Auth: virtual key per-user
           ├─ Model alias: gpt-4o-mini → openai/gpt-4o-mini
           ├─ Pricing: custom từ config.yaml
           ├─ Cost tracking: actual tokens × pricing → LiteLLM_SpendLogs
           ├─ Budget enforcement: per-key rolling 24h
           └─ Forward to providers
              ├─ OpenAI (gpt-4o-mini, gpt-4.1-mini)
              └─ Groq (llama-3.1-8b-instant, llama-3.3-70b-versatile)
```

**Tính năng:**

- **OpenAI-compatible API**: Client `AsyncOpenAI` chỉ cần `base_url` + `api_key`. Call site `client.chat.completions.create(...)` không đổi.
- **DB-backed**: Postgres (`database litellm`) bật virtual key per-user, per-key budget, spend logs, Admin UI `/ui`. LiteLLM tự migrate bảng `LiteLLM_*`.
- **Cost tính chính xác**: Gateway tính từ actual tokens × custom pricing (config.yaml). Virtual key có `max_budget = daily_cost_limit_usd`, rolling 24h. `QuotaService` đọc spend từ `/key/info` làm **source of truth**. Token/request từ `usage_logs`.
- **Model alias**: Tên code (`gpt-4o-mini`) tách khỏi provider real (`openai/gpt-4o-mini`) → không cần đổi code router.
- **Pure proxy**: Gateway **không** retry/fallback (loại bỏ để giữ đơn giản). Fallback ở tầng app (fallback_answer, template response).

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

## 3 trụ cột của AI Gateway

Gateway hiện thực 3 trụ cột: **API Key Management, Logging 2-lớp, Cost Tracking**. 
(Routing ← tách ra tầng app, không ở gateway.)

```text
Spring (8081)                      chatbot-service (8000)          LiteLLM Gateway (4000)
─────────────                      ──────────────────────          ──────────────────────
cấp virtual key/user
  ──llm_key──────────────────────────► contextvar per-request ──► auth theo virtual key
                                                                     │
                                        App-tier routing:           │
                                        keyword → COMPLEX/SIMPLE    │
                                        cost-aware downgrade        │
                                        chọn model                  │
                                                                     │
                                        ──model_name───────────────► alias map
                                                                     │ forward to provider
đọc spend về quota                                                   │ tính cost
  ◄───────────────────/key/info◄─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┤ ghi spend log
                                                                     │ enforce budget
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

Lý do chọn **C**: bài toán **đa người dùng có quota/cost per-user**
([product-spec §7](product-spec.md#7-quản-lý-usage-chi-phí-và-quota)) cần
credential per-user để chặn tiền đúng người; phần lớn là cấu hình LiteLLM có sẵn
chứ không tự code key lifecycle. Đây là lựa chọn có chủ đích vượt phạm vi demo
tối thiểu ([product-spec §17](product-spec.md#17-phạm-vi-demo-tối-thiểu)) để có
least privilege và giới hạn blast radius: lộ key một user chỉ ảnh hưởng budget
của người đó và có thể thu hồi, thay vì lộ key chung của toàn hệ thống.

### 2. Logging — 2 lớp

- **Gateway-native**: mỗi request LLM ghi `LiteLLM_SpendLogs` (DB `litellm`): model, token, cost, latency, **gắn đúng end-user** nhờ tham số `user=user_id` ở mỗi completion call. Xem trực quan tại Admin UI `/ui` (tab Logs/Usage).
- **App-side** (audit/analytics): Spring ghi `usage_logs` (token, cost ước tính,
  latency, `answer_source`) + `audit_logs` mỗi lượt chat qua
  `ChatInteractionRecorder`.

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

### 3. Cost Tracking — gateway tính chính xác, enforce 2 tầng

#### 3.1 Gateway tính cost (chính xác, per-call)

**LiteLLM** là **single source of truth** cho cost thực tế:

```text
User call LLM
  ↓
LiteLLM nhận response từ provider (OpenAI/Groq)
  ├─ Extract: input_tokens, output_tokens
  ├─ Tính cost = (input_tokens × input_price_per_1m) / 1M
  │            + (output_tokens × output_price_per_1m) / 1M
  │
  │  Giá lấy từ config.yaml (custom pricing, không phải built-in):
  │  ├─ gpt-4o-mini:     In: $0.15/1M, Out: $0.60/1M
  │  ├─ gpt-4.1-mini:    In: $0.40/1M, Out: $1.60/1M
  │  ├─ groq-llama-8b:   In: $0.05/1M, Out: $0.08/1M
  │  └─ groq-llama-70b:  In: $0.59/1M, Out: $0.79/1M
  │
  └─ Ghi vào LiteLLM_SpendLogs: cost, tokens, model, virtual_key_id, timestamp, user_id
     Accumulate spend trên virtual key (rolling 24h)
```

**Ví dụ:**
```
Input: 500 tokens, Output: 100 tokens, Model: gpt-4o-mini
Cost = (500 × $0.15/1M) + (100 × $0.60/1M)
     = $0.000075 + $0.00006
     = $0.000135 per call
     ≈ $0.14 per 1000 calls
```

#### 3.2 Virtual key budget enforcement (per-user, realtime)

Mỗi user Spring có **1 virtual key** với:
- `max_budget` = `daily_cost_limit_usd` của quota policy
- `budget_duration: "1d"` (rolling 24h từ lúc tạo/reset key)
- `models`: danh sách model được phép gọi

**Enforcement flow:**

```text
Chatbot-service gọi LLM
  ├─ Header: Authorization: Bearer <virtual_key>, user: <user_id>
  ↓
LiteLLM:
  ├─ Verify key tồn tại + không bị block
  ├─ Check: current_spend + call_cost ≤ max_budget ?
  │   ├─ YES → forward call tới provider
  │   │         ghi spend log, trả response ✓
  │   └─ NO  → HTTP 429 "budget_exceeded"
  ↓
Chatbot-service:
  ├─ Catch 429 → is_budget_error()
  └─ Raise GatewayBudgetExceededError
     → chat_routes passthrough HTTP 429
     → Spring API returns 429 to frontend
```

**Budget không reset** ngay khi thay đổi policy: admin sửa `daily_cost_limit_usd` → Spring trigger `LlmGatewayKeyService.syncBudgetForPolicy` → POST `/key/update` xuống gateway → LiteLLM cập nhật `max_budget` của từng key thuộc policy đó.

#### 3.3 Cost fallback ở Spring (2 tầng)

Khi gateway sập hoặc không trả được spend, Spring fallback tính cost từ `usage_logs`:

```
QuotaService.effectiveUsedCost(userId):
  ├─ Try: LiteLLMSpendService.getSpendForUser(userId)
  │       → Query gateway /key/info
  │       → Lấy spend (chính xác từ LiteLLM_SpendLogs)
  │       → Cache 5s (tránh quá tải gateway)
  │       ✓ Source of truth
  │
  └─ Fallback: usage_logs.summarizeSuccessfulUsage(userId)
              → CostEstimationService.estimateUsd(provider, model, inputTokens, outputTokens, fallbackEstimate)
                ├─ Try: model_pricing table (nếu có record)
                │       Cost = (inputTokens × inputPrice/1M) + (outputTokens × outputPrice/1M)
                │       ✓ Chính xác (nếu pricing config đủ)
                │
                └─ Fallback: chatbot-service ước tính (response.usage.estimated_cost_usd)
                            ✓ Ước tính (~90%)
```

**Lý do có 3 tầng fallback:**

| Tầng | Khi nào | Chi phí | Độ chính xác |
|---|---|---|---|
| **Gateway** | OK | LiteLLM_SpendLogs | 100% (actual tokens × pricing) |
| **model_pricing table** | Gateway sập | Spring DB | ~99% (nếu config đầy đủ) |
| **Chatbot-service ước tính** | Không có pricing | response.usage | ~90% (ước tính, không từ provider) |

**Không double-count:** cost chỉ được tính **một lần** cho enforcement. QuotaService ưu tiên gateway (realtime) và khi enforce quota status. `usage_logs.estimated_cost` chỉ dùng làm:
1. **Analytics breakdown** — dashboard admin (gateway `/key/info` chỉ trả tổng/key, không breakdown theo model/ngày)
2. **Fallback enforcement** — khi gateway down

#### 3.4 Phân vai cost vs token

| Chỉ số | Gateway tính | Spring fallback | Reset | Enforcement |
|---|---|---|---|---|
| **Cost** | ✓ chính xác (LiteLLM_SpendLogs) | Usage_logs (model_pricing → ước tính) | Rolling 24h | Per-key budget + daily policy limit |
| **Token** | ✗ không | ✓ usage_logs (từ chatbot-service) | Calendar day | Daily token limit |
| **Request** | ✗ không | ✓ usage_logs (request_count) | Calendar day | Daily request limit |

**Tại sao khác nhau?** Gateway chỉ biết cost → enforce budget. Spring biết token/request → enforce khác — tránh single-point-of-failure cho cả 3 chỉ số.

### 4. Routing — App-tier chọn model, gateway chỉ proxy

**Refactoring:** Gateway loại bỏ retry/fallback để giữ vai trò đơn giản: **pure proxy** + key auth. Model routing logic hoàn toàn ở tầng app.

#### 4.1 App-tier routing (model selection)

`agents/model_router.py` quyết định **trước** khi gọi LLM:

```text
Input: question, quota_used_ratio
  ↓
Classifier: keyword ("phân tích"+"xu hướng"?) → COMPLEX | SIMPLE
  ↓
Cost-aware downgrade:
  ├─ quota_used_ratio ≥ 0.8 → chọn model_simple (rẻ, nhanh)
  └─ else → chọn model theo độ khó
  ↓
Router output: "gpt-4.1-mini" hoặc "gpt-4o-mini"
```

Kỹ thuật:
- **Keyword classifier**: nhanh, không gọi LLM
- **LLM router (optional)**: gọi model rẻ để confirm câu SIMPLE (tăng độ chính xác)
- **Cost-aware downgrade**: khi quota gần hết → chọn model rẻ tự động

Chi tiết: [M16-M17](M16-M17-model-routing-retry-fallback.md).

#### 4.2 Gateway-tier (pure proxy, không routing)

LiteLLM config:
```yaml
model_list:
  - model_name: gpt-4o-mini
    litellm_params:
      model: openai/gpt-4o-mini
      api_key: $OPENAI_API_KEY
  # ... còn lại
```

**Gateway chỉ:**
- ✓ Alias map: `gpt-4o-mini` (tên code) → `openai/gpt-4o-mini` (tên provider)
- ✓ Forward call → provider
- ✓ Ghi spend log
- ✗ **Không retry** (loại bỏ `num_retries`)
- ✗ **Không fallback** (loại bỏ `fallbacks`)

**Lý do loại bỏ gateway retry/fallback:**
- App-tier quyết định model rồi → gateway chỉ execute
- Retry phức tạp hóa gateway, tách coupling với app logic
- Nếu provider lỗi → app layer (fallback_answer, template response) xử lý

#### 4.3 Phân vai App vs Gateway

| Trách nhiệm | App-tier | Gateway |
|---|---|---|
| Chọn model (simple/complex) | ✅ | ❌ |
| Biết quota user | ✅ | ❌ |
| Downgrade khi quota gần hết | ✅ | ❌ |
| Proxy LLM call | ❌ | ✅ |
| Giữ provider key | ❌ | ✅ |
| Ghi spend log | ❌ | ✅ |
| Enforce per-key budget | ❌ | ✅ |
| Retry khi lỗi | ❌ (để cho app/fallback xử lý) | ❌ (giữ đơn giản) |

**Trace flow:**

```text
Chatbot-service:
  ├─ Tính độ khó câu hỏi → COMPLEX
  ├─ Check quota: 75% dùng → downgrade → "gpt-4o-mini"
  └─ Call LLM: request_body.model = "gpt-4o-mini"
       ↓
Gateway (pure proxy):
  ├─ Map alias: "gpt-4o-mini" → openai/gpt-4o-mini
  ├─ Check budget: spend + cost ≤ max_budget? ✓
  ├─ Forward to OpenAI
  ├─ Receive response + tokens
  ├─ Tính cost, ghi spend log
  └─ Return response.model = "gpt-4o-mini"
       ↓
Chatbot-service:
  ├─ Nhận response
  ├─ Answer generator dùng response.model để log cost
  └─ Return answer
```

**Nếu provider lỗi:**
- Gateway không retry → trả error
- Chatbot-service nhận lỗi
- Chat route sử dụng `fallback_answer` (template, rule-based)
- Trả user response bình thường (không 500)

### 3.5 Virtual key lifecycle

```
Lần đầu user chat:
  Spring POST /api/chat
    ├─ Check virtual key trong DB
    ├─ Không có → LlmGatewayKeyService.provisionKeyForUser()
    │            → LiteLLMAdminClient.generateKey()
    │            → POST /key/generate {user_id, max_budget, models}
    │            → Nhận: {key: "sk-...", hash: "..."}
    │            → Lưu vào llm_virtual_keys table
    └─ Có → dùng key cũ
  
  Chatbot-service chat:
    ├─ Set contextvar: gateway_context.set_gateway_context(key, user_id)
    ├─ Call LLM 3 lần: extra headers Authorization + user param
    │  ├─ Intent extraction
    │  ├─ Model routing
    │  └─ Answer generation
    └─ Gateway auth key → ghi spend log
  
Admin thay đổi policy:
  Spring admin API PUT /api/admin/quota-policies/{id}
    ├─ Update daily_cost_limit_usd
    └─ LlmGatewayKeyService.syncBudgetForPolicy()
       → Tìm mọi user thuộc policy
       → LiteLLMAdminClient.updateKeyBudget()
       → PUT /key/update {key_id, max_budget}
       → Ngay lập tức enforce ở gateway
  
User vượt budget:
  Gateway spend + call_cost ≥ max_budget
    ├─ HTTP 429 "budget_exceeded"
    └─ Spring 429 → frontend: "Hết hạn mức chi phí"

User bị khóa / Admin thu hồi key:
  LiteLLMAdminClient.blockKey() / deleteKey()
    ├─ DELETE /key/{key_id}
    ├─ Hoặc PUT /key/block
    └─ User sau đó không gọi được LLM (fallback template)
```

**Lưu ý:**
- Virtual key **mã hóa** trong LiteLLM DB bằng `LITELLM_SALT_KEY` → không thể đọc plaintext
- `llm_virtual_keys` table Spring chỉ lưu mapping `user_id → key_alias`, key thật không được lưu
- `LITELLM_SALT_KEY` **không được đổi** sau khi đã tạo key (sẽ không decrypt được)

## Thành phần

| File | Vai trò |
|---|---|
| `infra/litellm/config.yaml` | `model_list` (alias→provider), `request_timeout`, model pricing (`input_cost_per_token`, `output_cost_per_token`), `master_key`, `database_url`, `store_model_in_db` |
| `infra/litellm/docker-compose.yml` | Service `litellm` port `4000`, `DATABASE_URL`/`LITELLM_SALT_KEY`/`UI_*`, healthcheck `/health/liveliness` |
| `infra/litellm/.env.example` | `OPENAI_API_KEY`, `GROQ_API_KEY`, `LITELLM_MASTER_KEY`, `DATABASE_URL`, `LITELLM_SALT_KEY`, `LITELLM_UI_USERNAME/PASSWORD` (tạo `.env` thật, **không commit**) |
| `run-dev.ps1` | Tạo database `litellm` (idempotent) rồi khởi động/stop LiteLLM (khi `infra/litellm/.env` tồn tại) |
| `chatbot-service/app/config.py` | `litellm_base_url`/`litellm_master_key`; property `llm_base_url`/`llm_api_key`/`use_llm` (gateway là đường LLM duy nhất) |
| `chatbot-service/agents/gateway_context.py` | contextvar `current_llm_key`/`current_end_user`; `gateway_call_kwargs()` (extra_headers+user); `GatewayBudgetExceededError` |
| `agents/intent/llm_extractor.py`, `agents/answer_generator.py`, `agents/model_router.py` | 3 call site LLM: `**gateway_call_kwargs()` + `raise_if_budget_exceeded` |
| `chat/response_builder.py` | `AnswerResult.model` đọc `response.model`; map alias→(provider, pricing-model) cho analytics |
| Spring `integration/client/LiteLLMAdminClient.java` | Outbound adapter quản virtual key qua master key: `/key/generate`, `/key/update`, `/key/info`, `/key/block` |
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

**`backend/.env`** (Spring quản virtual key + đọc spend):
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
- Degradation: `docker stop medical-litellm` → chat vẫn trả lời bằng rule-based/template (fallback_answer), không 500.
- Cost calculation: query Admin UI `/ui` → tab "Logs" xem spend log chi tiết (model, tokens, cost); so sánh với Spring usage_logs (estimated_cost) để verify fallback logic.

## Ghi chú / mở rộng

### Hiệu năng & Consistency

- **Proxy latency**: Thêm 1 hop mạng → đo overhead khi smoke test. Typically <50ms overhead.
- **Spend log async**: LiteLLM flush spend log theo lô (vài giây) → `LiteLLMSpendService` cache 5s + eventual consistency. `/key/info` có thể trễ vài giây so với call vừa xong. Chấp nhận cho phạm vi hiện tại.
- **Cửa sổ budget lệch với quota ngày lịch**: Gateway `budget_duration: "1d"` là **rolling 24h** (tính từ lúc tạo key), Spring quota reset theo **ngày lịch** (`quotaZone`). Cost hiển thị/chặn có thể lệch vài giờ — chấp nhận hiện tại; nếu cần đồng bộ tuyệt đối, chuyển việc chặn cost về `usage_logs` (ngày lịch) hoặc reset key theo mốc ngày.

### Vận hành

- `LITELLM_SALT_KEY` **không được đổi** sau khi đã tạo virtual key (mã hóa không thể giải).
- `DATABASE_URL` dùng `host.docker.internal:5433` (Docker Desktop/Windows). Linux thuần cần chung network hoặc `--add-host`.
- **Model pricing config**: Chỉnh sửa `infra/litellm/config.yaml` → thay đổi `input_cost_per_token`, `output_cost_per_token` cho mỗi model → LiteLLM reload lúc boot (không runtime). Nếu cần update runtime → dùng Admin UI `/ui` hoặc `/config` endpoint.

### Mở rộng

- **Team keys / RBAC nâng cao**: Admin UI quản model (`store_model_in_db`) đã sẵn ở gateway, có thể mở rộng sau.
- **Semantic cache**: Xây dựng trên spend log này (vector cache theo spend, user, model).
- **Cost-aware multi-provider routing**: Chọn `groq-llama-8b` cho câu đơn giản (refactor phần này thành M18+).
- **Real-time cost dashboard**: Pipe `LiteLLM_SpendLogs` → frontend dashboard (chứ không chỉ Admin UI `/ui`).
