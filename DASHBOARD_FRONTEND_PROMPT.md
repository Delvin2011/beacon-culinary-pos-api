# Back Office Dashboard — Frontend

The landing page of the back office. **Build it to the design**: https://claude.ai/artifact/Lbrd1NCmBwV9wTZCvX9fSD (a
Manager view and a Head Chef view; the numbers in the design are sample data and the "Sample figures" chip is not part
of the product). The backend is done; its contract is **`DASHBOARD.md`** in the backend repo. This prompt repeats
what the screen needs.

## Principles
1. **The server decides what appears.** Render exactly the sections returned by the layout endpoint, in that order.
   Never check permissions in the browser to decide on a section.
2. **Sections load independently.** One slow or failing section never blanks the page.
3. **The browser formats; it never calculates.** Percentages, comparisons, ranks, medians and totals arrive ready.
   The only client work is layout, scaling bars and charts to the values given, and formatting numbers.
4. **Absent fields stay absent; null fields mean "nothing yet".** A field that is **missing** is one the user may not
   see (e.g. `soldValue`, `onTheTill`): show nothing in its place, no dash, no zero. A field that is **`null`** has no
   value yet (no comparison day, no history, nothing open): use the empty wording given below.
5. **Server text is shown verbatim.** `title`, `detail`, `secondary`, `compareLabel`, `typicalBasis` and `label`
   arrive written; don't rebuild them.

## 1. Page and route
- Route `/dashboard`, the **first menu item**, and the landing page after login for anyone with `DASHBOARD_VIEW`.
  **Until U2** that is a temporary role check: **ADMIN and HEAD_CHEF** land on `/dashboard`; everyone else keeps their
  current landing page. (This decides only the landing page, never which sections show.)
- A **fluid page**: the menu stacks above the content on a narrow screen, cards wrap, tables scroll sideways inside
  their card. No sticky or full-height menu.
- **Header:** "Dashboard", today's date, "updated hh:mm", and the **range control** (Today · 7 days · Month, default
  **7 days**), labelled "Range for trends and top items". Keep the range in the URL (`?range=today|7d|month`); any
  other value in the URL falls back to `7d` (the server answers `400` to anything else).
- "updated hh:mm" is the **latest `asOf`** among the sections shown. Each section's `asOf` is when its figures were
  computed: a cached answer keeps its original `asOf`, so it can be up to `refreshSeconds` old.
- The range control changes only sections whose layout entry has `usesRange: true`: `sales-trend`, `sales-mix`,
  `top-sellers`, `payment-mix`, `basket`, `account-customers`. Send `range` only to those.

## 2. Endpoints, loading and refreshing
```
GET /admin/dashboard/layout
→ { asOf, timezone: "Africa/Johannesburg", sections: [{ key, title, usesRange, refreshSeconds }] }

GET /admin/dashboard/sections/{key}?range=7d
→ { key, asOf, range, definitionVersion, data, reportLink: { key, params } | null }
```
1. Call the layout. Then call every section **in parallel** (with `range` for the range-aware ones). The layout
   carries no figures.
2. Show a **skeleton** card per section while it first loads.
3. **Refresh each section on its own timer** (`refreshSeconds`: 30 s for `attention` and `now`, 300 s for `heatmap`,
   60 s for the rest). The server caches for the same time, so refreshing faster brings nothing new. Pause all timers
   while the tab is hidden; refresh everything straight away when it becomes visible. Changing the range reloads only
   the range-aware sections.
4. Never replace a card with a skeleton on refresh: keep the last good numbers, update them in place, and show a small
   "Updating…" note while a refresh is slow (over ~2 s).
