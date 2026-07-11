# Terminology Enrichment — Giải thích mã y khoa (LOINC + RxNorm + MedlinePlus)

> Lớp bổ trợ giải thích/metadata y khoa: khi câu hỏi yêu cầu giải thích khái niệm,
> ý nghĩa chỉ số hoặc thông tin định danh thuốc/xét nghiệm, hệ thống tra 3 nguồn
> terminology công khai rồi để LLM tóm tắt sang tiếng Việt. Lớp này là **data-fetch
> thuần (không gọi LLM)**, tách biệt hoàn toàn với FHIR retrieval và answer generation.

## Vấn đề

Trước thay đổi này:

- Chatbot chỉ trả **dữ liệu bệnh nhân có cấu trúc** từ FHIR (giá trị lab, danh sách
  thuốc, chẩn đoán) — không giải thích được "HbA1c 7.2% nghĩa là gì", "Metformin dùng
  để làm gì".
- Câu hỏi khái niệm thuần ("HbA1c là gì?") rơi vào `unsupported_question`.
- Mã chuẩn (LOINC/RxNorm/SNOMED) đã có sẵn trong evidence (`code_detail.coding[]`
  từ normalizer) nhưng không được khai thác.
- Nếu để LLM tự giải thích từ trí nhớ thì vi phạm quy tắc "không bịa ngoài evidence"
  (product-spec §9) — cần một nguồn kiến thức có kiểm soát đưa vào evidence.

## Nguồn dữ liệu

| Nguồn | Endpoint | Auth | Phủ resource | Vai trò |
|---|---|---|---|---|
| **LOINC** | `https://fhir.loinc.org` — FHIR `CodeSystem/$lookup` | Basic Auth (tài khoản loinc.org miễn phí) | Observation | Metadata chuẩn cho xét nghiệm/chỉ số lâm sàng (component, property, scale, method…) |
| **RxNorm** | `https://rxnav.nlm.nih.gov` — REST JSON | **Không cần key** | MedicationRequest | Metadata thuốc: RxCUI, tên chuẩn, term type; tra được theo mã lẫn theo tên (`find_rxcui_by_string`) |
| **MedlinePlus Connect** | `https://connect.medlineplus.gov/service` | **Không cần key** | Observation + MedicationRequest + Condition | Nội dung giáo dục thân thiện người dùng; nhận mã LOINC, RxNorm, NDC, ICD-10-CM, SNOMED CT |

Thiếu `LOINC_USERNAME/PASSWORD` → nhánh LOINC tự bỏ qua (gate `has_credentials`),
2 nguồn còn lại vẫn chạy bình thường.

## Kiến trúc

```text
Spring → chatbot-service POST /chat
  ├─ Intent extractor (LLM tool-calling / rule fallback)
  │    ├─ đặt IntentPlan.explain = true|false        ◄ mấu chốt hành vi có điều kiện
  │    └─ hoặc chọn tool explain_concept (term/code) ◄ câu hỏi khái niệm thuần
  ├─ FHIR retrieval → evidence (normalized, có code_detail.coding[])
  └─ _finalize_chat_response (chat/response_builder.py)
       ├─ plan.explain == false ──► KHÔNG gọi terminology (0 request ngoài)
       ├─ plan.explain == true  ──► enrich_payload(evidence)
       │      │
       │      ▼  terminology/enrichment_service.py
       │    extract_codes_from_payload(evidence)   ── trích mã 1 lần
       │      ├─ _enrich_loinc(codes)        ┐
       │      ├─ _enrich_rxnorm(codes)       ├─ asyncio.gather (SONG SONG)
       │      └─ _enrich_medlineplus(codes)  ┘
       │    mỗi nhánh: gate flag → cache → cap 5 req → try/except → []
       │      ▼
       │    payload["external_knowledge"] = [...]   ── top-level, cạnh evidence
       └─ answer generator (LLM qua LiteLLM gateway):
            prompt nhận external_knowledge → giải thích ngắn gọn bằng TIẾNG VIỆT
```

### Cấu trúc package `chatbot-service/terminology/`

| File | Vai trò |
|---|---|
| `schemas.py` | `TerminologyCode` (mã trích từ evidence), `ExternalKnowledgeItem` (kết quả), enum `TerminologySource`/`KnowledgeType` |
| `code_extractor.py` | Trích mã từ normalized evidence: Observation `code_detail` + `components[].code_detail`, Condition `code_detail`, MedicationRequest `medication_detail`; dedupe |
| `cache.py` | `TerminologyCache` TTL in-process; key `terminology\|source\|type\|system\|code\|text` — **không chứa patient id** → chia sẻ được giữa user (product-spec §8.2) |
| `loinc_client.py` / `loinc_parser.py` | `$lookup` + parse FHIR `Parameters` → display, component, property, scale, method… |
| `rxnorm_client.py` / `rxnorm_parser.py` | `fetch_properties(rxcui)` + `find_rxcui_by_string(name)` (tra theo tên khi thuốc không có mã) |
| `medlineplus_client.py` / `medlineplus_parser.py` | Map hệ mã → OID + parse Atom-like feed (strip HTML, cap 3 entry) |
| `enrichment_service.py` | Điều phối: fan-out `asyncio.gather`, cache, cap request, dedupe chéo nguồn; singleton `get_enrichment_service()` |

