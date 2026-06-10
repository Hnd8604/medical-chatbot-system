# Phân Rã Module Nghiệp Vụ Chi Tiết

Tài liệu này phân rã file `module_nghiep_vu_chatbot_y_te_moscow.md` thành các module nhỏ hơn để dễ thiết kế, chia task, kiểm thử và theo dõi tiến độ.

Nguyên tắc thiết kế của dự án:

- Dữ liệu y tế có cấu trúc phải đi qua FHIR REST API.
- Không query trực tiếp các bảng nội bộ của HAPI FHIR PostgreSQL.
- LLM dùng để hiểu intent, gọi tool, tổng hợp câu trả lời và giải thích, không dùng để tự tạo dữ liệu bệnh nhân.
- App database chỉ lưu dữ liệu ứng dụng như user, session, message, usage, quota, cost, audit, cache.

## Cách Đọc Tài Liệu

Mỗi module lớn được phân rã thành các module nhỏ:

```text
Mx.y - Tên module nhỏ
Mục tiêu:
Yêu cầu:
Việc cần làm:
Tiêu chí hoàn thành:
```

## I. MUST HAVE

## M1. User & Authentication

### M1.1 - Mô hình người dùng

Mục tiêu:

- Xác định dữ liệu tối thiểu cần lưu cho một người dùng trong hệ thống.

Yêu cầu:

- Mỗi người dùng có định danh duy nhất.
- Có username hoặc email để đăng nhập.
- Có họ tên hiển thị.
- Có trạng thái tài khoản như active, locked, disabled.
- Có vai trò hoặc nhóm quyền.

Việc cần làm:

- Thiết kế entity/table `app_users`.
- Bổ sung các field tối thiểu: `id`, `username`, `email`, `display_name`, `password_hash`, `status`, `role`, `created_at`, `updated_at`.
- Tạo seed user demo nếu chưa có auth thật.
- Đảm bảo username/email unique.

Tiêu chí hoàn thành:

- Có thể lấy user hiện tại từ database.
- Có thể phân biệt user thường, doctor, admin.
- User bị khóa không được phép dùng chức năng chat hoặc tra cứu dữ liệu.

### M1.2 - Đăng nhập

Mục tiêu:

- Cho phép người dùng đăng nhập bằng tài khoản được cấp.

Yêu cầu:

- Nhận username/email và password.
- Kiểm tra password an toàn bằng hash.
- Trả token/session nếu đăng nhập thành công.
- Trả lỗi thân thiện nếu sai thông tin đăng nhập.

Việc cần làm:

- Thêm endpoint `POST /api/auth/login`.
- Thêm DTO request/response cho login.
- Tích hợp Spring Security hoặc cơ chế auth phù hợp.
- Hash password bằng BCrypt hoặc thuật toán an toàn tương đương.
- Lưu audit log cho login thành công/thất bại.

Tiêu chí hoàn thành:

- Đăng nhập đúng trả token/session.
- Đăng nhập sai không lộ thông tin nhạy cảm.
- User locked không đăng nhập được.

### M1.3 - Đăng xuất và quản lý phiên đăng nhập

Mục tiêu:

- Cho phép người dùng kết thúc phiên làm việc.

Yêu cầu:

- Token/session hết hiệu lực sau logout.
- Có thời hạn phiên đăng nhập.
- Có thể xử lý token hết hạn.

Việc cần làm:

- Thêm endpoint `POST /api/auth/logout`.
- Nếu dùng JWT stateless, cân nhắc blacklist hoặc short-lived access token.
- Nếu dùng session stateful, xóa session server-side.
- Frontend clear local auth state sau logout.

Tiêu chí hoàn thành:

- Sau logout, request cần auth bị từ chối.
- Session hết hạn trả lỗi rõ ràng.

### M1.4 - Role Based Access Control

Mục tiêu:

- Phân quyền chức năng theo vai trò.

Yêu cầu:

- Tối thiểu có các vai trò: `USER`, `DOCTOR`, `ADMIN`.
- Staff/doctor được tra cứu bệnh nhân theo phạm vi được cấp.
- Admin được xem dashboard quản trị và cấu hình hệ thống.

Việc cần làm:

- Thiết kế bảng `roles` hoặc enum role trong `app_users`.
- Thêm annotation hoặc service check quyền ở Spring.
- Bảo vệ endpoint chat, patient search, quota, admin.
- Viết test cho user không đủ quyền.

Tiêu chí hoàn thành:

- User không có quyền không truy cập được endpoint nhạy cảm.
- Admin truy cập được endpoint quản trị.

### M1.5 - Quản trị tài khoản

Mục tiêu:

- Cho phép Admin quản lý trạng thái tài khoản.

Yêu cầu:

- Admin xem danh sách user.
- Admin khóa/mở khóa tài khoản.
- Admin chỉnh role nếu cần.

Việc cần làm:

- Thêm API `GET /api/admin/users`.
- Thêm API `PATCH /api/admin/users/{id}/status`.
- Thêm API `PATCH /api/admin/users/{id}/role`.
- Ghi audit log cho mọi thao tác admin.

Tiêu chí hoàn thành:

- Admin có thể khóa tài khoản.
- User bị khóa không thể chat hoặc tra cứu dữ liệu.
- Audit log ghi rõ ai đã thay đổi gì.

## M2. Conversation Management

### M2.1 - Tạo phiên hội thoại

Mục tiêu:

- Tạo phiên chat mới khi người dùng bắt đầu cuộc trò chuyện.

Yêu cầu:

- Mỗi session thuộc về một user.
- Session có title, trạng thái, thời gian tạo/cập nhật.
- Có thể gắn `active_patient_id` nếu staff đang chọn bệnh nhân.

Việc cần làm:

- Thiết kế entity/table `chat_sessions`.
- Khi `POST /api/chat` không có `session_id`, tạo session mới.
- Tạo title từ câu hỏi đầu tiên.
- Lưu `active_patient_id` nếu có.

Tiêu chí hoàn thành:

- Chat mới tạo đúng một session.
- Session hiển thị được trong lịch sử hội thoại.

### M2.2 - Gửi câu hỏi

Mục tiêu:

- Nhận câu hỏi tự nhiên từ frontend và chuyển qua pipeline xử lý.

Yêu cầu:

- Request có `message`.
- Có thể có `session_id`.
- Có thể có `patient_id`.
- Có thể có context từ session memory.

Việc cần làm:

- Duy trì endpoint `POST /api/chat`.
- Validate message không rỗng.
- Kiểm tra session thuộc user hiện tại.
- Nếu không có `patient_id`, dùng `active_patient_id` trong memory nếu phù hợp.

Tiêu chí hoàn thành:

- User gửi được câu hỏi mới.
- User hỏi tiếp trong session cũ vẫn giữ context.

### M2.3 - Nhận và hiển thị câu trả lời

Mục tiêu:

- Nhận câu trả lời từ chatbot-service và trả về frontend.

Yêu cầu:

- Response gồm `answer`, `intent`, `tool_name`, `evidence`, `usage`.
- Nếu cần chọn bệnh nhân, response có `needs_patient_selection` và `patient_candidates`.

Việc cần làm:

- Chuẩn hóa `ChatResponse`.
- Frontend render answer, evidence, usage.
- Frontend render candidate cards khi bệnh nhân mơ hồ.

