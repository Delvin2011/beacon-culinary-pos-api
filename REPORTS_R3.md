# Reports R3 — End of Day: contract additions

The end-of-day routine: **Cashier Reading** (per shift) → compared on the **X Reading** (the
day so far) → the manager **signs off** each shift → **Close Day** → a numbered, stored
**Z Reading**. Plus two changes to shipped reports: **Cash-up & Variance** (sign-off) and
**Tender Summary** (check list with reprint). Migration: `V93__end_of_day.sql`.

Tests: `EndOfDayIntegrationTests` (16) and `EndOfDayPreviousDayIntegrationTests` (1); full suite
386 passing.

## Corrections to the work order

1. **Configurable start day.** `reports.close-day.first-trading-day` (env
   `CLOSE_DAY_FIRST_TRADING_DAY`) is where Z numbering starts. **It's set to 2026-10-12 for
   testing**; change it to the go-live day. Earlier days can't be closed
   (`BEFORE_FIRST_TRADING_DAY`) and don't block later ones. Set empty, every earlier trading day
   with shifts must be closed first, as the work order describes. The frontend reads it from
   `GET /admin/settings/close-day` → `{ "firstTradingDay": "2026-10-12" }` (`null` when empty), and
   the X Reading's header shows it as "Z numbering starts".
   **Shifts before the first trading day are outside sign-off everywhere:** no Sign off action (on
   the Cashier Reading or Cash-up rows), and `POST …/signoff` refuses with 409
   `BEFORE_FIRST_TRADING_DAY`. They're left out of Cash-up's `signoff=UNSIGNED`, raise no
   `unsigned-shifts` or `not-signed-off` warning, and show "Not required" (the Cashier Reading adds
   "(Z numbering starts 12 Oct 2026)"). The empty Z Reading adds a "Z numbering starts" line.
2. **Test data.** The R2 fixture has a Sat 26 Sep shift, so the main R3 tests set the start day to
   2026-10-05 (Monday is Z-0001, as in the work order). Scenario 9 runs without it: Saturday
   becomes Z-0001 and Monday Z-0002.
3. **Names.** The test users are "Canteen Cashier" (S1), "Canteen Cashier B" (S2) and "Canteen
   Admin", where the work order says Thandiwe M. and Sipho N.
4. **Versions.** Cash-up & Variance goes 2 → **3** and Tender Summary 2 → **3** (complimentary
   meals already took them to 2).
5. **Tampering (scenario 7).** Changing `orders.total` doesn't change net sales (that's built from
   the order's parts) or any payment, so `x-agrees-with-reports` also fails when any of Sales
   Summary's, Tender Summary's or Cash-up's own checks fail for the day. A tampered order total
   then fails it through Sales Summary's `net-agrees-with-orders`.

## Envelope additions (Step 2)

- **`template: STATEMENT`** reports fill **`sections[]`**; `columns`, `rows` and `totals` are
  `null`.
  - `KEY_VALUE` `{ key, type, title?, items: [ { label, type, value, emphasis? } ] }`.
    `type` is a column type (`INT`, `MONEY`, `VARIANCE`, `DATE`, `DATETIME`, `TEXT`).
    `emphasis: "TOTAL"` marks a bold line. `value` may be `null` (show "—").
  - `TABLE` `{ key, type, title, columns, rows, totals, slipColumns? }`. `slipColumns` holds at most
    3 keys for the 80 mm slip. A table without them has `printOn: ["A4"]`.
  - `SIGNATURE_LINES` `{ key, type, labels }`.
  - `printOn` on any section: `["A4"]`, `["SLIP80"]` or both (the default when absent).
- **`meta`** adds `exportFormats` (`["csv","xlsx"]` for tables, `["xlsx"]` for statements), and
  on statements `statementTitle`, `reference` (e.g. `"Z-0001"`, `"Shift #12"`), `interim` and
  `business: { name }` (`app.business.name`, default "Beacon Culinary").
