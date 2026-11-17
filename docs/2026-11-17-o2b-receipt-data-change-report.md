# O2b receipt data: change report

**Branch:** `feature/o2b-receipt-data`, branched from `feature/o2-tills-shifts`. O2, and O1 beneath it, aren't merged into `main` yet. Nothing has been pushed or merged.
**Contract:** `docs/2026-11-17-o2b-receipt-data-api-contract.md` is the contract the frontend builds against.
**Tests:** 825 of 825 pass on the full suite. O2 ended at 779; O2b adds 46 tests.
**Database:** two migrations, `V108__o2b_receipt_settings_and_order_vat.sql` and `V109__o2b_receipt_title_and_line_names.sql`, applied only to the test database (`BeaconculinaryTest`).

## 1. What changed

The backend now gives the frontend everything it needs to print a receipt:
- **Business details:** a single, site-wide set of receipt settings. An ADMIN edits them; the till reads them.
- **VAT:** every new sale or complimentary order stores the VAT rate and the VAT included in its total.
- **Cashier and till:** each order now gives the cashier's name and the till's name, alongside the existing till code.

These fields appear wherever an order is returned: in the 201 from `POST /orders`, in the O1 replay, in `GET /orders/{id}`, and in reprints. Orders from before O2b keep `null` VAT fields and aren't backfilled.

Then, on the recommendations (contract section 8.2; no final answers yet):
- **Business name in reports:** the X, Z and Cashier readings take it from the receipt settings, and fall back to `app.business.name` while that's empty.
- **Receipt title:** a new `receiptTitle` setting (e.g. "Tax Invoice"), empty by default.
- **Receipt number:** every order carries `receiptNumber`, `yyyyMMdd-NNNN`, derived from its date and number.
- **Line names:** each line's name and each extra's component name are saved at sale, so later renames don't change old receipts.

## 2. Commits

| Step | Commit | What |
|---|---|---|
| 1 | `aa84693` | API contract |
| 2 | `24f8bbd` | V108; `VatRule`; VAT snapshot on new orders; `vatRate`, `vatIncludedAmount`, `cashierName`, `tillName` on `OrderDto` |
| 3 | `9d3781d` | `GET /pos/receipt-settings`, `GET` and `PUT /admin/receipt-settings` |
| 4 | `ca2bb3e`, `c28dccb` | This report, and its file-list fix |
| A1 | `908a767` | Contract additions (section 8.2) |
| A2 | `b48e8b9` | V109; `receiptNumber`; line and extra names stored at sale |
| A3 | `d820cf2` | `receiptTitle`; business name in reports from the receipt settings |
| A4 | (this commit) | WS1-1b backlog note; this update |

## 3. The tasks

| Task | Status | Where |
|---|---|---|
| Receipt settings table, one row, nothing customer-specific seeded | Done. Row 1 starts with every value empty and `show_vat_line` = 0 | V108 |
| `GET` and `PUT /admin/receipt-settings`, ADMIN only | Done, under the existing `/admin/**` ADMIN rule (no security change) | `receipts/ReceiptSettingsController`, `ReceiptSettingsService` |
| `GET /pos/receipt-settings` | Done, for CASHIER or ADMIN under the existing `/pos/**` rule | same |
| Validation | Text is trimmed; blank becomes `null`; blank address lines are dropped; at most 4 lines; length limits apply after trimming; `showVatLine` is required. Errors come back as a 400 field map | `ReceiptSettingsService` |
| Who saved, and when | `updated_by`, `updated_by_name`, `updated_at` (UTC). The admin view shows `updatedBy` (name) and `updatedAt` (`…Z`) | `ReceiptSettingsStore` |
| VAT snapshot on every new order | `vat_rate` = `vat.standard-rate` (0.1500); `vat_included_amount` = `round(originalTotal × rate ÷ (1 + rate), 2, HALF_UP)`, stored once at creation | `VatRule`, `OrderService` |
| Existing orders stay `NULL` | Yes. No backfill | V108 |
| `cashierName`, `tillCode`, `tillName` on `OrderDto` | Done. The till fields are `null` for the unregistered till | `OrderMapper` |
| Everything in the 201 and in reprints | Yes. Reprints copy `OrderDto`, so they carry the new fields without further changes | `ReceiptReprintService` (unchanged) |
| Business name in reports (recommendation) | Receipt settings' `businessName`, else `app.business.name`; read for each report, so a stored Z keeps its name | `receipts/BusinessName`, `ReportService`, `EndOfDayStatements` |
| Receipt title (recommendation) | `receiptTitle`, at most 50 characters, trimmed, `null` when blank | V109, `ReceiptSettingsService` |
| Receipt number (recommendation) | `receiptNumber` on every order, derived and not stored; unique through `IX_orders_date_number` | `orders/ReceiptNumbers`, `OrderMapper` |
| Line names at sale (recommendation) | `order_lines.name`, `order_line_extras.component_name`, filled at sale; older rows show the current name | V109, `OrderService`, `OrderMapper` |

## 4. The VAT rule, for O4

