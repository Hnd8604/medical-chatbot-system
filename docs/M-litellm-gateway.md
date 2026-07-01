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
- **Stateless config-only**: không gắn DB riêng của LiteLLM. Source of truth cho cost/quota vẫn là Spring (`usage_logs`, `model_pricing`, `QuotaService`) — tránh trùng lặp.
- Tên model trong code (`model_simple`, `model_complex`) được khai làm **alias** trong LiteLLM nên không phải đổi code router.

## Thành phần

| File | Vai trò |
|---|---|
| `infra/litellm/config.yaml` | `model_list` (alias→provider), `num_retries`, `request_timeout`, `fallbacks`, `master_key` |
| `infra/litellm/docker-compose.yml` | Service `litellm` port `4000`, đọc key từ `.env`, healthcheck `/health/liveliness` |
| `infra/litellm/.env.example` | Mẫu `OPENAI_API_KEY`, `GROQ_API_KEY`, `LITELLM_MASTER_KEY` (tạo `.env` thật, **không commit**) |
| `chatbot-service/app/config.py` | `litellm_base_url`/`litellm_master_key`; property `llm_base_url`/`llm_api_key`/`use_llm` (gateway là đường LLM duy nhất) |
| `agents/intent/factory.py`, `agents/answer_generator.py` | 2 factory dùng `llm_api_key`/`llm_base_url` |
| `agents/answer_generator.py`, `chat/response_builder.py` | `AnswerResult.model` đọc `response.model`; map alias→(provider, pricing-model) để cost đúng |
| `run-dev.ps1` | Khởi động/stop LiteLLM (có điều kiện: chỉ khi `infra/litellm/.env` tồn tại) |

## Biến môi trường

**`infra/litellm/.env`** (không commit):
```
OPENAI_API_KEY=...
GROQ_API_KEY=...
LITELLM_MASTER_KEY=sk-local-dev
```

**`chatbot-service/.env`** (KHÔNG chứa key provider — chỉ master key của gateway):
```
LITELLM_BASE_URL=http://localhost:4000
LITELLM_MASTER_KEY=sk-local-dev   # phải khớp master_key của gateway
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

`OpenAIAnswerGenerator` đọc `response.model` (phản ánh cả khi gateway **fallback** sang model khác) → set `llm_model`/`llm_provider` thực tế → `CostEstimationService` tính cost chính xác.

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
- Fallback: cố tình để key sai cho `gpt-4.1-mini` → câu phức tạp vẫn trả lời nhờ fallback `gpt-4o-mini`.

## Ghi chú / mở rộng

- Proxy thêm 1 hop mạng → đo overhead latency khi smoke.
- Extension: LiteLLM **DB-backed** (virtual keys, per-key budget, spend dashboard) — cân nhắc khi muốn budget ở tầng gateway, nhưng sẽ trùng một phần với `QuotaService`.
- Cost-aware multi-provider routing (chọn `groq-llama-8b` cho câu đơn giản) là milestone riêng, build trên gateway này.