5. Responses per section:
   | Status | Meaning | Do |
   |---|---|---|
   | `200` | the section | render |
   | `500` `{ key, error }` | that section failed | keep the card (last good numbers if any), small message + **Retry** |
   | `404` | section switched off since the layout loaded | drop the card silently |
   | `403` | permissions changed since the layout loaded | drop the card silently |
   | `400` | bad range (shouldn't happen) | reset the range to `7d` |
   | `401` | session expired | the app's usual sign-in/refresh handling |
6. If the **layout** returns `403`, redirect to the user's landing page.
7. **"View report" links:** when `reportLink` is not null, link to the existing report viewer for `reportLink.key`
   with `reportLink.params` as its parameters (`from`, `to`, and e.g. `groupBy`, `type`, `sortBy`). The report shows
   the same figures for the same range.

## 3. The sections
Lay out as in the design: Needs attention and Right now side by side first; then the tile row; then the trend and
the donut; busy hours and the heat map; what sells and sell-through; then light meal stock and the four
customer-pattern cards (how customers pay, the basket, waiting, account customers). Sections the user doesn't receive
leave no gap: cards re-flow.

Formatting everywhere: money `R 4 820,00` (space thousands, comma decimals; negative `-R 60,00`); percentages from the
numbers given, with a comma (`62,5%`); times are already Johannesburg `HH:mm` strings; day labels as given.

### Needs attention — `attention`
`{ items: [{ key, severity, tag, title, detail, action: { label, path }, count, since }], moreCount }`, already
sorted (RED, AMBER, BLUE, NEUTRAL, then oldest first) and capped at 8.
- Each item: a coloured dot by `severity`, the **`tag` chip** (as given: "Shift", "Sign-off", "End of day", "Stock",
  "Light meals", "Cash", "Planning"), `title`, `detail`, and an action button (`action.label`) that navigates to
  `action.path`. Colour never carries the meaning alone: the tag and title say it.
- Severity → colour: RED red chip, AMBER amber chip, BLUE accent blue, NEUTRAL neutral chip.
- "and {moreCount} more" when `moreCount > 0`. The count chip in the card header shows `items.length + moreCount`.
- **Empty state:** "Nothing needs your attention."
- **Action paths.** The backend sends these paths; map each to the real route in the app (tell the backend team if a
  path should change, it's a one-line change there):
  | Item `key` | `action.path` | Should open |
  |---|---|---|
  | `OVERDUE_SHIFT` | `/pos-activity` | POS Activity |
  | `UNSIGNED_SHIFTS`, `DAY_NOT_CLOSED` | `/end-of-day` | shift sign-off / Close Day |
  | `PENDING_APPROVALS`, `OWN_REQUESTS_WAITING` | `/stock/requests` | Stock Requests |
  | `LIGHT_MEAL_STOCK` | `/catalogue/light-meals` | Catalogue Management, light meals |
  | `CASH_VARIANCE` | `/reports/cash-up-variance` | Cash-up & Variance report |
  | `SLOW_PLANNED_MEALS` | `/daily-planning` | Daily Planning |

### Right now — `now`
`{ kitchen: { pending, inProgress, open, longestWaitMinutes }, bar: {…} | null, readyToCollect: { count,
oldestMinutes }, onTheTill?: { shiftId, owner, since, expectedCash } | null }`
- Rows: Kitchen, Bar, Ready to collect, On the till.
- `bar: null` → no Bar row (the bar station isn't switched on).
- `longestWaitMinutes` / `oldestMinutes` are `null` when nothing is waiting: show the count only ("Nothing open").
- `onTheTill` **absent** → no till row (no cash permission). `onTheTill: null` → the row reads "No shift open".
  Otherwise "{owner} since {since}" (`since` is `HH:mm`, or "Tue 13 Oct 07:00" for a shift left open from an earlier
  day) and "Expected cash R …".

### Today tiles — `today`
`{ day, compareDay, compareLabel, tiles: [{ key, label, value, type, secondary, delta, tone }] }`
- Tiles in the order given: `netSales`, `orders`, `averageOrder`, `voidsRefunds`, `complimentary` (only when that
  stage is on), `discounts`. Format `value` by `type`: `MONEY` → `R 420,00`, `COUNT` → `7`.
- `secondary` is the line under the value, shown verbatim. For the first three tiles it is the comparison label
  ("vs last Mon, same time"); for the others a description ("24.6% of gross sales · 2 orders", "R 45,00 at list
  price", "1 order").
- `delta` is a whole percent or `null`. Show it as a chip **"+6%"** / **"−3%"** / **"0%"** (signs, not arrows alone)
  next to the comparison label. `null` → no chip; the label then reads "No comparison yet" instead of `secondary`
  (the server sends `null` when the same weekday last week had no trading).
- `tone` → colour: `POSITIVE` teal, `WATCH` amber, `NEUTRAL` grey. On `voidsRefunds` the tone is `WATCH` when voids
  and refunds are above the watch percentage of gross sales.

### Net sales trend — `sales-trend` (range)
`{ days: [{ day, label, netSales, compare, isToday }] }`
- One bar per trading day (`label`, e.g. "Mon 5"): the last 7 trading days, or every trading day of the month.
- `compare` (the same weekday a week earlier; same time of day for today) is an **amber marker** on the bar; `null` →
  no marker.
- The `isToday` bar is the dark blue one and carries its value label.
- Legend: "Bars: net sales. Amber mark: the same weekday last week." "View Sales Summary" from `reportLink`.
- Empty `days` (no trading yet) → "No trading days yet".

### Where sales come from — `sales-mix` (range)
`{ basis: "BEFORE_DISCOUNTS", total, groups: [{ key, label, amount, percent }] }`
- Donut with the legend (label, percent, amount) and the total in the middle. Only groups with sales are sent, in
  order: meal periods (`BREAKFAST`, `LUNCH`, any other period), then `LIGHT_MEALS`, `BEVERAGES`. Give each key a fixed
  colour so it doesn't change between ranges.
- Under the title write **"Before discounts"** when `basis` is `BEFORE_DISCOUNTS`, so nobody expects it to equal net
  sales. Percentages add up to exactly 100.
- Empty `groups` → "No sales in this range".

### Busy hours — `busy-hours`
`{ hours: [{ hour, today, typical }], peak: { hour, orders } | null, typicalBasis }`
- Bars for `today` per hour (`hour` 6 → "06:00"), an amber line for `typical`. Hours run across the opening hours
  (06:00–14:00 today), wider if orders fell outside them.
- `typical` is `null` with no history → draw no line, and show no `typicalBasis`. Otherwise show `typicalBasis`
  verbatim as the line's legend ("Average of the last 4 Wednesdays", or "Last Monday").
- Mark the `peak` bar with its label; `peak: null` (no orders yet) → no mark.
- Note "{HH}:00 is still running" on the bar for the current hour (the hour of the section's `asOf` in Johannesburg
  time), when it is in the list.

### When customers come — `heatmap`
`{ from, to, hours: [6, …, 14], rows: [{ weekday, label, tradingDays, cells }], max }`
- A CSS grid: one row per weekday given (weekdays that never traded are not sent), one column per hour. Each cell's
  blue tint is scaled to `max`; **always print the number in the cell**, rounded to a whole number, in dark text.
- It covers the 28 days before today (`from`–`to`, today excluded); each cell is the average orders per trading day
  of that weekday. Add the "Fewer orders … More orders" legend and a note "Average orders, {from}–{to}".
- `rows` empty → "Not enough history yet".

### What sells — `top-sellers` (range)
`{ sortBy, type, items: [{ rank, name, tag, portions, soldValue? }] }`, at most 5.
- Each row: rank, name, the tag chip (`SET_MEAL` "Set meal", `LIGHT_MEAL` "Light meal", `BEVERAGE` "Beverage",
  `EXTRA` "Extra"), a bar scaled to the largest value in the current sort, portions, and **sold value only when the
  field exists**.
- The **"By portions / By value"** toggle is shown **only when `soldValue` exists**. It re-requests the section with
  `sortBy=PORTIONS` or `sortBy=VALUE` (plus the range). Don't offer it otherwise: `sortBy=VALUE` is refused (`403`)
  for users who can't see values. Keep the choice while the range changes.
- Sold value is net portions at the price they were rung at, **before discounts**: say "Sold value (before discounts)"
  in the column header or tooltip.
- `items` empty → "Nothing sold in this range". Link to Meal & Extras Sales from `reportLink`.

### Planned meals: sell-through today — `sell-through`
`{ thresholdPercent, afterTime, rows: [{ name, planned, sold, sellThroughPercent, soldOutAt, slow }] }`
- A table: meal, planned, sold, a sell-through chip (`sellThroughPercent`, `null` when nothing was planned → "–"),
  sold out at (`soldOutAt` `HH:mm`, or blank).
- `slow: true` rows get the amber "slow" chip. Footnote: "Slow: under {thresholdPercent}% sold after {afterTime}."
- Note: "Light meals and beverages are always available, so they have no planned portions."
- `rows` empty → "No set meals planned today".

### Light meals: kitchen stock — `light-meal-stock`
`{ meals: [{ mealId, name, status, unitsAvailable, limitingIngredient, lowStockUnits, outToday }], moreCount }`,
sorted Out, Low, OK, at most 8.
- A table: meal, status chip (`OUT` red "Out", `LOW` amber "Low", `OK` teal "OK"), units available, limiting
  ingredient. Show "marked out today" beside the name when `outToday` is true. "and {moreCount} more" when > 0.
- **Review** opens the existing Review panel (`GET /admin/meal-catalog/{mealId}/stock-review`) and **Request stock**
  the existing Request dialog, both from the light meal stock stage. They follow that stage's temporary role checks
  until U2: Review needs `MENU_VIEW` + `STOCK_VIEW` (ADMIN, HEAD_CHEF), Request stock needs `REQUEST_ISSUE` (ADMIN,
  HEAD_CHEF, STOCK_ADMIN, STOCK_CLERK). Everyone who receives this section today may use both; hide a button the
  user may not use when U2 lands.
- After a request is sent, refresh this section (and `attention`).
- `meals` empty → "No light meals with a recipe to track".

### How customers pay — `payment-mix` (range)
`{ basis: "MONEY_COLLECTED", total, methods: [{ method, amount, percent }] }`
- One stacked bar (Cash, Card, Account) plus the legend (method, percent, amount). Label: **"Share of money
  collected"** (a split order has more than one method, so these are shares of money, not of orders).
- `methods` empty → "Nothing collected in this range".

### The basket — `basket` (range)
`{ averageOrder, series: [{ day, value }], itemsPerOrder, extrasAttachPercent }`
- The average order (money, for the range), a small line chart of `series`, "{itemsPerOrder} items per order" and
  "Extras on {extrasAttachPercent}% of meal orders".
- `series` is always the **last 7 trading days**, whatever the range; label it "Last 7 trading days".
- `itemsPerOrder` / `extrasAttachPercent` are `null` with no orders → leave that line out.

### Waiting — `waiting`
`{ kitchenMedianMinutes, barMedianMinutes, collectMedianMinutes, longest: { minutes, at } | null }` (minutes have one
decimal; show them rounded to whole minutes).
- Median minutes for kitchen (paid to ready), bar (omit when `null`) and collection (ready to collected), plus
  "Longest wait today: {minutes} min at {at}".
- All `null` → "No orders through the kitchen yet today".

### Account customers — `account-customers` (range)
`{ accounts: [{ accountId, name, amount }], total, orders }`
- The top 3 accounts with bars, and "{total} on {orders} orders" (all accounts, not only the top 3).
- `accounts` empty → "No account orders in this range".

## 4. Look
Light theme to match the rest of the back office. Tokens from the design:
- page ground `#F5F6F8`, cards `#FFFFFF` with a `1px #E3E6EB` border and 12px radius, 20px padding
- ink `#172033`, muted text `#5B6475`
- accent blue `#2455D6` (light blue `#A9BEF2`), amber `#C77700` for lines and marks, amber tint chip `#FFF1DB` with
  `#8A4B00` text, red chip `#FDE8E6` with `#B42318` text, teal chip `#DDF4EE` with `#0B6B5D` text, neutral chip
  `#EEF0F4` with `#3B4457` text
- system sans-serif; tabular figures for all numbers.

Use the app's chart library, matched to the design. Charts get a text summary in their `aria-label` (the key
numbers), and every interactive control is at least 44px high.

## 5. States to handle
- **No sales yet today:** tiles show `R 0,00` and `0`; the trend shows today's bar at zero (once a shift has opened
  today); What sells, the mix and payment mix show their empty wording; no error.
- **First days of use:** deltas, `compare` and `typical` are `null`; the heat map shows "Not enough history yet".
- **A section that is not in the layout** doesn't appear. **A section that errors** keeps its card with Retry.
- **Slow network:** keep the last good numbers visible with "Updating…"; never blank a card.

## Acceptance
- [ ] The page renders exactly the sections in the layout response, in order, and nothing else; no client-side
      permission check decides a section.
- [ ] Each section loads and refreshes on its own timer, pauses in a hidden tab and refreshes on return; a failing
      section shows Retry without affecting the others; a `404`/`403` section drops out quietly.
- [ ] The range control changes only the six range-aware sections and is kept in the URL.
- [ ] Needs attention shows severity dots with tag chips, working action buttons (paths mapped to real routes),
      "and N more", and its empty state.
- [ ] Tiles show values, `secondary`, delta chips with signs and tones as delivered; a `null` delta shows no chip.
- [ ] Sales mix says "Before discounts"; payment mix says "Share of money collected".
- [ ] A Head Chef sees no money anywhere: What sells shows portions only with no toggle; Right now has no till row.
- [ ] Right now shows "No shift open" when `onTheTill` is `null`, and no till row when it is absent.
- [ ] The light meal stock card opens the existing Review panel and Request dialog.
- [ ] The page re-flows to one column on a narrow screen with scrollable tables.
- [ ] Charts have text alternatives; every number in the heat map is printed; colour is never the only signal.
- [ ] The dashboard is the landing page for ADMIN and HEAD_CHEF (`DASHBOARD_VIEW` once U2 lands).

## Test scenarios
Use the backend's report fixture (R1 + discount, complimentary and R2 additions) and test clocks. **Set
`CLOSE_DAY_FIRST_TRADING_DAY=2026-10-05` on the backend for these.** The default (2026-10-12) puts 5 Oct before the
end-of-day process, and the sign-off items would not appear.

- **Manager, Mon 5 Oct 15:30**
  - Tiles: R 420,00 · 7 · R 84,00 · R 140,00 ("24.6% of gross sales · 2 orders", amber) · 1 ("R 45,00 at list
    price") · R 10,00 ("1 order"). The first three read "vs last Mon, same time", or "No comparison yet", with no
    delta chips.
  - Needs attention: one item, "2 closed shifts are not signed off" (tag "Sign-off").
  - Right now: nothing open, "No shift open".
  - Busy hours: 07:00 2 (peak), 08:00–12:00 1 each, no typical line.
  - What sells (Today): Breakfast Plate 5 (R 200,00), Extra Egg 4 (R 40,00), Chicken & Rice 2 (R 120,00), Pap & Stew 2
    (R 70,00).
  - Sales mix (Today): Breakfast 55,8% R 240,00, Lunch 44,2% R 190,00, total R 430,00.
  - Payment mix (Today): cash 62,5%, card 16,1%, account 21,4% of R 560,00.
  - Sell-through: Breakfast Plate 5/20 (25%), Chicken & Rice 2/20 (10%, slow), Pap & Stew 2/20 (10%, slow).
- **Manager, Tue 6 Oct 15:30:** Needs attention shows "Mon 5 Oct is not closed" (tag "End of day") first, then "2
  closed shifts are not signed off". Right now: the admin's till since 08:00, expected cash R 140,00, 1 ready to
  collect.
- **Head Chef:** the layout lists attention, now, busy-hours, top-sellers, sell-through, light-meal-stock, waiting, in
  that order. No money anywhere: What sells shows 5 · 4 · 2 · 2 portions with no toggle; Right now has no till row.
  The page never requests a sales section (the network log shows only the seven).
- **Cashier, Kitchen, Barista, Stock Admin, Stock Clerk:** the layout returns 403; the user goes to their landing page.
- **Range to Month:** only the trend, mix, what sells, payment mix, basket and accounts reload.
- **Hide the tab for two minutes, show it again:** every section refreshes at once.
- **One section fails:** stub one section's response to `500` in the browser (dev tools or a test interceptor): only
  that card shows Retry.
- **Phone width:** the menu stacks, cards become one column, the sell-through and stock tables scroll sideways.

## Not in version 1
Customising or reordering sections, exporting the dashboard, meal photos, push or email alerts, and any editing of
thresholds (they are configuration for now).
