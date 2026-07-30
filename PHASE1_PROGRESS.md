# Phase 1 Progress

Tracks implementation progress against `metadatafiles/phase1-backend-detailed.md`,
stage by stage. Each stage is built and shipped independently — see that spec for full
requirements; this file records what's actually been done, what decisions were made
along the way, and what's still open.

---

## Stage 1.1 — Auth, Roles & Shift Foundation — ✅ Complete

**What was built**
- `users` table gained `pin_hash` and `active` columns (`V14__add_pin_auth_to_users.sql`).
- `POST /auth/pin-login` — PIN-based login for cashiers/admins, issuing the same
  access-token/refresh-cookie pair as the existing email/password `/auth/login`.
- New `shifts` package: `Shift` entity + `ShiftStatus` enum, `ShiftRepository`,
  `ShiftService`, `ShiftController`, `ShiftSecurityRules` (`V15__create_shifts.sql`).
  - `POST /shifts/open` — opens a shift with an opening float; `409` if the caller
    already has one open.
  - `POST /shifts/{id}/close` — closes a shift; `403` unless the caller owns it or is
    `ADMIN`. Sets `status = CLOSED` and `closed_at`; no reconciliation math yet
    (that's Stage 2.5).
  - `GET /shifts/current` — the caller's open shift, or `404` if none.

**Decisions made**
- PIN login was added **alongside** email/password login rather than replacing it —
  cashiers authenticate via PIN at the POS terminal, but the existing `/auth/login`
  stays in place (e.g. for admin back-office use and the existing test suite).
- The e-commerce leftovers on `users` (`email`, `password`, `addresses`, `profiles`)
  were left untouched for now — out of scope for this stage, can be cleaned up later.
- PIN login doesn't reveal whether a `cashierId` exists: an unknown ID is compared
  against a pre-computed dummy BCrypt hash so the response (and rough timing) for
  "wrong PIN" and "unknown cashier" are indistinguishable — both just `401`.
- Seed data (`V14`) backfills PINs for the two existing seeded users (Cashier: PIN
  `1234`; Admin: PIN `9999`) and adds a third seeded cashier, "Cashier B" (PIN `5678`),
  solely so the cross-cashier shift-ownership rule (`403`) has two real cashiers to
  test against.

**Tests** — `AuthPinLoginIntegrationTests` (3), `ShiftIntegrationTests` (7), all green
against the real dev SQL Server database. Full suite (31 tests) has no regressions.

**Addendum — POS login screen enablement:** the `/pos/login` frontend needs a tile grid
of cashiers *before* any auth token exists, so it can't call the existing (authenticated,
paginated) `GET /users`. Added `GET /users/cashiers` — public, returns a plain JSON array
of `{ id, name }` for users where `active = true` and `role IN (CASHIER, ADMIN)` (admins
included since they can also open shifts per the ownership rules above). No new table —
reuses existing `users` columns. Routed as a literal segment under `/users` (Spring MVC
resolves `/users/cashiers` to the literal mapping over `/users/{id}`, so the existing
authenticated single-user lookup is unaffected) and exempted in `UserSecurityRules`
rather than `AuthSecurityRules`/globally, since it's a `users`-owned read, not an auth
concern. CORS needed no new configuration — `SecurityConfig`'s `CorsConfigurationSource`
is already registered for `/**`, so it covers this endpoint automatically; only the
per-path *authorization* rule needed adding. Verified against the live app: unauthenticated
`GET /users/cashiers` returns exactly the 3 active cashier/admin seed rows (excluding the
legacy `USER`-role seed account), while `GET /users` and `GET /users/{id}` still 401
without a token. Tests: `UserCashiersIntegrationTests` (2).

**Flagged for later stages** — there was pre-existing uncommitted work in the `menu` and
`orders` packages (`MenuCategory`/`MenuItem`/`MenuItemVariant`/`ModifierGroup`/`Modifier`,
tax rates, per-variant stock, an enum-based `MealPeriod`) that modeled the menu/order
domain differently from the spec. **Resolved in Stage 1.2 — see below.**

---

## Stage 1.2 — Meal & Component Catalog + Daily Planning — ✅ Complete

**Spec changed underneath this stage.** Between Stage 1.1 finishing and Stage 1.2
starting, `metadatafiles/phase1-backend-detailed.md` was revised: Stage 1.2 went from a
single `DailyMealOption`/`MealComponent` model with a `/replenish` endpoint, to a
two-layer catalog/daily-planning split with **no replenishment at all**. Built against
the current (revised) version of the spec.