Tiêu chí hoàn thành:

- Câu trả lời tiếng Việt hiển thị đúng.
- Evidence hiển thị được để staff kiểm chứng.

### M2.4 - Tiếp tục hội thoại cũ

Mục tiêu:

- Cho phép user mở lại một session và hỏi tiếp.

Yêu cầu:

- Load được messages theo session.
- Session phải thuộc user hiện tại.
- Giữ memory của session.

Việc cần làm:

- API `GET /api/chat/sessions/{sessionId}/messages`.
- Frontend click lịch sử để load message.
- Khi gửi tiếp, frontend gửi `session_id`.

Tiêu chí hoàn thành:

- Mở session cũ hiển thị đúng lịch sử.
- Câu hỏi tiếp theo dùng đúng context session.

### M2.5 - Danh sách hội thoại

Mục tiêu:

- Hiển thị danh sách các cuộc hội thoại gần đây.

Yêu cầu:

- Có title.
- Có thời gian cập nhật.
- Có số message.
- Có preview message cuối.
- Có active patient nếu session đang gắn bệnh nhân.

Việc cần làm:

- API `GET /api/chat/sessions?limit=`.
- Query group message count và last message preview.
- UI sidebar dạng ChatGPT-like.

Tiêu chí hoàn thành:

- Danh sách session không bị lỗi layout.
- Click session load đúng messages.

## M3. Message History

### M3.1 - Lưu user message

Mục tiêu:

- Lưu đầy đủ câu hỏi của người dùng.

Yêu cầu:

- Message có role `user`.
- Gắn với session.
- Có thời gian tạo.
- Có metadata nếu cần.

Việc cần làm:

- Thiết kế entity/table `chat_messages`.
- Lưu message trước khi gọi chatbot-service.
- Metadata nên lưu `request_patient_id`, `effective_patient_id`.

Tiêu chí hoàn thành:

- Database có đủ user message sau mỗi lượt chat.

### M3.2 - Lưu assistant message

Mục tiêu:

- Lưu câu trả lời của chatbot.

Yêu cầu:

- Message có role `assistant`.
- Gắn với cùng session.
- Metadata lưu intent/tool/evidence refs/memory update.

Việc cần làm:

- Lưu answer sau khi chatbot-service trả về.
- Lưu metadata JSONB.
- Không lưu raw FHIR bundle quá lớn nếu không cần.

Tiêu chí hoàn thành:

- Mở lại session thấy đủ user và assistant messages.

### M3.3 - System message và metadata

Mục tiêu:

- Hỗ trợ message hệ thống nếu sau này cần thông báo trạng thái hoặc lỗi.

Yêu cầu:

- Role hỗ trợ `system`.
- Metadata có thể lưu structured JSON.

Việc cần làm:

- Đảm bảo enum role hỗ trợ `system`.
- Quy định trường hợp nào tạo system message.
- Không lạm dụng system message cho log kỹ thuật.

Tiêu chí hoàn thành:

- Có thể lưu thông báo hệ thống trong hội thoại mà không phá UI.

### M3.4 - Truy xuất lịch sử đúng thứ tự

Mục tiêu:

- Trả message theo thứ tự thời gian.

Yêu cầu:

- Sort `created_at ASC`.
- Chỉ lấy message của session thuộc user hiện tại.

Việc cần làm:

- Repository query theo `session_id + user_id`.
- Test không đọc được session của user khác.

Tiêu chí hoàn thành:

- UI render đúng thứ tự chat.

### M3.5 - Retention và dữ liệu nhạy cảm

Mục tiêu:

- Chuẩn bị quy tắc lưu/xóa message có dữ liệu y tế.

Yêu cầu:

- Không lưu quá nhiều raw medical record trong metadata.
- Có thể xóa/ẩn conversation nếu cần.

Việc cần làm:

- Định nghĩa retention policy.
- Thêm soft delete cho session/message nếu cần.
- Thêm audit khi export/delete.

Tiêu chí hoàn thành:

- Có chính sách rõ ràng cho dữ liệu hội thoại chứa thông tin y tế.

## M4. Medical Data Query

### M4.1 - Phân loại intent y tế

Mục tiêu:

- Xác định user đang hỏi loại dữ liệu y tế nào.

Yêu cầu:

- Nhận diện nhóm câu hỏi về Patient, Encounter, Observation, Condition, MedicationRequest.
- Hỗ trợ tiếng Việt không dấu và có dấu.
- Có fallback khi LLM không khả dụng.

Việc cần làm:

- Duy trì intent extractor trong chatbot-service.
- Định nghĩa tool names ổn định.
- Thêm test cho từ khóa tiếng Việt.

Tiêu chí hoàn thành:

- Câu hỏi phổ biến route đúng tool.

### M4.2 - Tra cứu thông tin bệnh nhân

Mục tiêu:

- Lấy thông tin hành chính của bệnh nhân.

Yêu cầu:

- Tìm theo FHIR id, tên, phone, birth date, identifier.
- Xử lý không tìm thấy hoặc nhiều kết quả.

Việc cần làm:

- Tool `search_patients`.
- Tool `get_patient_by_id`.
- Normalize Patient gồm id, tên, giới tính, ngày sinh, phone, identifier.
- UI hiển thị candidate để chọn.

Tiêu chí hoàn thành:

- Staff tìm được bệnh nhân và chọn đúng bệnh nhân.

### M4.3 - Tra cứu lịch sử khám

Mục tiêu:

- Lấy các lần khám gần đây của bệnh nhân.

Yêu cầu:

- Dùng FHIR `Encounter`.
- Có limit.
- Sort mới nhất trước.

Việc cần làm:

- Tool `get_encounters`.
- Endpoint patient encounters.
- Normalize Encounter gồm id, status, class, period, reason, provider/location nếu có.

Tiêu chí hoàn thành:

- Hỏi "lần khám gần nhất" trả đúng encounter gần nhất.

### M4.4 - Tra cứu thuốc

Mục tiêu:

- Lấy thuốc hoặc y lệnh thuốc của bệnh nhân.

Yêu cầu:

- Dùng FHIR `MedicationRequest`.
- Hiển thị tên thuốc, liều, trạng thái, thời gian, lý do nếu có.

Việc cần làm:

- Tool `get_medication_requests`.
- Normalize MedicationRequest đầy đủ các trường quan trọng.
- Answer generator trả lời rõ "theo dữ liệu FHIR hiện có".

Tiêu chí hoàn thành:

- Hỏi thuốc của một bệnh nhân trả đúng danh sách thuốc.

### M4.5 - Tra cứu chỉ số/xét nghiệm

Mục tiêu:

- Lấy Observation của bệnh nhân.

Yêu cầu:

- Hỗ trợ huyết áp, glucose, nhịp tim và observation chung.
- Có limit và sort mới nhất trước.
- Trả đơn vị, thời gian, reference range nếu có.

Việc cần làm:

- Tool `get_observations`.
- Normalize Observation gồm code/display, effective time, value, unit, components, interpretation.
- Hỗ trợ câu hỏi "chỉ số đó có cao không?" bằng context/resource id.

Tiêu chí hoàn thành:

- Hỏi huyết áp gần nhất trả được systolic/diastolic và ngày ghi nhận.

