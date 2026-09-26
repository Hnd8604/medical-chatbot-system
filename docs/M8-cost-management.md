# M8. Cost Management

Quản lý chi phí vận hành AI: bảng giá theo model, tính cost từng request, thống kê và giám sát cost. Dựa trên dữ liệu [M7 (Usage)](M7-usage-tracking.md).

## M8.1 - Bảng giá model

**Mục tiêu:** Lưu giá token theo model.

**Hành vi:**
- Bảng `model_pricing` trong baseline V1, entity `ModelPricing`: provider/model
  (unique khi active), giá input/output **per 1M tokens**, currency.
- Baseline V1 seed giá cho các model OpenAI và Groq đang dùng, gồm
  `llama-3.1-8b-instant`.
- API `GET /api/model-pricing` (active pricing); admin quản trị qua `ModelPricingAdminController`/`ModelPricingAdminService`.

**Tiêu chí hoàn thành:** Spring tìm được pricing cho model hiện tại.

## M8.2 - Tính cost từng request

**Mục tiêu:** Tính estimated cost từ token.

**Công thức** (`CostEstimationService`):
```
cost = input_tokens  * input_price_per_1m  / 1_000_000
     + output_tokens * output_price_per_1m / 1_000_000
```

**Hành vi:**
- `estimateUsd(provider, model, inputTokens, outputTokens, fallback)`: tìm pricing active theo provider+model; nếu có → tính theo công thức; nếu **thiếu pricing** → dùng `fallbackEstimatedCostUsd` từ chatbot-service.
- Round **6 chữ số thập phân** (`RoundingMode.HALF_UP`, `COST_SCALE = 6`).

**Tiêu chí hoàn thành:** `usage_logs.estimated_cost_usd` khác 0 khi có token và pricing.

## M8.3 - Cost summary

**Mục tiêu:** Thống kê chi phí theo ngày/model/user.

**Hành vi:**
- API `GET /api/usage/cost-summary?from=&to=` (`CostManagementService.currentUserCostSummary`).
- Aggregate từ `usage_logs`: tổng cost, cost theo model, theo ngày, missing-pricing model nếu có.
- UI hiển thị khối "Chi phí AI" (`PatientInsightPanel` / `UsagePage`).

**Tiêu chí hoàn thành:** Staff/admin xem được cost hôm nay.

## M8.4 - Admin cost view

**Mục tiêu:** Cho Admin giám sát chi phí vận hành.

**Hành vi:**
- `GET /api/admin/costs/users/{username}` (cost theo user), `GET /api/admin/costs/pricing` (bảng giá) — `AdminCostController`.
- UI: trang **Usage & Cost** (`AdminUsageCostPage`) — tải quota policies (`/api/admin/quotas/policies`), bảng giá (`/api/admin/costs/pricing`), danh sách user, rồi cost/quota theo user đã chọn (`/api/admin/costs/users/{username}`, `/api/admin/quotas/users/{username}`).
- Cảnh báo khi vượt ngưỡng: quota cost limit → chặn + alert ([M9](M9-quota-management.md), [M19](M18-M19-audit-alert.md)).

**Tiêu chí hoàn thành:** Admin nắm được chi phí theo thời gian.

## Luồng chương trình

```
Mỗi lượt chat (`ChatInteractionRecorder` — M7):
   CostEstimationService.estimateUsd(provider, model, inTok, outTok, fallback)
      findActiveByProviderAndModel(provider, model)
        ├─ có pricing → cost = inTok*inPrice/1e6 + outTok*outPrice/1e6  (scale 6)
        └─ thiếu      → fallback cost từ chatbot-service (normalize scale 6)
   → ghi usage_logs.estimated_cost_usd

Xem chi phí:
   GET /api/usage/cost-summary?from=&to=  → CostManagementService
        aggregate usage_logs → tổng cost, theo model, theo ngày
   GET /api/admin/costs/users/{username}  → AdminCostController (giám sát admin)
```

## Luồng trong code

- **Tính cost:** `CostEstimationService.estimateUsd()` / `calculate()` trong
  [CostEstimationService.java](../backend/src/main/java/com/medicalchatbot/backend/service/CostEstimationService.java).
- **Gọi khi ghi usage:**
  [ChatInteractionRecorder.java](../backend/src/main/java/com/medicalchatbot/backend/service/ChatInteractionRecorder.java).
- **Summary:** `UsageLogRepository` trả các projection tổng hợp;
  `CostMapper` chuyển chúng thành API DTO cho `CostManagementService`.
- **Admin cost:** `AdminCostController` (`/api/admin/costs`).

## Thành phần liên quan trong mã nguồn

| Vai trò | File |
|---|---|
| Tính cost | `backend/.../service/CostEstimationService.java` |
| Summary cost | `backend/.../service/CostManagementService.java` |
| Projection/mapper | `backend/.../repository/projection/Cost*Projection.java`, `.../mapper/CostMapper.java` |
| Admin cost API | `backend/.../controller/AdminCostController.java` |
| Pricing entity/admin | `backend/.../entity/ModelPricing.java`, `.../service/ModelPricingAdminService.java` |
| Usage & Cost UI | `frontend/src/pages/AdminUsageCostPage.tsx` |
| Migration giá | `db/migration/V1__baseline_schema_and_seed.sql` (bảng `model_pricing` + seed giá) |
