# M4 — Medical Intent Classification & Patient Data Query

## Tổng quan

Module M4 chịu trách nhiệm phân loại intent (mục đích) từ câu hỏi tiếng Việt của nhân viên y tế và định tuyến truy vấn đến đúng FHIR resource tương ứng.

**Luồng xử lý:**
```
Câu hỏi (tiếng Việt)
        ↓
  IntentExtractor.extract()
        ↓
    IntentPlan  ──────────────────────────────────────────────────┐
  (tool_name, patient_id,                                         │
   confidence_score, ...)                                         ↓
        ↓                                              chat_routes.py
  FHIR Tool Call                                  định tuyến đến _answer_*()
  (medications / observations /                              hàm tương ứng
   encounters / conditions /
   patient / search)
        ↓
  Dữ liệu FHIR chuẩn hóa
        ↓
  AnswerGenerator → Câu trả lời tiếng Việt
```

---

## Cấu trúc package

```
chatbot-service/agents/
├── intent_extractor.py          ← Shim re-export (backward compat)
└── intent/
    ├── constants.py             ← Tên các FHIR tool constants
    ├── vocabulary.py            ← Danh sách từ khóa y tế tiếng Việt/Anh
    ├── models.py                ← IntentPlan dataclass + IntentExtractor Protocol
    ├── text_utils.py            ← Hàm xử lý văn bản (normalize, extract regex)
    ├── patient_utils.py         ← Phân tích thông tin bệnh nhân từ text
    ├── observation_utils.py     ← Suy luận loại observation (glucose, huyết áp...)
    ├── fhir_tools.py            ← OpenAI function tool definitions
    ├── guardrails.py            ← Hậu xử lý kết quả intent (enforce routing)
    ├── rule_extractor.py        ← RuleBasedIntentExtractor (keyword matching)
    ├── openai_extractor.py      ← OpenAIIntentExtractor (LLM function calling)
    └── factory.py               ← get_intent_extractor() factory
```

---

## Các Intent được hỗ trợ

| Intent | Tool Name | Trigger keywords | FHIR Resource |
|--------|-----------|-----------------|---------------|
| medications | `get_medication_requests` | thuoc, medication, drug | MedicationRequest |
| observations | `get_observations` | chi so, xet nghiem, glucose, huyet ap, spo2, creatinine, alt... | Observation |
| encounters | `get_encounters` | kham, lan kham, nhap vien, cap cuu, tai kham | Encounter |
| conditions | `get_conditions` | chan doan, benh ly, di ung, tien su benh | Condition |
| patient | `get_patient_by_id` | thong tin, benh nhan, so dien thoai | Patient |
| patients | `search_patients` | danh sach, tim kiem + (ten/sdt/ngay sinh) | Patient (search) |
| unknown | `unsupported_question` | (không khớp keyword nào) | — |

---

## IntentPlan

Kết quả của mỗi lần phân loại intent:

```python
@dataclass(frozen=True)
class IntentPlan:
    tool_name: str           # tên FHIR tool sẽ gọi
    patient_id: str          # FHIR patient ID (default: "BN2026-00001")
    search_name: str | None  # tên bệnh nhân trích từ câu hỏi
    search_phone: str | None # số điện thoại trích từ câu hỏi
    search_birth_date: str | None  # ngày sinh (ISO YYYY-MM-DD)
    search_identifier: str | None  # CCCD/CMND/BHYT
    observation_type: str | None   # loại chỉ số (glucose, spo2, ...)
    limit: int               # số kết quả tối đa
    all_patients: bool       # True nếu câu hỏi về tất cả bệnh nhân
    reason: str | None       # lý do (dùng khi unsupported)
    source: str              # "rules" | "llm" | "*_guardrail"
    usage: dict              # token usage (input/output/cost)
```

---

## Hai chế độ extraction

### 1. Rule-based (không cần API key)
**Class:** `RuleBasedIntentExtractor`  
**Cách hoạt động:** Khớp keyword từ `vocabulary.py` theo thứ tự ưu tiên:
1. Medications → Observations → Encounters → Contact → Conditions → Search/List → Patient info → Unsupported

