# Tài liệu nghiệp vụ — Medical Agent System

Mô tả chức năng, luồng chương trình và luồng trong code cho từng module (M*),
được đối chiếu trực tiếp với mã nguồn hiện tại (`backend`, `chatbot-service`,
`frontend`, `infra`).

> **Mô tả đề tài & hướng chuyên sâu:** [mo-ta-de-tai.md](mo-ta-de-tai.md) — nội dung sản phẩm, các phân hệ, và hướng tối ưu vận hành (token/quota/cache/routing).

## Tài liệu nền tảng

| Tài liệu | Nội dung |
|---|---|
| [Product specification](product-spec.md) | Phạm vi sản phẩm, vai trò, quy tắc dữ liệu và tiêu chí chấp nhận |
| [Mô tả đề tài](mo-ta-de-tai.md) | Bối cảnh, mục tiêu và các phân hệ của đề tài |
| [Hướng tối ưu](optimization-direction.md) | Token, chi phí, cache, routing và chỉ số đánh giá |
| [Database ERD](../backend/docs/m12-database-erd.md) | ERD và data dictionary của app PostgreSQL |
| [Patient link](../backend/docs/patient-link.md) | Liên kết tài khoản ứng dụng với hồ sơ FHIR |

## Kiến trúc tổng quan

```
frontend (React)  ──HTTP──►  backend (Spring Boot)  ──HTTP──►  chatbot-service (FastAPI)
                                        │                                        │
                                        ▼                                        ▼
                                 App PostgreSQL                            HAPI FHIR + PostgreSQL
                                 (Flyway, JPA)                            Qdrant (semantic cache)
```

- **frontend:** UI chat, lịch sử, panel bệnh nhân, admin dashboard.
- **backend:** auth, session/message, quota/cost/usage, audit/alert/notification, export, gọi chatbot-service.
- **chatbot-service:** intent extraction, tool execution (FHIR), answer generation (LLM), semantic cache.

## Mục lục module

### Xác thực & hội thoại
| Module | Mô tả |
|---|---|
| [M1 — Authentication & Access Control](M1-authentication.md) | Đăng ký/đăng nhập (JWT), phân quyền role, onboarding liên kết hồ sơ bệnh nhân |
| [Auth — Refresh Token](auth-refresh-token.md) | Access + refresh token (rotation, Redis) |
| [Auth — Quên mật khẩu](password-reset.md) | Đặt lại mật khẩu qua mã OTP gửi email (Redis, dev fallback) |
| [M2 — Conversation Management](M2-conversation-management.md) | Tạo session, gửi câu hỏi, nhận trả lời, tiếp tục hội thoại |
| [M3 — Message History](M3-message-history.md) | Lưu & truy xuất tin nhắn (user/assistant/system) |
| [M4 — Medical Data Query](M4-medical-data-query.md) | Phân loại intent y tế, tra cứu dữ liệu bệnh nhân |
| [M5 — FHIR Integration](M5-fhir-integration.md) | Tích hợp HAPI FHIR (client, endpoint, normalize, seed) |
| [M6 — AI Integration](M6-ai-integration.md) | LLM: intent, tool execution, answer generation, fallback |
| [M15 — Context Management](M15-context-management.md) | Recent messages, session memory, summary, token budget |
| [Context Compression — Rolling Summary](M-context-rolling-summary.md) | LLM rolling summary (async thuần), chạy song song, trigger theo độ dài hội thoại |

### Vận hành AI
| Module | Mô tả |
|---|---|
| [LiteLLM Gateway](M-litellm-gateway.md) | Gateway duy nhất tới provider, virtual key và budget |
| [LangGraph Agent](M-langgraph-agent.md) | Graph tùy chọn cho routing, multi-step plan và policy validation |
| [Terminology Enrichment](M-terminology-enrichment.md) | Giải thích mã y khoa từ LOINC, RxNorm và MedlinePlus |
| [M7 — Usage Tracking](M7-usage-tracking.md) | Ghi token/model/latency mỗi lượt chat |
| [M8 — Cost Management](M8-cost-management.md) | Bảng giá model, tính & thống kê chi phí |
| [M9 — Quota Management](M9-quota-management.md) | Hạn mức/ngày, chặn 429 khi vượt |
| [M10 — Rate Limiting](M10-rate-limiting.md) | Chống spam (Redis, window 60s) |
| [M14 — Cache Management](M14-cache-management.md) | Semantic cache (Qdrant) + observability |
| [M16 / M17 — Model Routing & Retry/Fallback](M16-M17-model-routing-retry-fallback.md) | Chọn model theo độ phức tạp, fallback khi lỗi |

### Nền tảng & quản trị
| Module | Mô tả |
|---|---|
| [M11 — Logging & Error Handling](M11-logging-error-handling.md) | Log kỹ thuật, lỗi thân thiện, bảo vệ dữ liệu nhạy cảm |
| [M12 — Database Design](M12-database-design.md) | Schema app DB, Flyway migration, JPA |
| [M13 — Admin Dashboard](M13-admin-dashboard.md) | KPI, usage analytics, error monitoring, filters |
| [M18 / M19 — Audit Log & Alert](M18-M19-audit-alert.md) | Nhật ký truy cập + cảnh báo sự cố |
| [M20 / M22 — System Config & Feedback](M20-M22-system-config-feedback.md) | Cấu hình hệ thống + phản hồi người dùng |
| [Notification SSE](notification-sse-stream.md) | Luồng thông báo thời gian thực bằng Server-Sent Events |

### Tiện ích & dữ liệu
| Module | Mô tả |
|---|---|
| [M21 / M23 / M25 — Search / Export / Notification](M21-M23-M25-utilities.md) | Tìm kiếm, xuất hội thoại, thông báo |
| [M24 / M26 — Advanced Analytics & Backup/Restore](M24-M26-analytics-backup.md) | Phân tích nâng cao + sao lưu/phục hồi |
| [Backup lên Google Drive](M-backup-gdrive.md) | Lịch backup, script và cấu hình lưu trữ ngoài máy |

> Thư mục `docs/` được track trong git — tài liệu kỹ thuật (kiến trúc, thiết kế DB, API, hướng dẫn triển khai) là một phần deliverable của đề tài.