- **`actions[]`** (report level, omitted when empty):
  `{ key, label, method, path, confirm, enabled, disabledReasons[], fields? }`. `fields`
  (`[ { name, label, required } ]`, omitted when empty) are text inputs the viewer collects in the
  confirm step and sends as the JSON body keyed by `name`. Used by `SIGN_OFF` (optional `note`)
  and `REVOKE_SIGN_OFF` (required `note`, labelled "Reason").
- **`appliesWhen`** on a parameter (omitted when always relevant): `{ param, values[] }`. The
  parameter only applies when `param` has one of `values`, so the viewer can hide it otherwise,
  and the server ignores it then. Tender Summary's `checkNumber` has
  `{ "param": "groupBy", "values": ["CHECK"] }`.
- **Row actions:** rows may carry `_actions: [ { key, label, method?, path?, confirm?, onSuccess,
  report?, params? } ]`. `onSuccess` is `RELOAD`, `PRINT_RECEIPT` or `OPEN_REPORT` (navigation
  only: `report` + `params`, no `method`/`path`).
- **`page`** becomes `{ number, size, totalRows, paged }`. `paged: false` is R2's limit
  ("Showing N of M"). `paged: true`: request other pages with `page` (0-based) and `size` (1–200,
  default 50); bad values → 400 `{ "page" | "size": "…" }`. Totals cover every row. Exports ignore
  paging.
- **`ParamType.INT`**: an optional whole number (e.g. `checkNumber`); a bad value →
  `"<Label> must be a whole number."`.
- Statements export as **XLSX only** (`format=csv` → 400 `{ "format": "Statements export as xlsx
  only." }`): a title block, every section stacked in order, then the Checks sheet. File name
  e.g. `x-reading_2026-10-05.xlsx`, `z-reading_1.xlsx`.

## Endpoints

| Method and path | Who | Result |
|---|---|---|
| `POST /admin/shifts/{id}/signoff` `{ note? }` | ADMIN | `{ shiftId, signedOff, by, at, note }` |
| `POST /admin/shifts/{id}/signoff/revoke` `{ note }` | ADMIN | same; `note` required (400) |
| `POST /admin/days/{tradingDay}/close` | ADMIN | 200 with the Z envelope |
| `GET /admin/settings/close-day` | ADMIN | `{ firstTradingDay }` (a date, or `null` when not configured) |
| `GET /shifts/{id}/reading` | the shift's owner or ADMIN | the Cashier Reading envelope (actions left out for a cashier) |
| `POST /admin/orders/{id}/reprint-receipt` | ADMIN | the order's receipt payload + `reprint: { copyNumber, reprintedBy, reprintedAt }` |

Conflicts are 409 `{ error, code, blockers?, zNumber? }`:

| `code` | When |
|---|---|
| `SHIFT_NOT_CLOSED` | signing off an open shift |
| `ALREADY_SIGNED_OFF` / `NOT_SIGNED_OFF` | sign off twice / revoke when not signed off |
| `DAY_ALREADY_CLOSED` | revoking on a closed day (with `zNumber`) |
| `CLOSE_DAY_BLOCKED` | Close Day refused; `blockers[]` lists **all** of: `NO_SHIFTS_FOR_DAY`, `SHIFT_OPEN` (+`shiftId`), `SHIFT_NOT_SIGNED_OFF` (+`shiftId`), `PREVIOUS_DAY_NOT_CLOSED` (+`date`), `DAY_ALREADY_CLOSED` (+`zNumber`, alone), `DAY_IN_FUTURE` (alone), `BEFORE_FIRST_TRADING_DAY` (alone). Each blocker has a plain `message`. |
| `DAY_CLOSED` | `POST /shifts/open` on a trading day that has a Z (with `zNumber`) |

- **Sign-off** is an append-only log (`shift_signoffs`); a shift's state is its latest row.
- **Close Day** and **Open Shift** (and sign-off/revoke) take a SQL Server application lock on
  the trading day, so a shift can't open while the Z is built, and two Close Days give exactly
  one Z (the other gets `DAY_ALREADY_CLOSED`).
- **Z readings** are insert-only: no code path updates or deletes them, and a trigger refuses
  UPDATE and DELETE. `z_number` is gap-free (max + 1, unique).