### M4.6 - Tra cứu chẩn đoán/bệnh lý

Mục tiêu:

- Lấy Condition của bệnh nhân.

Yêu cầu:

- Dùng FHIR `Condition`.
- Hiển thị tên chẩn đoán, clinical status, verification status, onset, recorded date.

Việc cần làm:

- Tool `get_conditions`.
- Normalize Condition đầy đủ.
- Tránh tự kết luận bệnh ngoài dữ liệu ghi nhận.

Tiêu chí hoàn thành:

- Hỏi "bệnh nhân được chẩn đoán gì" trả đúng Condition.

### M4.7 - Kiểm soát quyền truy cập dữ liệu y tế

Mục tiêu:

- Đảm bảo chỉ user có quyền mới xem dữ liệu bệnh nhân.

Yêu cầu:

- Kiểm tra quyền trước khi trả dữ liệu y tế.
- Không lộ dữ liệu bệnh nhân khác.

Việc cần làm:

- Thiết kế access control theo role/scope.
- Áp dụng ở Spring trước khi gọi chatbot-service.
- Ghi audit log khi xem dữ liệu bệnh nhân.

Tiêu chí hoàn thành:

- User không có quyền không thể xem patient data.

## M5. FHIR/Medical Database Integration

### M5.1 - HAPI FHIR infrastructure

Mục tiêu:

- Chạy HAPI FHIR JPA Server với PostgreSQL.

Yêu cầu:

- Có Docker Compose riêng.
- HAPI expose tại `/fhir`.
- PostgreSQL dùng named volume.

Việc cần làm:

- Duy trì `infra/hapi-fhir/docker-compose.yml`.
- Duy trì `application.yaml` cho HAPI.
- Tài liệu hóa port, user, password local.

Tiêu chí hoàn thành:

- `GET /fhir/metadata` trả CapabilityStatement.

### M5.2 - FHIR client

Mục tiêu:

- Tập trung toàn bộ logic gọi HAPI FHIR.

Yêu cầu:

- Không gọi HAPI phân tán nhiều nơi.
- Có timeout và error handling.
- Dùng FHIR search params chuẩn.

Việc cần làm:

- Duy trì `fhir/client.py`.
- Hàm `get_patient`, `search_patients`, `search_patient_resources`, `get_resource`.
- Xử lý 404, 5xx, timeout.

Tiêu chí hoàn thành:

- Mọi truy vấn FHIR từ chatbot-service đi qua FHIR client.

### M5.3 - Resource endpoint layer

Mục tiêu:

- Expose endpoint đọc FHIR đã normalize.

Yêu cầu:

- Endpoint cho Patient, Observation, Encounter, Condition, MedicationRequest.
- Validate limit.
- Trả JSON ổn định cho Spring/frontend.

Việc cần làm:

- Duy trì `api/fhir_routes.py`.
- Không trả raw Bundle trực tiếp cho UI nếu không cần.
- Thêm test contract cho endpoint.

Tiêu chí hoàn thành:

- Spring proxy được đầy đủ endpoint patient detail.

### M5.4 - Normalization layer

Mục tiêu:

- Chuyển FHIR resource phức tạp thành dữ liệu dễ dùng cho UI/LLM.

Yêu cầu:

- Giữ các field quan trọng: resource type, id, code, display, date, value, subject.
- Không làm mất provenance/evidence cần thiết.

Việc cần làm:

- Duy trì `fhir/normalizer.py`.
- Có normalizer riêng cho từng resource.
- Thêm field khi answer cần giải thích chi tiết.

Tiêu chí hoàn thành:

- LLM nhận evidence gọn nhưng đủ để trả lời.

### M5.5 - Seed và kiểm tra dữ liệu demo

Mục tiêu:

- Có dữ liệu demo đa dạng để test.

Yêu cầu:

- Seed id cố định để chạy nhiều lần không tạo trùng.
- Dữ liệu có nhiều bệnh nhân và resource liên quan.

Việc cần làm:

- Duy trì seed transaction Bundle.
- Script `seed_fhir_data.py`.
- Script `check_connection.py`.

Tiêu chí hoàn thành:

- Demo chạy được sau khi clone repo và seed data.

## M6. AI Integration

### M6.1 - Intent extraction

Mục tiêu:

- Hiểu câu hỏi user và chọn tool phù hợp.

Yêu cầu:

- Ưu tiên structured tool call.
- Có rule fallback.
- Không tự trả lời dữ liệu y tế nếu chưa retrieve.

Việc cần làm:

- Duy trì `agents/intent_extractor.py`.
- Định nghĩa schema tool call.
- Test câu hỏi tiếng Việt cho từng tool.

Tiêu chí hoàn thành:

- Tool được chọn đúng cho các câu hỏi demo.

### M6.2 - Tool execution

Mục tiêu:

- Thực thi tool đã chọn để lấy dữ liệu FHIR.

Yêu cầu:

- Tool chỉ gọi API đã định nghĩa.
- Không sinh SQL.
- Có xử lý ambiguous patient.

Việc cần làm:

- Route tool trong `api/chat_routes.py`.
- Resolve patient id trước khi gọi resource tool.
- Trả candidate khi nhiều bệnh nhân.

Tiêu chí hoàn thành:

- Tool execution không hallucinate dữ liệu.

### M6.3 - Answer generation

Mục tiêu:

- Tạo câu trả lời tiếng Việt từ evidence đã retrieve.

Yêu cầu:

- Câu trả lời dựa trên evidence.
- Nói rõ nếu thiếu dữ liệu.
- Không đưa chẩn đoán/lời khuyên vượt quá dữ liệu.

Việc cần làm:

- Duy trì `agents/answer_generator.py`.
- Prompt quy định tiếng Việt, an toàn y tế, không bịa dữ liệu.
- Template fallback khi không có LLM.

Tiêu chí hoàn thành:

- `answer_source=llm` khi gọi LLM thật.
- `usage` có token khi LLM được gọi.

### M6.4 - Prompt và context control

Mục tiêu:

- Kiểm soát dữ liệu gửi vào LLM để tránh vượt token và lộ dữ liệu thừa.

Yêu cầu:

- Chỉ gửi normalized evidence cần thiết.
- Không gửi raw FHIR Bundle quá lớn.
- Có giới hạn kích thước evidence.

Việc cần làm:

- Giữ `MAX_EVIDENCE_JSON_CHARS`.
- Rút gọn evidence trước khi prompt.
- Log khi evidence bị cắt.

Tiêu chí hoàn thành:

- Prompt ổn định với dữ liệu demo lớn vừa phải.

### M6.5 - LLM error fallback

Mục tiêu:

- Hệ thống vẫn trả lời được khi LLM lỗi.

Yêu cầu:

- Intent có rule fallback.
- Answer có template fallback.
- Lỗi kỹ thuật không lộ ra user.

Việc cần làm:

- Catch exception từ OpenAI API.
- Trả answer template nếu có evidence.
- Log lỗi vào audit/usage nếu cần.

Tiêu chí hoàn thành:

- Tắt API key vẫn demo được flow cơ bản.

## M7. Usage Tracking

### M7.1 - Log mỗi request AI

Mục tiêu:

- Ghi nhận mỗi lượt chat có gọi pipeline AI.

Yêu cầu:

