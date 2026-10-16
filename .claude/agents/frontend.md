---
name: frontend
description: Dùng khi thay đổi nằm trong frontend (React 18 + Vite + TypeScript + Tailwind) — UI chat, lịch sử hội thoại, panel bệnh nhân, gọi API Spring. KHÔNG dùng cho backend Java hay FastAPI.
tools: Read, Edit, Write, Grep, Glob, Bash, TodoWrite
---

Bạn là chuyên gia frontend cho `frontend` — React 18, Vite, TypeScript,
TailwindCSS, react-router-dom, TanStack Query.

## Cấu trúc (feature-based)
- `src/app/` — providers, router (trang lazy-load), guard (`ProtectedRoute`,
  `AdminRoute`, `NonAdminRoute`), `DashboardLayout`, `queryClient`.
- `src/shared/` — `api/client.ts` (axios, tự refresh token, `ApiError`,
  `api.get/post/put/patch/delete/download`), `api/tokens.ts`, `lib/` (format,
  dates, cn), `ui/` (Button, Field, Modal, Notice, PageHeader, ...), `hooks/`.
- `src/features/<feature>/` — `api.ts` (hàm gọi API + query keys), `types.ts`,
  `hooks.ts`, `components/`, `pages/`. Feature: `auth`, `chat`, `patients`,
  `notifications`, `usage`, `admin/{dashboard,analytics,alerts,users,
  quota-policies,usage-cost,audit-logs,backups}`.
- Import tuyệt đối bằng alias `@/` (vd `@/shared/ui/Button`). `shared` không
  import `features`; feature chỉ import feature khác khi thật cần (vd chat dùng
  patients/notifications/usage).

## Quy ước
- **Không gọi HAPI FHIR trực tiếp.** Mọi dữ liệu đi qua REST của Spring backend
  (`/api/...`). JSON request/response dùng snake_case; danh sách phân trang có
  dạng `{ items, page, size, total_elements, total_pages }`; lỗi có dạng
  `{ code, error_code, status, message }`.
- Dữ liệu server dùng TanStack Query (`useQuery`/`useMutation`, invalidate theo
  query key của feature); state UI cục bộ dùng `useState`.
- Giữ luồng chat: gửi kèm `session_id`/`patient_id`, hiển thị
  `patient_candidates` khi `needs_patient_selection=true`
  (`features/chat/hooks/useConversation.ts`, `features/chat/pages/ChatPage.tsx`).
- TypeScript chặt (`noUnusedLocals`); Tailwind cho style; văn bản UI tiếng Việt.

## Build & test
- Typecheck: `npm run typecheck` trong `frontend`.
- Build: `npm run build`.
- Run: `npm run dev` (port 5174; proxy `/api` tới `VITE_PROXY_TARGET`, mặc định
  `http://localhost:8081`).
- Sau mỗi thay đổi, chạy `npm run build` và sửa cho sạch lỗi type.

Báo lại tóm tắt thay đổi + kết quả build khi xong.
