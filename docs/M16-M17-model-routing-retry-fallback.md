# M16 / M17 — Model Routing & Retry/Fallback

- **M16 — Model Routing:** chọn model AI phù hợp theo độ phức tạp câu hỏi (tiết kiệm chi phí).
- **M17 — Retry & Fallback:** xử lý lỗi tạm thời và chuyển phương án dự phòng khi model/service chính lỗi.

Cả hai bổ trợ cho [M6 (AI Integration)](M6-ai-integration.md).

---

# M16. Model Routing

Ba kỹ thuật routing được áp dụng chồng lên nhau:

| Kỹ thuật | Cơ chế | Trạng thái |
|---|---|---|
| Classifier Router | Keyword tiếng Việt (không dấu) → SIMPLE/COMPLEX, zero cost | Luôn bật |
| LLM Router | Model rẻ (`MODEL_ROUTER`) phân loại qua tool call, hybrid với keyword | Bật qua `ENABLE_LLM_ROUTER=true` |
| Cost-aware Routing | Quota gần cạn → hạ cấp COMPLEX xuống `model_simple` | Luôn bật, áp sau khi phân loại |

## M16.1 - Phân loại request

**Mục tiêu:** Biết request đơn giản hay phức tạp.

**Hành vi (Classifier Router — keyword):**
- `ModelRouter._classify()` trả `QueryComplexity.SIMPLE | COMPLEX`.
- Coi là **COMPLEX** khi câu hỏi chứa từ khóa phân tích (`phân tích`, `giải thích`, `so sánh`, `xu hướng`, `nguy hiểm`, `interpret`, `analyze`...).
- Ngược lại là **SIMPLE** (vd patient lookup).

**Tiêu chí hoàn thành:** Mỗi request có category rõ ràng (trả về kèm `query_complexity` trong metadata routing).

## M16.1b - LLM Router (hybrid)

**Mục tiêu:** Bắt được câu hỏi phức tạp mà keyword bỏ sót (recall thấp), vẫn giữ chi phí thấp.

**Hành vi (`LLMModelRouter.route()`):**
1. **Fast-path keyword:** keyword classifier nói COMPLEX → tin luôn (precision cao), **không** gọi LLM → `routing_source = "keyword"`.
2. Keyword nói SIMPLE → gọi model rẻ (`MODEL_ROUTER`, mặc định `gpt-4o-mini`) qua **LiteLLM gateway** với tool `classify_complexity` (`tool_choice` ép buộc, `temperature=0`) để xác nhận lại → `routing_source = "llm_router"`.
3. **Fallback:** LLM lỗi/timeout/kết quả không hợp lệ → dùng kết quả keyword (`routing_source = "keyword"`), không chặn request.
4. **Cache phân loại:** kết quả LLM được cache theo `normalize_text(message)` (LRU in-memory, tối đa 512 entry) — cùng câu hỏi (kể cả khác dấu tiếng Việt) không gọi LLM lần hai.

**Latency:** router chạy **song song với intent extraction** (`asyncio.gather` trong `/chat`) nên LLM Router gần như không cộng thêm độ trễ (call intent extraction thường chậm hơn).

**Usage:** token của router call trả về trong `RoutingDecision.usage` và được cộng vào `usage` tổng của response (`combine_usage(plan.usage, answer_usage, router_usage)`) để Spring ghi usage log đúng ([M7](M7-usage-tracking.md)).

**Cấu hình (`chatbot-service/.env`):**

```env
ENABLE_LLM_ROUTER=false   # bật LLM Router (cần LITELLM_MASTER_KEY)
MODEL_ROUTER=gpt-4o-mini  # model rẻ chuyên phân loại
```

- Không có `LITELLM_MASTER_KEY` hoặc `ENABLE_LLM_ROUTER=false` → factory `build_model_router()` trả keyword router như cũ.

**Trường response liên quan:** `query_complexity` (`simple|complex`), `routing_source` (`keyword|llm_router`).

