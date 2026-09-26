# M14. Cache Management

Tối ưu tốc độ phản hồi và giảm chi phí LLM bằng **semantic cache** (Qdrant) ở chatbot-service, kèm observability cache hit/miss qua usage logs ở Spring.

## M14.1 - Cache policy

**Mục tiêu:** Xác định loại dữ liệu được phép cache.

**Hành vi:**
- Cache câu trả lời theo **scope `user_id` + `patient_id`** (mỗi user/bệnh nhân có cache riêng) → không lộ dữ liệu chéo.
- TTL ngắn (`cache_ttl_seconds = 300s`) để tránh trả dữ liệu cũ.
- Câu hỏi không gắn bệnh nhân dùng key `NO_PATIENT_CACHE_KEY`.

**Tiêu chí hoàn thành:** Biết request nào được cache.

## M14.2 - Cache key design

**Mục tiêu:** Tạo cache key ổn định + cho phép câu hỏi tương đương hit cache.

**Hành vi:**
- Dùng **semantic similarity** thay vì so khớp text: câu hỏi được embed (`sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2`, vector 384 chiều) và tìm trong Qdrant.
- Filter cứng theo `user_id`, `patient_id`, `created_at >= now - TTL`; match khi `score >= cache_similarity_threshold` (0.94).

**Tiêu chí hoàn thành:** Câu hỏi tương đương có thể hit cache.

## M14.3 - Cache storage và TTL

**Mục tiêu:** Lưu cache có thời gian sống.

**Hành vi:**
- Storage chính: **Qdrant** vector DB (collection `medical_chat_cache`).
- `created_at` lưu timestamp; đọc cache bỏ qua điểm cũ hơn TTL (`Range(gte = now - ttl)`).
- `cleanup_expired_cache()` xóa điểm hết hạn; `invalidate_patient_cache(patient_id)` xóa cache của một bệnh nhân.
- App PostgreSQL không giữ cache câu trả lời. Bảng khung `cache_entries` trong
  baseline đã bị xóa bởi Flyway V5 vì không có code sử dụng.

**Tiêu chí hoàn thành:** Cache hit/miss hoạt động đúng.

## M14.4 - Cache observability

**Mục tiêu:** Theo dõi cache có giảm cost không.

**Hành vi:**
- Cache hit trả `tool_name = "cache_hit"`, `answer_source = "semantic_cache_strict"`, `usage = 0` và `saved_usage` (token đáng lẽ tốn) → Spring lưu `saved_tokens`/`saved_cost` vào `usage_logs` (schema baseline V1).
- `MetricsService.getCacheMetrics()` → `usageLogRepository.getCacheObservabilityMetrics()` cho dashboard (`GET /api/metrics/cache` — [M13](M13-admin-dashboard.md)).

**Tiêu chí hoàn thành:** Admin biết tỷ lệ cache hit.

## Luồng chương trình

```
POST /chat (chatbot-service) — đầu pipeline
   ▼
get_cached_chat_payload(request, cache_service)
   patient_id_for_cache = patient_hint hoặc NO_PATIENT_CACHE_KEY
   cache.get_cached_answer(user_id, patient_id, question):
      embed(question) → Qdrant query_points
      filter: user_id == ∧ patient_id == ∧ created_at >= now-TTL
      điểm tốt nhất score >= 0.94 ?
        ├─ có → CACHE HIT: trả answer cũ, usage=0, saved_usage=token tiết kiệm
        └─ không/hết hạn → CACHE MISS
   ├─ HIT  → trả ngay (bỏ qua intent/FHIR/LLM)   → Spring ghi saved_tokens/saved_cost
   └─ MISS → chạy full pipeline (M4/M6) → sau khi có answer LLM:
               cache.save_to_cache(user_id, patient_id, intent, question, answer, usage)

Dọn dẹp/định kỳ: cleanup_expired_cache() (xóa created_at < now-TTL)
Khi dữ liệu bệnh nhân đổi: invalidate_patient_cache(patient_id)
```

## Luồng trong code

- **Đọc cache (đầu pipeline):** `get_cached_chat_payload()` ([cache_flow.py](../chatbot-service/chat/cache_flow.py)); gọi tại [chat_routes.py](../chatbot-service/api/chat_routes.py).
- **Semantic cache service:** `get_cached_answer` / `save_to_cache` / `cleanup_expired_cache` / `invalidate_patient_cache` ([semantic_cache.py](../chatbot-service/services/semantic_cache.py)).
- **Config cache:** [config.py](../chatbot-service/app/config.py).
- **Observability (Spring):** `MetricsService`
  ([MetricsService.java](../backend/src/main/java/com/medicalchatbot/backend/service/MetricsService.java));
  `ChatInteractionRecorder` ghi usage/saved usage sau khi
  `ChatbotResponseMapper` đọc response upstream.

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| Semantic cache (Qdrant) | `chatbot-service/services/semantic_cache.py` |
| Cache flow (đọc đầu pipeline) | `chatbot-service/chat/cache_flow.py` |
| Config cache | `chatbot-service/app/config.py` |
| Observability metrics | `backend/.../service/MetricsService.java`, `.../controller/MetricsController.java` |
| Saved usage + migration | `backend/.../service/ChatInteractionRecorder.java`, `.../mapper/ChatbotResponseMapper.java`, `db/migration/V1__baseline_schema_and_seed.sql` |
| Cache persistence | Qdrant collection `medical_chat_cache`; không có JPA entity/bảng cache trong schema hiện tại |
