# M6. AI Integration

Kết nối LLM (OpenAI-compatible) cho hai nhiệm vụ: hiểu ý định câu hỏi (intent extraction) và sinh câu trả lời tiếng Việt từ evidence FHIR — kèm kiểm soát prompt/token và fallback khi LLM lỗi. Bổ trợ cho [M4](M4-medical-data-query.md), [M16/M17](M16-M17-model-routing-retry-fallback.md).

## M6.1 - Intent extraction

**Mục tiêu:** Hiểu câu hỏi user và chọn tool phù hợp.

**Hành vi:**
- Ưu tiên **structured tool call** (`openai_extractor`), có **rule fallback** (`rule_extractor`).
- Không tự trả lời dữ liệu y tế nếu chưa retrieve (intent chỉ chọn tool, không sinh nội dung y tế).
- Schema tool call định nghĩa trong `agents/intent/fhir_tools.py` + `models.py`.

**Tiêu chí hoàn thành:** Tool được chọn đúng cho câu hỏi demo.

## M6.2 - Tool execution

**Mục tiêu:** Thực thi tool đã chọn để lấy dữ liệu FHIR.

**Hành vi:**
- Tool chỉ gọi API FHIR đã định nghĩa qua `FhirClient` — **không sinh SQL**.
- Route tool trong `api/chat_routes.py`; resolve patient id trước khi gọi resource tool; trả candidate khi nhiều bệnh nhân.

**Tiêu chí hoàn thành:** Tool execution không hallucinate dữ liệu.

## M6.3 - Answer generation

**Mục tiêu:** Tạo câu trả lời tiếng Việt từ evidence đã retrieve.

**Hành vi:**
- `OpenAIAnswerGenerator.generate()` — system prompt quy định: chỉ trả lời dựa trên evidence FHIR, **không bịa** thông tin, không đưa chẩn đoán/lời khuyên vượt dữ liệu, nói rõ khi thiếu dữ liệu, trả lời tiếng Việt không Markdown đậm.
- `temperature=0.2`; làm sạch Markdown bằng `clean_llm_answer()`.
- Khi LLM thật được gọi: `answer_source = "llm"`, `usage` có token (`prompt_tokens`/`completion_tokens`).

**Tiêu chí hoàn thành:** `answer_source=llm` và `usage` có token khi gọi LLM thật.

## M6.4 - Prompt và context control

**Mục tiêu:** Kiểm soát dữ liệu gửi vào LLM (tránh vượt token & lộ dữ liệu thừa).

**Hành vi:**
- `compact_evidence_for_llm()`: chỉ gửi evidence đã normalize, giới hạn `MAX_EVIDENCE_ITEMS = 20`.
- `MAX_EVIDENCE_JSON_CHARS = 12000`: nếu JSON evidence vượt ngưỡng → **cắt bớt** item cuối cho tới khi đạt.
- `compact_resource_data()` chỉ giữ whitelist field quan trọng (không gửi raw Bundle).

**Tiêu chí hoàn thành:** Prompt ổn định với dữ liệu demo lớn vừa phải.

## M6.5 - LLM error fallback

**Mục tiêu:** Hệ thống vẫn trả lời được khi LLM lỗi.

**Hành vi:**
- Intent: rule fallback khi không có/không gọi được LLM.
- Answer: `TemplateAnswerGenerator` sinh câu trả lời template từ evidence; `OpenAIAnswerGenerator` bắt mọi `Exception` từ OpenAI API → trả `source="template_fallback"` (không lộ lỗi kỹ thuật cho user).
- Khi không có evidence → `source="template_no_evidence"`.
- Chọn generator theo cấu hình: `use_llm_answer = enable_llm_answer AND openai_api_key` → **tắt API key vẫn demo được flow cơ bản**.

**Tiêu chí hoàn thành:** Tắt API key vẫn demo được flow cơ bản.

## Luồng chương trình

```
Câu hỏi → IntentExtractor
            ├─ OpenAI structured tool call  (nếu có API key)
            └─ rule fallback                 (luôn sẵn sàng)
        ▼ IntentPlan
Tool execution (chat_routes) → FhirClient → evidence (normalized)
        ▼
AnswerGenerator.generate(question, intent, tool, patient_id, evidence, fallback_answer, model)
   compact_evidence_for_llm()  → cắt theo MAX_EVIDENCE_ITEMS / MAX_EVIDENCE_JSON_CHARS
        ▼
   OpenAI chat.completions (temperature=0.2)
        ├─ thành công → answer_source="llm", usage=token
        ├─ exception   → answer_source="template_fallback" (dùng fallback_answer)
        └─ rỗng        → answer_source="template_fallback"
   (không có API key → TemplateAnswerGenerator: answer_source="template")
        ▼
ChatResponse.answer + usage  → Spring lưu UsageLog (M7)
```

## Luồng trong code

- **Answer generator + fallback:** [answer_generator.py:57-145](chatbot-service/agents/answer_generator.py#L57-L145).
- **Prompt/context control:** `compact_evidence_for_llm()` + hằng số ([answer_generator.py:9-10](chatbot-service/agents/answer_generator.py#L9-L10), [148-233](chatbot-service/agents/answer_generator.py#L148-L233)).
- **Cấu hình bật/tắt LLM:** `use_llm_answer` / `use_openai_llm` ([config.py:37-43](chatbot-service/app/config.py#L37-L43)).
- **Intent (LLM vs rule):** [agents/intent/openai_extractor.py](chatbot-service/agents/intent/openai_extractor.py), [rule_extractor.py](chatbot-service/agents/intent/rule_extractor.py).
- **Gắn vào pipeline:** `_finalize_chat_response()` trong [chat_routes.py](chatbot-service/api/chat_routes.py#L199-L324).

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| Answer generation + fallback | `chatbot-service/agents/answer_generator.py` |
| Intent extraction | `chatbot-service/agents/intent_extractor.py`, `agents/intent/*` |
| Cấu hình LLM | `chatbot-service/app/config.py` |
| Pipeline tool execution | `chatbot-service/api/chat_routes.py` |
