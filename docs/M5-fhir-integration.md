# M5. FHIR / Medical Database Integration

Tích hợp với HAPI FHIR JPA Server: hạ tầng, FHIR client tập trung, endpoint đọc đã normalize, lớp normalization và seed dữ liệu demo. Là nguồn dữ liệu y tế cho [M4](M4-medical-data-query.md).

## M5.1 - HAPI FHIR infrastructure

**Mục tiêu:** Chạy HAPI FHIR JPA Server với PostgreSQL.

**Hành vi:**
- Docker Compose riêng tại `infra/hapi-fhir/`; HAPI expose tại `/fhir`; PostgreSQL dùng named volume.
- Cấu hình base URL qua env `FHIR_BASE_URL` (mặc định `http://localhost:8080/fhir` — `app/config.py`).

**Tiêu chí hoàn thành:** `GET /fhir/metadata` trả CapabilityStatement.

## M5.2 - FHIR client

**Mục tiêu:** Tập trung toàn bộ logic gọi HAPI FHIR vào một nơi.

**Hành vi:**
- `FhirClient` (`fhir/client.py`) là điểm duy nhất gọi HAPI — dùng `httpx.AsyncClient` với header `Accept: application/fhir+json`.
- Hàm: `get_patient`, `search_patients`, `search_patients_flexible`, `search_patient_resources`, `get_resource`, `get_metadata`.
- **Timeout** lấy từ `fhir_request_timeout_seconds` (mặc định 20s).
- **Error handling tập trung** trong `_get()`:
  - 404 → `FhirNotFoundError`.
  - 4xx/5xx khác → `FhirClientError("FHIR Server trả về lỗi.", technical_detail)`.
  - lỗi mạng/timeout/JSON sai → `FhirClientError("FHIR Server hiện không khả dụng...")`.
- `search_patients_flexible` thử tìm theo từng token tên khi tìm nguyên cụm không ra (tăng tỉ lệ match tên tiếng Việt).

**Tiêu chí hoàn thành:** Mọi truy vấn FHIR từ chatbot-service đi qua FHIR client.

## M5.3 - Resource endpoint layer

**Mục tiêu:** Expose endpoint đọc FHIR đã normalize cho Spring/frontend.

**Hành vi:**
- `api/fhir_routes.py` cung cấp endpoint cho Patient, Observation, Encounter, Condition, MedicationRequest.
- Validate `limit`; trả JSON đã normalize (không trả raw Bundle trực tiếp cho UI).
- Spring proxy `/api/patients/...` qua `ChatbotQueryService`, sau đó mới gọi
  outbound adapter `integration/client/ChatbotServiceClient`.

**Tiêu chí hoàn thành:** Spring proxy được đầy đủ endpoint patient detail.

## M5.4 - Normalization layer

**Mục tiêu:** Chuyển FHIR resource phức tạp thành dữ liệu gọn cho UI/LLM.

**Hành vi:**
- `fhir/normalizer.py` có normalizer riêng cho từng resource, giữ field quan trọng: resource type, id, code, display, date, value, subject.
- Giữ provenance/evidence cần thiết để LLM trích dẫn (evidence refs trong [M3](M3-message-history.md)).

**Tiêu chí hoàn thành:** LLM nhận evidence gọn nhưng đủ để trả lời.

## M5.5 - Seed và kiểm tra dữ liệu demo

**Mục tiêu:** Có dữ liệu demo đa dạng để test.

**Hành vi:**
- Seed transaction Bundle với id cố định (chạy nhiều lần không tạo trùng); script `seed_fhir_data.py`, kiểm tra kết nối bằng `check_connection.py`.
- Trạng thái FHIR kiểm tra qua `GET /fhir/status` (proxy `GET /api/chatbot/status`).

**Tiêu chí hoàn thành:** Demo chạy được sau khi clone repo và seed data.

## Luồng chương trình

```
chatbot-service (answerer / fhir_routes)
        │  gọi tập trung
        ▼
FhirClient._get(path, params)         (httpx.AsyncClient, timeout, Accept: fhir+json)
        ▼
HAPI FHIR JPA Server  (/fhir)  ── PostgreSQL (named volume)
        ▼
Bundle/Resource JSON
        ▼
fhir/normalizer.py  → resource đã normalize (id, code, value, date, subject...)
        ▼
evidence gọn cho LLM / JSON ổn định cho Spring/frontend

Lỗi: 404 → FhirNotFoundError | khác → FhirClientError
     → chat_routes bắt → HTTP 502 cho Spring (M11)
```

## Luồng trong code

- **Client + error handling:** `FhirClient._get()` ([client.py](../chatbot-service/fhir/client.py)); search ([client.py](../chatbot-service/fhir/client.py)).
- **Config base URL/timeout:** [config.py](../chatbot-service/app/config.py).
- **Endpoint layer:** [api/fhir_routes.py](../chatbot-service/api/fhir_routes.py).
- **Normalization:** [fhir/normalizer.py](../chatbot-service/fhir/normalizer.py).
- **Application facade phía Spring:**
  [ChatbotQueryService.java](../backend/src/main/java/com/medicalchatbot/backend/service/ChatbotQueryService.java).
- **HTTP adapter phía Spring:**
  [ChatbotServiceClient.java](../backend/src/main/java/com/medicalchatbot/backend/integration/client/ChatbotServiceClient.java).

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| FHIR client tập trung | `chatbot-service/fhir/client.py` |
| Endpoint đọc FHIR | `chatbot-service/api/fhir_routes.py` |
| Normalization | `chatbot-service/fhir/normalizer.py` |
| Config FHIR | `chatbot-service/app/config.py` |
| Hạ tầng HAPI | `infra/hapi-fhir/` |
| Proxy FHIR (Spring) | `backend/.../service/ChatbotQueryService.java` → `.../integration/client/ChatbotServiceClient.java` |