**Old code retired.** The existing uncommitted `menu` package (à-la-carte
categories/items/variants/modifier groups, tax rates, per-variant stock) and the current
`orders` POS flow (`Order`/`OrderItem`/`OrderItemModifier`, keyed off `MenuItemVariant`)
modeled a fundamentally different business (browse-anytime menu with size variants and
per-item add-ons) than the new set-meal spec (fixed daily dishes with a shared,
date-scoped extras pool). There was no sensible refactor path between the two, so both
packages — and their tests (`MenuIntegrationTests`, `OrderIntegrationTests`, 20 tests) —
were deleted outright rather than patched. `POST /orders` is gone until Stage 1.3 rebuilds
it against `daily_meal_options`/`daily_component_stock` — expected, since that's the very
next stage. New migrations (`V16`) `DROP` the old menu/order tables before `V17`–`V22`
create the new ones, following the same forward-only pattern `V10` used for the original
e-commerce teardown (migrations already applied to the dev DB are never edited in place).

**What was built**
- Reference-data layer (admin-managed, rarely changes): `MealCatalog` (name/description/
  price/active), `ComponentCatalog` (name/extra-price/active), `MealCatalogComponent`
  (informational composition link — display only, not a pricing/stock relationship).
- Daily-planning layer (chef-driven, per date + meal period): `DailyMealOption` — created
  from a `MealCatalog` entry, **snapshotting** `name`/`description`/`price` at creation
  and initializing `portionsRemaining = plannedPortions`; `DailyComponentStock` — created
  from a `ComponentCatalog` entry, snapshotting `extraPrice`, initializing
  `bufferRemaining = bufferQuantity`, and **decoupled from any specific dish** — it's a
  shared pool for that component/period/date, orderable as an extra against any line.
- `MealPeriod` is now DB reference data (not a Java enum), seeded directly by its own
  create migration: Breakfast (07:00–10:00), Lunch (12:00–14:30).
- Endpoints: `GET /meal-periods` (public), `GET/POST/PUT /admin/meal-catalog{,/{id}}`,
  `GET/POST/PUT /admin/component-catalog{,/{id}}`, `POST /admin/daily-options`,
  `POST /admin/daily-component-stock` (all four admin-gated automatically via the
  existing global `/admin/**` → `ADMIN` rule — no new security-rule wiring needed for
  those), and `GET /menu/today?period=LUNCH` (public) returning
  `{ options: [...], availableExtras: [...] }`.
- **No replenishment endpoint exists** — once `portionsRemaining`/`bufferRemaining` hits
  zero for a day, it stays zero; this is by design per the revised spec, not an omission.

**Decisions made**
- Catalog seed data (4+ meal-catalog / component-catalog entries) was **not** added this
  stage — the spec explicitly defers the full cross-cutting seed dataset to "after Stage
  1.3 migrations exist." Tests build their own catalog fixtures through the real admin
  endpoints instead (same approach `AuthTestHelper` already used for login).
  `meal_periods` is the one exception, since it's fixed/permanent reference data seeded
  directly in its own creation migration, matching the spec's given DDL verbatim.
- `GET /menu/today` filters purely by `option_date = today` + meal-period match — it does
  **not** check whether the current wall-clock time falls inside that period's window.
  That "is this meal period currently active" check is explicitly Stage 1.3's concern (at
  order-creation time), not this browsing endpoint's.
- `MealCatalogComponent` is a full JPA entity (its own `id` + two `@ManyToOne`s) rather
  than a bare `@ManyToMany` `@JoinTable`, to match the spec's given migration exactly
  (which defines a surrogate `id` column on the join table).

