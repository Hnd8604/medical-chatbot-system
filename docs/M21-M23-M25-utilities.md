# M21 / M23 / M25 — Tính năng tiện ích

Ba module tiện ích bổ trợ cho luồng hội thoại chính ([M2](M2-conversation-management.md)):

- **M21 — Conversation Search:** tìm kiếm & lọc hội thoại cũ.
- **M23 — Export Conversation:** xuất hội thoại ra PDF/CSV có kiểm soát quyền và audit.
- **M25 — Notification:** thông báo in-app (quota/cost warning, system error) kèm trạng thái đọc.

---

# M21. Conversation Search

## M21.1 - Search index

**Mục tiêu:** Tìm hội thoại cũ theo từ khóa.

**Hành vi:**
- `GET /api/chat/sessions?query=&limit=` → `ChatApplicationService.sessions()`.
- Khi có `query`, gọi `searchSessionsForUser()` — SQL `ILIKE` (V1) trên **title**, **active_patient_id** và **nội dung message** (subquery `exists` vào `chat_messages`).
- Luôn ràng buộc `s.user_id = :userId` → chỉ search session của user hiện tại.
- `query` được normalize: trim, bỏ rỗng, giới hạn tối đa 100 ký tự (`normalizeSessionSearchQuery()`, ném 400 nếu quá dài).
- Frontend debounce 300ms trước khi gọi (`ChatPage` `useEffect` quanh `loadSessions`).

**Tiêu chí hoàn thành:** User tìm được session theo keyword.

## M21.2 - Filter hội thoại

**Mục tiêu:** Lọc hội thoại theo metadata (date range, patient id, intent/tool).