**Khi nào dùng:** `OPENAI_API_KEY` không có hoặc `ENABLE_LLM_ANSWER=false`

### 2. LLM-based (OpenAI function calling)
**Class:** `OpenAIIntentExtractor`  
**Cách hoạt động:**
1. Gửi câu hỏi + FHIR tool definitions lên OpenAI
2. LLM chọn 1 trong 7 tools và cung cấp parameters (patient_id, name, phone, ...)
3. Nếu LLM lỗi → tự động fallback sang RuleBasedIntentExtractor
4. Guardrails chạy sau để đảm bảo routing chính xác

**Khi nào dùng:** `OPENAI_API_KEY` hợp lệ và `ENABLE_LLM_ANSWER=true`

---

## Guardrails

Các hàm hậu xử lý chạy sau khi extractor (cả LLM lẫn rule) trả về kết quả:

| Guardrail | Mục đích |
|-----------|---------|
| `enforce_patient_list_routing` | Câu hỏi "danh sách bệnh nhân" → bắt buộc dùng `search_patients` |
| `enforce_contact_detail_routing` | Câu hỏi số điện thoại → route đúng về `get_patient_by_id` |
| `apply_all_patient_scope` | Đặt `all_patients=True` khi câu hỏi về tất cả bệnh nhân |
| `apply_patient_id_hint` | Sync patient_id từ message hoặc context |
| `apply_patient_search_criteria_hint` | Trích name/phone/birth_date còn thiếu |
| `add_observation_type_hint` | Suy luận loại chỉ số cụ thể (glucose, spo2...) |

---

## Từ vựng y tế (vocabulary.py)

File này có thể chỉnh sửa mà không cần hiểu code logic. Chỉ cần thêm từ vào list thích hợp.

### Các loại observation được nhận biết

| Keyword | observation_type trả về |
|---------|------------------------|
| huyet ap, blood pressure | blood_pressure |
| glucose, duong huyet | glucose |
| nhip tim, heart rate | heart_rate |
| cholesterol | cholesterol |
| hba1c, a1c | hba1c |
| spo2, do bao hoa oxy | spo2 |
| nhip tho, tan so tho | respiratory_rate |
| can nang, weight | weight |
| chieu cao, height | height |
| bmi | bmi |
| creatinine | creatinine |
| ure, urea, bun | urea |
| ferritin | ferritin |
| bilirubin | bilirubin |
| ast, sgot | ast |
| alt, sgpt | alt |
| triglyceride | triglyceride |
| ldl | ldl |
| hdl | hdl |

---

## API Response

`POST /chat` trả về intent metadata:

```json
{
  "answer": "Bệnh nhân đang dùng Metformin 500mg...",
  "intent": "medications",
  "tool_name": "get_medication_requests",
  "intent_source": "rules",
  "patient_id": "BN2026-00001",
  "evidence": [...],
  "usage": {...}
}
```

---

## Chạy tests

```bash
cd chatbot-service

# Test module M4 mới
python -m pytest tests/test_intent_extractor.py -v

# Test toàn bộ (đảm bảo không break code cũ)
python -m pytest tests/ -v
```

---

## Thêm từ khóa mới

Chỉ cần mở [agents/intent/vocabulary.py](../agents/intent/vocabulary.py) và thêm vào list thích hợp:

```python
# Ví dụ: thêm từ khóa bệnh thận
CONDITION_KEYWORDS = [
    ...
    "benh than",       # thêm vào đây
    "suy than",
]
```

Để thêm loại observation mới, mở [agents/intent/observation_utils.py](../agents/intent/observation_utils.py):

```python
def infer_observation_type(message: str) -> str | None:
    ...
    if contains_any(text, ["ure", "urea", "bun"]):
        return "urea"
    # Thêm tại đây:
    if contains_any(text, ["vitamin d"]):
        return "vitamin_d"
```
