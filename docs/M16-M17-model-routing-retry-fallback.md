# M16 / M17 — Model Routing & Retry/Fallback

- **M16 — Model Routing:** chọn model AI phù hợp theo độ phức tạp câu hỏi (tiết kiệm chi phí).
- **M17 — Retry & Fallback:** xử lý lỗi tạm thời và chuyển phương án dự phòng khi model/service chính lỗi.

Cả hai bổ trợ cho [M6 (AI Integration)](M6-ai-integration.md).

---

# M16. Model Routing

## M16.1 - Phân loại request

**Mục tiêu:** Biết request đơn giản hay phức tạp.

**Hành vi:**
- `ModelRouter._classify()` trả `QueryComplexity.SIMPLE | COMPLEX`.
- Coi là **COMPLEX** khi câu hỏi chứa từ khóa phân tích (`phân tích`, `giải thích`, `so sánh`, `xu hướng`, `nguy hiểm`, `interpret`, `analyze`...).
- Ngược lại là **SIMPLE** (vd patient lookup).

**Tiêu chí hoàn thành:** Mỗi request có category rõ ràng (trả về kèm `query_complexity` trong metadata routing).

## M16.2 - Routing rule

**Mục tiêu:** Chọn model phù hợp.

**Hành vi:**
- `ModelRouter.route(message, quota_used_ratio)`: COMPLEX → `model_complex`, SIMPLE → `model_simple`.
- **Hạ cấp theo quota:** khi COMPLEX nhưng `quota_used_ratio >= 0.8` (`_QUOTA_DOWNGRADE_RATIO`) thì vẫn dùng `model_simple` để tiết kiệm. `quota_used_ratio` do caller (`/chat`) truyền vào, mặc định `0.0`.
- Model lấy từ settings: `model_simple` (mặc định `gpt-4o-mini`), `model_complex` (mặc định `gpt-4.1-mini`).
- Model được chọn truyền xuống `AnswerGenerator.generate(model=...)` và phản ánh trong `usage_logs.llm_model` ([M7.3](M7-usage-tracking.md)).

**Tiêu chí hoàn thành:** Usage logs phản ánh model được chọn.

## M16.3 - Admin model config

**Mục tiêu:** Đổi routing mà không sửa code.

**Hành vi hiện tại:**
- Cấu hình qua **env** (`MODEL_SIMPLE`, `MODEL_COMPLEX`, `LLM_PROVIDER`, `OPENAI_BASE_URL` — `app/config.py`). `OPENAI_BASE_URL` cho phép trỏ sang provider OpenAI-compatible khác (vd **Groq**).
- Bảng giá theo model + admin API (`model_pricing`, `/api/model-pricing`, `ModelPricingAdminController`) cho phép quản trị giá/model — đã seed giá cho Groq `llama-3.1-8b-instant` (`V13`, [M8](M8-cost-management.md)). (V2 hướng tới full admin routing UI.)

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
- **Template fallback** khi LLM fail: `OpenAIAnswerGenerator` bắt exception → `TemplateAnswerGenerator` sinh câu trả lời từ evidence (`answer_source = "template_fallback"`) — xem [M6.5](M6-ai-integration.md).
- Khi không có evidence → `template_no_evidence`.
- Khi không cấu hình API key → toàn bộ dùng `TemplateAnswerGenerator` (`answer_source = "template"`).
- `answer_source` đóng vai trò metadata cho biết câu trả lời đến từ LLM hay fallback.

**Tiêu chí hoàn thành:** User vẫn nhận câu trả lời an toàn khi model chính lỗi.

## M17.3 - User-facing failure response

**Mục tiêu:** Trả thông báo phù hợp khi không xử lý được.

**Hành vi:**
- Không hallucinate: fallback chỉ dùng dữ liệu evidence có sẵn.
- Lỗi FHIR → `502` với message tiếng Việt; lỗi chatbot-service down → `502 CHATBOT_UNAVAILABLE` (xem [M11](M11-logging-error-handling.md)).
- Frontend render lỗi tại chỗ (message role `error`) không phá layout ([ChatPage.tsx](frontend-react/src/routes/ChatPage.tsx#L302-L316)).

**Tiêu chí hoàn thành:** Lỗi service ngoài không làm app crash.

## Luồng chương trình

```
M16 routing (mỗi request):
   model_router.route(message, quota_used_ratio)
      _classify → COMPLEX nếu keyword phân tích, ngược lại SIMPLE
      COMPLEX + quota_used_ratio ≥ 0.8 → model_simple (hạ cấp)
      COMPLEX → model_complex   |   SIMPLE → model_simple
   → truyền model xuống AnswerGenerator → ghi vào usage_logs.llm_model

M17 fallback (khi sinh câu trả lời):
   OpenAIAnswerGenerator.generate(model)
      try OpenAI
        ├─ OK            → answer_source="llm", usage=token
        ├─ Exception     → answer_source="template_fallback" (fallback_answer)
        └─ answer rỗng   → answer_source="template_fallback"
   (không API key → TemplateAnswerGenerator: answer_source="template")

   Lỗi tầng service (Spring ApiExceptionHandler):
      FHIR/chatbot 5xx → 502 + alert CRITICAL (M18/M19)
      4xx              → propagate, không retry
```

## Luồng trong code

- **Routing:** `ModelRouter.route()` / `_classify()` ([model_router.py:29-41](chatbot-service/agents/model_router.py#L29-L41)); model từ settings ([config.py:12-13](chatbot-service/app/config.py#L12-L13)).
- **Áp routing vào pipeline:** [chat_routes.py:192-196](chatbot-service/api/chat_routes.py#L192-L196).
- **Template fallback:** [answer_generator.py:104-145](chatbot-service/agents/answer_generator.py#L104-L145).
- **Phân loại lỗi service (Spring):** [ApiExceptionHandler.java:82-131](spring-backend/src/main/java/com/medicalchatbot/backend/exception/ApiExceptionHandler.java#L82-L131).

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| Model router | `chatbot-service/agents/model_router.py` |
| Answer fallback | `chatbot-service/agents/answer_generator.py` |
| Cấu hình model | `chatbot-service/app/config.py` |
| Xử lý lỗi service (Spring) | `spring-backend/.../exception/ApiExceptionHandler.java` |
| Bảng giá/model admin | `spring-backend/.../service/ModelPricingAdminService.java` |
