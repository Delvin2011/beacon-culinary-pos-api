# Back Office Dashboard — backend

The back office's landing page: a set of small, independent sections, each its own request, permission check and
cache entry. Read only. Every figure comes from the same shared definitions as its report (R1 `v_order_facts`,
R2 `v_order_line_facts` / `v_account_movements`), so a dashboard number equals the report number for the same range.
Johannesburg time; days are trading days (the date a shift opened).

## Endpoints
```
GET /admin/dashboard/layout                      DASHBOARD_VIEW (else 403)
GET /admin/dashboard/sections/{key}?range=7d     the section's own permission
```
- `layout` → `{ asOf, timezone, sections: [{ key, title, usesRange, refreshSeconds }] }`: only sections the caller may
  see **and** whose stage flags are on, in display order. Runs no section query.
- A section → `{ key, asOf, range, definitionVersion, data, reportLink }`. `asOf` is when the figures were computed
  (a cached answer keeps its original `asOf`). `reportLink` = `{ key, params }` for "View report", or `null`.
- Unknown key (or switched-off stage) **404** · not permitted **403** · bad `range` or parameter **400**
  (`{ "range": "Range must be today, 7d or month." }`) · the section itself failed **500** `{ key, error }`. A failure
  affects that section only.
- `range` = `today` | `7d` (default) | `month`. Validated on every section; used only where `usesRange`.
  For a period figure: today; the last 7 trading days; the calendar month to date. For a series (trend), `today` and
  `7d` both mean the last 7 trading days.

## Sections
| Key | Permission | Range | Refresh | Report link |
|---|---|---|---|---|
| `attention` | DASHBOARD_VIEW (+ each item's own) | – | 30 s | – |
| `now` | DASHBOARD_VIEW (`onTheTill`: REPORTS_CASH) | – | 30 s | – |
| `today` | REPORTS_SALES | – | 60 s | Sales Summary (day) |
| `sales-trend` | REPORTS_SALES | ✓ | 60 s | Sales Summary (day) |
| `sales-mix` | REPORTS_SALES | ✓ | 60 s | Meal & Extras Sales (meal period) |
| `busy-hours` | DASHBOARD_VIEW | – | 60 s | Sales Summary (hour) |
| `heatmap` | REPORTS_SALES | – | 300 s | Sales Summary (hour, the 28 days) |
| `top-sellers` | DASHBOARD_VIEW (`soldValue`: REPORTS_SALES) | ✓ | 60 s | Meal & Extras Sales |
| `sell-through` | DASHBOARD_VIEW | – | 60 s | – |
| `light-meal-stock` | STOCK_VIEW | – | 60 s | – |
| `payment-mix` | REPORTS_CASH | ✓ | 60 s | Tender Summary |
| `basket` | REPORTS_SALES | ✓ | 60 s | Sales Summary |
| `waiting` | DASHBOARD_VIEW | – | 60 s | – |
| `account-customers` | REPORTS_ACCOUNTS | ✓ | 60 s | Account Balances |

**Permissions until U2** map onto roles (`DashboardPermission`): DASHBOARD_VIEW = ADMIN, HEAD_CHEF ·
REPORTS_SALES / REPORTS_CASH / REPORTS_ACCOUNTS / SIGN_OFF_SHIFTS / RUN_EOD = ADMIN · STOCK_VIEW = ADMIN, HEAD_CHEF,
STOCK_ADMIN · DAILY_PLANNING = ADMIN, HEAD_CHEF · approving stock and viewing all requests = ADMIN, STOCK_ADMIN.
So the Head Chef gets `attention`, `now` (no `onTheTill`), `busy-hours`, `top-sellers` (no `soldValue`),
`sell-through`, `light-meal-stock` and `waiting`. Cashier, Kitchen, Barista, Stock Admin and Stock Clerk get 403.
Fields the caller may not see are **absent**, not null.

## Payloads (money = number with 2 decimals; percentages one decimal)
- **attention** `{ items: [{ key, severity, tag, title, detail, action: { label, path }, count, since }], moreCount }`.
  Sorted RED, AMBER, BLUE, NEUTRAL, then oldest `since` first; at most 8. Rules:
  `OVERDUE_SHIFT` (REPORTS_CASH), `UNSIGNED_SHIFTS` (SIGN_OFF_SHIFTS, `signOff`), `DAY_NOT_CLOSED` (RUN_EOD, `signOff`),
  `PENDING_APPROVALS` / `OWN_REQUESTS_WAITING` (BLUE), `LIGHT_MEAL_STOCK` (STOCK_VIEW, `lightMealStock`; RED if any
  OUT), `CASH_VARIANCE` (REPORTS_CASH, NEUTRAL), `SLOW_PLANNED_MEALS` (DAILY_PLANNING). The `action.path` values are
  suggestions (`/pos-activity`, `/end-of-day`, `/stock/requests`, `/catalogue/light-meals`,
  `/reports/cash-up-variance`, `/daily-planning`): map them to the real routes.
- **now** `{ kitchen: { pending, inProgress, open, longestWaitMinutes }, bar: {…} | null, readyToCollect: { count,
  oldestMinutes }, onTheTill?: { shiftId, owner, since, expectedCash } | null }`.
- **today** `{ day, compareDay, compareLabel, tiles: [{ key, label, value, type, secondary, delta, tone }] }`. Tiles:
  `netSales`, `orders`, `averageOrder` (delta vs the same weekday last week, `tone` POSITIVE ≥ 0 / WATCH / NEUTRAL when
  there is no comparison), `voidsRefunds` ("24.6% of gross sales · 2 orders"; WATCH above the watch percent),
  `complimentary` (flag `complimentary`; "R 45,00 at list price"), `discounts` ("1 order").
- **sales-trend** `{ days: [{ day, label, netSales, compare, isToday }] }`.
- **sales-mix** `{ basis: "BEFORE_DISCOUNTS", total, groups: [{ key, label, amount, percent }] }`. Groups: the set-meal
  meal periods (`BREAKFAST`, `LUNCH`…), then `LIGHT_MEALS`, `BEVERAGES` (flag `categories`). Only groups with sales.
- **busy-hours** `{ hours: [{ hour, today, typical }], peak: { hour, orders } | null, typicalBasis }`.
- **heatmap** `{ from, to, hours, rows: [{ weekday, label, tradingDays, cells }], max }`.
- **top-sellers** `{ sortBy, type, items: [{ rank, name, tag, portions, soldValue? }] }`; `sortBy=PORTIONS|VALUE`
  (VALUE needs REPORTS_SALES, else 403), `type=ALL|MEAL|EXTRA`. `tag` = `SET_MEAL`, `LIGHT_MEAL`, `BEVERAGE`, or
  `EXTRA` for extras.
- **sell-through** `{ thresholdPercent, afterTime, rows: [{ name, planned, sold, sellThroughPercent, soldOutAt, slow }] }`.
- **light-meal-stock** `{ meals: [{ mealId, name, status, unitsAvailable, limitingIngredient, lowStockUnits, outToday }],
  moreCount }`.
- **payment-mix** `{ basis: "MONEY_COLLECTED", total, methods: [{ method, amount, percent }] }` (shares add up to 100).
- **basket** `{ averageOrder, series: [{ day, value }], itemsPerOrder, extrasAttachPercent }`.
- **waiting** `{ kitchenMedianMinutes, barMedianMinutes, collectMedianMinutes, longest: { minutes, at } | null }`.
- **account-customers** `{ accounts: [{ accountId, name, amount }], total, orders }`.

## Definitions and decisions
- **Same time.** While today is still trading (no Z reading yet), today is compared with last week's same weekday up
  to the current time of day (`ReportFacts.salesTotalsUntil`, the same select as Sales Summary). Completed days compare
  in full. No shifts on the comparison day → `null`, never 0.
- **Typical weekday / heatmap.** Averages over the weekday's trading days in the 28 days before today (today
  excluded), dividing by the number of those trading days.