**Hành vi hiện tại:**
- **Patient id:** đã hỗ trợ qua chính ô search (ILIKE khớp `active_patient_id`).
- **Date range:** `findSessionsByDateRangeForUser(userId, from, to)` đang được dùng cho export theo ngày ([M23](#m23-export-conversation)); có thể tái dùng cho filter danh sách.
- **Intent/tool:** lưu trong `metadata_json` của assistant message và trong `chat_sessions.last_intent`/`last_tool_name` (từ [M15](M15-context-management.md)) — là cơ sở để mở rộng filter.

**Tiêu chí hoàn thành:** Staff tìm lại cuộc chat theo bệnh nhân.

---

# M23. Export Conversation

## M23.1 - Export scope

**Mục tiêu:** Xuất hội thoại có kiểm soát.

**Hành vi:**
- Export theo **session**: `GET /api/chat/sessions/{sessionId}/export?format=`.
- Export theo **date range**: `GET /api/chat/export?from=&to=&format=`.
- **Kiểm tra quyền trước khi export**: export session gọi `existsForUser(sessionId, userId)`, ném lỗi nếu session không thuộc user; export theo ngày chỉ lấy session của user hiện tại (`findSessionsByDateRangeForUser`).

**Tiêu chí hoàn thành:** User chỉ export được dữ liệu mình có quyền xem.

## M23.2 - Export formats

**Mục tiêu:** Hỗ trợ định dạng phổ biến.

**Hành vi:**
- **PDF** (mặc định) qua OpenPDF — `generatePdf()`; nhúng font Arial (`C:/Windows/Fonts/arial.ttf`) để hiển thị tiếng Việt Unicode, fallback Helvetica nếu thiếu font.
- **CSV** qua Apache Commons CSV — `generateCsv()`; có **BOM UTF-8** (`EF BB BF`) để Excel đọc đúng tiếng Việt; cột `Session, Time, Role, Content`.
- Chỉ include nội dung cần thiết (title, thời gian, role, content) — **không** include raw sensitive metadata.

**Tiêu chí hoàn thành:** File export đọc được và đúng nội dung.

## M23.3 - Export audit

**Mục tiêu:** Truy vết thao tác xuất dữ liệu.

**Hành vi:**
- Mỗi lần export đều ghi `AuditLog` action `EXPORT_CONVERSATION` qua `logExportAction()`.
- Ghi `resourceType` (`SESSION` hoặc `DATE_RANGE`), `resourceId` (session id hoặc "from to to"), và `format` (`pdf`/`csv`) trong metadata.

**Tiêu chí hoàn thành:** Admin biết ai đã export dữ liệu nào.

---

# M25. Notification

## M25.1 - Notification channel

**Mục tiêu:** Xác định cách gửi thông báo.

**Hành vi:**
- **In-app**: bảng `notifications` (entity `Notification`), API `GET /api/notifications`.
- **Email**: hiện là **mock** — `sendMockEmail()` chỉ log ra console khi user có email (sẵn sàng thay bằng provider thật).

**Tiêu chí hoàn thành:** User/Admin nhận được thông báo quan trọng.

## M25.2 - Notification templates

**Mục tiêu:** Chuẩn hóa nội dung thông báo.

**Hành vi:**
- Enum `NotificationType`: `QUOTA_WARNING`, `SYSTEM_ERROR`.
- Nội dung quota warning được format chuẩn với biến phần trăm sử dụng, ví dụ: *"Hạn mức sử dụng hằng ngày của bạn đã đạt 85.0%. Vui lòng sử dụng tiết kiệm."* (`QuotaService`).

**Tiêu chí hoàn thành:** Thông báo nhất quán, dễ hiểu.

## M25.3 - Notification dispatch

**Mục tiêu:** Gửi đúng người, đúng thời điểm, không trùng lặp.

**Hành vi:**
- `QuotaService` chỉ tạo quota warning nếu `!hasQuotaWarningBeenSentToday(userId)` → **chống gửi trùng trong ngày**.
- Trạng thái đọc/chưa đọc: `is_read` + API `POST /api/notifications/{id}/read` và `POST /api/notifications/read-all`.
- `markAsRead()` kiểm tra notification thuộc đúng user trước khi cập nhật (chống truy cập chéo).
- **Realtime qua SSE**: `NotificationService.createNotification()` push thông báo mới tới các tab
  đang mở qua `GET /api/notifications/stream`; frontend `ChatPage` nhận và hiển thị badge tức thì
  (thay cho polling 60s cũ). Chi tiết: [notification-sse-stream.md](notification-sse-stream.md).

**Tiêu chí hoàn thành:** Người nhận thấy thông báo và đánh dấu đã đọc được.

---

## Luồng chương trình

### M21 — Search
```
HistorySidebar (ô tìm kiếm) → ChatPage.sessionQuery (debounce 300ms)
   GET /api/chat/sessions?query=&limit=30
      → ChatApplicationService.sessions(query, limit)
         normalizeSessionSearchQuery(query)   (trim, ≤100 ký tự, else 400)
         query == null → findRecentSessionsForUser(userId, limit)
         query != null → searchSessionsForUser(userId, query, limit)
                          ILIKE trên title / active_patient_id / message content
                          AND s.user_id = userId
      → ChatSessionListResponse (title, updated_at, message_count, preview, active_patient)
```

### M23 — Export
```
ExportModal / nút Export (ChatPage)
   GET /api/chat/sessions/{id}/export?format=pdf|csv     (theo session)
   GET /api/chat/export?from=&to=&format=pdf|csv          (theo ngày)
      → ExportService
         kiểm tra quyền: existsForUser(id, userId) | findSessionsByDateRangeForUser
         findMessagesForSession(id, userId)  (ASC, từ M3)
         format=csv → generateCsv() (BOM UTF-8, cột Session/Time/Role/Content)
         else       → generatePdf() (OpenPDF, font Arial cho tiếng Việt)
         logExportAction() → AuditLog action EXPORT_CONVERSATION
      → trả byte[] kèm Content-Disposition: attachment
```

### M25 — Notification
```
Nguồn phát:
   QuotaService (khi usage chạm ngưỡng)
      if !hasQuotaWarningBeenSentToday(userId):
         createNotification(userId, QUOTA_WARNING, title, content)
            → lưu notifications
            → notificationStreamService.publish(userId, item)   ← push SSE
            → (nếu có email) sendMockEmail() log

Tiêu thụ (realtime, xem notification-sse-stream.md):
   ChatPage mở EventSource: GET /api/notifications/stream?token=<jwt>
      event "connected"    → loadNotifications() (baseline + resync sau reconnect)
      event "notification" → prepend item + unread_count++ (dedupe theo id)
   User đọc: POST /api/notifications/{id}/read  (kiểm tra ownership)
             POST /api/notifications/read-all
```

## Luồng trong code

| Module | Điểm vào | File:hàm |
|---|---|---|
| M21 | API search/list | [ChatApplicationService.sessions()](../backend/src/main/java/com/medicalchatbot/backend/service/ChatApplicationService.java) |
| M21 | SQL ILIKE | [ChatSessionRepository.findSearchSessionViewsForUser()](../backend/src/main/java/com/medicalchatbot/backend/repository/ChatSessionRepository.java) |
| M21 | UI search box | [ChatPage.tsx loadSessions()](../frontend/src/pages/ChatPage.tsx) |
| M23 | API export | [ExportController](../backend/src/main/java/com/medicalchatbot/backend/controller/ExportController.java) |
| M23 | PDF/CSV + audit | [ExportService](../backend/src/main/java/com/medicalchatbot/backend/service/ExportService.java) |
| M23 | UI | [ChatPage.exportSession()](../frontend/src/pages/ChatPage.tsx), `components/chat/ExportModal.tsx` |
| M25 | API notification + SSE stream | [NotificationController](../backend/src/main/java/com/medicalchatbot/backend/controller/NotificationController.java) |
| M25 | Tạo/đọc + mock email + push SSE | [NotificationService](../backend/src/main/java/com/medicalchatbot/backend/service/NotificationService.java) |
| M25 | Registry emitter realtime | [NotificationStreamService](../backend/src/main/java/com/medicalchatbot/backend/service/NotificationStreamService.java) — xem [notification-sse-stream.md](notification-sse-stream.md) |
| M25 | Phát quota warning | [QuotaService](../backend/src/main/java/com/medicalchatbot/backend/service/QuotaService.java) |

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| Search/list session | `backend/.../service/ChatApplicationService.java`, `.../repository/ChatSessionRepository.java` |
| Export controller/service | `backend/.../controller/ExportController.java`, `.../service/ExportService.java` |
| Notification controller/service/entity | `backend/.../controller/NotificationController.java`, `.../service/NotificationService.java`, `.../entity/Notification.java`, `.../enums/NotificationType.java` |
| Realtime SSE (stream + client) | `backend/.../service/NotificationStreamService.java`, `frontend/src/services/api.ts` (`openNotificationStream`) — xem `docs/notification-sse-stream.md` |
| Phát notification (quota) | `backend/.../service/QuotaService.java` |
| UI search / export / notification | `frontend/src/pages/ChatPage.tsx`, `.../components/chat/{HistorySidebar,ExportModal}.tsx` |
