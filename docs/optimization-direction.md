# Hướng chuyên sâu — Tối ưu vận hành & quản lý tài nguyên AI

Tên đề tài: **Tối ưu vận hành hệ thống chatbot y tế thông qua quản lý token, quota, cache và model routing.**

Trọng tâm: **tối ưu chi phí, hiệu năng, độ ổn định** khi dùng AI. Bài thiên về
engineering tối ưu, vận hành thực tế, cost-control, scalable AI system.

Mục tiêu định lượng:

- **Giảm** chi phí, latency, số token sử dụng.
- **Tăng** throughput và khả năng phục vụ nhiều người dùng đồng thời.

---

## 1. Trục công việc chính

| Trục | Mô tả | Trạng thái repo |
|---|---|---|
| Theo dõi token / lượt gọi / chi phí | Theo user, model, thời gian | `usage_logs` (Spring), `usage` trong response chatbot-service |
| Quota | Theo user hoặc nhóm user | `quota_policies`, `QuotaService`, `QuotaPolicyAdminService` |
| Rate limit + budget limit | Giới hạn tần suất và ngân sách | một phần trong QuotaService |
| Cache câu trả lời / kết quả truy vấn | Exact + **semantic cache** | `cache_entries`; semantic cache = việc cần làm |
| Context pruning / summary hội thoại | Rút gọn hội thoại cũ | việc cần làm |
| Model routing | Câu đơn giản → model rẻ; phức tạp → model mạnh | `agents/model_router.py` (M16), LLM Router |
| Retry / fallback | Khi AI service lỗi | M17 (retry/fallback), xem docs bên dưới |
| AI Gateway | Lớp trung gian quản lý key/log/cost/routing | **LiteLLM** — `infra/litellm/`, `docs/M-litellm-gateway.md` |

Tài liệu milestone liên quan đã có trong repo:

- `docs/M-litellm-gateway.md` — AI Gateway (LiteLLM): API key, logging, cost tracking, routing.
- `docs/M16-M17-model-routing-retry-fallback.md` — model routing + retry + fallback.

---

## 2. Các kỹ thuật trọng tâm (keyword)

### 2.1 Semantic Cache

Thay vì cache theo exact text, cache theo **ý nghĩa câu hỏi**. Ví dụ:

```text
"Các xét nghiệm gần nhất của bệnh nhân A"
"Cho tôi xem xét nghiệm mới nhất của bệnh nhân A"
→ cùng một cache entry
```

Công nghệ khả thi: **Redis Vector Similarity Search**, **Qdrant** (đã có trong stack), **Milvus**.

Lưu ý an toàn: dữ liệu bệnh nhân phải cache có TTL ngắn, scope theo user/session,
kiểm tra quyền, không chia sẻ giữa user (xem `docs/product-spec.md` §8.3).

### 2.2 Context Compression

Rút gọn hội thoại cũ để giảm token đầu vào. Kỹ thuật:

- Conversation Summary
- Rolling Summary
- Memory Compression

Framework tham khảo: **LangGraph Memory**.

### 2.3 Intelligent Model Routing

```text
Truy vấn FAQ / đơn giản  → model rẻ (vd GPT-4o / model nhỏ)
Phân tích phức tạp       → model mạnh (vd GPT-5)
```

Kỹ thuật: **LLM Router**, **Classifier Router**, **Cost-aware Routing**.
Repo đã có keyword classifier + cost-aware quota downgrade + hybrid LLM Router
trong `chatbot-service/agents/model_router.py` (M16).

### 2.4 AI Gateway

Lớp trung gian quản lý: **API Key**, **Logging**, **Cost Tracking**, **Routing**.
Công nghệ: **LiteLLM** (`infra/litellm/`). Provider keys chỉ nằm trong gateway;
chatbot-service không gọi provider trực tiếp.

---

## 3. Chỉ số đánh giá (để báo cáo/đồ án)

Đo trước–sau khi áp dụng từng kỹ thuật:

- Giảm **bao nhiêu token** (input/output).
- Giảm **bao nhiêu chi phí** ($/request, $/user/ngày).
- Tăng **throughput bao nhiêu %** (request/giây, concurrent users).
- Giảm **latency** (p50/p95) — đặc biệt với cache hit vs miss.
- Tỉ lệ **cache hit** (exact vs semantic).
- Tỉ lệ **routing** về model rẻ so với model mạnh, và độ chính xác của router.
- Tỉ lệ **fallback/retry** thành công khi AI service lỗi.

Gợi ý: log đủ trường trong `usage_logs` (token in/out, model, cost, latency, cache_hit,
routing_source, query_complexity) để dashboard admin có thể dựng các biểu đồ này.