- **"As of now."** Live queries ignore shifts and orders later than the clock, so a later-opened shift never shows
  early.
- **End-of-day rules follow R3's first trading day** (`reports.close-day.first-trading-day`): days before it are
  outside the end-of-day process, so their shifts are never "unsigned" and the days never "not closed".
- **Slow planned meals in Needs attention** fire between the cut-off (`slowMeal.afterTime`, 12:30) and the end of
  opening hours (15:00): it is a prompt to act during service. The Planned meals card still marks `slow` after closing.
- **Basket.** Items per order = meal and beverage units ÷ sales orders not voided. Extras attach = orders with a
  planned meal and at least one extra ÷ orders with a planned meal (sales orders not voided).
- **Top sellers** add an item's meal-period rows together (the report has one row per item and meal period).
- **Percent shares** use largest-remainder rounding so they always add up to 100.0.

## Configuration (`application.yaml`, no tables)
`dashboard.variance.threshold` 50.00 · `dashboard.voidRefund.watchPercent` 3.0 · `dashboard.slowMeal.thresholdPercent`
25 · `dashboard.slowMeal.afterTime` "12:30" · `dashboard.openingHours` "06:00-15:00" · `dashboard.cache.enabled` true ·
`dashboard.features.{complimentary,categories,barStation,lightMealStock,signOff,reportDefinitions,accountReports}`
(all shipped, so all on). Quote the times: YAML reads an unquoted 12:30 as a number.

## Caching
Per section for its refresh time, keyed by section + range + whether the caller sees values (+ the role, the
section's own parameters, and the user for `attention`). Expiry is on the application clock. A committed new order or
status change clears the cache; shift changes rely on the short lifetimes.

## Changes outside the dashboard
- **Meal & Extras Sales**: new **Sold value** column (net portions × order-time price, before discounts), on item and
  meal-period rows and totals; definition version 3. It is not "Gross as rung".
- `ReportFacts.salesTotalsUntil` (same-time totals) and `LightMealStockService.reviewAll` (one-pass review).
- Index `IX_orders_status_created_at` (V102) for the live sections.
- `AdminSecurityRules`: `GET /admin/dashboard/**` for ADMIN and HEAD_CHEF. No existing rule or report changed.

## Known gap
**Beverages are missing from Meal & Extras Sales**: it joins meal periods and beverages have none (see
`CATALOGUE_AND_BAR.md`). The dashboard's What sells and Where sales come from **do** include beverages, so once
beverages sell, those two differ from the report by exactly the beverage lines until the R2 fix lands.

## Not in version 1
Per-user layouts, a cash-control card, accounts owed, stock readiness beyond light meals, wastage and food cost, meal
photos, export, and email or phone alerts.