- Mỗi lượt thành công tạo một dòng usage.
- Có user, session, operation, status.

Việc cần làm:

- Entity/table `usage_logs`.
- Lưu trong Spring sau khi chatbot-service trả response.
- Ghi latency.

Tiêu chí hoàn thành:

- DB có usage row sau mỗi `POST /api/chat` thành công.

### M7.2 - Token input/output

Mục tiêu:

- Lưu token đầu vào và đầu ra.

Yêu cầu:

- Lấy token từ OpenAI response usage.
- Cộng token intent + answer.
- Nếu không gọi LLM, token là 0.

Việc cần làm:

- chatbot-service trả `usage.input_tokens`, `usage.output_tokens`.
- Spring lưu vào `usage_logs`.

Tiêu chí hoàn thành:

- UI/API quota status hiển thị token đã dùng.

### M7.3 - Model/provider tracking

Mục tiêu:

- Biết request dùng provider/model nào.

Yêu cầu:

- Lưu `llm_provider`.
- Lưu `llm_model`.

Việc cần làm:

- chatbot-service trả provider/model từ settings.
- Spring lưu vào usage log và audit metadata.

Tiêu chí hoàn thành:

- Query usage logs thấy model thật.

### M7.4 - Aggregation theo user/time

Mục tiêu:

- Tính usage theo user và khoảng thời gian.

Yêu cầu:

- Tổng request.
- Tổng input token.
- Tổng output token.
- Tổng cost.

Việc cần làm:

- Repository query aggregate.
- API quota/cost summary.
- Filter theo ngày.

Tiêu chí hoàn thành:

- `GET /api/quota/status` trả đúng usage hôm nay.

### M7.5 - Usage theo nhóm người dùng

Mục tiêu:

- Chuẩn bị thống kê usage theo role/group.

Yêu cầu:

- User có role/group.
- Aggregate được theo role/group.

Việc cần làm:

- Khi có auth/role thật, thêm query group by role.
- Dashboard admin hiển thị top role/group.

Tiêu chí hoàn thành:

- Admin biết nhóm nào dùng nhiều tài nguyên nhất.

## M8. Cost Management

### M8.1 - Bảng giá model

Mục tiêu:

- Lưu giá token theo model.

Yêu cầu:

- Provider/model unique khi active.
- Có giá input/output per 1M tokens.
- Có currency.

Việc cần làm:

- Table `model_pricing`.
- Seed giá demo cho model đang dùng.
- API `GET /api/model-pricing`.

Tiêu chí hoàn thành:

- Spring tìm được pricing cho model hiện tại.

### M8.2 - Tính cost từng request

Mục tiêu:

- Tính estimated cost từ token.

Yêu cầu:

- Công thức:

```text
cost = input_tokens * input_price_per_1m / 1_000_000
     + output_tokens * output_price_per_1m / 1_000_000
```

Việc cần làm:

- Service `CostEstimationService`.
- Round về 6 chữ số thập phân.
- Fallback về cost từ chatbot-service nếu thiếu pricing.

Tiêu chí hoàn thành:

- `usage_logs.estimated_cost_usd` khác 0 khi có token và pricing.

### M8.3 - Cost summary

Mục tiêu:

- Thống kê chi phí theo ngày/model/user.

Yêu cầu:

- Tổng cost.
- Cost theo model.
- Cost theo ngày.
- Missing pricing model nếu có.

Việc cần làm:

- API `GET /api/usage/cost-summary`.
- Query aggregate từ `usage_logs`.
- UI hiển thị khối "Chi phí AI".

Tiêu chí hoàn thành:

- Staff/admin xem được cost hôm nay.

### M8.4 - Admin cost view

Mục tiêu:

- Cho Admin giám sát chi phí vận hành.

Yêu cầu:

- Lọc theo date range, model, user.
- Hiển thị top cost users.
- Cảnh báo nếu vượt ngưỡng.

Việc cần làm:

- Thêm Admin Dashboard sau khi có auth.
- Thêm API admin cost analytics.
- Thêm chart/table.

Tiêu chí hoàn thành:

- Admin nắm được chi phí theo thời gian.

## M9. Quota Management

### M9.1 - Quota policy

Mục tiêu:

- Định nghĩa hạn mức sử dụng.

Yêu cầu:

- Hạn mức request/ngày.
- Hạn mức token/ngày.
- Hạn mức cost/ngày.

Việc cần làm:

- Table `quota_policies`.
- Gắn policy cho user.
- Seed policy demo.

Tiêu chí hoàn thành:

- User có quota policy trước khi chat.

### M9.2 - Kiểm tra quota trước request

Mục tiêu:

- Chặn request trước khi phát sinh chi phí nếu đã vượt hạn mức.

Yêu cầu:

- Check usage logs trong ngày.
- So sánh với policy.
- Trả HTTP 429 nếu vượt.

Việc cần làm:

- Service `QuotaService.assertQuotaAvailable`.
- Exception `QuotaExceededException`.
- Global handler trả response rõ ràng.

Tiêu chí hoàn thành:

- Khi request_count hoặc token/cost vượt limit, chat bị chặn.

### M9.3 - Ghi nhận usage sau request

Mục tiêu:

- Cập nhật quota thông qua usage log.

Yêu cầu:

- Không cần bảng quota counter riêng ở V1.
- Usage logs là nguồn tính quota.

Việc cần làm:

- Lưu usage sau mỗi chat thành công.
- Aggregate usage theo ngày khi check quota.

Tiêu chí hoàn thành:

- Quota status thay đổi sau mỗi request.

### M9.4 - Quota theo nhóm

Mục tiêu:

- Hỗ trợ quota cho role/group.

Yêu cầu:

- Role/group có default policy.
- User có thể override policy riêng.

Việc cần làm:

- Thiết kế quan hệ user-policy hoặc role-policy.
- Xác định priority: user policy > role policy > default.

Tiêu chí hoàn thành:

- Có thể cấp hạn mức khác nhau cho Doctor/Admin/User.

### M9.5 - Lịch sử thay đổi quota

Mục tiêu:

- Audit thay đổi quota.

Yêu cầu:

- Ghi ai thay đổi.
- Ghi giá trị cũ/mới.
- Ghi thời gian.

Việc cần làm:

- Có thể dùng `audit_logs`.
- Hoặc thêm bảng `quota_change_logs` nếu cần chi tiết.

Tiêu chí hoàn thành:

- Admin có thể truy vết thay đổi quota.

## M10. Rate Limiting

### M10.1 - Chính sách rate limit

Mục tiêu:

- Chống spam request trong thời gian ngắn.

Yêu cầu:

- Limit theo user.
- Limit theo IP nếu chưa đăng nhập.
- Có window như 1 phút hoặc 5 phút.

Việc cần làm:

- Chọn storage: in-memory, Redis hoặc database.
- Cấu hình limit theo endpoint.
- Định nghĩa response khi bị chặn.

Tiêu chí hoàn thành:

- Gửi quá nhiều request liên tục bị chặn.

### M10.2 - Enforcement layer

Mục tiêu:

- Áp dụng rate limit trước business logic.

Yêu cầu:

- Chặn trước khi gọi chatbot-service.
- Không ghi usage log thành công cho request bị chặn.

Việc cần làm:

