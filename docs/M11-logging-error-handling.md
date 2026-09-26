# M11. Logging & Error Handling

Ghi log kỹ thuật để debug và trả lỗi thân thiện cho người dùng, đồng thời bảo vệ dữ liệu nhạy cảm không bị lộ trong log. Bao trùm cả Spring backend và chatbot-service.

## M11.1 - Application logs

**Mục tiêu:** Ghi log kỹ thuật để debug.

**Hành vi:**
- Spring dùng Slf4j (`@Slf4j`); chatbot-service dùng `logging`/`app/logger.py`.
- Log request quan trọng và lỗi service; log ra `logs/` khi chạy local.
- **Không** log API key/password/raw sensitive data.

**Tiêu chí hoàn thành:** Khi lỗi có thể xem logs tìm nguyên nhân.

## M11.2 - Friendly error response

**Mục tiêu:** Trả lỗi dễ hiểu cho frontend/user.

**Hành vi:**
- `ApiExceptionHandler` (`@RestControllerAdvice`) chuẩn hóa body lỗi: `{status, error_code, message, detail}` — message tiếng Việt, **không stack trace**.
- Map status phù hợp: validation → 400, not found → 404, quota → 429, rate limit → 429, chatbot 4xx → propagate, chatbot 5xx/unavailable → 502, còn lại → 500.
- chatbot-service translate lỗi FHIR thành `user_message` tiếng Việt (`FhirClientError`).

**Tiêu chí hoàn thành:** Lỗi FHIR/LLM/DB không lộ chi tiết nhạy cảm.

## M11.3 - External service error logs

**Mục tiêu:** Ghi lỗi khi gọi LLM hoặc HAPI.

**Hành vi:**
- FHIR client bắt lỗi và gắn `technical_detail` (HTTP code + body) cho log nội bộ, `user_message` cho UI ([M5.2](M5-fhir-integration.md)).
- LLM client qua LiteLLM bắt lỗi provider thông thường → fallback template +
  `reason=str(exc)`; lỗi budget vẫn được chuyển thành HTTP 429 ([M6.5](M6-ai-integration.md)).
- Spring phân biệt nguồn lỗi qua log tag: `[GATEWAY ERROR]` (chatbot-service), `[VALIDATION ERROR]`, `[QUOTA]`, `[RATE LIMIT]`, `[SYSTEM FATAL]` — và tạo alert theo loại (AI_SERVICE / SECURITY / SYSTEM_CORE).

**Tiêu chí hoàn thành:** Phân biệt được lỗi AI, FHIR, DB, validation.

## M11.4 - Sensitive data protection

**Mục tiêu:** Hạn chế rò rỉ dữ liệu bệnh nhân trong log.

**Hành vi:**
- Không log raw FHIR Bundle lớn — chỉ evidence đã compact ([M6.4](M6-ai-integration.md)).
- Metadata message chỉ lưu evidence refs, không lưu raw record ([M3.5](M3-message-history.md)).
- Error body trả về đã được làm sạch (`buildErrorResponse` chỉ trả message an toàn).

**Tiêu chí hoàn thành:** Log đủ debug nhưng không lộ dữ liệu thừa.

## Luồng chương trình

```
Exception phát sinh ở controller/service
   ▼
ApiExceptionHandler (@RestControllerAdvice) bắt theo loại:
   AppException + ErrorCode       → lỗi nghiệp vụ typed, service không phụ thuộc HTTP
   ResponseStatusException        → compatibility cho call site web/legacy
   HttpClientErrorException.NotFound → 404 (message VN)
   RestClientResponseException    → 4xx propagate | 5xx → 502 + alert CRITICAL + notify user
   RestClientException (down)     → 502 CHATBOT_UNAVAILABLE + alert CRITICAL + notify user
   ConstraintViolation / MethodArgumentNotValid → 400 VALIDATION_ERROR
   QuotaExceededException         → 429 QUOTA_EXCEEDED
   RateLimitExceededException     → 429 + alert RATE_LIMIT_VIOLATION
   Exception (catch-all)          → 500 + alert FATAL_ERROR_500 + notify user
   ▼
body chuẩn hóa: { status, error_code, message(VN), detail, code }
   (`code` là mã số ổn định; log nội bộ giữ technical detail)
   ▼
Frontend render message (role "error"), không crash layout
```

## Luồng trong code

- **Application error:**
  [AppException.java](../backend/src/main/java/com/medicalchatbot/backend/exception/AppException.java)
  mang `ErrorCode` nhưng không kế thừa exception Spring MVC.
- **Global handler:**
  [ApiExceptionHandler.java](../backend/src/main/java/com/medicalchatbot/backend/exception/ApiExceptionHandler.java)
  map exception thành `ApiErrorResponse` và che message nội bộ cho lỗi 5xx.
- **Lỗi FHIR (Python):** `FhirClientError`
  ([client.py](../chatbot-service/fhir/client.py)).
- **Lỗi LLM (Python):** [answer_generator.py](../chatbot-service/agents/answer_generator.py).
- **Logger config:** [app/logger.py](../chatbot-service/app/logger.py); Spring Slf4j trong các service.

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| Global exception handler | `backend/.../exception/ApiExceptionHandler.java` |
| Exceptions | `backend/.../exception/AppException.java`, `ErrorCode.java`, `{QuotaExceededException,RateLimitExceededException}.java` |
| Lỗi FHIR | `chatbot-service/fhir/client.py` |
| Lỗi LLM | `chatbot-service/agents/answer_generator.py` |
| Logger | `chatbot-service/app/logger.py` |