**Tiêu chí hoàn thành:** Câu hỏi suy luận không chứa keyword vẫn được route sang `model_complex`; LLM router lỗi không làm hỏng request.

## M16.2 - Routing rule

**Mục tiêu:** Chọn model phù hợp.

**Hành vi:**
- `route(message, quota_used_ratio)` (async, trả `RoutingDecision`): COMPLEX → `model_complex`, SIMPLE → `model_simple`.
- **Hạ cấp theo quota (cost-aware):** khi COMPLEX nhưng `quota_used_ratio >= 0.8` (`_QUOTA_DOWNGRADE_RATIO`) thì vẫn dùng `model_simple` để tiết kiệm. `quota_used_ratio` do caller (`/chat`) truyền vào, mặc định `0.0`. Áp dụng cho cả keyword router lẫn LLM router.
- Model lấy từ settings: `model_simple` (mặc định `gpt-4o-mini`), `model_complex` (mặc định `gpt-4.1-mini`).
- Model được chọn truyền xuống `AnswerGenerator.generate(model=...)` và phản ánh trong `usage_logs.llm_model` ([M7.3](M7-usage-tracking.md)).

**Tiêu chí hoàn thành:** Usage logs phản ánh model được chọn.

## M16.3 - Admin model config

**Mục tiêu:** Đổi routing mà không sửa code.

**Hành vi hiện tại:**
- Cấu hình qua **env** (`MODEL_SIMPLE`, `MODEL_COMPLEX`, `LLM_PROVIDER`, `LITELLM_BASE_URL` — `app/config.py`). Đa provider (vd **Groq**) khai ở LiteLLM gateway (`infra/litellm/config.yaml`), không trỏ trực tiếp từ chatbot-service.
- Bảng `model_pricing` có API đọc `GET /api/model-pricing` và CRUD dành cho admin
  tại `/api/admin/model-pricing` (`ModelPricingAdminController`) — baseline V1 đã
  seed giá cho Groq `llama-3.1-8b-instant` ([M8](M8-cost-management.md)).
  (V2 của tính năng hướng tới full admin routing UI, không phải Flyway V2.)

**Tiêu chí hoàn thành:** Admin đổi model routing được an toàn (qua env/config).

---

# M17. Retry & Fallback

## M17.1 - Retry policy

**Mục tiêu:** Tự retry lỗi tạm thời.

**Hành vi:**
- Nguyên tắc: retry timeout/5xx, **không** retry lỗi validation/permission (4xx), có max attempts.
- FHIR client phân biệt rõ loại lỗi (`FhirNotFoundError` vs `FhirClientError`) để tầng trên quyết định ([M5.2](M5-fhir-integration.md)).
- Phía Spring, lỗi 5xx từ chatbot-service được phân loại riêng trong `ApiExceptionHandler` (không gộp với 4xx).

## M17.2 - Fallback model/service

**Mục tiêu:** Chuyển phương án dự phòng khi model chính lỗi.

**Hành vi:**
- **Template fallback** khi LLM fail: `LLMAnswerGenerator` bắt exception → `TemplateAnswerGenerator` sinh câu trả lời từ evidence (`answer_source = "template_fallback"`) — xem [M6.5](M6-ai-integration.md). Ngoại lệ: lỗi **vượt budget** từ gateway được `raise_if_budget_exceeded` chuyển thành `GatewayBudgetExceededError` → route trả HTTP 429 (không fallback template) — xem [M-litellm-gateway](M-litellm-gateway.md).
- Khi không có evidence → `template_no_evidence`.
- Khi không cấu hình API key → toàn bộ dùng `TemplateAnswerGenerator` (`answer_source = "template"`).
- `answer_source` đóng vai trò metadata cho biết câu trả lời đến từ LLM hay fallback.

**Tiêu chí hoàn thành:** User vẫn nhận câu trả lời an toàn khi model chính lỗi.

## M17.3 - User-facing failure response