- Implement filter/interceptor ở Spring.
- Ghi audit/log cho rate-limited event.

Tiêu chí hoàn thành:

- Request vượt rate limit trả 429.

### M10.3 - UI thông báo rate limit

Mục tiêu:

- Người dùng biết vì sao bị chặn.

Yêu cầu:

- Thông báo thân thiện.
- Có thể hiển thị thời gian chờ.

Việc cần làm:

- Frontend xử lý 429.
- Hiển thị message rõ ràng.

Tiêu chí hoàn thành:

- UI không crash khi bị rate limit.

## M11. Logging & Error Handling

### M11.1 - Application logs

Mục tiêu:

- Ghi log kỹ thuật để debug.

Yêu cầu:

- Log request quan trọng.
- Log lỗi service.
- Không log API key/password/raw sensitive data.

Việc cần làm:

- Chuẩn hóa logging ở Spring và FastAPI.
- Log correlation id nếu có.
- Log ra file trong `logs/` khi chạy local.

Tiêu chí hoàn thành:

- Khi lỗi có thể xem logs để tìm nguyên nhân.

### M11.2 - Friendly error response

Mục tiêu:

- Trả lỗi dễ hiểu cho frontend/user.

Yêu cầu:

- Không trả stack trace.
- Có status code phù hợp.
- Có message tiếng Việt.

Việc cần làm:

- Global exception handler ở Spring.
- Error translator ở chatbot-service.
- Frontend render lỗi rõ ràng.

Tiêu chí hoàn thành:

- Lỗi FHIR/LLM/DB không làm lộ chi tiết nhạy cảm.

### M11.3 - External service error logs

Mục tiêu:

- Ghi lỗi khi gọi LLM hoặc HAPI.

Yêu cầu:

- Biết service nào lỗi.
- Có latency/status.
- Có technical detail trong log nội bộ.

Việc cần làm:

- Catch FHIR client error.
- Catch OpenAI client error.
- Ghi audit hoặc app log tùy loại lỗi.

Tiêu chí hoàn thành:

- Có thể phân biệt lỗi AI, FHIR, DB, validation.

### M11.4 - Sensitive data protection

Mục tiêu:

- Hạn chế rò rỉ dữ liệu bệnh nhân trong log.

Yêu cầu:

- Không log raw FHIR Bundle lớn.
- Không log toàn bộ prompt nếu chứa dữ liệu nhạy cảm.
- Mask phone/email nếu cần.

Việc cần làm:

- Viết helper redaction.
- Quy định field nào được log.
- Review logging ở service.

Tiêu chí hoàn thành:

- Log đủ debug nhưng không lộ dữ liệu thừa.

## M12. Database Design

### M12.1 - Core application schema

Mục tiêu:

- Thiết kế schema app database tách khỏi HAPI database.

Yêu cầu:

- Bảng user, session, message, usage, quota, audit, cache, pricing.
- Dùng UUID.
- Có timestamp.

Việc cần làm:

- Flyway migrations.
- JPA entities mapping đúng schema.
- Hibernate `ddl-auto=validate`.

Tiêu chí hoàn thành:

- Spring start được và validate schema pass.

### M12.2 - User/role schema

Mục tiêu:

- Lưu thông tin người dùng và phân quyền.

Yêu cầu:

- `app_users`.
- `roles` hoặc role field.
- Quan hệ user-policy.

Việc cần làm:

- Thiết kế migration.
- Entity `User`.
- Repository `UserRepository`.

Tiêu chí hoàn thành:

- Lấy được user hiện tại và role/quota policy.

### M12.3 - Conversation/message schema

Mục tiêu:

- Lưu session và messages.

Yêu cầu:

- `chat_sessions`.
- `chat_messages`.
- Metadata JSONB.
- Memory fields trong session.

Việc cần làm:

- Index theo user/session/created_at.
- Query history hiệu quả.

Tiêu chí hoàn thành:

- History API nhanh với dữ liệu demo.

### M12.4 - Usage/cost/quota schema

Mục tiêu:

- Lưu dữ liệu vận hành AI.

Yêu cầu:

- `usage_logs`.
- `quota_policies`.
- `model_pricing`.

Việc cần làm:

- Index theo user/status/created_at.
- Numeric precision cho cost.
- Seed pricing.

Tiêu chí hoàn thành:

- Quota/cost summary chạy đúng.

### M12.5 - Audit/cache schema

Mục tiêu:

- Lưu audit và cache.

Yêu cầu:

- `audit_logs`.
- `cache_entries`.
- Metadata JSONB.

Việc cần làm:

- Audit action/resource/user/session.
- Cache key/value/ttl.

Tiêu chí hoàn thành:

- Có thể tra cứu audit và cache entry.

## II. SHOULD HAVE

## M13. Admin Dashboard

### M13.1 - KPI tổng quan

Mục tiêu:

- Cho Admin xem tình trạng hệ thống.

Yêu cầu:

- Tổng user.
- Tổng conversation.
- Tổng AI requests.
- Tổng token.
- Tổng cost.

Việc cần làm:

- API admin dashboard summary.
- UI cards/charts.
- Filter theo ngày.

Tiêu chí hoàn thành:

- Admin nhìn nhanh được mức sử dụng hệ thống.

### M13.2 - Usage analytics

Mục tiêu:

- Phân tích usage theo user/model/time.

Yêu cầu:

- Top users.
- Usage theo ngày/tháng.
- Token/cost theo model.

Việc cần làm:

- Query aggregate từ usage logs.
- Chart trong admin dashboard.

Tiêu chí hoàn thành:

- Admin biết ai dùng nhiều tài nguyên nhất.

### M13.3 - Error monitoring

Mục tiêu:

- Theo dõi lỗi hệ thống.

Yêu cầu:

- Lỗi AI.
- Lỗi FHIR.
- Lỗi DB.
- Lỗi quota/rate limit.

Việc cần làm:

- API thống kê lỗi.
- UI table lỗi gần đây.
- Link sang audit/log nếu cần.

Tiêu chí hoàn thành:

- Admin xem được lỗi gần đây và tỷ lệ lỗi.

### M13.4 - Admin filters

Mục tiêu:

- Cho phép lọc dashboard.

Yêu cầu:

- Filter theo user.
- Filter theo model.
- Filter theo date range.

Việc cần làm:

- Query params ở API.
- UI filter controls.

Tiêu chí hoàn thành:

- Dashboard không chỉ xem tổng mà drill-down được.

## M14. Cache Management

### M14.1 - Cache policy

Mục tiêu:

- Xác định loại dữ liệu được phép cache.

Yêu cầu:

- Cache tốt cho câu hỏi giải thích chung.
- Cẩn thận với dữ liệu bệnh nhân.
- Patient data nếu cache phải có scope user/session và TTL ngắn.

Việc cần làm:

- Viết cache policy.
- Định nghĩa cache categories.
- Không cache dữ liệu nhạy cảm nếu chưa có access control.

Tiêu chí hoàn thành:

- Biết request nào được cache và request nào không.

### M14.2 - Cache key design

Mục tiêu:

- Tạo cache key ổn định.

Yêu cầu:

- Key gồm operation, params, user/session scope nếu cần.
- Không chứa raw sensitive text quá dài.

Việc cần làm:

- Helper tạo cache key.
- Normalize query trước khi hash.