### Hai luồng dùng chung một service

1. **Explain dữ liệu bệnh nhân** (`plan.explain=true`): "Chỉ số HbA1c đó nghĩa là gì?"
   → lấy Observation qua FHIR như bình thường → enrich từ mã trong evidence.
2. **Explain khái niệm thuần** (tool `explain_concept`): "HbA1c là gì?", "Metformin
   dùng để làm gì?" → không truy FHIR; answerer (`_answer_explain_concept` trong
   `chat/resource_answerers.py`) dựng evidence tổng hợp từ `term`/`code` rồi gọi cùng
   enrichment service. `evidence` trả về rỗng (không có dữ liệu bệnh nhân),
   `external_knowledge` mang kết quả tra cứu. Không cần `patient_id`, không qua
   role-gating `FHIR_PROTECTED_TOOLS`.

### Cờ `explain` được đặt như thế nào

- **LLM extractor** (`agents/intent/llm_extractor.py`): các tool dữ liệu
  (observations/conditions/medications/resource + biến thể all-patient) có thêm param
  `explain: boolean`; system prompt hướng dẫn đặt `true` khi user hỏi ý nghĩa/giải
  thích/định nghĩa/công dụng, `false` khi chỉ hỏi giá trị/ngày/danh sách/liều.
- **Rule fallback** (`agents/intent/rule_extractor.py`): keyword
  `EXPLAIN_KEYWORDS` trong `vocabulary.py` — "la gi", "y nghia", "giai thich",
  "dung de lam gi", "cong dung", "tac dung", "noi len dieu gi"…
- Tool `explain_concept` luôn có `explain=true`.

## Ví dụ hành vi

| Câu hỏi | explain | Gọi terminology? | Câu trả lời |
|---|---|---|---|
| "HbA1c mới nhất của BN001 là bao nhiêu?" | false | ❌ Không | Chỉ số liệu: "…bản ghi HbA1c mới nhất (2026-05-24) là 7.2%." |
| "Chỉ số HbA1c đó nghĩa là gì?" | true | ✅ Có | Số liệu + giải thích: "HbA1c (LOINC 4548-4) phản ánh đường huyết trung bình 2–3 tháng… Giá trị 7.2% của bệnh nhân…" |
| "HbA1c là gì?" | explain_concept | ✅ Có (không cần FHIR) | Giải thích khái niệm thuần bằng tiếng Việt |
| "Metformin dùng để làm gì?" | explain_concept | ✅ Có (RxNorm tra theo tên) | Metadata thuốc + nội dung MedlinePlus, tóm tắt tiếng Việt |

## Quyết định thiết kế

| Quyết định | Lý do |
|---|---|
| Enrich **có điều kiện** theo `plan.explain`, không nhồi vào mọi câu trả lời | Câu hỏi dữ liệu thuần không tốn request ngoài + token prompt; câu trả lời gọn; giữ mục tiêu latency/cost của đề tài |
| Code thuần + `asyncio.gather`, **không LangGraph** | Chỉ là trích mã → fetch → gộp: không có nhánh phức tạp/persistence/state chia sẻ. Nhất quán với việc summary generator cũng đã gỡ LangGraph về async thuần |
| `external_knowledge` gắn **top-level** cạnh `evidence`, không lồng vào `data` | Tránh bị `compact_resource_data` (key whitelist trong answer_generator) strip mất; tách bạch "dữ liệu bệnh nhân" vs "kiến thức chung" |
| Cache global theo mã, TTL dài, **không scope user/patient** | Định nghĩa LOINC/RxNorm là kiến thức chung không nhạy cảm (product-spec §8.2); LOINC 30 ngày, RxNorm 1 ngày, mặc định 12h |
| Mỗi nguồn 1 flag riêng + gate `has_credentials` cho LOINC | Bật/tắt độc lập; thiếu key LOINC không làm chết 2 nguồn kia |
| Mỗi fetch `try/except → []`, gather với `return_exceptions=True` | Terminology là việc phụ — một nguồn sập không được làm hỏng câu trả lời chính (không 5xx) |
| Cap 5 request/nguồn/payload + cap 8 item, 600 ký tự/summary vào prompt | Chặn phình token khi evidence nhiều mã |
| Client fetch HTTP thuần, **không qua LiteLLM gateway** | Gateway chỉ dành cho LLM; call LLM answer (đã kèm external_knowledge) vẫn qua `gateway_call_kwargs()` → giữ budget per-user |
| Nội dung nguồn là tiếng Anh → LLM answer generator dịch/tóm tắt | MedlinePlus chỉ có EN/ES; system prompt đã dặn "tóm tắt sang tiếng Việt, không bịa ngoài nội dung được cung cấp" |