- **Reprint** logs `receipt_reprints` with the next copy number per order (1, 2, …) and never
  changes the order. Print the receipt marked **"COPY n"**.

## The reports

| Key | Template | Params | Version |
|---|---|---|---|
| `x-reading` | STATEMENT | `tradingDay` (DATE, default today) | 1 |
| `cashier-reading` | STATEMENT | `shiftId` (ID, **required**; last 14 days, newest first, "Mon 5 Oct · Canteen Cashier · 07:00–11:00") | 1 |
| `z-reading` | STATEMENT | `zNumber` (ID; "Z-0001 · Mon 5 Oct 2026"; default the latest) | 1 |
| `cash-up-variance` | TABLE | + `signoff` `ALL`\|`UNSIGNED` | **3** |
| `tender-summary` | TABLE | `groupBy` + `CHECK`; + `checkNumber` (INT) | **3** |

All ADMIN only, under `DAILY_STATEMENTS` (the readings) or their existing categories.

**X Reading sections:** `header` (Business, Report, Trading day, Generated at, Day status "Open"
or "Closed, Z-0001") · `sales` · `complimentary` + `complimentaryByReason` (TABLE) · `tender` ·
`paidOut` · `cash` · `shifts` (TABLE; slip: shift, cashier, variance; "Signed off" shows the
manager's name or "Not signed off") · `cashierReadingsVsX` (TABLE: Cash/Card/Account, Cashier
readings · X reading · Difference) · `signatures` (Manager).
Checks: `cashier-readings-agree-with-x`, `x-agrees-with-reports`, and only when they apply:
`open-shifts` (WARN), `unsigned-shifts` (WARN).
Action `CLOSE_DAY`, `POST /admin/days/{day}/close`, confirm "Close Mon 5 Oct 2026? This produces
Z-000N and you cannot open another shift for this day.", disabled with each blocker's message.

**Z Reading:** the X builder at the moment of closing, plus a `signoffs` table (A4), title
"Z Reading", reference "Z-0001", no actions. Header: Z number, Trading day, Closed by, Closed at.
`z-reading` returns the **stored snapshot**, never a recalculation. With no closed day, an empty
statement says "No day has been closed yet."

**Cashier Reading sections:** `header` (Cashier, Shift, Trading day, Opened, Closed, Status) ·
`takings` (orders sold in this shift, reductions after payment on them, net, collected by
tender) · `cashUp` · `processed` (TABLE of voids, refunds and extras removed processed in this
shift; "Sold in earlier shift" marks cross-shift ones) · `rungByOthers` · `complimentary` ·
`signoff` · `signatures` (Cashier, Manager). An open shift: `meta.interim = true`, Status
"Open (interim)", counted and variance empty. Checks: `agrees-with-cash-up`, `not-signed-off`
(WARN). Actions: `SIGN_OFF` when closed and unsigned; `REVOKE_SIGN_OFF`
(`POST /admin/shifts/{id}/signoff/revoke`, required `note`) when signed off and the day isn't
closed yet. Both are left out of the till's `GET /shifts/{id}/reading` for a cashier.

**Cash-up & Variance (v3):** columns **Signed off by** and **Signed off at** (before Status);
`signoff=UNSIGNED` keeps closed shifts without a current sign-off; rows carry `_actions`
`SIGN_OFF` (closed and unsigned, `RELOAD`) and `OPEN_READING` (every shift, `OPEN_REPORT`
`cashier-reading` with `shiftId`). Cash figures unchanged.

**Tender Summary `groupBy=CHECK` (v3):** one row per sales order with payments (complimentary
orders have none), by day of sale and order number. At most 31 days (400 on `to` otherwise).
Columns: Date · Check # · Time · Rung by · Cash · Card · Account · Total · Order status (STATUS).
Rows carry `orderId` and a `REPRINT` row action (`PRINT_RECEIPT`). Paged (`paged: true`); totals
cover every matching row. Check `rows-agree-with-daily-totals` (without a `checkNumber` filter).
Exports ignore paging (cap 50,000 rows). The DAY/WEEK/MONTH groupings are unchanged.

## Not in R3
Reopening a closed day, server-side PDF, emailing readings, per-person readings.
