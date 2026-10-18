# Complimentary Meals — contract additions

A manager can authorise a **complimentary meal**: a normal order (kitchen, daily order number,
stock) with **no payment**. Its list value is kept so reports can show what was given away. It
is not a discount. Migration: `V92__complimentary_meals.sql`.

## Money fields
```
SALE:           pre_discount_total − sale_discount = original_total (= Σ payments) − adjustments = total
COMPLIMENTARY:  pre_discount_total = list value; sale_discount = 0; original_total = 0; total = 0; no payments
```

## `POST /orders`
Optional `complimentary` object (the whole order):
```json
"complimentary": {
  "reasonCode": "STAFF_MEAL",
  "note": "Kitchen team lunch",
  "authorization": { "authorizationToken": "..." }
}
```
- `reasonCode`: `STAFF_MEAL`, `MANAGEMENT`, `GUEST`, `TASTING`, `OTHER` (`OTHER` needs a `note`).
- `authorization`: exactly one of `authorizationToken` (single-use, consumed inside the order
  transaction, so a failed order leaves it valid) or `sessionToken` (never consumed). The
  approver must be an active `ADMIN`. `requested_by` is the signed-in user.
- Leave `payments` out (or send `[]`). A sale order without payments still gets
  `400 { "payments": "payments cannot be empty" }`, exactly as before.

| Code | HTTP | When |
|---|---|---|
| `COMPLIMENTARY_INVALID` | 400 | discount present, payments present, unknown or missing reason, no note for `OTHER`, not exactly one token. Checked before the token is used; nothing is written. |
| `AUTHORIZATION_INVALID` | 401 | token invalid, expired, already used, or the approver isn't an active `ADMIN` |

## Order responses
`POST /orders`, `GET /orders/{id}`, `GET /orders/today` (the receipt is built from these):
- `orderKind`: `SALE` or `COMPLIMENTARY`.
- `complimentary`: `{ reasonCode, note, authorizedBy: { id, name } }`, `null` for a sale.
The receipt shows "COMPLIMENTARY" and no payment.

## Adjustments (`POST /orders/{id}/adjustments`)
- **Void while `PENDING`**: allowed with the manager PIN. Stock is restored. It is recorded as a
  normal adjustment row with `amount = 0` and `refundMethod = CASH`, so it stays in the audit
  trail; nothing is paid out and no cash figure changes.
- **Refund** (order past `PENDING`) and **remove extras** (any status):
  `400 COMPLIMENTARY_ORDER_NOT_REFUNDABLE`, refused before any token is used.

## POS Oversight
`GET /admin/shifts/{id}/activity`:
- `orders[]` and `adjustments[]` rows carry `orderKind`.
- `totals.complimentary = { count, amount }`: complimentary orders sold in the shift, not voided,
  at list value. Always present (zeros when none).
- `shift.orderCount`, `totals.orderCount`, sales by method, discounts, voids, refunds and extras
  removed count **sale orders only** (a complimentary order's zero-value void is listed in
  `adjustments[]` but not in `totals.voids`).
- `expectedCashBreakdown` is unchanged.

`GET /admin/shifts` (list): `orderCount` counts sale orders only.

Unchanged: `GET /shifts/{id}/summary` (`ShiftCashCalculator`, not touched). Its `orderCount`
still counts every order rung in the shift, complimentary ones included.

## Reports
Shared definitions (V92):
- **Sales orders** = `order_kind = SALE`. `v_order_facts` is filtered once, so every R1 sales,
  tender and net-sales figure counts sales orders only. No existing R1 figure changes.
- **Complimentary meals** = `v_complimentary_facts`: complimentary orders not `VOIDED`;
  count = orders, value = Σ `pre_discount_total` (list value), on the day of sale and in the
  shift they were rung in.
- **Meal & Extras lines** (`v_order_line_facts`): new bucket `COMPLIMENTARY`, checked first.
  Lines on voided complimentary orders are left out.

| Report | Change | Version |
|---|---|---|
| Sales Summary | Two columns at the end: `complimentaryOrders` (Complimentary orders), `complimentaryValue` (Complimentary value (at list price)), by day of sale or the order's hour. A period with only complimentary orders gets a row with zero sales. Outside the net-sales identity. | 2 → **3** |
| Cash-up & Variance | `complimentaryMeals` and `complimentaryValue` per shift (before Status), with totals. Cash figures unchanged. | 1 → **2** |
| Meal & Extras Sales | `complimentaryPortions` and `complimentaryValue` (list price) at the end, for both groupings. Complimentary units are not in rung, voided, refunded/removed, net or gross, so `gross-agrees-with-sales-summary` still holds. | 1 → **2** |
| Tender Summary | No change. | 2 |
| Account Balances / Statement | No change (no payments, no account movement). | 1 |

Notes text on Sales Summary and Cash-up: "Complimentary meals are given away with manager
approval and no payment. They are shown separately and are not part of sales or net sales.
Value is at list price."

## Test data (added to `ReportFixture`; every existing figure unchanged)
- **O10**: Mon 12:30, S2, order number 8, R45 (Pap & Stew + Side Salad), `STAFF_MEAL`, approved
  by Admin, collected.
- **O11**: Tue 09:15, S3, order number 3, **R35** (one Pap & Stew; the work order's R30 can't be
  built from Tuesday's plan), `MANAGEMENT`, voided while pending (zero-value void in S3).

Results: Sales Summary Mon 1 / 45.00, Tue 0 / 0.00, total 1 / 45.00; by hour the 12:00 row
1 / 45.00. Cash-up S2 1 / 45.00, S1 and S3 0. POS Activity S2 `{1, 45.00}`, S3 `{0, 0.00}`.
Meal & Extras: Pap & Stew 1 portion / 35.00, Side Salad 1 / 10.00 complimentary.

## Tests
- New: `ComplimentaryOrderIntegrationTests` (7): checkout, every reason code, invalid requests
  with the token left usable, 401s, stock-conflict rollback and session tokens, void while
  pending, refused refund/remove-extras before the token is used, and a sale without payments.
- New in `ReportsIntegrationTests`: a complimentary-only day, two shifts of one day, and POS
  Activity totals on the shared fixture.
- Updated (column lists and versions only): `ReportsIntegrationTests` (Sales Summary by day,
  legacy discount, CSV export, Cash-up) and `ReportsR2IntegrationTests` (Meal & Extras by item,
  by meal period, CSV export). No test assumed every order had a payment.
