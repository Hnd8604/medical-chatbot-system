# Product Spec — Medical Chatbot

> Đây là spec sản phẩm đầy đủ + quy tắc FHIR/RAG/usage/cache. `CLAUDE.md` gốc import file này.
> (Trước đây là `AGENTS.md` gốc — đổi tên theo chuẩn Claude Code.)

## 1. Tổng quan dự án

Dự án này xây dựng một chatbot y tế giúp người dùng truy vấn dữ liệu chăm sóc sức khỏe bằng ngôn ngữ tự nhiên.

Hệ thống được thiết kế xoay quanh **truy xuất dữ liệu có cấu trúc dựa trên FHIR**, không để LLM sinh SQL trực tiếp.

Mục tiêu chính:

- Cho phép người dùng đặt câu hỏi bằng ngôn ngữ tự nhiên về bệnh nhân, lượt khám, chỉ số, chẩn đoán, và yêu cầu thuốc.
- Truy xuất dữ liệu y tế có cấu trúc từ một **HAPI FHIR Server**.
- Lưu trữ dữ liệu FHIR nội bộ thông qua **PostgreSQL**, do HAPI FHIR JPA quản lý.
- Dùng LLM để hiểu intent, lập kế hoạch truy vấn, sinh câu trả lời, và giải thích dựa trên tài liệu (tùy chọn).
- Theo dõi mức sử dụng, quota, lượng token tiêu thụ, và chi phí AI ước tính.
- Áp dụng các kỹ thuật tối ưu cơ bản như caching, giảm ngữ cảnh (context reduction), và gọi tool an toàn.

Chatbot không được truy cập hay sửa đổi trực tiếp các bảng cơ sở dữ liệu nội bộ của HAPI FHIR.

---

## 2. Kiến trúc cốt lõi

Kiến trúc khuyến nghị:

```text
User
  ↓
Chat UI / Frontend
  ↓
Chatbot Backend
  ↓
LLM Orchestrator / Agent Layer
  ↓
Tool Calling Layer
  ↓
FHIR Retrieval Service
  ↓
HAPI FHIR JPA Server
  ↓
PostgreSQL
```

Luồng truy xuất tài liệu (tùy chọn):

```text
User Question
  ↓
LLM Orchestrator
  ↓
RAG Service
  ↓
Vector Database / Document Store
  ↓
Medical guideline / explanation documents
```

Hệ thống nên dùng **hướng tiếp cận lai (hybrid)**:

- FHIR API cho dữ liệu bệnh nhân có cấu trúc.
- RAG cho tài liệu phi cấu trúc, giải thích y khoa, hướng dẫn, và ghi chú dài.
- Quản lý mức sử dụng cho số lượng request, số token, và ước tính chi phí.

---

## 3. Quy tắc quan trọng: Không truy vấn trực tiếp PostgreSQL của HAPI

HAPI FHIR dùng PostgreSQL làm lớp lưu trữ nội bộ thông qua JPA/Hibernate.

Schema cơ sở dữ liệu không phải là một schema nghiệp vụ đơn giản kiểu:

```sql
patients(id, name, gender)
observations(id, patient_id, code, value)
conditions(id, patient_id, diagnosis)
```

Thay vào đó, HAPI tạo ra các bảng nội bộ như:

```text
hfj_resource
hfj_res_ver
hfj_spidx_token
hfj_spidx_string
hfj_spidx_date
hfj_res_link
...
```

Các bảng này là chi tiết triển khai (implementation details).

Do đó:

```text
Đúng:
LLM → intent/tham số → Backend → FHIR REST API → HAPI FHIR → PostgreSQL

Sai:
LLM → SQL được sinh ra → các bảng nội bộ HAPI trong PostgreSQL
```

Chatbot backend phải giao tiếp với HAPI FHIR qua các endpoint REST như:

```http
GET /fhir/Patient
GET /fhir/Patient/{id}
GET /fhir/Observation?patient=Patient/{id}
GET /fhir/Encounter?patient=Patient/{id}
GET /fhir/Condition?patient=Patient/{id}
GET /fhir/MedicationRequest?patient=Patient/{id}
```

