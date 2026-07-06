# Mô tả đề tài

Xây dựng hệ thống chatbot hỗ trợ người dùng tra cứu thông tin y tế từ FHIR Server hoặc cơ sở dữ liệu mô phỏng thông qua ngôn ngữ tự nhiên. Đề tài tập trung vào xây dựng một hệ thống hỏi đáp có kiến trúc rõ ràng, có khả năng quản lý lịch sử hội thoại, theo dõi mức sử dụng AI và áp dụng một số kỹ thuật tối ưu vận hành cơ bản.

Hệ thống gồm các phân hệ chính:

- Phân hệ hội thoại: tiếp nhận câu hỏi, quản lý phiên chat, lưu lịch sử trao đổi và trả lời người dùng.
- Phân hệ truy vấn dữ liệu y tế: kết nối với FHIR Server hoặc database trung gian để lấy dữ liệu bệnh nhân, lịch sử khám, thuốc, xét nghiệm, chẩn đoán.
- Phân hệ quản lý tài nguyên sử dụng: theo dõi số lượt gọi, token tiêu thụ, chi phí, quota theo người dùng hoặc nhóm người dùng.
- Phân hệ tối ưu vận hành: cache kết quả, rút gọn context, giới hạn dữ liệu gửi vào mô hình, routing request theo loại truy vấn, fallback khi dịch vụ ngoài gặp lỗi.
- Phân hệ quản trị và giám sát: dashboard thống kê usage, chi phí, tần suất truy vấn, cảnh báo vượt quota, nhật ký hoạt động và audit log.

Các vấn đề kỹ thuật có thể triển khai:

- Thiết kế database cho user, conversation, message, usage log, quota, cache, audit log
- Rate limiting, quota management, budget limit
- Model Routing
- Context pruning, lưu summary hội thoại
- Theo dõi chi phí theo user/model/thời gian
- Logging, monitoring, retry, fallback, error handling
- Đóng gói và triển khai hệ thống bằng Docker/Docker Compose

Về phần AI: Đã có sẵn các API call đến AI service nội bộ hoặc mô hình LLM bên ngoài.

*Yêu cầu đầu ra*

- Xây dựng được chatbot có khả năng hỏi đáp trên dữ liệu y tế từ FHIR Server hoặc cơ sở dữ liệu mô phỏng.
- Có thiết kế kiến trúc hệ thống và thiết kế cơ sở dữ liệu rõ ràng.
- Có chức năng quản lý lịch sử hội thoại, quota, usage và chi phí.
- Có dashboard theo dõi hoạt động hệ thống và mức sử dụng tài nguyên.
- Có áp dụng các kỹ thuật tối ưu vận hành như cache, rate limit, quota control, context reduction, model routing.
- Có tài liệu kỹ thuật gồm kiến trúc, thiết kế DB, API và hướng dẫn triển khai.
- Hệ thống có thể đóng gói và chạy bằng Docker/Docker Compose.

Hướng 2: Tối ưu vận hành và quản lý tài nguyên AI

Tên đề tài:
Tối ưu vận hành hệ thống chatbot y tế thông qua quản lý token, quota, cache và model routing

Trọng tâm: tối ưu chi phí, hiệu năng, độ ổn định khi dùng AI.

Tập trung làm:

- Theo dõi token, số lượt gọi, chi phí theo user/model/thời gian.
- Quota theo user hoặc nhóm user.
- Rate limit, budget limit.
- Cache câu trả lời hoặc kết quả truy vấn.
- Context pruning, summary hội thoại.
- Model routing: câu đơn giản dùng model rẻ, câu phức tạp dùng model mạnh.
- Retry, fallback khi AI service lỗi.

Phù hợp nếu muốn bài thiên về: engineering tối ưu, vận hành thực tế, cost-control, scalable AI system.

Mục tiêu: Giảm chi phí, latency, token sử dụng; Tăng throughput, khả năng phục vụ nhiều người dùng

*Keyword:*

Semantic Cache: thay vì cache theo exact text

Ví dụ:
"Các xét nghiệm gần nhất của bệnh nhân A"
và
"Cho tôi xem xét nghiệm mới nhất của bệnh nhân A"
→ cùng cache.

Công nghệ:

- Redis Vector Similarity Search
- Qdrant
- Milvus

Context Compression: Rút gọn hội thoại cũ.

Kỹ thuật:

- Conversation Summary
- Rolling Summary
- Memory Compression

Framework:

- LangGraph Memory

Intelligent Model Routing

Ví dụ:
Truy vấn FAQ -> sử dụng GPT 4o
Phân tích phức tạp -> GPT-5

Kỹ thuật:

- LLM Router
- Classifier Router
- Cost-aware Routing

AI Gateway

Tạo một lớp trung gian quản lý:

- API Key
- Logging
- Cost Tracking
- Routing

Công nghệ:

- LiteLLM

Đánh giá:

- Giảm bao nhiêu token
- Giảm bao nhiêu chi phí
- Tăng throughput bao nhiêu %