Tiêu chí hoàn thành:

- Câu hỏi tương đương có thể hit cache.

### M14.3 - Cache storage và TTL

Mục tiêu:

- Lưu cache có thời gian sống.

Yêu cầu:

- `cache_entries` hoặc Redis.
- Có `expires_at`.
- Tự bỏ qua cache hết hạn.

Việc cần làm:

- Repository/service cache.
- Cleanup expired cache.
- Cấu hình TTL.

Tiêu chí hoàn thành:

- Cache hit/miss hoạt động đúng.

### M14.4 - Cache observability

Mục tiêu:

- Theo dõi cache có giúp giảm cost không.

Yêu cầu:

- Log cache hit/miss.
- Thống kê hit rate.

Việc cần làm:

- Thêm metadata vào usage/audit.
- Admin dashboard cache metrics.

Tiêu chí hoàn thành:

- Admin biết tỷ lệ cache hit.

## M15. Context Management

### M15.1 - Recent messages context

Mục tiêu:

- Gửi một số message gần nhất để giữ ngữ cảnh ngắn hạn.

Yêu cầu:

- Có limit cố định.
- Sort đúng thứ tự.
- Không gửi toàn bộ lịch sử.

Việc cần làm:

- Query recent messages.
- Gửi vào `conversation_context`.

Tiêu chí hoàn thành:

- Chatbot hiểu câu hỏi nối tiếp ngắn.

### M15.2 - Session memory

Mục tiêu:

- Lưu memory nhẹ cho phiên chat.

Yêu cầu:

- `active_patient_id`.
- `memory_summary`.
- `last_intent`.
- `last_tool_name`.
- `last_resource_type`.
- `last_resource_id`.

Việc cần làm:

- Update memory sau mỗi chat.
- Không overwrite active patient khi hỏi all-patients.

Tiêu chí hoàn thành:

- Refresh trang, mở session cũ vẫn hỏi tiếp được.

### M15.3 - Long conversation summary

Mục tiêu:

- Tóm tắt hội thoại dài để tiết kiệm token.

Yêu cầu:

- Khi session dài quá threshold, tạo summary.
- Không làm mất patient/resource context quan trọng.

Việc cần làm:

- Rule-based summary V1 hoặc LLM summary V2.
- Lưu summary vào session.
- Test câu hỏi sau khi summary.

Tiêu chí hoàn thành:

- Không cần gửi full history vào LLM.

### M15.4 - Token budget guard

Mục tiêu:

- Tránh prompt vượt token limit.

Yêu cầu:

- Giới hạn evidence.
- Giới hạn recent messages.
- Giới hạn answer length nếu cần.

Việc cần làm:

- Estimate prompt size.
- Cắt context theo priority.
- Log khi context bị rút gọn.

Tiêu chí hoàn thành:

- Không lỗi do prompt quá lớn trong demo thường gặp.

## M16. Model Routing

### M16.1 - Phân loại request

Mục tiêu:

- Biết request đơn giản hay phức tạp.

Yêu cầu:

- Patient lookup đơn giản.
- Medical summary phức tạp.
- General explanation/RAG sau này.

Việc cần làm:

- Thêm request classification.
- Định nghĩa categories.

Tiêu chí hoàn thành:

- Mỗi request có category rõ ràng trong metadata.

### M16.2 - Routing rule

Mục tiêu:

- Chọn model phù hợp.

Yêu cầu:

- Model rẻ cho intent/extraction đơn giản.
- Model mạnh hơn cho answer phức tạp nếu cần.

Việc cần làm:

- Config routing rules.
- Cho phép fallback model.
- Lưu selected model.

Tiêu chí hoàn thành:

- Usage logs phản ánh model được chọn.

### M16.3 - Admin model config

Mục tiêu:

- Admin chỉnh routing mà không sửa code.

Yêu cầu:

- Default model.
- Fallback model.
- Rule theo operation/category.

Việc cần làm:

- Config table hoặc file.
- Admin API/UI.

Tiêu chí hoàn thành:

- Admin đổi model routing được an toàn.

## M17. Retry & Fallback

### M17.1 - Retry policy

Mục tiêu:

- Tự retry lỗi tạm thời.

Yêu cầu:

- Retry timeout/5xx.
- Không retry lỗi validation/permission.
- Có max attempts.

Việc cần làm:

- Retry wrapper cho OpenAI/FHIR.
- Exponential backoff.
- Log mỗi lần retry.

Tiêu chí hoàn thành:

- Lỗi tạm thời có cơ hội tự phục hồi.

### M17.2 - Fallback model/service

Mục tiêu:

- Chuyển sang phương án dự phòng khi model chính lỗi.

Yêu cầu:

- Model fallback.
- Template fallback nếu LLM fail.

Việc cần làm:

- Config fallback model.
- Catch exception và retry model phụ.
- Ghi metadata `fallback_used`.

Tiêu chí hoàn thành:

- User vẫn nhận câu trả lời an toàn khi model chính lỗi.

### M17.3 - User-facing failure response

Mục tiêu:

- Trả thông báo phù hợp khi không xử lý được.

Yêu cầu:

- Không hallucinate.
- Nói rõ không thể lấy dữ liệu hiện tại.

Việc cần làm:

- Chuẩn hóa error response.
- UI hiển thị lỗi không phá layout.

Tiêu chí hoàn thành:

- Lỗi service ngoài không làm app crash.

## M18. Audit Log

### M18.1 - Audit truy cập dữ liệu bệnh nhân

Mục tiêu:

- Ghi lại ai xem dữ liệu bệnh nhân nào.

Yêu cầu:

- User id.
- Patient id/resource id.
- Action.
- Time.

Việc cần làm:

- Audit khi chat trả patient data.
- Audit khi mở patient detail.
- Metadata gồm intent/tool.

Tiêu chí hoàn thành:

- Có thể truy vết lịch sử truy cập patient data.

### M18.2 - Audit thao tác admin

Mục tiêu:

- Ghi lại thay đổi cấu hình quan trọng.

Yêu cầu:

- Quota changes.
- Model config changes.
- User status/role changes.

Việc cần làm:

- Wrapper audit trong admin service.
- Lưu old/new values.

Tiêu chí hoàn thành:

- Mỗi thay đổi admin có audit record.

### M18.3 - Audit search/filter

Mục tiêu:

- Cho Admin tra cứu audit log.

Yêu cầu:

- Filter theo user, action, resource, date range.
- Pagination.

Việc cần làm:

- API `GET /api/admin/audit-logs`.
- UI table.

Tiêu chí hoàn thành:

- Admin tìm được audit event cần thiết.

## M19. Alert Management

### M19.1 - Alert threshold

Mục tiêu:

- Cấu hình ngưỡng cảnh báo.

Yêu cầu:

- Quota gần hết.
- Cost vượt ngưỡng.
- Error rate cao.
- FHIR/AI service down.

Việc cần làm:

- Config thresholds.
- Job hoặc check runtime.

Tiêu chí hoàn thành:

- Hệ thống biết khi nào cần tạo alert.

### M19.2 - Alert generation

Mục tiêu:

- Tạo cảnh báo khi điều kiện xảy ra.

Yêu cầu:

- Alert có severity.
- Có status open/resolved.
- Có source.

Việc cần làm:

- Table `alerts` nếu cần.
- Service tạo alert.
- Chống tạo trùng alert liên tục.

Tiêu chí hoàn thành:

- Alert được lưu và hiển thị.

### M19.3 - Alert dashboard

Mục tiêu:

- Admin xem cảnh báo.

Yêu cầu:

- Danh sách alert.
- Filter severity/status.
- Mark resolved.

Việc cần làm:

- Admin API/UI.

Tiêu chí hoàn thành:

- Admin xử lý được cảnh báo.

## M20. System Configuration

### M20.1 - LLM configuration

Mục tiêu:

- Quản lý cấu hình LLM.

Yêu cầu:

- Provider.
- Model default.
- Model fallback.
- Timeout.
- Max token.

Việc cần làm:

- Config qua env trước.
- Sau này có thể đưa vào database/admin UI.

Tiêu chí hoàn thành:

- Có thể đổi model mà không sửa logic nghiệp vụ.

### M20.2 - FHIR configuration

Mục tiêu:

- Quản lý cấu hình FHIR server.

Yêu cầu:

- Base URL.
- Timeout.
- Retry.

Việc cần làm:

- Env `FHIR_BASE_URL`.
- Health check `/fhir/status`.

Tiêu chí hoàn thành:

- Chatbot-service báo rõ trạng thái FHIR.

### M20.3 - Quota/cache/retry configuration

Mục tiêu:

- Quản lý các cấu hình vận hành.

Yêu cầu:

- Default quota.
- Cache TTL.
- Retry attempts.
- Rate limit.

Việc cần làm:

- Config file/env V1.
- Admin config V2.
- Validate config khi startup.

Tiêu chí hoàn thành:

- Sai config được phát hiện sớm.

## III. COULD HAVE

## M21. Conversation Search

### M21.1 - Search index

Mục tiêu:

- Tìm kiếm hội thoại cũ theo từ khóa.

Yêu cầu:

- Search title/message content.
- Chỉ search session của user hiện tại.

Việc cần làm:

- Query SQL `ILIKE` V1.
- Full-text search V2 nếu cần.

Tiêu chí hoàn thành:

- User tìm được session theo keyword.

### M21.2 - Filter hội thoại

Mục tiêu:

- Lọc hội thoại theo metadata.

Yêu cầu:

- Date range.
- Patient id.
- Intent/tool.

Việc cần làm:

- API query params.
- UI filter controls.

Tiêu chí hoàn thành:

- Staff tìm lại cuộc chat theo bệnh nhân.

## M22. Feedback

### M22.1 - Feedback UI

Mục tiêu:

- Cho người dùng đánh giá câu trả lời.

Yêu cầu:

- Useful/not useful.
- Comment tùy chọn.

Việc cần làm:

- Button feedback trên assistant message.
- API submit feedback.

Tiêu chí hoàn thành:

- Feedback gắn đúng message.

### M22.2 - Feedback storage

Mục tiêu:

- Lưu feedback để cải thiện hệ thống.

Yêu cầu:

- Message id.
- User id.
- Rating.
- Comment.
- Created at.

Việc cần làm:

- Table `message_feedback`.
- Repository/service.

Tiêu chí hoàn thành:

- Admin xem được feedback.

### M22.3 - Feedback analytics

Mục tiêu:

- Đo chất lượng câu trả lời.

Yêu cầu:

- Tỷ lệ helpful.
- Câu trả lời bị dislike nhiều.
- Lọc theo intent/tool.

Việc cần làm:

- Admin report.

Tiêu chí hoàn thành:

- Có cơ sở ưu tiên cải thiện chatbot.

## M23. Export Conversation

### M23.1 - Export scope

Mục tiêu:

- Cho phép xuất hội thoại có kiểm soát.

Yêu cầu:

- Export theo session.
- Export theo date range.
- Kiểm tra quyền trước khi export.

Việc cần làm:

- API export.
- UI chọn phạm vi.

Tiêu chí hoàn thành:

- User chỉ export được dữ liệu mình có quyền xem.

### M23.2 - Export formats

Mục tiêu:

- Hỗ trợ định dạng phổ biến.

Yêu cầu:

- PDF.
- CSV hoặc Excel.

Việc cần làm:

- Generate file server-side.
- Include metadata cần thiết.
- Không include raw sensitive metadata không cần.

Tiêu chí hoàn thành:

- File export đọc được và đúng nội dung.

### M23.3 - Export audit

Mục tiêu:

- Truy vết thao tác xuất dữ liệu.

Yêu cầu:

- Ghi user, session/date range, format.

Việc cần làm:

- Audit log action `EXPORT_CONVERSATION`.

Tiêu chí hoàn thành:

- Admin biết ai đã export dữ liệu nào.

## M24. Advanced Analytics

### M24.1 - Question analytics

Mục tiêu:

- Biết người dùng hỏi nhiều về chủ đề nào.

Yêu cầu:

- Group theo intent/tool.
- Count theo ngày.

Việc cần làm:

- Lưu intent/tool trong metadata.
- Query analytics.

Tiêu chí hoàn thành:

- Admin thấy top question categories.

### M24.2 - Error analytics

Mục tiêu:

- Theo dõi chất lượng vận hành.

Yêu cầu:

- Error rate theo service.
- Error type.
- Time series.

Việc cần làm:

- Chuẩn hóa error log.
- Dashboard chart.

Tiêu chí hoàn thành:

- Phát hiện service hay lỗi.

### M24.3 - Performance analytics

Mục tiêu:

- Theo dõi latency.

Yêu cầu:

- Average latency.
- P95/P99 nếu đủ dữ liệu.
- Theo model/tool.

Việc cần làm:

- Lưu latency trong usage logs.
- Query aggregate.

Tiêu chí hoàn thành:

- Biết request nào chậm.

## M25. Notification

### M25.1 - Notification channel

Mục tiêu:

- Xác định cách gửi thông báo.

Yêu cầu:

- In-app notification.
- Email sau này.

Việc cần làm:

- Table notifications.
- UI notification center.
- Email provider nếu cần.

Tiêu chí hoàn thành:

- Admin/user nhận được thông báo quan trọng.

### M25.2 - Notification templates

Mục tiêu:

- Chuẩn hóa nội dung thông báo.

Yêu cầu:

- Quota warning.
- Cost warning.
- System error.

Việc cần làm:

- Template message.
- Variables.

Tiêu chí hoàn thành:

- Thông báo nhất quán và dễ hiểu.

### M25.3 - Notification dispatch

Mục tiêu:

- Gửi thông báo đúng người đúng thời điểm.

Yêu cầu:

- Không gửi trùng quá nhiều.
- Ghi trạng thái sent/read.

Việc cần làm:

- Service dispatch.
- Read/unread API.

Tiêu chí hoàn thành:

- Người nhận thấy được thông báo và đánh dấu đã đọc.

## M26. Backup & Restore

### M26.1 - Backup policy

Mục tiêu:

- Định nghĩa dữ liệu nào cần backup.

Yêu cầu:

- App DB.
- HAPI DB demo nếu cần.
- Không backup secrets vào repo.

Việc cần làm:

- Viết quy trình backup.
- Chọn nơi lưu file backup.

Tiêu chí hoàn thành:

- Có lịch backup rõ ràng.

### M26.2 - Backup automation