---

## 4. Các thành phần chính

### 4.1 Chat UI

Trách nhiệm:

- Hiển thị tin nhắn chat.
- Gửi câu hỏi của người dùng tới backend.
- Hiển thị câu trả lời của chatbot.
- Tùy chọn hiển thị evidence đã truy xuất hoặc dữ liệu nguồn.
- Hiển thị cảnh báo usage/quota khi cần.

Frontend không nên gọi trực tiếp FHIR Server.

---

### 4.2 Chatbot Backend

Trách nhiệm:

- Nhận tin nhắn của người dùng.
- Quản lý các phiên chat.
- Lưu lịch sử hội thoại.
- Gọi LLM orchestrator.
- Gọi các tool truy xuất FHIR.
- Gọi các tool RAG khi cần.
- Theo dõi usage và quota.
- Trả câu trả lời cuối cùng về cho frontend.

Backend là lớp điều phối trung tâm.

---

### 4.3 LLM Orchestrator / Lớp Agent

Trách nhiệm:

- Hiểu intent của người dùng.
- Trích xuất các thực thể hữu ích như tên bệnh nhân, ID bệnh nhân, khoảng thời gian, loại resource, loại xét nghiệm, hoặc tên thuốc.
- Quyết định tool nào cần được gọi.
- Tránh bịa (hallucinate) dữ liệu y tế.
- Chỉ sinh câu trả lời cuối từ dữ liệu đã truy xuất.

LLM nên tạo ra các lệnh gọi tool có cấu trúc, không phải SQL thô.

Ví dụ output nội bộ:

```json
{
  "intent": "get_patient_observations",
  "parameters": {
    "patient_id": "Patient/123",
    "observation_type": "blood_pressure",
    "limit": 5
  }
}
```

---

### 4.4 FHIR Retrieval Service

Trách nhiệm:

- Chuyển intent và tham số có cấu trúc thành các lệnh gọi FHIR REST API.
- Gọi HAPI FHIR Server.
- Normalize response JSON của FHIR thành định dạng đơn giản hơn cho LLM.
- Xử lý lỗi từ FHIR Server.
- Ngăn các truy vấn không an toàn hoặc quá rộng.

Ví dụ:

```text
Input:
{
  "patient_id": "Patient/123",
  "resource": "Observation",
  "code": "blood-pressure",
  "limit": 5
}

FHIR request:
GET /fhir/Observation?patient=Patient/123&code=...&_sort=-date&_count=5
```

---

### 4.5 HAPI FHIR Server

Trách nhiệm:

- Cung cấp FHIR REST API chuẩn.
- Validate các resource FHIR.
- Lưu trữ resource qua JPA/Hibernate.
- Duy trì các chỉ mục tìm kiếm (search index).
- Quản lý lịch sử và tham chiếu của resource FHIR.

Thiết lập khuyến nghị:

```text
HAPI FHIR JPA Server + PostgreSQL
```

HAPI FHIR Server nên được coi là nguồn sự thật (source of truth) cho dữ liệu y tế có cấu trúc.

---

### 4.6 PostgreSQL

Trách nhiệm:

- Lưu bền vững các resource và chỉ mục FHIR cho HAPI FHIR.
- Lưu dữ liệu nội bộ của HAPI FHIR.
- Tùy chọn lưu dữ liệu riêng của ứng dụng trong các schema hoặc database tách biệt.

Không sửa thủ công các bảng nội bộ của HAPI trừ khi có lý do bảo trì rõ ràng.

Nếu dự án cần bảng ứng dụng, hãy dùng các bảng/schema tách biệt như:

```text
app_users
chat_sessions
chat_messages
usage_logs
quota_policies
cache_entries
```

Không trộn logic ứng dụng với các bảng nội bộ của HAPI.

---

### 4.7 RAG Service

Dùng RAG cho kiến thức phi cấu trúc hoặc bán cấu trúc, ví dụ:

- Tài liệu giải thích y khoa.
- Quy định bệnh viện.
- Hướng dẫn sử dụng thuốc.
- Các PDF hướng dẫn lâm sàng.
- Ghi chú dài của bác sĩ.
- Giải thích chung về kết quả xét nghiệm.

Không dùng RAG làm phương pháp chính cho dữ liệu bệnh nhân có cấu trúc chính xác.

Dùng RAG hợp lý:

```text
"Giá trị glucose cao có nghĩa là gì?"
"Giải thích chẩn đoán này bằng ngôn ngữ đơn giản."
"Các nguyên nhân thường gặp của tăng huyết áp là gì?"
```

Dùng RAG không hợp lý:

```text
"Kết quả glucose mới nhất của Patient/123 là gì?"
"Patient/123 đang dùng những thuốc nào?"
"Chẩn đoán gần nhất là gì?"
```

Những câu đó nên dùng truy vấn FHIR.

---

## 5. Chiến lược truy xuất dữ liệu

### 5.1 Dữ liệu y tế có cấu trúc

Dùng FHIR API.

Ví dụ:

| Câu hỏi của người dùng | FHIR Resource |
|---|---|
| Thông tin bệnh nhân | Patient |
| Lượt khám gần nhất | Encounter |
| Huyết áp / nhịp tim / đường huyết | Observation |
| Chẩn đoán | Condition |
| Thuốc | MedicationRequest |
| Báo cáo xét nghiệm | DiagnosticReport |

Ví dụ luồng:

```text
Người dùng hỏi:
"Patient/123 đang dùng những thuốc nào?"

Backend:
GET /fhir/MedicationRequest?patient=Patient/123

LLM:
Chỉ tóm tắt dữ liệu MedicationRequest được trả về.
```

---

### 5.2 Kiến thức phi cấu trúc

Dùng RAG.

Ví dụ luồng:

```text
Người dùng hỏi:
"Giá trị glucose này có nghĩa là gì?"

Backend:
1. Truy xuất Observation glucose từ FHIR.
2. Truy xuất tài liệu giải thích qua RAG.
3. Yêu cầu LLM giải thích dựa trên cả hai nguồn.
```

---

### 5.3 Câu hỏi hỗn hợp

Một số câu hỏi cần cả FHIR lẫn RAG.

Ví dụ:

```text
Người dùng hỏi:
"Patient/123 bị cao huyết áp. Giải thích điều đó nghĩa là gì."
```

Quy trình khuyến nghị:

```text
1. Dùng FHIR để truy xuất các observation huyết áp.
2. Dùng RAG để truy xuất giải thích về các ngưỡng huyết áp.
3. Sinh câu trả lời cẩn trọng.
4. Tránh đưa ra chẩn đoán trừ khi dữ liệu hỗ trợ rõ ràng.
```

---

## 6. Quy tắc gọi Tool

LLM nên gọi các tool backend được định nghĩa trước thay vì tự tạo truy vấn cơ sở dữ liệu.

Các tool khuyến nghị:

### search_patient

Tìm bệnh nhân theo tên, mã định danh, ngày sinh, hoặc số điện thoại.

```json
{
  "name": "search_patient",
  "parameters": {
    "name": "Nguyen Van A",
    "birth_date": "2003-01-01"
  }
}
```

### get_patient_by_id

Lấy một bệnh nhân theo FHIR ID.

```json
{
  "name": "get_patient_by_id",
  "parameters": {
    "patient_id": "Patient/123"
  }
}
```

### get_observations

Lấy các observation của một bệnh nhân.

```json
{
  "name": "get_observations",
  "parameters": {
    "patient_id": "Patient/123",
    "type": "blood_pressure",
    "limit": 5
  }
}
```

### get_encounters

Lấy các lượt khám (encounter) của một bệnh nhân.

```json
{
  "name": "get_encounters",
  "parameters": {
    "patient_id": "Patient/123",
    "limit": 5
  }
}
```

### get_conditions

Lấy các chẩn đoán (condition) của một bệnh nhân.

```json
{
  "name": "get_conditions",
  "parameters": {
    "patient_id": "Patient/123"
  }
}
```

