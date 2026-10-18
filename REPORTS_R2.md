# Reports R2 — Backend Work Order (corrected) and contract additions

R2 adds three reports to the R1 reporting foundation. **Status: built.** Acceptance tests:
`ReportsR2IntegrationTests` (27 tests); R1, registry and Head Chef suites still pass unchanged
in their figures.

| Key | Title | Category | Roles |
|---|---|---|---|
| `meal-extras-sales` | Meal & Extras Sales | `SALES` | `ADMIN`, `HEAD_CHEF` |
| `account-balances` | Account Balances | `PAYMENTS_ACCOUNTS` | `ADMIN` |
| `account-statement` | Account Statement | `PAYMENTS_ACCOUNTS` | `ADMIN` |

## Corrections to the original work order

1. **Migration** is `V91__reports_r2_definitions.sql` (V88–V90 were already taken).
2. **Pre-range fixture activity** moved to **Sat 26 Sep** (R200 account order on A1, own closed
   shift) and **Sun 27 Sep** (R50 payment). The original 3/4 Oct fell inside R1's default
   range (30 Sep – 6 Oct) and would have changed R1's `totals.orders = 9`. Acme's opening is
   still 150.00.
3. **`limit`** accepts `ALL` or any positive whole number; `ALL`, `10`, `20`, `50` are the
   suggested options. (`limit = 2` was not a valid option before.)
4. **HEAD_CHEF exists** (V89), so `meal-extras-sales` is `ADMIN` + `HEAD_CHEF` now.
5. **Payments have no method or reference column.** Reference = `Payment #<id>`, Description =
   the payment's note (the fixture's Tuesday payment has the note `EFT`).
6. **Notes wording**: removed extras on a still-`PENDING` order are a void (cancelled before
   cooking) but count as *Refunded/removed*, as the bucket rule says. The notes now read:
   "Voided portions are from orders voided whole. Refunded or removed portions are from orders
   refunded whole, or are extras taken off an order."
7. **Export title block** names an ID parameter by its label (`accountId=Acme Logistics`), using
   the new `meta.paramLabels`.
8. **Rank ties**: item name A–Z, then meal period in order of the day.

## Step 1 answers

**Extras removal.** One-time per order (`orders.extras_adjusted`). It takes every remaining extra
at its full quantity and sets `order_line_extras.adjusted = 1`, recorded as an `order_adjustments`
row with scope `EXTRAS_ONLY` (action `VOID` while the order is `PENDING`, otherwise `REFUND`). A
later whole-order adjustment is for the order's remaining total, so nothing is paid back twice.
Removed units = `adjusted = 1` on an order that isn't `VOIDED`.