Mục tiêu:

- Tự động backup theo lịch.

Yêu cầu:

- Cron/scheduled job.
- Log kết quả.
- Alert nếu fail.

Việc cần làm:

- Script `pg_dump`.
- Scheduled task hoặc CI job.

Tiêu chí hoàn thành:

- Backup được tạo định kỳ.

### M26.3 - Restore procedure

Mục tiêu:

- Khôi phục dữ liệu khi cần.

Yêu cầu:

- Có hướng dẫn restore.
- Test restore trên môi trường local/staging.

Việc cần làm:

- Script restore.
- Tài liệu từng bước.

Tiêu chí hoàn thành:

- Có thể restore database từ backup đã tạo.

## IV. WON'T HAVE / LATER

## M27. Auto Diagnosis

### M27.1 - Ràng buộc an toàn

Mục tiêu:

- Không để hệ thống tự chẩn đoán thay bác sĩ.

Yêu cầu:

- Không kết luận bệnh nếu FHIR Condition không ghi.
- Không đưa lời khuyên điều trị dứt khoát.

Việc cần làm:

- Prompt safety rules.
- Answer template có câu "theo dữ liệu hiện có".
- Test câu hỏi yêu cầu chẩn đoán.

Tiêu chí hoàn thành:

- Chatbot từ chối hoặc trả lời an toàn khi bị yêu cầu tự chẩn đoán.

### M27.2 - Clinical approval later

Mục tiêu:

- Nếu muốn làm clinical decision support, cần quy trình riêng.

Yêu cầu:

- Có chuyên gia y tế kiểm định.
- Có guideline rõ ràng.
- Có disclaimer và risk control.

Việc cần làm:

- Đưa ra khỏi scope demo.
- Ghi rõ trong docs.

Tiêu chí hoàn thành:

- Scope không bị hiểu nhầm là hệ thống chẩn đoán.

## M28. Auto Prescription

### M28.1 - Không kê đơn tự động

Mục tiêu:

- Chatbot không tự tạo đơn thuốc.

Yêu cầu:

- Chỉ hiển thị thuốc đã có trong `MedicationRequest`.
- Không đề xuất thêm/sửa/ngừng thuốc.

Việc cần làm:

- Guardrail trong prompt.
- Test câu hỏi "hãy kê thuốc".

Tiêu chí hoàn thành:

- Chatbot từ chối kê đơn mới.

### M28.2 - Medication display only

Mục tiêu:

- Tra cứu thuốc ở mức hiển thị dữ liệu.

Yêu cầu:

- Nêu rõ thuốc là dữ liệu ghi nhận.
- Nếu thiếu thông tin liều, nói là thiếu.

Việc cần làm:

- Normalize MedicationRequest đầy đủ.
- Answer format an toàn.

Tiêu chí hoàn thành:

- Không biến tra cứu thuốc thành chỉ định điều trị.

## M29. Real Hospital Integration

### M29.1 - Integration readiness

Mục tiêu:

- Chuẩn bị để sau này kết nối bệnh viện thật.

Yêu cầu:

- FHIR base URL configurable.
- Auth với FHIR server.
- Mapping code/terminology.

Việc cần làm:

- Không hardcode HAPI demo.
- Thiết kế adapter config.

Tiêu chí hoàn thành:

- Có thể thay FHIR endpoint qua config.

### M29.2 - Data governance

Mục tiêu:

- Đảm bảo an toàn khi dùng dữ liệu thật.

Yêu cầu:

- Access control.
- Audit.
- Consent nếu cần.
- Không gửi dữ liệu thừa vào LLM.

Việc cần làm:

- Security review trước khi dùng real data.
- Chính sách masking/minimization.

Tiêu chí hoàn thành:

- Có checklist trước khi tích hợp hệ thống thật.

## M30. Mobile Application

### M30.1 - Responsive web first

Mục tiêu:

- Bản đầu ưu tiên web responsive.

Yêu cầu:

- UI dùng được trên tablet/mobile cơ bản.
- Không cần native app trong scope đầu.

Việc cần làm:

- Responsive CSS.
- Test viewport nhỏ.

Tiêu chí hoàn thành:

- Dashboard không vỡ layout trên màn hình nhỏ.

### M30.2 - Native app later

Mục tiêu:

- Định nghĩa hướng làm mobile sau này.

Yêu cầu:

- Reuse Spring API.
- Auth token phù hợp mobile.
- Notification nếu cần.

Việc cần làm:

- Chưa triển khai trong demo.
- Ghi future scope.

Tiêu chí hoàn thành:

- Không làm phát sinh scope mobile trong giai đoạn hiện tại.

## M31. Voice Chatbot

### M31.1 - Speech to text

Mục tiêu:

- Cho phép hỏi bằng giọng nói sau này.

Yêu cầu:

- Ghi âm.
- Chuyển speech sang text.
- Xin quyền microphone.

Việc cần làm:

- Chưa triển khai.
- Thiết kế privacy notice nếu làm.

Tiêu chí hoàn thành:

- Voice input được đưa thành text trước khi gọi chat.

### M31.2 - Text to speech

Mục tiêu:

- Đọc câu trả lời bằng giọng nói sau này.

Yêu cầu:

- TTS tiếng Việt.
- Không tự đọc dữ liệu nhạy cảm ở nơi công cộng nếu chưa có xác nhận.

Việc cần làm:

- Chưa triển khai.
- Thêm toggle bật/tắt đọc kết quả.

Tiêu chí hoàn thành:

- User kiểm soát được voice output.

## M32. AI Training / Fine-tuning

### M32.1 - Không fine-tune trong scope đầu

Mục tiêu:

- Tránh mở rộng scope và rủi ro dữ liệu.

Yêu cầu:

- Không huấn luyện model từ đầu.
- Không fine-tune bằng dữ liệu bệnh nhân thật.

Việc cần làm:

- Dùng LLM API có sẵn.
- Cải thiện bằng prompt/tool/RAG trước.

Tiêu chí hoàn thành:

- Không có pipeline training trong bản demo.

### M32.2 - Evaluation trước khi training

Mục tiêu:

- Nếu sau này cần fine-tune, phải đánh giá trước.

Yêu cầu:

- Bộ câu hỏi test.
- Gold answers.
- Data governance.
- Chuyên gia y tế review.

Việc cần làm:

- Ghi vào future scope.
- Ưu tiên RAG và rule/tool improvement.

Tiêu chí hoàn thành:

- Fine-tuning chỉ được xem xét khi có nhu cầu và dữ liệu hợp lệ.

## Gợi Ý Thứ Tự Triển Khai Tiếp Theo

Dựa trên trạng thái hiện tại của dự án, thứ tự nên làm tiếp:

1. Hoàn thiện auth cơ bản và user thật: M1.
2. Làm access control cho patient data: M4.7, M18.1.
3. Hoàn thiện admin dashboard tối thiểu cho usage/cost/quota: M13, M8, M9.
4. Thêm cache an toàn cho câu hỏi giải thích chung: M14.
5. Thêm RAG cho kiến thức y khoa không phải dữ liệu bệnh nhân: mở rộng M6/M14/M15.
6. Tăng observability, error handling, audit: M11, M18, M19.
7. Sau đó mới xét mobile, voice, hospital integration thật.