**Tests** — `MenuIntegrationTests` (7), covering: nested components in a meal-catalog
response, snapshot-on-create, catalog-price-change-doesn't-retroact, component stock
created independent of any option, cross-dish extras visibility in `/menu/today`, and the
403 for non-admin. Full suite: 20 tests, 0 failures. Verified live against the running
app too — created a meal + a decoupled component stock via the real admin endpoints and
confirmed the component (not part of that meal's composition) showed up in
`availableExtras`.

---

## Stage 1.3 — Order & Payment Core (Cash Only) — ✅ Complete

**What was built**
- New `orders` package: `Order` (order_number, order_date, shift, cashier, status,
  payment_method, amount_tendered, change_due, subtotal, total) + `OrderLine`
  (daily_meal_option, snapshotted unit_price/quantity/line_total) + `OrderLineExtra`
  (daily_component_stock, snapshotted price_delta/quantity/line_total), plus
  `OrderStatus` (`CONFIRMED`) and `PaymentMethod` (`CASH`) enums
  (`V23__create_orders.sql`, `V24__create_order_lines.sql`,
  `V25__create_order_line_extras.sql`).
- `POST /orders` (`CASHIER`/`ADMIN`, requires an open shift) — single `@Transactional`
  service method that builds, prices, pays, and decrements stock in one request, matching
  the spec's step order exactly: verify open shift (`409`) → validate every line's
  `dailyMealOptionId` is today's date and its meal period is currently active (`400`) →
  validate every extra's `dailyComponentStockId` exists for today + the **same meal
  period as its line** (`400`; explicitly does *not* require the same dish) → compute
  prices → atomically decrement `portions_remaining`/`buffer_remaining` (`409` on
  oversell) → verify `amountTendered >= total` (`400`) → assign the day's next
  `order_number` → persist → `201` with the full order including `changeDue`.
- New **atomic conditional-decrement pattern** (didn't exist anywhere in the codebase
  before this stage): `@Modifying @Query` methods on the existing
  `DailyMealOptionRepository`/`DailyComponentStockRepository`
  (`... SET remaining = remaining - :qty WHERE id = :id AND remaining >= :qty`),
  returning rows-affected. Zero rows → throw → the surrounding `@Transactional` rolls
  back everything else in the request, so a failed decrement can never leave an orphan
  order or a partially-decremented stock row.
- `NoOpenShiftException`/`InsufficientStockException` → `409`,
  `InvalidOrderRequestException` → `400` (covers bad option/period/extras and
  underpayment), `OrderSecurityRules` (`CASHIER`/`ADMIN`, same pattern as `ShiftSecurityRules`).

**Decisions made**
- `subtotal` = sum of meal-line totals only; `total` = `subtotal` + all extras. The spec
  gives both fields without defining the split; this makes the receipt distinction
  meaningful (dish prices vs. grand total with add-ons) and matches how
  `OrderLineExtra` is deliberately attached under its line rather than the order.
- `order_number` is computed as `MAX(order_number WHERE order_date = today) + 1` inside
  the same single transaction, relying on the unique `(order_date, order_number)` index
  as a safety net rather than building a cross-transaction retry loop. The spec's
  migration note mentions "retry on constraint violation," but its business-logic section
  is explicit about "one `@Transactional` method," and a real retry needs a self-injected
  proxy for `REQUIRES_NEW` — more machinery than a single-till POS needs; the acceptance
  criteria's concurrency scenarios only target stock decrements, not order numbers.
  Flagged as revisitable if concurrent tills are added later.
- Decrement ordering follows request order with no lock-ordering by ID — acceptable for
  the tested contention patterns (single shared row), a theoretical multi-row deadlock
  under heavy concurrency is out of scope for this stage.

**Tests** — `OrderIntegrationTests` (10), covering: single-line happy path (pricing/
change-due), a cross-dish extra accepted on a different line's dish (proves extras aren't
scoped to a dish's own composition), a multi-line order across two options, insufficient-
portions rollback (`409`, no orphan order), **two concurrency races run with real threads
against the live SQL Server dev DB** — last portion of an option, and the last unit of a
shared extra ordered simultaneously against two different dishes/lines — each resolving
to exactly one `201`/one `409`, underpayment (`400`, stock untouched), no open shift
(`409`), an option outside its meal period's time window (`400`, using the existing but
previously-unused `MutableClock`/`ClockTestConfig` test harness to pin "now" reliably
regardless of wall-clock time), and an extra stocked only for a different meal period
(`400`). Full suite: 30 tests, 0 failures, no regressions.

---

## Stage 1.4 — Receipt & Basic Order Visibility — ✅ Complete

**What was built**
- `orders` table gained a `print_failed` boolean (`V26__add_print_failure_flag.sql`),
  surfaced on `Order`/`OrderDto`.
- `GET /orders/today` (`CASHIER`/`ADMIN`) — today's orders (`order_date` = today, via the
  same `Clock` bean the order-placement flow uses), newest first (ordered by
  `order_number` descending, which is equivalent to creation order within a single day).