### get_medication_requests

Lấy các yêu cầu thuốc của một bệnh nhân.

```json
{
  "name": "get_medication_requests",
  "parameters": {
    "patient_id": "Patient/123"
  }
}
```

### retrieve_documents

Lấy các đoạn văn bản liên quan từ tài liệu y khoa.

```json
{
  "name": "retrieve_documents",
  "parameters": {
    "query": "meaning of high glucose level"
  }
}
```

---

## 7. Quản lý Usage, Chi phí, và Quota

Hệ thống nên theo dõi mức sử dụng ở nhiều cấp độ.

### 7.1 Quota theo Request

Theo dõi số lần gọi AI mà một người dùng thực hiện.

```text
Free user: 50 AI requests/ngày
Admin user: 500 AI requests/ngày
```

Hữu ích để giới hạn spam và lạm dụng.

### 7.2 Quota theo Token

Theo dõi token đầu vào và đầu ra.

```text
User A đã dùng 25,000 token hôm nay.
Giới hạn: 100,000 token/ngày.
```

Hữu ích vì một request có thể rẻ hoặc đắt tùy vào kích thước ngữ cảnh.

### 7.3 Quota theo Chi phí

Theo dõi chi phí ước tính.

```text
User A đã dùng khoảng $0.42 hôm nay.
Giới hạn: $1.00/ngày.
```

Hữu ích vì các mô hình khác nhau có giá khác nhau.

### 7.4 Vì sao theo dõi cả ba?

| Chỉ số | Kiểm soát |
|---|---|
| Số request | Số lần gọi |
| Số token | Kích thước ngữ cảnh và response |
| Chi phí | Số tiền thực tế đã chi |

Một người dùng có thể gửi ít request nhưng mỗi request có ngữ cảnh lớn. Người khác lại gửi nhiều request nhỏ. Chỉ theo dõi một chỉ số là không đủ.

---

## 8. Quy tắc Caching

Hệ thống có thể cache các kết quả an toàn và có thể lặp lại.

### 8.1 Cache Hit

Cache hit nghĩa là hệ thống tìm thấy một kết quả trước đó cho cùng request hoặc request tương đương và tái sử dụng nó thay vì gọi lại dịch vụ tốn kém.

### 8.2 Ứng viên Cache tốt

- Giải thích y khoa chung.
- Kết quả truy xuất RAG cho các khái niệm phổ biến.
- Metadata FHIR.
- Các ánh xạ mã như ánh xạ LOINC/SNOMED.
- Dữ liệu tổng hợp không nhạy cảm.

Cẩn thận với dữ liệu riêng của bệnh nhân.

### 8.3 Cache dữ liệu bệnh nhân

Dữ liệu riêng của bệnh nhân có thể thay đổi và nhạy cảm. Nếu cache, nó nên có:

- TTL ngắn.
- Phạm vi theo user/session.
- Kiểm tra quyền.
- Không chia sẻ giữa các user.
- Logic vô hiệu hóa (invalidation) rõ ràng.

---

## 9. Quy tắc An toàn và Riêng tư

Hệ thống phải:

- Không bao giờ bịa dữ liệu bệnh nhân.
- Không bao giờ khẳng định một chẩn đoán trừ khi dữ liệu đã truy xuất nói rõ như vậy.
- Tránh đưa ra lời khuyên y tế mang tính khẳng định.
- Ưu tiên các cụm như "hồ sơ cho thấy" hoặc "theo dữ liệu hiện có".
- Thể hiện sự không chắc chắn khi thiếu dữ liệu.
- Tôn trọng kiểm soát truy cập.
- Tránh để lộ dữ liệu của bệnh nhân này cho người dùng khác.
- Ghi log dữ liệu nhạy cảm một cách cẩn thận.
- Tránh lưu hồ sơ y tế thô trong prompt của LLM trừ khi cần thiết.

Phong cách khuyến nghị:

```text
Theo dữ liệu FHIR hiện có, Patient/123 có các thuốc được ghi nhận sau...
```