- **Basis:** `originalTotal`, the amount paid after any discount at sale. A complimentary order has an `originalTotal` of 0.00, so it stores the rate and 0.00 VAT.
- **Formula:** `total × rate ÷ (1 + rate)`, rounded half up to 2 decimals, once. Nothing is rounded along the way.
- **Rate:** the rate is stored at scale 4 (`DECIMAL(6,4)`), so a later rate change doesn't alter past orders.
- **After a void or refund:** neither one changes the stored figures. O4 should work out the VAT on refunds from the refund amounts, using the order's stored `vat_rate`. It should not recompute the VAT from `total`.
- **Item rating:** every item is treated as standard-rated. VAT is worked out on the order total, not per line.

## 5. Tests

| Class | Tests | What |
|---|---|---|
| `VatRuleTest` | 13 | Worked examples (including 0.00 and 0.01), a 0% rate, half-up at a 100% rate, a single rounding, the stored rate's scale, and refusal of negative or null values |
| `O2bOrderReceiptIntegrationTests` | 8 | The snapshot for cash, discounted, split and complimentary orders, and unchanged after a refund. The new fields in the 201, the replay, `GET /orders/{id}` and the reprint. Till fields `null` on the unregistered till. A reprint of an order from before O2b has `null` VAT |
| `O2bReceiptSettingsIntegrationTests` | 16 | Empty defaults. Saving trimmed values and who saved. Full replacement. Values at their limits. Length errors as a field map. More than 4 lines. `showVatLine` missing. The receipt title saved and too long. Writes and admin reads ADMIN-only. The POS read for CASHIER and ADMIN. 401 without sign-in |
| `O2bReceiptAdditionsIntegrationTests` | 6 | The receipt number's format and padding, its presence in the 201, `GET /orders/{id}` and the reprint, and a different number per order. Names stored at sale, kept after a rename, and the current name for older rows |
| `O2bReportBusinessNameIntegrationTests` | 3 | The X reading's business name: the server setting when none is saved, the saved name, and the fallback for a blank stored name |

## 6. Files

**Created (18)**
- `docs/2026-11-17-o2b-receipt-data-api-contract.md`
- `docs/2026-11-17-o2b-receipt-data-change-report.md`
- `src/main/resources/db/migration/V108__o2b_receipt_settings_and_order_vat.sql`
- `src/main/resources/db/migration/V109__o2b_receipt_title_and_line_names.sql`
- `src/main/java/com/beaconculinary/api/orders/ReceiptNumbers.java`
- `src/main/java/com/beaconculinary/api/orders/VatRule.java`
- `src/main/java/com/beaconculinary/api/receipts/BusinessName.java`
- `src/main/java/com/beaconculinary/api/receipts/ReceiptSettingsController.java`
- `src/main/java/com/beaconculinary/api/receipts/ReceiptSettingsDtos.java`
- `src/main/java/com/beaconculinary/api/receipts/ReceiptSettingsExceptionHandler.java`
- `src/main/java/com/beaconculinary/api/receipts/ReceiptSettingsService.java`
- `src/main/java/com/beaconculinary/api/receipts/ReceiptSettingsStore.java`
- `src/main/java/com/beaconculinary/api/receipts/ReceiptSettingsValidationException.java`
- `src/test/java/com/beaconculinary/api/orders/VatRuleTest.java`
- `src/test/java/com/beaconculinary/api/orders/O2bOrderReceiptIntegrationTests.java`
- `src/test/java/com/beaconculinary/api/orders/O2bReceiptAdditionsIntegrationTests.java`
- `src/test/java/com/beaconculinary/api/receipts/O2bReceiptSettingsIntegrationTests.java`
- `src/test/java/com/beaconculinary/api/reports/O2bReportBusinessNameIntegrationTests.java`

**Modified (10)**
- `docs/2026-11-13-device-registration-api-contract.md` (backlog: two items delivered in O2)
- `docs/2026-11-17-o2-tills-shifts-api-contract.md` (a pointer to the O2b contract)
- `src/main/java/com/beaconculinary/api/orders/Order.java`
- `src/main/java/com/beaconculinary/api/orders/OrderDto.java`
- `src/main/java/com/beaconculinary/api/orders/OrderLine.java`
- `src/main/java/com/beaconculinary/api/orders/OrderLineExtra.java`
- `src/main/java/com/beaconculinary/api/orders/OrderMapper.java`
- `src/main/java/com/beaconculinary/api/orders/OrderService.java`
- `src/main/java/com/beaconculinary/api/reports/EndOfDayStatements.java`
- `src/main/java/com/beaconculinary/api/reports/ReportService.java`

**Deleted:** none.

## 7. Built on recommendations, to confirm

Built as the contract recommended (section 8.2), without final answers; each is easy to change:
1. **Business name in reports:** from the receipt settings, falling back to `app.business.name`.
2. **Tax invoice:** `receiptTitle` and `receiptNumber` are built. The accountant still needs to confirm the title and the number's format. The number isn't stored, so changing its format needs no data migration.
3. **Line names:** stored at sale; older orders aren't backfilled.
