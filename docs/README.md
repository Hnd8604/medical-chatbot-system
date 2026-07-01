# Tài liệu nghiệp vụ — Medical Agent System

Mô tả chức năng + luồng chương trình + luồng trong code cho từng module (M*), bám theo `module_nghiep_vu_chatbot_y_te_moscow_chi_tiet.md` và mã nguồn thực tế (`spring-backend`, `chatbot-service`, `frontend-react`, `infra`).

## Kiến trúc tổng quan

```
frontend-react (React)  ──HTTP──►  spring-backend (Spring Boot)  ──HTTP──►  chatbot-service (FastAPI)
                                        │                                        │
                                        ▼                                        ▼
                                 App PostgreSQL                            HAPI FHIR + PostgreSQL
                                 (Flyway, JPA)                            Qdrant (semantic cache)
```

- **frontend-react:** UI chat, lịch sử, panel bệnh nhân, admin dashboard.
- **spring-backend:** auth, session/message, quota/cost/usage, audit/alert/notification, export, gọi chatbot-service.
- **chatbot-service:** intent extraction, tool execution (FHIR), answer generation (LLM), semantic cache.

## Mục lục module

### Xác thực & hội thoại
| Module | Mô tả |
|---|---|
| [M1 — Authentication & Access Control](M1-authentication.md) | Đăng ký/đăng nhập (JWT), phân quyền role, onboarding liên kết hồ sơ bệnh nhân |
| [M2 — Conversation Management](M2-conversation-management.md) | Tạo session, gửi câu hỏi, nhận trả lời, tiếp tục hội thoại |
| [M3 — Message History](M3-message-history.md) | Lưu & truy xuất tin nhắn (user/assistant/system) |
| [M4 — Medical Data Query](M4-medical-data-query.md) | Phân loại intent y tế, tra cứu dữ liệu bệnh nhân |
| [M5 — FHIR Integration](M5-fhir-integration.md) | Tích hợp HAPI FHIR (client, endpoint, normalize, seed) |
| [M6 — AI Integration](M6-ai-integration.md) | LLM: intent, tool execution, answer generation, fallback |
| [M15 — Context Management](M15-context-management.md) | Recent messages, session memory, summary, token budget |

### Vận hành AI
| Module | Mô tả |
|---|---|
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

### Tiện ích & dữ liệu
| Module | Mô tả |
|---|---|
| [M21 / M23 / M25 — Search / Export / Notification](M21-M23-M25-utilities.md) | Tìm kiếm, xuất hội thoại, thông báo |
| [M24 / M26 — Advanced Analytics & Backup/Restore](M24-M26-analytics-backup.md) | Phân tích nâng cao + sao lưu/phục hồi |

> Thư mục `docs/` nằm trong `.gitignore` (tài liệu nội bộ, không track bởi git).