**Mục tiêu:** Trả thông báo phù hợp khi không xử lý được.

**Hành vi:**
- Không hallucinate: fallback chỉ dùng dữ liệu evidence có sẵn.
- Lỗi FHIR → `502` với message tiếng Việt; lỗi chatbot-service down → `502 CHATBOT_UNAVAILABLE` (xem [M11](M11-logging-error-handling.md)).
- Frontend render lỗi tại chỗ (message role `error`) không phá layout ([ChatPage.tsx](../frontend/src/pages/ChatPage.tsx)).

**Tiêu chí hoàn thành:** Lỗi service ngoài không làm app crash.

## Luồng chương trình

```
M16 routing (mỗi request, chạy song song với intent extraction qua asyncio.gather):
   model_router.route(message, quota_used_ratio) → RoutingDecision

   Keyword router (mặc định):
      _classify → COMPLEX nếu keyword phân tích, ngược lại SIMPLE

   LLM Router (ENABLE_LLM_ROUTER=true, hybrid):
      keyword nói COMPLEX → dùng luôn (source="keyword", 0 LLM call)
      keyword nói SIMPLE  → cache hit? → dùng cache (source="llm_router")
                          → gọi MODEL_ROUTER tool classify_complexity
                               ├─ OK        → simple|complex (source="llm_router", có usage)
                               └─ lỗi/lạ    → kết quả keyword (source="keyword")

   Chọn model (chung cả hai router):
      COMPLEX + quota_used_ratio ≥ 0.8 → model_simple (hạ cấp cost-aware)
      COMPLEX → model_complex   |   SIMPLE → model_simple
   → truyền model xuống AnswerGenerator → ghi vào usage_logs.llm_model
   → router_usage cộng vào usage tổng; routing_source/query_complexity trong response

M17 fallback (khi sinh câu trả lời):
   LLMAnswerGenerator.generate(model)
      gọi model qua LiteLLM gateway
        ├─ OK            → answer_source="llm", usage=token
        ├─ lỗi provider  → answer_source="template_fallback" (fallback_answer)
        ├─ budget 429    → chuyển tiếp 429, không fallback che mất trạng thái quota
        └─ answer rỗng   → answer_source="template_fallback"
   (không gateway key → TemplateAnswerGenerator: answer_source="template")

   Lỗi tầng service (Spring ApiExceptionHandler):
      FHIR/chatbot 5xx → 502 + alert CRITICAL (M18/M19)
      4xx              → propagate, không retry
```

## Luồng trong code

- **Routing (keyword):** `ModelRouter.route()` / `_classify()` / `_pick_model()` ([model_router.py](../chatbot-service/agents/model_router.py)); model từ settings ([config.py](../chatbot-service/app/config.py)).
- **LLM Router (hybrid):** `LLMModelRouter.route()` / `_llm_classify()` + cache ([model_router.py](../chatbot-service/agents/model_router.py)); factory `build_model_router()` chọn router theo `enable_llm_router` ([model_router.py](../chatbot-service/agents/model_router.py)).
- **Áp routing vào pipeline:** `asyncio.gather(intent, route)` + `routing_kwargs` ([chat_routes.py](../chatbot-service/api/chat_routes.py)); gộp `router_usage` và gắn `routing_source` ([response_builder.py](../chatbot-service/chat/response_builder.py)).
- **Template fallback:** [answer_generator.py](../chatbot-service/agents/answer_generator.py).
- **Phân loại lỗi service (Spring):** [ApiExceptionHandler.java](../backend/src/main/java/com/medicalchatbot/backend/exception/ApiExceptionHandler.java).

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| Model router | `chatbot-service/agents/model_router.py` |
| Answer fallback | `chatbot-service/agents/answer_generator.py` |
| Cấu hình model | `chatbot-service/app/config.py` |
| Xử lý lỗi service (Spring) | `backend/.../exception/ApiExceptionHandler.java` |
| Bảng giá/model admin | `backend/.../service/ModelPricingAdminService.java` |
