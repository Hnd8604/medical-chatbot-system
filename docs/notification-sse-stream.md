# Notification: Realtime qua SSE (Server-Sent Events)

Tài liệu này mô tả kênh **đẩy thông báo realtime** của `backend` → `frontend`.
Thay cho việc frontend poll `GET /api/notifications` mỗi 60 giây, mỗi thông báo mới (quota
warning, system error…) được **push tức thì** tới các tab đang mở của user qua một kết nối
SSE mở sẵn. Phần nghiệp vụ notification (tạo, đọc, mock email) xem [M25](M21-M23-M25-utilities.md);
tài liệu này chỉ tập trung vào lớp vận chuyển realtime.

## 1. Vì sao SSE (không phải polling / WebSocket)

| Cách | Đánh giá |
|---|---|
| **Polling 60s** (cũ) | Đơn giản nhưng trễ tới 60s, và gọi API liên tục kể cả khi không có gì mới. |
| **WebSocket** | Song công 2 chiều — thừa. Thông báo chỉ đi **một chiều** server → client. |
| **SSE** (bản này) | 1 chiều server → client trên HTTP thường, `EventSource` **tự kết nối lại**, không thêm hạ tầng. Vừa đủ nhu cầu. |

## 2. Endpoint

| Method | Path | Auth | Content-Type | Mô tả |
|---|---|---|---|---|
| GET | `/api/notifications/stream` | bearer **hoặc** `?token=` | `text/event-stream` | Mở kết nối SSE, giữ mở để nhận thông báo của user hiện tại. |

Các API notification khác (`GET /api/notifications`, `POST /{id}/read`, `POST /read-all`)
giữ nguyên — xem [M25](M21-M23-M25-utilities.md).

### Sự kiện gửi qua stream

| `event:` | `data:` | Ý nghĩa |
|---|---|---|
| `connected` | `"ok"` | Gửi ngay khi (re)connect — client dùng làm mốc **resync** (load đầy đủ). |
| `notification` | JSON của `NotificationItem` | Có thông báo mới cần hiển thị ngay. |

`NotificationItem`: `{ id, type, title, content, is_read, created_at }` — cùng shape với item
trong `GET /api/notifications` (dùng chung `NotificationItem.from(...)`).

## 3. Xác thực: vì sao token đi qua query param

`EventSource` của trình duyệt **không cho set header** `Authorization`. Nên riêng endpoint SSE,
access token được đính kèm qua query param `?token=<jwt>`.

Để hạn chế lộ token (URL hay bị ghi vào access log), [JwtAuthenticationFilter](../backend/src/main/java/com/medicalchatbot/backend/config/JwtAuthenticationFilter.java)
**chỉ** chấp nhận token-qua-query khi:

```text
method == GET  &&  requestURI kết thúc bằng "/api/notifications/stream"
```

Mọi endpoint khác vẫn **chỉ** đọc token từ header `Authorization: Bearer`. Thứ tự ưu tiên
trong `resolveToken()`: header trước, rồi mới tới query param (chỉ cho path stream), không có
thì coi là request ẩn danh. Toàn bộ flow validate JWT còn lại (parse, `token_version`, trạng
thái tài khoản) **không đổi**.

## 4. Phía server: registry emitter theo user

[NotificationStreamService](../backend/src/main/java/com/medicalchatbot/backend/service/NotificationStreamService.java)
giữ các kết nối đang mở:

```text
Map<UUID userId, List<SseEmitter>>   // CopyOnWriteArrayList: 1 user có thể mở nhiều tab
```

- **`subscribe(userId)`**: tạo `SseEmitter` (timeout 30 phút), thêm vào list của user, đăng ký
  `onCompletion/onTimeout/onError` để **tự dọn** khỏi map, rồi gửi event `connected`.
- **`publish(userId, item)`**: gửi event `notification` tới **mọi** emitter của user; emitter nào
  lỗi khi gửi thì bị loại bỏ. Nếu user không có kết nối nào (offline) → no-op (thông báo vẫn đã
  lưu DB, sẽ thấy ở lần load kế tiếp).

Điểm đẩy nằm ngay trong `NotificationService.createNotification(...)` (sau khi `save`), nên **cả
hai nguồn** phát thông báo đều tự động realtime, không phải sửa nơi gọi:

```text
QuotaService.checkAndTriggerQuotaWarning()  ─┐
ApiExceptionHandler.notifyCurrentUser()      ─┤→ NotificationService.createNotification()
                                                  ├─ notificationRepository.save()
                                                  ├─ notificationStreamService.publish(userId, item)   ← push SSE
                                                  └─ sendMockEmail() (nếu có email)
```