Không khuyến nghị:

```text
Bệnh nhân này chắc chắn mắc bệnh X.
```

trừ khi resource Condition hỗ trợ điều đó một cách rõ ràng.

---

## 10. Xử lý lỗi

Backend nên xử lý rõ ràng các trường hợp:

- **Không tìm thấy bệnh nhân**: "Không có bệnh nhân nào khớp với thông tin được cung cấp."
- **Tìm thấy nhiều bệnh nhân**: "Nhiều bệnh nhân khớp với tên này. Hãy hỏi thêm ngày sinh, mã định danh, hoặc một trường phân biệt khác."
- **Không tìm thấy resource**: "Không tìm thấy bản ghi Observation nào cho bệnh nhân này."
- **Lỗi FHIR Server**: Ghi log chi tiết kỹ thuật ở nội bộ, trả về một thông báo đơn giản cho người dùng.
- **Lỗi LLM Tool**: "Hệ thống không thể hoàn tất bước truy xuất dữ liệu." Không bịa câu trả lời.

---

## 11. Gợi ý cấu trúc thư mục Backend

```text
src/
  app/         main.py, config.py
  api/         chat_routes.py, health_routes.py
  agents/      orchestrator.py, prompts.py, tool_registry.py
  fhir/        client.py, patient_service.py, observation_service.py, ..., normalizer.py
  rag/         retriever.py, vector_store.py, document_loader.py
  usage/       usage_tracker.py, quota_service.py, cost_estimator.py
  cache/       cache_service.py, cache_keys.py
  db/          models.py, session.py, migrations/
  security/    auth.py, access_control.py
  tests/       test_fhir_client.py, test_patient_service.py, ...
```

Điều chỉnh cấu trúc này theo framework thực tế.

---

## 12. Docker Compose

Các dịch vụ khuyến nghị: `chatbot-backend`, `frontend`, `hapi-fhir`, `postgres`, `redis`, `vector-db`.

Tối thiểu cho demo FHIR: `chatbot-backend`, `hapi-fhir`, `postgres`.

Tùy chọn: `redis` (cache/rate limit), `qdrant` (vector DB cho RAG), `pgvector` (vector search trong PostgreSQL).

---

## 13. Biến môi trường

```env
# HAPI FHIR
FHIR_BASE_URL=http://localhost:8080/fhir

# PostgreSQL cho dữ liệu app
APP_DATABASE_URL=postgresql://app_user:app_password@localhost:5432/app_db

# PostgreSQL dùng bởi HAPI FHIR
HAPI_POSTGRES_DB=hapi
HAPI_POSTGRES_USER=admin
HAPI_POSTGRES_PASSWORD=admin

# LLM
LLM_PROVIDER=openai
LLM_MODEL=gpt-4.1-mini
LLM_API_KEY=replace_me

# Giới hạn sử dụng
DAILY_REQUEST_LIMIT=100
DAILY_TOKEN_LIMIT=100000
DAILY_COST_LIMIT_USD=1.00

# Cache
REDIS_URL=redis://localhost:6379
CACHE_TTL_SECONDS=300
```

Không commit API key hoặc mật khẩu thật.

---

## 14. Hướng dẫn phát triển

### 14.1 Chung

- Giữ lớp LLM tách biệt với lớp FHIR client.
- Giữ các lệnh gọi FHIR API tập trung trong module `fhir/`.
- Giữ các template prompt tập trung.
- Giữ việc theo dõi usage tập trung; không lặp lại logic quota giữa các controller.
- Normalize response FHIR trước khi gửi cho LLM.

### 14.2 FHIR

- Ưu tiên tham số tìm kiếm FHIR REST hơn là truy vấn cơ sở dữ liệu tùy biến.
- Luôn giới hạn các tìm kiếm rộng với `_count`.
- Dùng `_sort=-date` khi lấy bản ghi mới nhất.
- Dùng patient ID thay vì tên bệnh nhân khi đã xác định được bệnh nhân.
- Xử lý response dạng Bundle đúng cách.
- Giữ lại các trường quan trọng: `resourceType`, `id`, `code`, `display`, `effectiveDateTime`, `valueQuantity`, `subject`.