**Account ledger.**
- Charges: `order_payments` with method `ACCOUNT`, linked by `account_id`.
- Credits: `order_adjustments` with refund method `ACCOUNT_BALANCE` (voids, refunds, extras
  removed and legacy after-payment discounts alike — the same as Tender Summary's Account credits).
- Payments received: `account_payments` (`amount`, `note`, `recorded_by`, `created_at` only).
- No opening balance, no credit limit (so no Credit limit / Over-limit columns).
- The account screen (`GET /admin/accounts/{id}/balance`, `AccountService.computeBalance`)
  shows charged − reversed − paid over all time, with no date cut-off.

## Shared definitions (V91)

**`v_order_line_facts`** — one row per meal line and per extras line: `item_type`
(`MEAL`/`EXTRA`), `catalog_id`, `item_name` (the catalog's current name, so a rename keeps one
row), `meal_period_id` (from the plan entry sold), `plu_code` (`NULL`: no PLU column yet),
`order_id`, `shift_id`, `shift_opened_at_utc`, `trading_day` (day of sale), `quantity`,
`unit_price` (snapshot), `gross = unit_price × quantity` (order creation's arithmetic), and
`unit_bucket`:
- `VOIDED` — the order is `VOIDED` (wins over everything else);
- `REFUNDED_OR_REMOVED` — the order is `REFUNDED`, or the extra was removed (`adjusted = 1`);
  an extra removed and later refunded is counted here once;
- `NET` — otherwise.

A line's units always share one bucket (removal takes a whole extras line).

**`v_offered_items`** — one row per plan entry: meals from `daily_meal_options`, extras from
`daily_component_stock` (`item_type`, `catalog_id`, `meal_period_id`, `plu_code`, `plan_date`).
Every plan entry counts, whatever its status.

**`v_account_movements`** — one row per movement (`movement_type`, `source_id`, `account_id`,
`amount`, `entry_day`, `entry_at_utc`, `occurred_at`, `order_id`, `reference`, `description`):
- `CHARGE` — ACCOUNT payment on an order; `entry_day` = day of sale; reference `Order #n`.
- `CREDIT` — adjustment paid out `ACCOUNT_BALANCE`; `entry_day` = day processed; description
  `Order voided` / `Order refunded` / `Extras removed` / `Discount after payment (legacy)`.
- `PAYMENT` — settlement; `entry_day` = its Johannesburg date; reference `Payment #id`.

`entry_at_utc` is the instant whose Johannesburg date is `entry_day`, so ranges are a half-open
UTC window like R1. Charges with no `account_id` are left out, which makes
`charges-agree-with-tender` fail on such data.

## Envelope additions (contract)

- **`ParamType.ID`** — a database id; `options` is a list of `{ "value": "2", "label": "Lunch" }`
  objects, filled from the database each time the catalogue is requested. A value not in the
  options → `400` (`"Select an account"`, `"Select a meal period"`).
- **`ParamType.LIMIT`** — `"ALL"` or a positive whole number; `options` lists suggestions.
  Echoed in `meta.params` as text (`"ALL"`, `"10"`).
- **`required: true`** on a parameter spec (absent otherwise). Missing → `400`, field message
  `"Select an account"` for an ID, `"<Label> is required."` otherwise.
- **`page`** — `{ "number": 0, "size": 10, "totalRows": 37 }` when rows are a limited slice;
  `null` otherwise. Totals, ranks and shares always cover every matching row.
- **`meta.paramLabels`** — the label of each ID parameter that is set,
  e.g. `{ "accountId": "Acme Logistics" }`; omitted when empty.
- **`ColumnType.DECIMAL`** — a plain 2-decimal number with no currency (Avg net per day
  offered). Excel format `0.00`.

Rounding: percentages and averages to 2 decimals, half-up.

## The reports

### `meal-extras-sales` (v1)
Params: `from`, `to` · `groupBy` `ITEM`|`MEAL_PERIOD` · `type` `ALL`|`MEAL`|`EXTRA` ·
`mealPeriodId` (ID, optional) · `sortBy` `NET_PORTIONS`|`GROSS_SALES` · `direction`
`DESC`|`ASC` · `limit` (LIMIT).

- `ITEM` columns: `rank`, `item`, `type` (`Meal`/`Extra`), `plu`, `mealPeriod`, `daysOffered`,
  `portionsRung`, `voided`, `refundedOrRemoved`, `netPortions`, `avgNetPerDayOffered` (DECIMAL;
  null when days offered is 0), `grossAsRung`, `shareOfGross` (PERCENT).
- `MEAL_PERIOD` columns: `mealPeriod`, `itemsOffered` (items with days offered > 0),
  `portionsRung`, `voided`, `refundedOrRemoved`, `netPortions`, `grossAsRung`, `shareOfGross`.
- Rows = every item offered in the range plus anything sold, one per item per meal period.
- Check `gross-agrees-with-sales-summary`: gross as rung over all lines (no filters, no limit)
  = Sales Summary gross sales.

### `account-balances` (v1)
Params: `from`, `to` · `show` `ALL`|`OWING`|`MOVEMENT` · `sortBy` `CLOSING_BALANCE`|`NAME` ·
`direction` `DESC`|`ASC`. Columns: `account`, `openingBalance`, `charges`, `credits`,
`paymentsReceived`, `closingBalance`, `accountOrders`, `lastPayment` (DATETIME); rows also carry
`accountId`. Closing = opening + charges − credits − payments.

Checks (over every account, whatever `show`): `charges-agree-with-tender`,
`credits-agree-with-tender`, `balance-arithmetic` (count of failing accounts),
`matches-account-screen` (only when `to` ≥ today; sums `AccountService.getBalance` per account),
`credit-balances` (WARN with "N accounts are in credit", PASS at 0).

### `account-statement` (v1)
Params: `accountId` (ID, **required**; labels end "(inactive)" for closed accounts), `from`, `to`.
Columns: `occurredAt` (DATETIME), `type` (STATUS: OPENING neutral, CHARGE warning, CREDIT
positive, PAYMENT positive), `reference`, `description`, `charge`, `credit`, `payment`,
`balance` (running, server-side). First row is the opening balance at the start of `from`.
Totals: charge, credit, payment summed; `balance` = closing. Check
`closing-agrees-with-balances-report`.

## Fixture (added to `ReportFixture`; R1 data unchanged)
Catalog, plan and lines exactly as in the original work order; accounts A1 Acme Logistics,
A2 Zulu Catering, A3 Dormant Co; O6 → A1, O9 and its R15 extras credit → A2; Tue 6 Oct 14:00
R100 payment from A1 (note `EFT`); pre-range activity on 26/27 Sep (see correction 2).
`ReportFixture.clear` now also empties `account_payments` and `accounts`, and removes the
fixture's catalog items and any plan entries dated 26 Sep – 6 Oct 2026.

## Not in R2
Allocating discounts across items, per-cashier splits (R4), cost or margin columns, PDF
statements (R3), any change to the account screen.