> **Timeout & reconnect:** emitter đóng sau 30 phút; `EventSource` phía client tự mở lại → phát
> `connected` → client resync. Không cần job dọn thủ công (cleanup gắn với vòng đời emitter).

## 5. Phía client

[services/api.ts](../frontend/src/services/api.ts) — `openNotificationStream(handlers)`:

- Lấy access token từ `sessionStorage`, mở `EventSource` tới
  `${API_BASE_URL}/api/notifications/stream?token=<jwt>`.
- Trả về hàm **đóng kết nối** (`source.close()`) để cleanup khi unmount.

[ChatPage.tsx](../frontend/src/pages/ChatPage.tsx) trong một `useEffect`:

```text
mount → loadNotifications()                    // baseline
      → openNotificationStream({
            onConnected:   () => loadNotifications()   // resync sau mỗi (re)connect
            onNotification: raw => prepend item + unread_count++   // dedupe theo id
        })
unmount → close()                              // đóng EventSource
```

- **`notification`** → thêm item vào đầu danh sách + tăng badge chưa đọc, **bỏ qua nếu id đã có**
  (chống trùng khi vừa resync vừa nhận push).
- **`connected`** → gọi `loadNotifications()` để **bắt kịp phần bị lỡ** trong lúc mất kết nối
  (tab ngủ, mạng chập chờn). Đây là lưới an toàn khiến "không mất thông báo" dù SSE rớt.

## 6. Proxy / triển khai

- Vite dev proxy (`/api` → `:8081`, `changeOrigin`) truyền thẳng stream, không buffer SSE.
- SSE chạy trên Spring MVC async (Tomcat) mặc định — **không cần cấu hình thêm**. `SessionCreationPolicy.STATELESS`
  không ảnh hưởng vì `userId` được chốt tại thời điểm `subscribe`, emitter sống độc lập với
  SecurityContext của request.

## 7. Thành phần code

| File | Vai trò |
|---|---|
| [NotificationStreamService](../backend/src/main/java/com/medicalchatbot/backend/service/NotificationStreamService.java) | Registry emitter theo user; `subscribe` / `publish` / auto-cleanup |
| [NotificationController](../backend/src/main/java/com/medicalchatbot/backend/controller/NotificationController.java) | `GET /api/notifications/stream` → `SseEmitter` |
| [NotificationService](../backend/src/main/java/com/medicalchatbot/backend/service/NotificationService.java) | `createNotification()` gọi `publish()` sau khi lưu |
| [NotificationItem](../backend/src/main/java/com/medicalchatbot/backend/dto/response/NotificationItem.java) | `from(Notification)` — shape dùng chung cho REST + SSE |
| [JwtAuthenticationFilter](../backend/src/main/java/com/medicalchatbot/backend/config/JwtAuthenticationFilter.java) | `resolveToken()` — chấp nhận `?token=` riêng cho path stream |
| [services/api.ts](../frontend/src/services/api.ts) | `openNotificationStream()` — mở `EventSource`, trả hàm đóng |
| [ChatPage.tsx](../frontend/src/pages/ChatPage.tsx) | Đăng ký stream, prepend/dedupe item, resync khi `connected` |

## 8. Kiểm thử

`backend`, `./mvnw.cmd test` (JAVA_HOME = JDK 21):

- [JwtAuthenticationFilterTest](../backend/src/test/java/com/medicalchatbot/backend/config/JwtAuthenticationFilterTest.java):
  token qua query param xác thực được path `/api/notifications/stream`; token qua query param
  **bị bỏ qua** ở path khác (coi như ẩn danh).
- [NotificationControllerTest](../backend/src/test/java/com/medicalchatbot/backend/controller/NotificationControllerTest.java):
  list/mark-read giữ nguyên hành vi (mock thêm `NotificationStreamService`).

Sau khi thay đổi luồng SSE, chạy `./mvnw.cmd test` trong `backend/` và
`npm run typecheck` trong `frontend/`; không ghi cố định số lượng test vì bộ test
tiếp tục được mở rộng.

## 9. Giới hạn hiện tại / hướng mở rộng

- **Token trong URL**: đã giới hạn đúng path stream. Chặt hơn nữa: dùng cookie `HttpOnly` cho SSE
  (đổi cơ chế auth của endpoint này), nhưng với scope đồ án thì query-param-có-giới-hạn là đủ.
- **Nhiều instance backend**: registry emitter là **in-memory**, chỉ đúng khi chạy 1 instance.
  Muốn scale ngang: đẩy sự kiện qua Redis Pub/Sub để mọi instance cùng fan-out tới emitter của mình.
- **Bù trừ mất kết nối**: hiện dựa vào `connected` → full reload để resync. Có thể tối ưu bằng
  `Last-Event-ID` để chỉ gửi phần thiếu (chưa cần thiết ở quy mô hiện tại).