### 14.3 LLM

- LLM không được tạo ra sự thật không có trong dữ liệu đã truy xuất.
- LLM nên hỏi lại để làm rõ nếu danh tính bệnh nhân không rõ ràng.
- LLM nên gọi tool thay vì trả lời trực tiếp câu hỏi dữ liệu từ trí nhớ.
- LLM nên được cung cấp dữ liệu gọn, đã normalize thay vì bundle FHIR thô khổng lồ.

### 14.4 RAG

- Chia tài liệu theo các phần ngữ nghĩa, không phải các mảnh nhỏ tùy tiện.
- Lưu metadata nguồn.
- Trả về trích dẫn hoặc tên nguồn khi có thể.
- Không trộn tài liệu cũ/đã lỗi thời với tài liệu hiện hành mà không đánh dấu.

### 14.5 Kiểm thử

Test tối thiểu: tìm kiếm bệnh nhân; truy xuất observation; lượt khám gần nhất; condition; MedicationRequest; FHIR Server không khả dụng; kết quả FHIR rỗng; nhiều bệnh nhân khớp; vượt quota; cache hit/miss; parse lệnh gọi tool của LLM.

---

## 15. Ví dụ các Request FHIR

```http
GET  /fhir/metadata
POST /fhir/Patient
GET  /fhir/Patient?name=Nguyen
GET  /fhir/Observation?patient=Patient/123&_count=10
GET  /fhir/Observation?patient=Patient/123&_sort=-date&_count=5
GET  /fhir/Condition?patient=Patient/123
GET  /fhir/MedicationRequest?patient=Patient/123
```

---

## 16. Mẫu sinh câu trả lời khuyến nghị

```text
1. Nêu rõ đã tìm thấy dữ liệu gì.
2. Đề cập thời gian/ngày liên quan nếu có.
3. Tóm tắt kết quả rõ ràng.
4. Tránh khẳng định quá mức.
5. Đề cập nếu dữ liệu thiếu hoặc không đầy đủ.
```

Ví dụ:

```text
Theo dữ liệu FHIR hiện có, Patient/123 có ba bản ghi observation huyết áp. Bản ghi mới nhất từ ngày 2026-05-20 với tâm thu 130 mmHg và tâm trương 85 mmHg. Câu trả lời này chỉ dựa trên các resource Observation hiện có.
```

---

## 17. Phạm vi Demo tối thiểu

```text
1. HAPI FHIR + PostgreSQL chạy bằng Docker.
2. Dữ liệu giả cho Patient, Encounter, Observation, Condition, MedicationRequest.
3. Backend API cho chat.
4. Trích xuất intent và gọi tool bằng LLM.
5. Các tool truy xuất FHIR.
6. Theo dõi usage đơn giản: số request, số token, chi phí ước tính.
7. Cache cơ bản cho các truy vấn giải thích chung lặp lại.
8. RAG tùy chọn cho tài liệu giải thích y khoa.
9. Giao diện chat frontend đơn giản.
```

Không xây microservice quá mức trừ khi nhóm có đủ thời gian.

---

## 18. Ngoài phạm vi của phiên bản đầu

Tránh: tích hợp bệnh viện thật; dữ liệu bệnh nhân thật; hỗ trợ quyết định lâm sàng đầy đủ; mô hình phân quyền phức tạp; tích hợp đầy đủ terminology server SNOMED/LOINC; fine-tune một LLM y khoa; viết SQL trực tiếp vào bảng nội bộ HAPI; xây dựng hệ thống EHR hoàn chỉnh.

---

## 19. Nguyên tắc thiết kế cuối cùng

```text
Dùng FHIR API cho dữ liệu y tế có cấu trúc chính xác.
Dùng RAG cho giải thích và tài liệu.
Dùng LLM cho suy luận, điều phối, và sinh câu trả lời.
Không để LLM truy vấn trực tiếp cơ sở dữ liệu PostgreSQL của HAPI.
```