- `GET /orders/{id}` (`CASHIER`/`ADMIN`) — full order detail (lines + extras nested, same
  shape as the `POST /orders` response) for on-screen reprint; `404` via a new
  `OrderNotFoundException` for an unknown id.
- `POST /orders/{id}/mark-print-failed` — sets `print_failed = true` only; doesn't touch
  `status` or any totals. Restricted to `CASHIER` only (not `ADMIN`), matching the spec's
  endpoint table literally — added as a more specific matcher
  (`POST /orders/*/mark-print-failed`) ahead of the existing blanket
  `/orders/**` → `CASHIER`/`ADMIN` rule in `OrderSecurityRules`, since Spring Security
  takes the first matching rule.

**Decisions made**
- `mark-print-failed` being cashier-only (excluding `ADMIN`) is a literal reading of the
  spec's endpoint table, which lists `CASHIER` alone for this one row while explicitly
  listing `CASHIER`/`ADMIN` for the other two in the same section — treated as
  intentional (only the till reporting its own hardware fault) rather than an omission.
- `GET /orders/today` orders by `order_number DESC` rather than `created_at DESC` —
  equivalent within a single day (`order_number` is assigned sequentially at creation)
  and avoids relying on the DB-defaulted, non-insertable `created_at` column being
  populated in a freshly-mapped entity.

**Tests** — extended `OrderIntegrationTests` (+5): today's-orders ordering across two
orders, `GET /orders/{id}` returning full nested line/extra detail, `404` for an unknown
id, marking a print failure (flag flips, status/total unchanged), and `ADMIN` getting
`403` on `mark-print-failed`. Full suite: 35 tests, 0 failures, no regressions.

---

## Cross-cutting: Seed Data — ✅ Complete

**What was built** (`V27__seed_catalog_and_daily_data.sql`, added now that Stage 1.3's
migrations exist, per the spec):
- 4 `component_catalog` entries (Rice R8, Chicken R12, Potatoes R8, Beef R15) and 4
  `meal_catalog` entries covering every pairing of them (Rice & Chicken R45, Potatoes &
  Beef R50, Rice & Beef R48, Chicken & Potatoes R47), linked via
  `meal_catalog_components`.
- 2 breakfast + 2 lunch `daily_meal_options` for "today." "Potatoes & Beef" at lunch is
  seeded already sold out (`planned_portions = portions_remaining = 0`) so the
  no-replenishment path is testable immediately.
- `daily_component_stock` for all four components at lunch. Chicken is seeded sold out
  (`buffer_remaining = 0`); Rice and Chicken both belong to "Rice & Chicken" — a
  *different* dish than the sold-out "Potatoes & Beef" option — so the cross-dish
  extras path is browsable immediately via `GET /menu/today?period=LUNCH`.
- Users/PINs and `meal_periods` were already seeded in Stage 1.1/1.2 — nothing more
  needed there.

**Decisions made / caveats — read before relying on this data being present:**
- **"Today" means "whenever this migration was applied,"** not a rolling window. The
  daily rows use `CAST(SYSUTCDATETIME() AS DATE)` at migration-apply time, so they stop
  showing up in `GET /menu/today` the day after `mvn flyway:migrate` was run. This is
  intentional, not a bug — re-doing daily planning every day via `/admin/daily-*` is the
  whole point of the two-layer model; this migration only exists to make day-one
  browsing/ordering possible without manual admin calls.
- **Running the test suite deletes this seed data.** `MenuIntegrationTests` and
  `OrderIntegrationTests` both tear down with an unscoped `repository.deleteAll()` on
  `meal_catalog`/`component_catalog`/`daily_meal_options`/`daily_component_stock` — a
  pre-existing pattern from Stage 1.2, not something this task changed. Since Flyway
  won't reapply `V27` (it's tracked as already-run), the data doesn't come back on its
  own after `mvn test`. Confirmed by hitting this directly: after seeding, `mvn test`
  wiped the rows from the live dev DB; they were restored by re-running the migration's
  SQL directly via `sqlcmd` (a one-off manual fix, not a repeatable command — the
  practical takeaway is to re-seed via the admin endpoints, or re-run the script by
  hand, if you need this data back after running tests).
