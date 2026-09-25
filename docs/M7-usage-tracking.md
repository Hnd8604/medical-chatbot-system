# M7. Usage Tracking

Ghi nhận mỗi lượt chat có gọi pipeline AI: token in/out, provider/model, latency, status — làm nguồn dữ liệu cho [M8 (Cost)](M8-cost-management.md), [M9 (Quota)](M9-quota-management.md), [M13](M13-admin-dashboard.md), [M24](M24-M26-analytics-backup.md).

## M7.1 - Log mỗi request AI

**Mục tiêu:** Ghi nhận mỗi lượt chat có gọi pipeline AI.

**Hành vi:**
- Entity `UsageLog` (bảng `usage_logs`); mỗi lượt `POST /api/chat` thành công tạo một dòng usage qua `saveUsage()` trong `ChatApplicationService`.
- Lưu user, session, operation (`"chat"`), status (`"success"`), và **latency** (đo bằng `System.nanoTime()` quanh lời gọi chatbot-service).

**Tiêu chí hoàn thành:** DB có usage row sau mỗi `POST /api/chat` thành công.

## M7.2 - Token input/output

**Mục tiêu:** Lưu token đầu vào và đầu ra.

**Hành vi:**
- chatbot-service trả `usage.input_tokens` / `usage.output_tokens` (cộng token intent + answer; nếu không gọi LLM → 0).
- Spring đọc và lưu vào `usage_logs`; còn lưu `saved_tokens`/`saved_cost` khi cache hit ([M14](M14-cache-management.md)).

**Tiêu chí hoàn thành:** Quota status hiển thị token đã dùng.

## M7.3 - Model/provider tracking

- chatbot-service trả `llm_provider`, `llm_model` (từ settings).
- Spring lưu vào `usage_logs` + audit metadata ([M18](M18-M19-audit-alert.md)).
- **Tiêu chí:** query usage logs thấy model thật (phản ánh model routing — [M16](M16-M17-model-routing-retry-fallback.md)).

## M7.4 - Aggregation theo user/time

**Mục tiêu:** Tính usage theo user và khoảng thời gian.

**Hành vi:**
- `UsageLogRepository.summarizeSuccessfulUsage(userId, startOfDay, startOfNextDay)` tổng hợp: tổng request, tổng input/output token, tổng cost.
- Dùng cho `GET /api/quota/status` (hôm nay) và `GET /api/usage/cost-summary` (khoảng ngày).

**Tiêu chí hoàn thành:** `GET /api/quota/status` trả đúng usage hôm nay.

## M7.5 - Usage theo nhóm người dùng

- `app_users` có `role`; aggregate theo role/group là hướng mở rộng (admin dashboard top role/group).
- **Tiêu chí:** Admin biết nhóm dùng nhiều tài nguyên nhất.

## Luồng chương trình

```
POST /api/chat (thành công) — ChatApplicationService.chat (@Transactional)
   đo latency: startedAtNanos = System.nanoTime()
   → gọi chatbot-service → nhận usage {input_tokens, output_tokens},
                                  llm_provider, llm_model, answer_source, saved_usage
   latencyMs = nanos→ms
   ▼
saveUsage(user, session, chatbotResponse, latencyMs)
   estimatedCostUsd = CostEstimationService.estimateUsd(...)   (M8)
   usageLogRepository.save(user, session, provider, model, "chat", "success",
                           latencyMs, inputTokens, outputTokens, cost,
                           null, answerSource, savedTokens, savedCost)
   ▼
usage_logs (1 dòng/lượt)
   ▼
Aggregation: summarizeSuccessfulUsage(userId, from, to)
   → quota status (M9) / cost summary (M8) / dashboard (M13)
```

## Luồng trong code

- **Ghi usage:** `saveUsage()` ([ChatApplicationService.java:180-230](backend/src/main/java/com/medicalchatbot/backend/service/ChatApplicationService.java#L180-L230)); đo latency ([L89-100](backend/src/main/java/com/medicalchatbot/backend/service/ChatApplicationService.java#L89-L100)).
- **Aggregate:** `UsageLogRepository.summarizeSuccessfulUsage()` (dùng trong [QuotaService.statusForUser()](backend/src/main/java/com/medicalchatbot/backend/service/QuotaService.java#L159-L199)).
- **Token nguồn:** `usage` trong response của chatbot-service ([answer_generator.py:128-133](chatbot-service/agents/answer_generator.py#L128-L133)).

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| Ghi usage | `backend/.../service/ChatApplicationService.java` (`saveUsage`) |
| Entity/repository | `backend/.../entity/UsageLog.java`, `.../repository/UsageLogRepository.java` |
| Token nguồn (LLM) | `chatbot-service/agents/answer_generator.py` |
| Migration | `db/migration/V1`, `V3` (status/provider/model/latency), `V7` (cache fields) |
