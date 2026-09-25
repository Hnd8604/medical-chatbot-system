# M13. Admin Dashboard

Giao diện quản trị hệ thống dành cho Admin: KPI tổng quan, biểu đồ analytics (intent/error/performance), theo dõi & xử lý alert, và các trang con (audit logs, usage/cost). KPI request giờ lấy từ API analytics chuyên dụng ([M24](M24-M26-analytics-backup.md)) thay vì đếm audit-logs.

## M13.1 - KPI tổng quan

**Mục tiêu:** Cho Admin xem nhanh tình trạng hệ thống.

**Chỉ số:** tổng user, tổng AI requests, cache hit, thông báo, alert.

**Hành vi:**
- `AdminDashboardPage.tsx` gọi song song (`Promise.allSettled`):
  - `GET /api/admin/users?page=0&size=1` → tổng số user (`total_elements`).
  - `GET /api/admin/analytics/requests?from=&to=` → tổng AI request (`RequestAnalyticsSummaryResponse`).
  - `GET /api/metrics/cache` → cache metrics.
  - `GET /api/notifications` → thông báo.
- Dùng `Promise.allSettled` nên một endpoint lỗi không làm sập cả dashboard — phần lỗi hiển thị banner cảnh báo.

**Tiêu chí hoàn thành:** Admin nhìn nhanh được mức sử dụng hệ thống.

## M13.2 - Usage / analytics charts

**Mục tiêu:** Phân tích theo intent / lỗi / hiệu năng và theo thời gian.

**Hành vi:**
- Biểu đồ lấy từ `/api/admin/analytics/*` (`AnalyticsService` — [M24](M24-M26-analytics-backup.md)):
  - `GET /api/admin/analytics/intents` → top câu hỏi theo intent/ngày.
  - `GET /api/admin/analytics/errors` → lỗi theo service/loại/ngày.
  - `GET /api/admin/analytics/performance` → latency AVG/P95/P99 theo model.
- Chi tiết usage/cost xem ở trang con **Usage & Cost** (`AdminUsageCostPage`): quota policies, bảng giá, cost theo user/khoảng ngày.

**Tiêu chí hoàn thành:** Admin biết chủ đề hỏi nhiều, service hay lỗi, request chậm.

## M13.3 - Error monitoring & alert handling

**Mục tiêu:** Theo dõi và **xử lý** lỗi/cảnh báo.

**Hành vi:**
- `GET /api/admin/alerts` (có filter + phân trang) → danh sách alert.
- `POST /api/admin/alerts/{alertId}/resolve?resolvedBy=` → đánh dấu alert đã xử lý ngay trên dashboard ([M19](M18-M19-audit-alert.md)).
- Biểu đồ error analytics tổng hợp từ `alerts` ([M24.2](M24-M26-analytics-backup.md)).

**Tiêu chí hoàn thành:** Admin xem và resolve được alert.

## M13.4 - Admin filters & sub-pages

**Mục tiêu:** Drill-down theo user / model / khoảng ngày.

**Hành vi:**
- Analytics endpoint nhận `from`/`to`/`limit`; alerts/audit nhận filter + `page`/`size`.
- Trang con drill-down:
  - **Audit Logs** (`AdminAuditLogsPage`) — lọc theo user/action/resource/date, phân trang ([M18.3](M18-M19-audit-alert.md)).
  - **Usage & Cost** (`AdminUsageCostPage`) — quota/pricing/cost theo user.
  - **Users** (`AdminUsersPage`), **Config** (`AdminConfigPage`).

**Tiêu chí hoàn thành:** Dashboard drill-down được qua các trang con.

## Luồng chương trình

```
Admin mở Dashboard (AdminDashboardPage.tsx) → loadDashboard()
        ▼
Promise.allSettled([
   GET /api/admin/users?page=0&size=1            → tổng user
   GET /api/admin/analytics/requests?from=&to=   → tổng AI request (M24.4)
   GET /api/metrics/cache                        → cache hit/miss
   GET /api/notifications                        → thông báo
])
+ tải biểu đồ analytics (theo filter from/to/limit):
   GET /api/admin/analytics/intents
   GET /api/admin/analytics/errors
   GET /api/admin/analytics/performance
+ alert:
   GET /api/admin/alerts (filter, phân trang)
   POST /api/admin/alerts/{id}/resolve?resolvedBy=   (xử lý alert)
        ▼
Mỗi promise resolve/reject độc lập → fulfilled set state | rejected → banner cảnh báo

Trang con: AdminAuditLogsPage (/api/audit-logs), AdminUsageCostPage (/api/admin/{quotas,costs})
```

## Luồng trong code

- **Frontend tổng hợp:** `AdminDashboardPage.loadDashboard()` ([AdminDashboardPage.tsx:388-393](frontend/src/routes/AdminDashboardPage.tsx#L388-L393)); tải chart ([L305-327](frontend/src/routes/AdminDashboardPage.tsx#L305-L327)); resolve alert ([L373](frontend/src/routes/AdminDashboardPage.tsx#L373)).
- **Backend analytics:** `AnalyticsService` + `AdminAnalyticsController` (`/api/admin/analytics/*`) — [M24](M24-M26-analytics-backup.md).
- **Backend KPI/khác:** `AdminUserController` (`/api/admin/users`), `MetricsController` (`/api/metrics/cache`), `AdminAlertController` (`/api/admin/alerts` + resolve), `AdminCostController` / `CostManagementService` (usage/cost).
- **Nguồn dữ liệu thô:** mọi lượt chat ghi `UsageLog` + `AuditLog` trong `ChatApplicationService` ([saveUsage/saveAuditLog](backend/src/main/java/com/medicalchatbot/backend/service/ChatApplicationService.java#L180-L320)).

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| Trang dashboard | `frontend/src/routes/AdminDashboardPage.tsx` |
| Trang con | `frontend/src/routes/{AdminAuditLogsPage,AdminUsageCostPage,AdminUsersPage,AdminConfigPage}.tsx` |
| Analytics (KPI request + chart) | `backend/.../service/AnalyticsService.java`, `.../controller/AdminAnalyticsController.java` |
| KPI user | `backend/.../controller/AdminUserController.java` |
| Audit | `backend/.../controller/AuditLogController.java` |
| Cost theo user/model | `backend/.../controller/AdminCostController.java`, `.../service/CostManagementService.java` |
| Cache metrics | `backend/.../controller/MetricsController.java` |
| Alert + resolve | `backend/.../controller/AdminAlertController.java` |
| Nguồn usage thô | `backend/.../service/ChatApplicationService.java` (UsageLog/AuditLog) |
