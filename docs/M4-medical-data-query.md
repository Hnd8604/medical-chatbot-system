# M4. Medical Data Query

Phân loại ý định (intent) câu hỏi y tế và tra cứu dữ liệu bệnh nhân từ FHIR theo từng loại resource, kèm kiểm soát quyền truy cập. Đây là lõi nghiệp vụ phía chatbot-service, được gọi từ [M2](M2-conversation-management.md) và dựa trên [M5 (FHIR)](M5-fhir-integration.md), [M6 (AI)](M6-ai-integration.md).

## M4.1 - Phân loại intent y tế

**Mục tiêu:** Xác định user đang hỏi loại dữ liệu y tế nào.

**Hành vi:**
- `IntentExtractor.extract()` phân câu hỏi ra `IntentPlan` với `tool_name` ổn định: `search_patients`, `get_patient_by_id`, `get_encounters`, `get_medication_requests`, `get_observations`, `get_conditions`.
- Hỗ trợ tiếng Việt **có dấu và không dấu** (rule extractor + vocabulary).
- **Fallback khi LLM không khả dụng:** có `rule_extractor` chạy độc lập với `openai_extractor` (xem [M6.5](M6-ai-integration.md)).

**Tiêu chí hoàn thành:** Câu hỏi phổ biến route đúng tool.

## M4.2 - Tra cứu thông tin bệnh nhân

**Mục tiêu:** Lấy thông tin hành chính của bệnh nhân.

**Hành vi:**
- Tool `search_patients` (theo tên/phone/birth date/identifier) và `get_patient_by_id` (theo FHIR id).
- Normalize Patient gồm id, tên, giới tính, ngày sinh, phone, identifier (`fhir/normalizer.py`).
- Xử lý nhiều kết quả: trả `needs_patient_selection` + `patient_candidates` để UI hiển thị candidate cards (`_patient_selection_payload`).

**Tiêu chí hoàn thành:** Staff tìm và chọn đúng bệnh nhân.

## M4.3 - Tra cứu lịch sử khám

- Tool `get_encounters` dùng FHIR `Encounter`, có `limit`, sort mới nhất trước (`_sort=-date`).
- Normalize: id, status, class, period, reason, provider/location.
- **Tiêu chí:** hỏi "lần khám gần nhất" trả đúng encounter gần nhất.

## M4.4 - Tra cứu thuốc

- Tool `get_medication_requests` dùng FHIR `MedicationRequest`.
- Hiển thị tên thuốc, liều, trạng thái, thời gian, lý do.
- Answer generator nói rõ "theo dữ liệu FHIR hiện có" (system prompt — [M6.3](M6-ai-integration.md)).

## M4.5 - Tra cứu chỉ số/xét nghiệm

- Tool `get_observations` dùng FHIR `Observation`; hỗ trợ huyết áp, glucose, nhịp tim và observation chung (`observation_utils.py`).
- Normalize: code/display, effective time, value, unit, components, interpretation, reference range.
- Hỗ trợ câu hỏi nối tiếp "chỉ số đó có cao không?" qua `last_resource_id`/context ([M15](M15-context-management.md)).
- **Tiêu chí:** hỏi huyết áp gần nhất trả systolic/diastolic + ngày.

## M4.6 - Tra cứu chẩn đoán/bệnh lý

- Tool `get_conditions` dùng FHIR `Condition`.
- Normalize: tên chẩn đoán, clinical status, verification status, onset, recorded date.
- **Không tự kết luận bệnh** ngoài dữ liệu ghi nhận (ràng buộc trong prompt answer generator).

## M4.7 - Kiểm soát quyền truy cập dữ liệu y tế

**Mục tiêu:** Chỉ user có quyền mới xem dữ liệu bệnh nhân.

**Hành vi (hai lớp):**
1. **Spring backend** (`UserPatientScopeService`): trước khi gọi chatbot-service, resolve `patientScope` + `allowedPatientIds` theo role/link bệnh nhân (xem [M2](M2-conversation-management.md)).
2. **Chatbot-service** (`_apply_user_patient_scope` + `_ensure_role_can_access_plan`): nếu `user_role == "USER"`, ép `patient_id` thuộc `allowed_patient_ids`, cấm `search_patients`/`all_patients`, ném `403` nếu vượt quyền.
- Mọi lần xem dữ liệu bệnh nhân đều ghi `AuditLog` (action `VIEW_*`) — xem [M18](M18-M19-audit-alert.md).

**Tiêu chí hoàn thành:** User không có quyền không xem được patient data.

## Luồng chương trình

```
Câu hỏi tự nhiên (từ /chat của chatbot-service)
        ▼
IntentExtractor.extract() → IntentPlan { tool_name, patient_id, observation_type,
                                         all_patients, limit }
        ▼
Áp policy quyền: _apply_user_patient_scope / _ensure_role_can_access_plan
        ▼
Dispatch theo tool_name:
   search_patients        → _answer_patients
   get_patient_by_id      → _answer_patient
   get_encounters         → _answer_encounters       ┐
   get_medication_requests→ _answer_medications      │ resolve patient_id;
   get_observations       → _answer_observations     │ nếu mơ hồ → needs_patient_selection
   get_conditions         → _answer_conditions       ┘
        ▼ (mỗi answerer gọi FhirClient → normalize → evidence)
_finalize_chat_response → AnswerGenerator (LLM) sinh câu trả lời tiếng Việt + usage
```

## Luồng trong code

- **Intent:** `IntentExtractor.extract()` ([intent_extractor.py](chatbot-service/agents/intent_extractor.py)), rule vs LLM ([agents/intent/rule_extractor.py](chatbot-service/agents/intent/rule_extractor.py), [openai_extractor.py](chatbot-service/agents/intent/openai_extractor.py)), tool names ([constants.py](chatbot-service/agents/intent/constants.py)).
- **Dispatch:** [chat_routes.py:209-303](chatbot-service/api/chat_routes.py#L209-L303).
- **Answerers + resolve patient:** [resource_answerers.py](chatbot-service/chat/resource_answerers.py) (`_resolve_patient_id_for_tool`, `_patient_selection_payload`).
- **Normalize:** [fhir/normalizer.py](chatbot-service/fhir/normalizer.py).
- **Quyền truy cập:** [chat_routes.py:103-161](chatbot-service/api/chat_routes.py#L103-L161); phía Spring [UserPatientScopeService.java](spring-backend/src/main/java/com/medicalchatbot/backend/service/UserPatientScopeService.java).

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| Intent extraction | `chatbot-service/agents/intent_extractor.py`, `agents/intent/*` |
| Dispatch tool | `chatbot-service/api/chat_routes.py` |
| Answerers theo resource | `chatbot-service/chat/resource_answerers.py` |
| Normalize FHIR | `chatbot-service/fhir/normalizer.py` |
| Quyền truy cập (Spring) | `spring-backend/.../service/UserPatientScopeService.java` |
