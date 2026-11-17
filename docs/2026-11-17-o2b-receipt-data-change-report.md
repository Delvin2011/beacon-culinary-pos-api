# O2b receipt data: change report

**Branch:** `feature/o2b-receipt-data`, branched from `feature/o2-tills-shifts`. O2, and O1 beneath it, aren't merged into `main` yet. Nothing has been pushed or merged.
**Contract:** `docs/2026-11-17-o2b-receipt-data-api-contract.md` is the contract the frontend builds against.
**Tests:** 814 of 814 pass on the full suite. O2 ended at 779; O2b adds 35 tests.
**Database:** one migration, `V108__o2b_receipt_settings_and_order_vat.sql`, applied only to the test database (`BeaconculinaryTest`).

## 1. What changed

The backend now gives the frontend everything it needs to print a receipt:
- **Business details:** a single, site-wide set of receipt settings. An ADMIN edits them; the till reads them.
- **VAT:** every new sale or complimentary order stores the VAT rate and the VAT included in its total.
- **Cashier and till:** each order now gives the cashier's name and the till's name, alongside the existing till code.

These fields appear wherever an order is returned: in the 201 from `POST /orders`, in the O1 replay, in `GET /orders/{id}`, and in reprints. Orders from before O2b keep `null` VAT fields and aren't backfilled.

## 2. Commits

| Step | Commit | What |
|---|---|---|
| 1 | `aa84693` | API contract |
| 2 | `24f8bbd` | V108; `VatRule`; VAT snapshot on new orders; `vatRate`, `vatIncludedAmount`, `cashierName`, `tillName` on `OrderDto` |
| 3 | `9d3781d` | `GET /pos/receipt-settings`, `GET` and `PUT /admin/receipt-settings` |
| 4 | (this commit) | This report |

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
| `O2bReceiptSettingsIntegrationTests` | 14 | Empty defaults. Saving trimmed values and who saved. Full replacement. Values at their limits. Length errors as a field map. More than 4 lines. `showVatLine` missing. Writes and admin reads ADMIN-only. The POS read for CASHIER and ADMIN. 401 without sign-in |

## 6. Files

**Created (13)**
- `docs/2026-11-17-o2b-receipt-data-api-contract.md`
- `docs/2026-11-17-o2b-receipt-data-change-report.md`
- `src/main/resources/db/migration/V108__o2b_receipt_settings_and_order_vat.sql`
- `src/main/java/com/beaconculinary/api/orders/VatRule.java`
- `src/main/java/com/beaconculinary/api/receipts/ReceiptSettingsController.java`
- `src/main/java/com/beaconculinary/api/receipts/ReceiptSettingsDtos.java`
- `src/main/java/com/beaconculinary/api/receipts/ReceiptSettingsExceptionHandler.java`
- `src/main/java/com/beaconculinary/api/receipts/ReceiptSettingsService.java`
- `src/main/java/com/beaconculinary/api/receipts/ReceiptSettingsStore.java`
- `src/main/java/com/beaconculinary/api/receipts/ReceiptSettingsValidationException.java`
- `src/test/java/com/beaconculinary/api/orders/VatRuleTest.java`
- `src/test/java/com/beaconculinary/api/orders/O2bOrderReceiptIntegrationTests.java`
- `src/test/java/com/beaconculinary/api/receipts/O2bReceiptSettingsIntegrationTests.java`

**Modified (5)**
- `docs/2026-11-17-o2-tills-shifts-api-contract.md` (a pointer to the O2b contract)
- `src/main/java/com/beaconculinary/api/orders/Order.java`
- `src/main/java/com/beaconculinary/api/orders/OrderDto.java`
- `src/main/java/com/beaconculinary/api/orders/OrderMapper.java`
- `src/main/java/com/beaconculinary/api/orders/OrderService.java`

**Deleted:** none.

## 7. Still open

These aren't built until answered (contract, section 8):
1. **Business name in reports:** take it from `app.business.name`, or from the receipt settings with the setting as a fallback?
2. **Tax invoice:** what title and unique serial number? `orderNumber` restarts each day.
3. **Line names:** store each line's name on the order so reprints are exact?