### Trade-off đã chấp nhận

- **Enrichment chạy tuần tự trước answer generation** (không song song như summary)
  vì answer cần `external_knowledge` trong prompt → cộng thêm latency ~1 round-trip
  HTTP khi `explain=true` (cache hit thì ≈ 0). Chấp nhận vì chỉ xảy ra với câu hỏi
  giải thích.
- **Câu hỏi khái niệm lab theo tên tự do** ("xét nghiệm creatinine là gì" — không có
  mã) chưa tra được LOINC (cần `$expand`); hiện chỉ thuốc tra được theo tên qua
  RxNorm `find_rxcui_by_string`. MedlinePlus vẫn có thể khớp theo display name.
- Cache in-process (mất khi restart, không chia sẻ giữa worker) — đủ cho demo;
  Redis là mở rộng sau.

## Cấu hình

`chatbot-service/.env` (xem `.env.example`):

```env
# Terminology enrichment
LOINC_ENABLED=true
RXNORM_ENABLED=true
MEDLINEPLUS_ENABLED=true
LOINC_USERNAME=...        # tài khoản loinc.org miễn phí; để trống -> LOINC tự bỏ qua
LOINC_PASSWORD=...
TERMINOLOGY_TIMEOUT_SECONDS=5
# TTL cache (giây) — mặc định trong app/config.py:
# TERMINOLOGY_CACHE_TTL_SECONDS=43200   (12h — MedlinePlus)
# RXNORM_CACHE_TTL_SECONDS=86400        (1 ngày)
# LOINC_CACHE_TTL_SECONDS=2592000       (30 ngày)
```

## Response shape

Khi enrich chạy và có kết quả, response `/chat` có thêm trường top-level:

```json
{
  "answer": "…giải thích tiếng Việt…",
  "evidence": [ { "resource_type": "Observation", "id": "OBS-...", "data": { … } } ],
  "external_knowledge": [
    {
      "source": "LOINC",
      "type": "lab_test",
      "system": "http://loinc.org",
      "code": "4548-4",
      "display": "Hemoglobin A1c/Hemoglobin.total in Blood",
      "summary": "LOINC term: …; component: …; scale: …",
      "url": null,
      "fields": { "component": "…", "scale": "…" }
    },
    { "source": "MedlinePlus", "type": "lab_test", "summary": "…", "url": "https://medlineplus.gov/…" }
  ]
}
```

`intent` mới: `explain_concept`. Cờ `explain` không xuất hiện trong response — nó là
thuộc tính của `IntentPlan` nội bộ.

## Kiểm thử

### Unit (không cần mạng — mock `httpx.MockTransport`)

```powershell
cd chatbot-service
python -m unittest discover tests    # 214 tests
```

| File | Phủ |
|---|---|
| `tests/test_terminology_foundation.py` | Cache TTL/key không chứa patient; code extractor 3 loại resource + text-only + rỗng; 3 parser; `as_dict` |
| `tests/test_terminology_enrichment.py` | Fan-out đủ 3 nguồn; LOINC thiếu creds → skip nhưng RxNorm/MedlinePlus vẫn chạy; cache hit không gọi HTTP lần 2; một nguồn lỗi các nguồn khác vẫn trả; flags off → rỗng |
| `tests/test_terminology_wiring.py` | `explain=false` → không gọi service; `explain=true` → enrich; answerer `explain_concept` gắn `external_knowledge` + fallback khi không tra được |

### End-to-end (cần stack chạy: `.\run-dev.ps1`)

```powershell
# 1. data_only — KHÔNG enrich, không có external_knowledge
POST /chat  "HbA1c mới nhất của bệnh nhân BN2026-00001 là bao nhiêu?"
# expect: answer chỉ số liệu, response KHÔNG có external_knowledge

# 2. explain dữ liệu bệnh nhân — CÓ enrich
POST /chat  "Chỉ số HbA1c của bệnh nhân BN2026-00001 nghĩa là gì?"
# expect: external_knowledge có source LOINC/MedlinePlus, answer tiếng Việt kèm giải thích

# 3. explain khái niệm thuần
POST /chat  "Thuốc Metformin dùng để làm gì?"
# expect: intent=explain_concept, external_knowledge từ RxNorm/MedlinePlus, evidence rỗng

# 4. degrade — đổi LOINC_PASSWORD sai / tắt mạng nguồn
# expect: chat vẫn trả lời bình thường (thiếu phần giải thích), không 5xx
```

## Mở rộng sau

- LOINC `$expand` để tra mã lab từ tên tự do ("creatinine" → 2160-0).
- Persist cache qua Redis (chia sẻ giữa worker, sống qua restart).
- Ngôn ngữ `es` cho MedlinePlus nếu cần (client đã hỗ trợ param language).
