# Chatbot agent — cách hoạt động

Tài liệu mô tả pipeline trả lời câu hỏi của `chatbot-service` sau khi tái cấu trúc:
chỉ còn **một đường duy nhất** là `POST /chat`, chạy bằng LangGraph.

Mục lục:

1. [Tổng quan](#1-tổng-quan)
2. [Đi qua một lượt chat](#2-đi-qua-một-lượt-chat)
3. [Từng bước trong graph](#3-từng-bước-trong-graph)
4. [Phân quyền theo role](#4-phân-quyền-theo-role)
5. [Kiểm soát chi phí LLM](#5-kiểm-soát-chi-phí-llm)
6. [Các lớp an toàn dữ liệu](#6-các-lớp-an-toàn-dữ-liệu)
7. [Contract request/response](#7-contract-requestresponse)
8. [Cấu trúc thư mục](#8-cấu-trúc-thư-mục)
9. [Cấu hình `.env`](#9-cấu-hình-env)
10. [Test và đo chất lượng](#10-test-và-đo-chất-lượng)
11. [Giới hạn đã biết](#11-giới-hạn-đã-biết)

---

## 1. Tổng quan

Một câu hỏi đi qua các bước sau:

```text
prepare ─ cache_lookup ──hit────────────────────────────────────────> END  (0 LLM call)
             │miss
             └─ route ──┬─ general_chat ──────┐
                        ├─ conversation_meta ─┼────────────────────> END
                        ├─ unsupported ───────┘
                        └─ plan ──┬─ (không lập được plan) ─ clarify ─> END
                                  └─ validate ──┬─ (plan hỏng) ─ clarify ─> END
                                                └─ execute ─ respond ────> END

Song song từ đầu lượt: rolling summary (tóm tắt hội thoại), chờ ở bước cuối.
```

Ý tưởng chính: **LLM chỉ làm hai việc cần "hiểu ngôn ngữ"**:

- **Hiểu câu hỏi**: router chọn loại câu hỏi, planner lập kế hoạch gọi tool nào.
- **Viết câu trả lời** từ dữ liệu đã lấy.

Mọi việc còn lại đều do code tất định làm: kiểm quyền, gọi FHIR, chuẩn hoá dữ liệu, cắt
bớt evidence, soát câu trả lời, tính chi phí. Nhờ vậy:

- LLM **không thể vượt quyền**: kế hoạch của LLM luôn bị validator kiểm lại.
- LLM **không chạm FHIR trực tiếp**: chỉ code gọi FHIR REST, LLM chỉ thấy dữ liệu đã chuẩn hoá.
- Khi LLM **hỏng hoặc hết tiền**, bot vẫn chạy được: router lỗi thì mặc định nhánh FHIR,
  planner lỗi thì dùng rule planner, answer lỗi thì dùng câu template.

---

## 2. Đi qua một lượt chat

Bác sĩ hỏi: **"Bệnh nhân BN2026-00001 có chẩn đoán và đang dùng thuốc gì?"**

| Bước | Việc xảy ra | LLM? |
|---|---|---|
| `prepare` | Tìm thấy mã `BN2026-00001` trong câu, đặt làm bệnh nhân của lượt | Không |
| `cache_lookup` | Tìm câu hỏi tương tự của chính user này, cùng bệnh nhân, trong 5 phút gần nhất. Giả sử miss | Không |
| `route` | Plan cache miss. Router LLM trả `{"route": "fhir"}` | 1 call (model rẻ) |
| `plan` | Planner LLM trả 2 step: `get_conditions` + `get_medication_requests` | 1 call |
| `validate` | Bỏ tham số lạ, giới hạn `limit`, kiểm quyền role DOCTOR → hợp lệ | Không |
| `execute` | Gọi 2 tool **song song** lên HAPI FHIR, gộp evidence | Không |
| `respond` | Chọn model (2 step ⇒ `model_complex`). LLM viết câu trả lời → grounding check → ghi cache | 1 call |

Kết quả là 3 LLM call. Nếu hỏi lại câu tương tự trong 5 phút, cache trả ngay với **0 call**.
Nếu người khác hỏi câu cùng dạng ("thuốc và chẩn đoán của BN2026-00009"), plan cache bỏ
qua router và planner, chỉ còn **1 call** cho bước viết câu trả lời.

---

## 3. Từng bước trong graph

Mỗi node nằm trong một file ở [agent/nodes/](../agent/nodes/). Dependency của request
(FhirClient, answer generator, cache, summary task) không nằm trong state mà đi qua
`config["configurable"]["deps"]` (xem [agent/state.py](../agent/state.py)).

### 3.1 `prepare` — chuẩn hoá đầu vào

[prepare.py](../agent/nodes/prepare.py). Không gọi LLM, không I/O.

- Chuẩn hoá `allowed_patient_ids` (danh sách hồ sơ USER được phép xem, Spring gửi sang).
- Xác định **bệnh nhân của lượt** (`provided_patient_id`), ưu tiên theo thứ tự:
  1. `patient_id` Spring gửi (bệnh nhân đang chọn trên giao diện);
  2. mã bệnh nhân viết trong câu hỏi;
  3. bệnh nhân đang nói tới trong hội thoại (`conversation_context.active_patient_id`);
  4. với USER: hồ sơ đầu tiên trong `allowed_patient_ids`.
- Rút gọn `conversation_context` (tóm tắt + vài tin nhắn gần nhất) để đưa vào prompt.
- Bật **low-cost mode** nếu `quota_used_ratio >= LOW_COST_MODE_RATIO` (mặc định 0.9).

### 3.2 `cache_lookup` — semantic cache câu trả lời

[cache_lookup.py](../agent/nodes/cache_lookup.py), dữ liệu ở Qdrant
([cache/semantic_cache.py](../cache/semantic_cache.py)).

- Khoá cache gồm `user_id` + bệnh nhân + câu hỏi (so bằng embedding, ngưỡng giống 0.94).
- TTL ngắn (300 giây) vì câu trả lời chứa dữ liệu bệnh nhân.
- **Hit** ⇒ trả luôn, huỷ rolling summary, `usage = 0`, kèm `saved_usage` (token đã tiết kiệm).
- Lỗi Qdrant ⇒ coi như miss, không làm hỏng lượt chat.

### 3.3 `route` — chọn loại câu hỏi

[route.py](../agent/nodes/route.py) + [planning/router.py](../agent/planning/router.py).
Thử từ rẻ tới đắt:

1. **Plan cache** (xem 5.2). Hit thì có luôn cả route lẫn plan, bỏ qua router và planner.
2. **Low-cost mode**: không gọi LLM, mặc định route `fhir`.
3. **Router LLM** (model rẻ), có LRU cache trong tiến trình (512 mục) theo câu hỏi đã
   bỏ dấu + hình thái ngữ cảnh.

Bốn route:

| Route | Khi nào | Đi tiếp |
|---|---|---|
| `fhir` | Cần đọc hồ sơ, hỏi khái niệm y khoa, hỏi trạng thái FHIR | `plan` |
| `general_chat` | Chào hỏi, cảm ơn, hỏi bot làm được gì | Câu trả lời ngắn từ LLM |
| `conversation_meta` | "Tóm tắt cuộc trò chuyện", "đang nói về bệnh nhân nào" | Trả lời từ ngữ cảnh hội thoại |
| `unsupported` | Đòi AI tự chẩn đoán, kê thuốc, đổi liều, tư vấn điều trị | Từ chối lịch sự |

Router lỗi ⇒ mặc định `fhir`. Đây là lựa chọn an toàn vì validator vẫn kiểm quyền phía
sau, còn nếu mặc định `general_chat`/`unsupported` thì câu hỏi dữ liệu thật sẽ bị nuốt mất.

Ba nhánh không-FHIR nằm trong [simple.py](../agent/nodes/simple.py). Mỗi nhánh là một LLM
call ngắn. LLM lỗi thì dùng câu template cố định, không bao giờ trả 503.

### 3.4 `plan` — lập kế hoạch

[plan.py](../agent/nodes/plan.py). Kế hoạch (`Plan`) là danh sách step, mỗi step gọi một
tool với tham số, ví dụ:

```json
{
  "answer_mode": "data_only",
  "steps": [
    {"id": "resolve_patient", "tool": "search_patients", "args": {"name": "Nguyễn Văn A"}},
    {"id": "m", "tool": "get_medication_requests", "args": {"patient_id": "$resolve_patient.patient_id"}}
  ]
}
```

`$resolve_patient.patient_id` nghĩa là "lấy patient_id từ kết quả step `resolve_patient`".

Ba nguồn plan:

| Nguồn | `intent_source` | Khi nào |
|---|---|---|
| Plan cache | `agent_plan_cache` | Đã có plan cho câu cùng dạng |
| LLM planner | `agent_llm` | Bình thường |
| Rule planner | `agent_rule_fallback` | Không có gateway, planner LLM lỗi/trả JSON hỏng, hoặc low-cost mode |

[Rule planner](../agent/planning/rule_planner.py) dựa trên từ khoá tiếng Việt (có dấu và
không dấu). Không nhận ra câu hỏi ⇒ không có plan ⇒ `clarify` hỏi lại người dùng, thay vì
đoán bừa một truy vấn.

Danh mục tool ở [tools/registry.py](../tools/registry.py). Đây là nguồn sự thật duy nhất:
prompt planner tự sinh danh mục từ registry, nên thêm tool không cần sửa prompt.

| Tool | Việc |
|---|---|
| `fhir_status` | Kiểm tra HAPI FHIR còn sống |
| `search_patients` | Tìm bệnh nhân theo tên / SĐT / ngày sinh / mã định danh |
| `get_patient_by_id` | Hồ sơ hành chính của một bệnh nhân |
| `get_resource_by_id` | Đọc một resource theo `ResourceType/id` |
| `get_observations` / `get_encounters` / `get_conditions` / `get_medication_requests` | Dữ liệu của **một** bệnh nhân |
| `get_all_patient_*` (4 tool) | Dữ liệu của **nhiều** bệnh nhân |
| `explain_concept` | Giải thích khái niệm y khoa (LOINC / RxNorm / MedlinePlus) |

### 3.5 `validate` — kiểm kế hoạch

[validate.py](../agent/nodes/validate.py) + [planning/validator.py](../agent/planning/validator.py).
Thuần, không I/O. Áp dụng cho **mọi** plan, kể cả plan lấy từ cache hay rule planner.

- Tool phải có trong registry.
- Bỏ tham số lạ mà tool không khai báo.
- Giới hạn `limit` (mặc định 5, tối đa 20).
- Cắt plan còn tối đa `MAX_PLAN_STEPS` (mặc định 4).
- Bỏ tham chiếu `$step.field` trỏ vào step không tồn tại (sau đó điền `patient_id` mặc
  định; vẫn thiếu thì executor hỏi lại).
- Điền `patient_id` mặc định nếu thiếu.
- Áp chính sách role (mục 4).

Kết quả:

- Vi phạm quyền ⇒ `PolicyError` ⇒ **HTTP 403**.
- Plan hỏng (tool lạ, `get_resource_by_id` thiếu tham số…) ⇒ graph rẽ sang `clarify`,
  trả `clarification_needed` (HTTP 200) với lý do trong `answer_reason`. Plan hỏng là lỗi
  của hệ thống, không phải của người hỏi, nên không trả 400.

### 3.6 `execute` — chạy kế hoạch

[execute.py](../agent/nodes/execute.py) + [execution/executor.py](../agent/execution/executor.py).

1. **Chốt bệnh nhân trước.** Chạy `search_patients` trước tiên:
   - nhiều bệnh nhân khớp ⇒ dừng, trả `patient_selection_needed` kèm `patient_candidates`
     để người dùng chọn, **chưa đọc dữ liệu bệnh nhân nào**;
   - không ai khớp ⇒ hỏi lại.
2. **Bơm `patient_id` đã chốt** vào các step còn lại, rồi **kiểm quyền lần hai**, vì
   validator không thấy được giá trị đến từ `$resolve_patient.patient_id`.
3. **Chạy song song** các step độc lập (`asyncio.gather`).
4. **Gộp kết quả**: nối evidence, ghép câu template của từng step.

**Lỗi từng phần (partial):**

| Tình huống | `response_status` |
|---|---|
| Mọi step chạy được | `answered` (hoặc `no_data` nếu không có dữ liệu) |
| Một số step lỗi FHIR, còn ít nhất 1 step chạy được | `partial` + `failed_steps` |
| Mọi step đều lỗi | `tool_error` |

Với `partial`, bot trả lời từ phần dữ liệu lấy được và nối thêm câu cố định, ví dụ:
*"Lưu ý: chưa lấy được dữ liệu đơn thuốc do lỗi hệ thống; vui lòng thử lại sau."*
Câu trả lời partial **không được ghi** vào semantic cache lẫn plan cache.

Plan do LLM lập chỉ được ghi vào plan cache khi lượt chạy ra `answered` hoặc `no_data`,
để không nhân bản plan hỏng.

### 3.7 `respond` — viết câu trả lời

[respond.py](../agent/nodes/respond.py) + [answer/finalize.py](../agent/answer/finalize.py).
Thứ tự trong `finalize_answer`:

1. **Chọn model** ([llm/model_select.py](../llm/model_select.py)), tất định, không tốn call:
   - nhiều step, hoặc cần giải thích, hoặc dữ liệu nhiều bệnh nhân ⇒ `model_complex`;
   - còn lại ⇒ `model_simple`;
   - quota đã dùng > 80% ⇒ hạ xuống `model_simple`.
2. **Evidence budget**: cắt evidence xuống dưới `EVIDENCE_MAX_CHARS`. Bỏ trường phụ trước,
   rồi mới bỏ bản ghi cũ nhất. Chỉ bớt, không viết thêm gì.
3. **Terminology**: chỉ khi câu hỏi mang ý "giải thích / ý nghĩa" thì mới gọi
   LOINC / RxNorm / MedlinePlus để lấy `external_knowledge`.
4. **Sinh câu trả lời**: LLM viết từ evidence. LLM lỗi hoặc không có evidence ⇒ dùng câu
   template của tool.
5. **Grounding check** (mục 6.2).
6. **Ghi semantic cache**: chỉ khi câu do LLM viết, đã qua grounding, và không phải partial.
7. **Chờ rolling summary** (đã chạy song song từ đầu lượt).
8. **Tính usage / cost**, dựng `memory_update` cho Spring lưu vào session.

### 3.8 Rolling summary

[memory/summary.py](../agent/memory/summary.py). Khi hội thoại đủ dài
(`total_message_count >= SUMMARY_TRIGGER_MESSAGE_COUNT`, mặc định 8), một LLM call rẻ gộp
tóm tắt cũ + tin nhắn gần đây thành tóm tắt mới. Call này chạy **song song** với cả lượt
nên không làm chậm câu trả lời. Kết quả nằm trong `memory_update.summary`. Cache hit, hỏi
chọn bệnh nhân hoặc lỗi thì summary bị huỷ.

---

## 4. Phân quyền theo role

Spring xác thực người dùng và gửi `user_role` + `allowed_patient_ids`. Chatbot enforce ở
**hai chỗ**: validator (trước khi chạy) và executor (sau khi resolve bệnh nhân / fetch resource).

| Hành động | USER | DOCTOR / ADMIN |
|---|---|---|
| Xem hồ sơ bệnh nhân trong `allowed_patient_ids` | Được | Được |
| Xem hồ sơ bệnh nhân khác | **403** | Được |
| `search_patients`, tool `get_all_patient_*` | **403** | Được |
| `get_resource_by_id` | **403** (trừ khi `ALLOW_USER_RESOURCE_LOOKUP=true`, khi đó kiểm chủ sở hữu sau khi fetch) | Được |
| `fhir_status`, `explain_concept` | Được | Được |
| USER chưa liên kết hồ sơ nào | **403** | — |

Plan lấy từ cache hay rule planner vẫn qua validator, nên **không có đường vòng** nào qua
chính sách role.

---

## 5. Kiểm soát chi phí LLM

### 5.1 Số LLM call mỗi lượt

| Tình huống | Router | Planner | Answer | Tổng |
|---|---|---|---|---|
| Semantic cache hit | – | – | – | **0** |
| Plan cache hit | – | – | 1 | **1** |
| Low-cost mode (quota ≥ 90%) | – | – (rule) | 1 | **1** |
| Câu FHIR bình thường | 1 | 1 | 1 | **3** |
| Template fast-path (nếu bật) | 1 | 1 | – | **2** |
| `general_chat` / `unsupported` / `conversation_meta` | 1 | – | 1 (chat) | **2** |
| Không có `LITELLM_MASTER_KEY` | – | – (rule) | – (template) | **0** |

Rolling summary cộng thêm 1 call rẻ khi hội thoại đủ dài.

### 5.2 Hai tầng cache

| | Semantic cache | Plan cache |
|---|---|---|
| Lưu gì | Câu hỏi → **câu trả lời** | Câu hỏi → **kế hoạch** (gọi tool nào) |
| Chứa dữ liệu bệnh nhân? | Có | **Không**: mọi định danh thay bằng placeholder |
| Phạm vi | Từng user + từng bệnh nhân | Dùng chung mọi user, tách theo role |
| TTL | 300 giây | 1 ngày |
| Hit bỏ được | Toàn bộ lượt | Router + planner (vẫn gọi FHIR ⇒ dữ liệu luôn mới) |

Plan cache khi đọc ra phải **điền lại định danh** (mã bệnh nhân, tên, mã resource) từ câu
hỏi hiện tại. Điền không được ⇒ coi như miss. Khoá có kèm "hình thái ngữ cảnh" (đang có
bệnh nhân hoạt động hay không), vì "bệnh nhân này dùng thuốc gì" cho plan khác nhau tuỳ ngữ cảnh.

### 5.3 Các cơ chế khác

- **Model theo stage**: router/chat/summary dùng model rẻ, planner dùng `model_complex`,
  answer chọn theo độ phức tạp của plan.
- **Evidence budget**: giới hạn token đầu vào của bước answer, khoản tốn nhất mỗi lượt.
- **LiteLLM gateway**: mọi call đi qua [llm/client.py](../llm/client.py) với virtual key
  của user và `metadata.stage`. Spend log ở gateway tách được chi phí theo từng stage.
  Vượt budget ⇒ **HTTP 429**.
- `stage_usage` trong response cho biết token của từng stage trong lượt.

---

## 6. Các lớp an toàn dữ liệu

### 6.1 Tóm tắt

| Lớp | Chống lại |
|---|---|
| Validator + kiểm quyền runtime | LLM lập plan vượt quyền |
| Chỉ gọi FHIR qua REST, LLM chỉ thấy evidence đã chuẩn hoá | LLM truy cập dữ liệu ngoài phạm vi |
| Chốt bệnh nhân trước khi đọc dữ liệu | Đọc nhầm hồ sơ khi tên trùng |
| Grounding check | LLM bịa số, ngày, mã |
| Không cache partial / câu chưa qua grounding | Nhân bản câu sai qua cache |
| Plan cache không chứa định danh | Lộ PHI qua cache dùng chung |
| `plan_steps` trong response không có `args` | Lộ định danh qua log/response |
| Prompt router/planner coi `context` là dữ liệu, không phải mệnh lệnh | Prompt injection qua lịch sử chat |

### 6.2 Grounding check

[answer/grounding.py](../agent/answer/grounding.py). Code thuần, không gọi LLM.

**Vấn đề:** LLM viết câu trả lời có thể bịa một con số hay ngày tháng không có trong dữ
liệu, và người dùng không phân biệt được.

**Cách làm:** sau khi LLM viết xong, dò từng dữ kiện "cứng" trong câu trả lời:

| Loại | Đạt khi |
|---|---|
| Mã bệnh nhân (`BN2026-00001`) | Có trong evidence / câu hỏi / là bệnh nhân của lượt |
| Tham chiếu resource (`Observation/obs-1`) | id có trong evidence |
| Ngày (`05/03/2024`) | Evidence có ngày tương ứng (`2024-03-05`) |
| Số thập phân, hoặc số ≥ 10 | Evidence có số khớp |

Nguồn hợp lệ gồm evidence, `external_knowledge`, câu hỏi của người dùng, và câu template
mà tool dựng từ chính evidence.

**Chuẩn hoá để không báo nhầm:**

- `7,2` khớp `7.2` (dấu thập phân kiểu Việt);
- `05/03/2024` khớp `2024-03-05`; "năm 2024", "tháng 3" khớp phần của ngày có thật;
- làm tròn: evidence `7.25`, câu trả lời `7,3` vẫn đạt;
- tuổi: evidence có `birth_date`, "45 tuổi" vẫn đạt (cho phép lệch ±1);
- số nguyên < 10 không kiểm (thường là số đếm: "3 thuốc", "2 lần khám"); giờ (`08:30`) bỏ qua.

**Ví dụ.** Evidence: glucose `7.25 mmol/L`, đo `2024-03-05`, bệnh nhân `BN2026-00001`.

| Câu LLM viết | Kết quả |
|---|---|
| "BN2026-00001 có glucose 7,3 mmol/L, đo ngày 05/03/2024." | Đạt |
| "BN2026-00001 có glucose **9,8** mmol/L, đo ngày **06/03/2024**." | Không đạt (số + ngày) |

**Khi không đạt**, tuỳ `GROUNDING_CHECK_MODE`:

| Mode | Hành vi |
|---|---|
| `enforce` (mặc định) | Thay bằng câu template (luôn đúng dữ liệu), `answer_source = "template_grounding_fallback"`, không ghi cache |
| `log` | Giữ câu LLM, ghi log cảnh báo, không ghi cache. Dùng để đo tỉ lệ báo nhầm trước khi bật `enforce` |
| `off` | Không kiểm |

Log chỉ ghi **loại** vi phạm (`number`, `date`…), không ghi giá trị, để không lộ định danh.

Response có `grounding: {checked, passed}`:

- `{checked: true, passed: true}`: câu LLM, đã soát, đúng;
- `{checked: true, passed: false}`: câu LLM sai, đã thay (hoặc chỉ log);
- `{checked: false, passed: null}`: câu không do LLM viết (template, cache, chào hỏi).

**Giới hạn:** chỉ soát số / ngày / mã, không hiểu nghĩa câu. LLM viết "tăng huyết áp"
trong khi evidence là "đái tháo đường" thì **không bắt được**. Số đúng nhưng gán nhầm chỉ
số cũng không bắt được. Phần này vẫn dựa vào prompt.

---

## 7. Contract request/response

### 7.1 Request — `POST /chat`

Định nghĩa ở [api/schemas.py](../api/schemas.py) (`ChatRequest`).

| Trường | Ý nghĩa |
|---|---|
| `user_id`, `user_role` (`USER\|DOCTOR\|ADMIN`) | Người hỏi |
| `session_id` | Phiên chat |
| `message` | Câu hỏi |
| `patient_id` | Bệnh nhân đang chọn trên giao diện (có thể null) |
| `allowed_patient_ids` | Hồ sơ USER được xem |
| `conversation_context` | `memory_summary`, `active_patient_id`, `last_intent`, `last_tool_name`, `last_resource_type`, `last_resource_id`, `recent_messages`, `total_message_count` |
| `quota_used_ratio` | Tỉ lệ quota đã dùng (≥ 0.8 hạ model, ≥ 0.9 low-cost mode) |
| `llm_key` | Virtual key LiteLLM của user |

### 7.2 Response

`ChatResponse` (Pydantic, `extra="allow"`). Response được validate trước khi trả; lệch
contract thì chỉ log kèm `trace_id`, lượt chat không bị hỏng.

**Trường mới:**

| Trường | Giá trị |
|---|---|
| `trace_id` | uuid4 hex, dùng để tra log |
| `response_status` | `answered \| partial \| no_data \| clarification_needed \| patient_selection_needed \| tool_error` |
| `agent_route` | `fhir \| general_chat \| conversation_meta \| unsupported \| cache` |
| `plan_steps` | `[{id, tool}]`, không có `args` |
| `failed_steps` | `[{id, tool, error_code}]`, chỉ có khi có step lỗi |
| `grounding` | `{checked, passed}` |
| `stage_usage` | `{agent_router: {...}, agent_planner: {...}, agent_answer: {...}, ...}` |
| `prompt_versions` | Version prompt router / planner / chat / answer |
| `evidence_pruning` | Chỉ có khi evidence bị cắt |

**Trường giữ nguyên tên cho Spring:**
`answer, intent, tool_name, intent_source, answer_source, answer_reason, patient_id,
observation_type, all_patients, patient_search, needs_patient_selection,
patient_candidates, pending_question, evidence, external_knowledge, answer_usage, usage,
saved_usage, summary_usage, memory_update, llm_provider, llm_model, query_complexity,
routing_source`.

**Gợi ý cho FE** theo `response_status`:

| Status | Hiển thị |
|---|---|
| `answered` | Câu trả lời bình thường |
| `partial` | Câu trả lời + cảnh báo thiếu dữ liệu (câu lưu ý đã nằm sẵn trong `answer`) |
| `no_data` | Thông báo không có dữ liệu |
| `clarification_needed` | Câu hỏi lại người dùng |
| `patient_selection_needed` | Danh sách `patient_candidates` để chọn, gửi lại kèm `pending_question` |
| `tool_error` | Thông báo sự cố hệ thống |

### 7.3 Mã lỗi HTTP

| Mã | Khi nào |
|---|---|
| 200 | Mọi lượt trả lời được, kể cả `clarification_needed`, `tool_error`, `partial` |
| 403 | Vi phạm chính sách role (`PolicyError`) |
| 429 | Virtual key vượt budget LiteLLM |
| 502 | FHIR lỗi ở tầng không bắt được trong tool |

Không còn 400 cho `/chat`. Endpoint `/chat/langgraph` đã bị xoá (404).

---

## 8. Cấu trúc thư mục

```text
chatbot-service/
  app/          main.py, config.py, logger.py          # khởi động, settings
  api/          chat.py, fhir.py, health.py, cache.py   # chỉ ánh xạ HTTP
                schemas.py (ChatRequest, ChatResponse), errors.py
  agent/        graph.py (nối node), state.py, errors.py, prompts.py
    nodes/      prepare, cache_lookup, route, plan, validate, execute, respond, simple
    planning/   router, planner, rule_planner, validator, plan_cache, plan
    execution/  executor
    answer/     generator, finalize, evidence_budget, grounding
    memory/     context (rút gọn hội thoại), summary (rolling summary)
  tools/        registry + mỗi resource một file + formatting
  fhir/         client.py (FHIR REST), normalizer.py
  llm/          client.py (một AsyncOpenAI duy nhất), pricing.py, model_select.py, usage.py
  nlp/          text, vocabulary, patient_refs, observation_types (xử lý tiếng Việt)
  cache/        semantic_cache.py (Qdrant)
  terminology/  LOINC, RxNorm, MedlinePlus
  tests/        test_*.py, fakes.py, eval/
```

Quy tắc phụ thuộc một chiều: `api → agent → tools → fhir`. `agent` dùng `llm` và `cache`.
`tools` dùng `terminology`. Mọi lớp dùng `nlp` và `app.config`. `tools/` không import
`agent/`; `fhir/` không import gì ngoài `app.config`.

**Thêm một tool mới:** viết handler trong `tools/<resource>.py` rồi khai báo `FhirTool`
trong `tools/registry.py`. Prompt planner, validator và executor tự nhận tool mới. Nhớ thêm
nhãn tiếng Việt vào `_DATA_LABELS` trong `agent/nodes/respond.py` cho câu lưu ý partial.

---

## 9. Cấu hình `.env`

Phần liên quan tới agent (đầy đủ ở [.env.example](../.env.example)):

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `LITELLM_BASE_URL`, `LITELLM_MASTER_KEY` | – | Gateway LLM. Thiếu key ⇒ chạy hoàn toàn bằng rule + template |
| `MODEL_SIMPLE`, `MODEL_COMPLEX` | `gpt-4o-mini`, `gpt-4.1-mini` | Model cho answer |
| `MODEL_ROUTE`, `MODEL_PLANNER`, `MODEL_CHAT` | trống | Model theo stage. Trống ⇒ route/chat dùng simple, planner dùng complex |
| `ROUTE_CACHE_SIZE` | 512 | LRU quyết định route (0 = tắt) |
| `MAX_PLAN_STEPS` | 4 | Trần số step của plan |
| `EVIDENCE_MAX_CHARS` | 12000 | Trần ký tự evidence vào prompt answer |
| `TEMPLATE_FAST_PATH` | false | Plan 1 step, ít evidence ⇒ dùng câu template, không gọi answer LLM |
| `LOW_COST_MODE_RATIO` | 0.9 | Ngưỡng quota bật low-cost mode |
| `GROUNDING_CHECK_MODE` | enforce | `enforce \| log \| off` |
| `ALLOW_USER_RESOURCE_LOOKUP` | false | Cho USER dùng `get_resource_by_id` |
| `ENABLE_PLAN_CACHE`, `PLAN_CACHE_TTL_SECONDS`, `PLAN_CACHE_SIMILARITY_THRESHOLD` | true, 86400, 0.95 | Plan cache |
| `CACHE_TTL_SECONDS`, `CACHE_SIMILARITY_THRESHOLD` | 300, 0.94 | Semantic cache |
| `SUMMARY_TRIGGER_MESSAGE_COUNT` | 8 | Ngưỡng bật rolling summary |

---

## 10. Test và đo chất lượng

**Unit test** (không cần mạng, LLM và FHIR đều giả lập):

```powershell
cd chatbot-service
..\.venv\Scripts\python.exe -m unittest discover tests
```

| File | Phạm vi |
|---|---|
| `test_agent_graph.py` | End-to-end qua graph + ánh xạ lỗi HTTP của `/chat` + contract |
| `test_agent_planning.py` | Router, planner, rule planner, validator, plan cache |
| `test_agent_executor.py` | Chốt bệnh nhân, chạy song song, partial, kiểm quyền runtime |
| `test_agent_answer.py` | Answer generator, finalize, evidence budget, summary |
| `test_agent_grounding.py` | Grounding check và chỗ móc vào finalize |
| `test_tools.py`, `test_nlp.py`, `test_llm.py`, `test_fhir_client.py`, `test_normalizer.py`, `test_terminology_*` | Các module còn lại |
| `test_eval_dataset.py` | Bộ câu hỏi eval có nhãn hợp lệ |

**Eval thật** (gọi LLM thật qua gateway, **tốn tiền**; cần stack đang chạy và FHIR có dữ liệu):

```powershell
python -m tests.eval.run_eval --json before.json
python -m tests.eval.run_eval --baseline before.json --verbose
```

Kết quả gồm độ chính xác route/tool/status, số LLM call, token, cost mỗi lượt, latency
p50/p95 và tỉ lệ cache hit. Bộ câu hỏi ở `tests/eval/questions.jsonl`.

---

## 11. Giới hạn đã biết

- **Grounding check** chỉ bắt số / ngày / mã, không bắt sai về nghĩa (mục 6.2).
- **Rule planner** dựa trên từ khoá nên dễ hiểu nhầm câu lạ. Ví dụ "bệnh nhân nhập viện
  lần nào" bị hiểu "nhập viện" là tên để tìm. Nó chỉ là đường lui khi LLM không dùng được.
- **Khoá semantic cache lệch nhẹ:** lúc tra dùng bệnh nhân xác định ở `prepare`, lúc ghi
  dùng bệnh nhân resolve sau khi chạy. Với câu tìm theo tên, lượt sau có thể không hit
  dù cùng câu hỏi. Không sai dữ liệu, chỉ giảm hit rate.
- **Không có lớp phát hiện cấp cứu:** câu như "tôi đang đau ngực" được router xếp vào
  `unsupported` và nhận câu từ chối chung, không có hướng dẫn gọi 115.
- **Spring và FE chưa đọc các trường mới** (`response_status`, `failed_steps`,
  `grounding`, `trace_id`…). Các trường cũ giữ nguyên nên tích hợp hiện tại vẫn chạy.