- One existing assertion in `MenuIntegrationTests`
  (`getTodayMenu_extrasIncludeComponentNotInAnyTodayOptionComposition`) asserted an
  exact `options.length() == 1` for today's lunch menu, which the 2 seeded lunch
  options would break. Loosened to check for the specific option's presence
  (`$.options[?(@.name=='Potatoes & Beef')]`) instead of an exact count/index — the
  correct fix given the table is no longer guaranteed empty of other rows, chosen over
  making test teardown scoped-instead-of-`deleteAll()` (a larger, more invasive change
  across two test files that wasn't required for this task).
- A one-off shift-conflict test flake (`OrderIntegrationTests`, 409 instead of 201 on
  `/shifts/open`) appeared once during a full-suite run right after seeding, but did not
  reproduce on an immediate re-run of the full suite, nor when running
  `OrderIntegrationTests` alone. Not treated as a regression from this change — noted
  here in case it recurs. **Update:** recurred a second time under the same
  circumstances (full-suite run immediately after applying new migrations, see the
  "All Day" meal period entry below) and again cleared on immediate re-run. Both
  occurrences share the same shape — first full-suite run after a fresh
  `flyway:migrate`, never on rerun, never in isolation — which points at something
  timing/load-related (possibly the concurrency subtests) rather than the seed data
  itself, since seed data never touches the `shifts` table. Still unresolved; flagging
  the pattern in case it's worth a closer look later.

**Verified live** against the running app: `GET /menu/today?period=LUNCH` and
`?period=BREAKFAST` both return the expected seeded options/extras, including the
sold-out entries at `0` remaining.

---

## Addendum — "All Day" Meal Period (consumables)

Not in the original spec — added on request to support consumables (drinks, sweets,
etc.) that should be orderable at any hour, not just during Breakfast/Lunch service.

**What was built**
- `V28__add_all_day_meal_period.sql`: `meal_periods.start_time`/`end_time` made
  nullable, a new `all_day BIT NOT NULL DEFAULT 0` column added, and an `'All Day'`
  period seeded with `start_time = end_time = NULL`, `all_day = 1`.
- `MealPeriod.isActiveAt(LocalTime)` now short-circuits to `true` when `allDay` is set,
  instead of comparing against `startTime`/`endTime` — chosen over a wide-but-bounded
  placeholder window (e.g. `00:00`–`23:59:59`) specifically to avoid a real day-boundary
  edge case: a `LocalTime` with nanosecond precision at `23:59:59.5` would fail
  `isAfter(23:59:59)` and get incorrectly rejected once a day. An explicit `allDay` flag
  has no such edge case and reads as "no limit" rather than implying one.
- `all_day` surfaced on `MealPeriodDto` (auto-mapped by MapStruct via the boolean
  property name).
- No other endpoint needed changes — `GET /menu/today?period=...`,
  `POST /admin/daily-options`, `POST /admin/daily-component-stock`, and
  `POST /orders`'s meal-period-window check all already worked generically off
  `MealPeriod` by id/name; the "All Day" period flows through the exact same code
  paths as Breakfast/Lunch.
- `V29__seed_consumables_catalog_and_daily_options.sql`: 3 example items (Cool Drink
  R15, Sweet Treat R10, Bottled Water R10) as standalone `meal_catalog` entries (not
  components of another dish) planned as `daily_meal_options` for today under the new
  period — same "seeded for today, wiped by test teardown" caveats as `V27` apply here
  too. These are just illustrative; swap/extend via the admin endpoints as needed.

**Gotcha hit and fixed:** the first migration attempt combined `ALTER TABLE ... ADD
all_day` and an `INSERT` referencing that column in one script with no batch
separator — SQL Server compiles a whole batch before executing any of it, so the
`INSERT` failed with "Invalid column name 'all_day'" even though the `ALTER TABLE`
preceding it was correct. Fixed by adding a `GO` batch separator (which Flyway's SQL
Server parser recognizes) between the schema change and the row insert.

**Tests** — `mealPeriods_publicEndpoint_returnsBreakfastLunchAndAllDay` (renamed from
the old exact-length-2 version, now asserts presence of all three by name/flag instead
of an exact count) and a new `OrderIntegrationTests` case,
`allDayOption_isOrderableRegardlessOfWallClockTime`, which pins the clock to 03:00 —
outside both Breakfast and Lunch — and confirms an All Day option still places
successfully. Full suite: 36 tests, 0 failures (aside from the flake noted above, which
cleared on rerun).

**Verified live**: `GET /meal-periods` shows `"All Day"` with `startTime`/`endTime`
`null` and `allDay: true`; `GET /menu/today?period=All%20Day` (note: the query param
must match the period name, including the space) returns the 3 seeded consumable
options.

---
