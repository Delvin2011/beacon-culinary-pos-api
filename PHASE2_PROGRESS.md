# Phase 2 Progress

Tracks implementation progress against the Phase 2 stage specs, stage by stage. Each stage
is built and shipped independently — this file records what's actually been done, what
decisions were made along the way, and what's still open.

---

## Addendum — Order-Timezone Bug Fix (pre-2.1)

Reported before Stage 2.1 work began: an order placed at 13:00 local time was rejected as
"outside the lunch timeframe" (Lunch is 12:00–14:30), which was wrong.

**Root cause** — `ClockConfig.clock()` returned `Clock.systemDefaultZone()`, which resolves
to whatever timezone the *server/container* runs in, not the business's local timezone. The
`Dockerfile`'s base image (`eclipse-temurin:17-jre-jammy`) has no `TZ` set, so the container
defaults to UTC. A 13:00 local order (business runs on `Africa/Johannesburg`, UTC+2) is 11:00
UTC on the server — before the 12:00 Lunch start — so `MealPeriod.isActiveAt()` correctly
evaluated `false` *for UTC*, which was wrong for actual wall-clock time.

**Fix** — `ClockConfig.clock()` now returns `Clock.system(ZoneId.of("Africa/Johannesburg"))`
instead of `systemDefaultZone()` — pinned explicitly in code rather than relying on
container/host timezone configuration staying correct across redeploys.

---

## Stage 2.1 — Backend: Kitchen Status Screen (KDS) — ✅ Complete

**What was built**
- Order lifecycle widened from Phase 1's single `CONFIRMED` status to
  `PENDING → IN_PROGRESS → DONE → COLLECTED`, with `VOIDED`/`REFUNDED` reserved for Stage
  2.6 (in the enum/DB constraint now, no code path produces them yet). Existing `CONFIRMED`
  orders migrated to `PENDING` — same state (paid, not yet touched by kitchen), renamed for
  the fuller lifecycle (`V30__widen_order_status.sql`).
- Status-change audit trail: `OrderStatusEvent` (`order_id`, `from_status` nullable,
  `to_status`, `changed_by`, `changed_at`) logs every transition, including the implicit
  `null → PENDING` at order creation (`V33__create_order_status_events.sql`).
- `KITCHEN` role added to `users` (`V31__add_kitchen_role.sql`), plus a single shared kiosk
  user, `Kitchen Station` (PIN `4321`), seeded via `V32__seed_kitchen_kiosk_user.sql` — logs
  in through the existing Stage 1.1 PIN-login endpoint unchanged.
- New `kitchen` package: `GET /kitchen/orders` (today's `PENDING`/`IN_PROGRESS` orders,
  oldest first, full line/extra detail), `PATCH /kitchen/orders/{id}/status`
  (`{ "status": "IN_PROGRESS" | "DONE" }`, strictly forward one-step transitions only —
  anything else is `400`), and `GET /kitchen/orders/stream` (SSE, `{ eventType, order }`
  where `eventType` is `ORDER_CREATED` | `STATUS_CHANGED` and `order` is the same shape as
  the `GET /kitchen/orders` response). All three gated to `KITCHEN`/`ADMIN`
  (`KitchenSecurityRules`).
- New `orders` package infrastructure (deliberately generic, not kitchen-specific — later
  Phase 2 stages build on it directly without further migrations):
  - `OrderStatusEventPublisher` — the single choke point that writes the audit row and
    broadcasts on the SSE stream together, so the two can never go out of sync. Both
    `POST /orders` (order creation) and the kitchen `PATCH` go through it.
  - `OrderEventBroadcaster` — a generic SSE emitter registry/fan-out, with no knowledge of
    "kitchen" as a concept.
  - `OrderSummaryDto`/`OrderSummaryMapper` — the canonical KOT-equivalent order shape, used
    as both the `GET /kitchen/orders` response body and the SSE payload's `order` field, so
    there's one serialization format for both (Stage 2.2's public display is expected to
    reuse this same stream/shape rather than a second one).

**Decisions made**
- Package boundary: `OrderStatusEvent`, `OrderEventBroadcaster`, and `OrderSummaryDto`/
  `Mapper` live in `orders`, not `kitchen`, even though only `kitchen` consumes them this
  stage — matches the spec's own framing of these as shared infrastructure, and keeps the
  dependency direction one-way (`kitchen` → `orders`, never the reverse), avoiding a package
  cycle between `OrderService` and `KitchenService`.
- Allowed transitions modeled as a `Map<OrderStatus, OrderStatus>` (`PENDING→IN_PROGRESS`,
  `IN_PROGRESS→DONE`) rather than a switch/if-chain — a status with no entry (e.g. `DONE`,
  or any target not equal to the map's value) falls through to `400` for free, which is what
  naturally makes "already-`DONE`" and "targeting `COLLECTED`" both rejected without special-
  casing either.
- `users.role` didn't actually have a CHECK constraint yet (Stage 1.1 only added a
  `DEFAULT`) — the spec's migration note assumed one existed to "widen." Added one now
  (`CK_users_role`) covering `USER`/`CASHIER`/`ADMIN`/`KITCHEN`, since none of the 4 existing
  seeded rows violated it.
- `orders.status`'s existing CHECK/DEFAULT constraints (from `V23`) were created inline and
  unnamed, so SQL Server auto-generated their names (e.g. `CK__orders__status__7755B73D`) —
  not something a migration can hardcode across environments. `V30` looks them up
  dynamically via `sys.check_constraints`/`sys.default_constraints` joined on
  `parent_column_id`, drops them, and replaces them with explicitly-named ones
  (`CK_orders_status`, `DF_orders_status`) so this doesn't recur.
- `Kitchen Station`'s `email`/`password` columns (`NOT NULL` in the schema, but this account
  is PIN-only in practice) were filled with the same "no known plaintext" filler BCrypt hash
  `AuthService.DUMMY_PIN_HASH` already uses for its constant-time PIN-miss comparison,
  rather than minting a new throwaway secret.
- `eventType` distinguishes `ORDER_CREATED` (the `from_status == null` case) from
  `STATUS_CHANGED` (everything else) — the spec only gave one example payload
  (`STATUS_CHANGED`) but described "order-created and status-change events" as two kinds;
  this lets a frontend tell "add a new card" from "update an existing one" without
  re-deriving it from `from_status` itself.

**Tests** — `KitchenIntegrationTests` (10): new order appears in the active queue as
`PENDING` with an audit event; `PENDING→IN_PROGRESS` and `IN_PROGRESS→DONE` both succeed,
log events, and the latter leaves the active queue; skipping straight to `DONE` from
`PENDING` is `400`; targeting `COLLECTED` is `400` both from `PENDING` and from `DONE`;
repeating a transition on an already-`DONE` order is `400`; `CASHIER` gets `403` on all
three kitchen endpoints while `KITCHEN`/`ADMIN` succeed; a previous-day order is excluded
from the active queue; and an SSE test that opens the stream via `MockMvc`, triggers a
`PATCH` from a second request on the same thread, and asserts the streamed response body
(read directly off the still-open `MockHttpServletResponse`) contains the `STATUS_CHANGED`
event with the correct order id/status.

**Regression fix required** — the new `order_status_events` FK broke
`OrderIntegrationTests`' teardown: `orderRepository.deleteAll()` failed with a
`DataIntegrityViolation` because audit rows still referenced those orders. Left uncaught,
this aborted the rest of teardown too (leftover open shifts), which cascaded into failures
across `OrderIntegrationTests` *and* `ShiftIntegrationTests` on the next run (spurious `409`s
from a shift that was never actually closed). Fixed by deleting
`orderStatusEventRepository` rows before `orderRepository` rows in `OrderIntegrationTests`'
`@AfterEach`, mirroring the same ordering already used in `KitchenIntegrationTests`. Full
suite: 46 tests, 0 failures, no other regressions.

**Verified live** — migrations applied against the real dev SQL Server DB: all 6
pre-existing `CONFIRMED` orders read `PENDING` afterward with nothing else changed;
`Kitchen Station` seeded at `id = 5` with `role = KITCHEN`; `order_status_events` table
created. (The historical `CONFIRMED → PENDING` data migration itself has no automated test
— by the time tests run against a freshly-migrated DB there's no more `CONFIRMED` data left
to migrate against — so this one was checked manually via `sqlcmd` instead.)

---

## Stage 2.2 — Backend: Public Display Board — ✅ Complete

**What was built**
- New `board` package, fully public (no auth, no role check —
  `PublicBoardSecurityRules.permitAll()`): `GET /public/board/today` (today's
  `PENDING`/`IN_PROGRESS`/`DONE` orders, minimal shape) and `GET /public/board/stream` (SSE,
  same minimal shape wrapped in `{ eventType, order }`).
- Minimal response shape by design — `PublicOrderDto { orderNumber, status }` only. No line
  items, no internal `orderId`, no cashier/customer data. `VOIDED`/`REFUNDED` (Stage 2.6)
  never appear, because the status filter list simply never includes them — nothing to
  change once that stage ships.
- No schema changes. Reuses `orders.OrderRepository.findByOrderDateAndStatusInOrderByCreatedAtAsc`
  verbatim (the exact method Stage 2.1 built for the kitchen queue), just with a 3-status
  list (`PENDING`/`IN_PROGRESS`/`DONE`) instead of kitchen's 2 (`PENDING`/`IN_PROGRESS`).

**Decisions made — refactored the Stage 2.1 broadcast wiring rather than bolt on a second one**
- The spec explicitly warned against "a second, independently-maintained status-tracking
  mechanism." Bolting a direct call from `orders.OrderStatusEventPublisher` onto a new
  `board`-owned broadcaster would have worked, but it would have made `orders` reach directly
  into both `kitchen` and `board` packages — coupling that gets worse with every future
  subscriber (Stage 2.3, 2.4, 2.6 would each need `OrderStatusEventPublisher` edited again).
- Instead, `OrderStatusEventPublisher.publish()` now does exactly two things: write the audit
  row, and publish one Spring `ApplicationEvent` (reusing the existing `OrderStatusStreamEvent`
  class as the event payload — no new type needed, `ApplicationEventPublisher.publishEvent`
  accepts any POJO). `orders` no longer holds a reference to any broadcaster or knows that
  `kitchen`/`board` exist at all.
- `KitchenService` gained a small `@EventListener` method that relays the event verbatim onto
  its existing `OrderEventBroadcaster` — this replaces the direct call `publish()` used to
  make, with identical runtime behavior (default `@EventListener` execution is synchronous, on
  the same thread/transaction, so the "same transaction/request" ordering guarantee from Stage
  2.1 still holds). `PublicBoardService` gained the equivalent listener, reducing the event down
  to `{ orderNumber, status }` before broadcasting on its own, separate `PublicBoardEventBroadcaster`.
- Two independent SSE emitter registries (`orders.OrderEventBroadcaster` for kitchen,
  `board.PublicBoardEventBroadcaster` for the public board) rather than one shared one — a
  shared registry would mean either qualifying every injection point (a DI pattern not used
  anywhere else in this codebase) or leaking the full kitchen payload to public clients.
  Keeping them separate, both driven from the one `publish()` call, satisfies "two views over
  one source of truth" without introducing that complexity.
- `PublicBoardEventBroadcaster` duplicates `OrderEventBroadcaster`'s ~20 lines rather than
  extending it — a shared base class would need `OrderEventBroadcaster` to stop being a
  `@Component` itself (to avoid Spring finding two beans of the same type for `KitchenService`'s
  existing unqualified injection), rippling a type change into already-shipped 2.1 code for a
  trivial, stable class. Duplicating this generic SSE-registry boilerplate reads the same as the
  already-duplicated per-feature `SecurityRules` beans — accepted as the same category of
  intentional small repetition, not something worth a shared abstraction for.

**Tests** — `PublicBoardIntegrationTests` (7): `GET /public/board/today` succeeds with no
`Authorization` header; response payload asserted field-by-field to contain only
`orderNumber`/`status` (no `orderId`, `id`, `lines`, `cashierId`, or `createdAt`); a status
change via the Stage 2.1 kitchen `PATCH` appears on the public stream with the correct
`orderNumber`/status and no leaked internal fields; `GET /public/board/stream` itself succeeds
unauthenticated; a previous-day order is excluded from `GET /public/board/today`; two
simultaneous subscribers to `/public/board/stream` both receive the same event from one
`PATCH` (proving a broadcast, not per-client polling); a `DONE` order remains on the board
(Stage 2.3's `COLLECTED` removal isn't built yet). Full suite: 53 tests, 0 failures — including
all 10 `KitchenIntegrationTests` passing unchanged after the `OrderStatusEventPublisher`
refactor, confirming the event-based relay behaves identically to the direct call it replaced.

---

## Stage 2.6 — Backend: Admin-Gated Voids & Refunds — ✅ Complete

**What was built**
- Four migrations (`V35`–`V38`): `orders.original_total` (immutable snapshot, backfilled from
  the existing `total` column, then tightened to `NOT NULL`) and `orders.extras_adjusted`
  (guard flag); `order_line_extras.adjusted` (rows flagged, never deleted, so the original
  order detail stays reconstructable); `order_adjustments` (the audit trail —
  `scope`/`action`/`reason_code`/`note`/`amount`/`requested_by`/`authorized_by`); and
  `authorization_tokens` (`token`, `admin_id`, `expires_at`, `used`).
- New `admin` package pieces: `AuthorizationToken` entity/repository and
  `AdminAuthorizationService`. `POST /admin/authorize` checks the submitted PIN against every
  active `ADMIN` (reusing Stage-1-era `UserRepository.findByActiveTrueAndRoleIn`, no new query
  needed), and on a match mints a 60-second single-use token (`SecureRandom` → 32 bytes →
  URL-safe base64). `AdminSecurityRules` carves out just this one `/admin/**` path for
  `CASHIER`/`ADMIN`; every other admin endpoint stays `ADMIN`-only, unchanged.
- New `orders` package pieces: `OrderAdjustment` entity/repository,
  `OrderAdjustmentScope`/`Action`/`ReasonCode` enums, a third `OrderEventType.ORDER_UPDATED`,
  and `OrderAdjustmentService` driving `POST /orders/{id}/adjustments`. `VOID` vs `REFUND` is
  derived from the order's current status at request time (`PENDING` → `VOID`, else `REFUND`)
  — never a client-supplied field. `WHOLE_ORDER` zeroes the order, restores stock for every
  not-already-adjusted line/extra on a `VOID`, and terminalizes the order. `EXTRAS_ONLY`
  restores only the affected extras' component stock, flags those `order_line_extras` rows
  `adjusted`, and leaves the order's status and base-meal stock untouched.
- Stock restoration reuses the exact decrement pattern from order creation, just additive:
  `DailyMealOptionRepository.incrementPortionsRemaining`/
  `DailyComponentStockRepository.incrementBufferRemaining` are the same conditional-`UPDATE`
  shape as `decrementPortionsRemaining`/`decrementBufferRemaining` minus the `WHERE ... >=`
  guard, since restoring stock can never oversell.
- `GET /orders/{id}` (Stage 1.4) now returns `originalTotal` alongside the still-mutable
  `total`, plus an `adjustments` array — no new endpoint needed, the existing `OrderMapper`
  just grew a `toDto(OrderAdjustment)` method.
- Event wiring reuses Stage 2.1's infrastructure rather than adding a parallel one:
  `WHOLE_ORDER` calls the existing `OrderStatusEventPublisher.publish()` (same audit-row +
  `STATUS_CHANGED` broadcast the kitchen `PATCH` already uses), so the kitchen and public
  boards drop the order for free — both already filter to non-terminal statuses, so nothing
  about the filters needed to change for this stage. `EXTRAS_ONLY` instead calls
  `orders.OrderEventBroadcaster.broadcast()` directly with the new `ORDER_UPDATED` event,
  bypassing the shared `ApplicationEventPublisher` entirely — the only way to reach the kitchen
  stream without also triggering `PublicBoardService`'s listener, since both listen on the same
  application-event channel.
- `OrderSummaryMapper` now filters out `adjusted` extras when building the `extras` list for a
  line. Because this one mapper backs `GET /kitchen/orders` *and* all three SSE event bodies
  (`ORDER_CREATED`/`STATUS_CHANGED`/`ORDER_UPDATED`), voided/refunded extras disappear from the
  kitchen ticket everywhere at once with no second code path to keep in sync.

**Decisions made**
- Token consumption runs in its own transaction. `AdminAuthorizationService.consumeToken()` is
  `@Transactional(propagation = REQUIRES_NEW)`, called from inside
  `OrderAdjustmentService.adjustOrder()`'s own `@Transactional` method. This is what actually
  makes the spec's "if the adjustment fails validation later in this method, the token should
  not be consumable a second time" true — had `consumeToken()` shared the caller's transaction,
  a later exception (bad order date, terminal order, `OTHER` without a note, ...) would roll
  back the token deletion right along with everything else, silently un-burning it and defeating
  the whole point of "forces a fresh PIN entry on retry."
- `EXTRAS_ONLY`'s two failure modes are checked in a specific order to land on the right status
  code. `order.extrasAdjusted` (order-level) is checked *first* and throws `409` immediately;
  only if that's `false` does the code compute the unadjusted-extras list and throw `400` on
  empty. Built the other way round first — checking "any unadjusted extras?" before the
  order-level flag — and a genuine second `EXTRAS_ONLY` call returned `400` instead of the
  spec's `409`, because after a first successful adjustment both signals go true together
  (every extra is individually `adjusted` *and* `order.extrasAdjusted` is set) so the wrong one
  was winning. Caught by the `secondExtrasOnlyAdjustment_returns409` test before landing.
- `AuthorizationToken`/`AdminAuthorizationService` live in `admin`, not a new `auth` package —
  matches the `/admin/authorize` URL namespace and the existing precedent that `AdminSecurityRules`
  already owns all of `/admin/**`. `OrderAdjustmentService` (in `orders`) depends on it the same
  directional way `kitchen`/`board` already depend on `orders`' shared infrastructure — no new
  package cycle.
- `OrderAdjustmentRepository` was added even though nothing outside `OrderAdjustmentService`
  queries it yet — same role `OrderStatusEventRepository` played from Stage 2.1 on: a thin
  marker repository that exists mainly so integration tests can assert against adjustment rows
  directly instead of only through the JSON response.

**Tests** — `OrderAdjustmentIntegrationTests` (16): valid/invalid admin PIN on
`POST /admin/authorize` (`401` doesn't reveal which admins exist); a cashier's own PIN cannot
self-authorize; `WHOLE_ORDER` void on a `PENDING` order restores both base-portion and extra
stock, zeroes the total, and drops the order from both the kitchen queue and the public board;
`WHOLE_ORDER` refund on a `DONE` order leaves stock untouched; `EXTRAS_ONLY` void on a `PENDING`
order restores only the extra's component stock (base portions untouched), reduces the total by
exactly the extras' sum, and fires `ORDER_UPDATED` on the kitchen SSE stream; `EXTRAS_ONLY`
refund on a `DONE` order reduces the total with no stock change and no kitchen event; a second
`EXTRAS_ONLY` attempt is `409` and leaves the first adjustment's effects unchanged; any
adjustment on an already-terminal order is `409`; an adjustment on a prior-day order is `400`;
`reasonCode = OTHER` is `400` without a `note` and succeeds with one; a token is rejected on
reuse; a token is rejected once expired, even though never used; a request that fails validation
(`OTHER` without a note) still burns the token, so the retry needs a fresh authorize call;
`WHOLE_ORDER` after a prior `EXTRAS_ONLY` on the same order succeeds with `amount` reflecting
only the remaining post-extras total, and doesn't double-restore the extra's stock; and
`GET /orders/{id}` returns the adjustment history plus `originalTotal` alongside the reduced
`total`, with the affected `order_line_extras` row showing `adjusted: true`.

**Verified live** — migrations applied against the real dev SQL Server DB via the Flyway CLI
directly (the `dev` profile runs with `spring.flyway.enabled: false`, same as every prior stage,
so schema changes here were applied out-of-band rather than via app startup). `V35` initially
failed with `Invalid column name 'original_total'` — SQL Server compiles a `.sql` script as one
batch, so `ALTER TABLE ... ADD` followed immediately by a statement referencing that new column
fails unless split across batches (the same class of issue `V30` dodged with dynamic `EXEC` SQL);
fixed by inserting `GO` separators between the `ADD COLUMN`, the backfill `UPDATE`, and the
`NOT NULL` tightening. Full suite: 69 tests, 0 failures, no regressions in any earlier stage.

---

## Stage 2.5 — Backend: Cash Drawer Reconciliation & Shift Close — ✅ Complete

**What was built**
- Three migrations (`V39`–`V41`): `shifts.closing_cash`/`expected_cash`/`variance`/
  `variance_reason_code` (checked)/`variance_note`/`variance_authorized_by` (FK `users`, all
  nullable — populated only on close, and only the variance columns only when non-zero);
  `idx_shifts_single_open`, a filtered unique index (`CREATE UNIQUE INDEX ... WHERE status =
  'OPEN'`) widening Stage 1.1's per-cashier "one open shift" rule to a system-wide one; and
  `order_adjustments.shift_id` (FK `shifts`, backfilled from each adjustment's order's own
  `shift_id` for any pre-existing rows, then tightened to `NOT NULL`) — split across `GO`
  batches the same way `V35` (Stage 2.6) had to be, since the backfill `UPDATE` references the
  column the same script just added.
- `ShiftService.openShift()` no longer runs an app-level "does this cashier already have a
  shift open" check — it just inserts and lets `idx_shifts_single_open` reject a second
  concurrent `OPEN` row, translating the resulting `DataIntegrityViolationException` into the
  same `409 ShiftAlreadyOpenException` the endpoint already returned. `saveAndFlush()` (not
  `save()`) is required here — otherwise the `INSERT` wouldn't execute until the transaction's
  own commit-time flush, by which point it's too late for this method's `try/catch` to see it.
- `GET /shifts/{id}/summary` (new, read-only, doesn't close anything) and `POST
  /shifts/{id}/close` (rewritten) both share a private `computeCashBreakdown()` helper
  implementing the spec's attribution formula: `expected_cash = opening_float + SUM(orders
  .original_total WHERE shift_id = this AND payment_method = CASH) − SUM(order_adjustments
  .amount WHERE shift_id = this)`. Both endpoints stay `CASHIER` (own shift only via a shared
  `loadShiftForCaller()` ownership check) / `ADMIN` (any), matching Stage 1.1's existing rule —
  no `ShiftSecurityRules` changes needed.
- `POST /shifts/{id}/close` now takes `{ countedCash, varianceAuthorization? }`.
  `varianceAuthorization` (`{ reasonCode, note?, authorizationToken }`) is required only when
  `countedCash != expectedCash` (compared via `BigDecimal.compareTo`, not `equals`, so `500` and
  `500.00` count as equal); when required but missing, `400`. Reuses Stage 2.6's exact
  `AdminAuthorizationService.consumeToken()` — no second authorization pathway — so the token is
  validated/burned in its own `REQUIRES_NEW` transaction before the `reasonCode = OTHER` +
  missing-`note` check, meaning a request that fails that later validation still burns the token
  (same "forces a fresh PIN entry on retry" behavior Stage 2.6 established). On success,
  `closingCash`/`expectedCash`/`variance` are always persisted (snapshotted, not left to be
  recomputed later); the three variance-specific columns stay `null` on a zero-variance close.
- `OrderAdjustmentService.adjustOrder()` gained one line: it now looks up whichever `Shift` is
  currently `OPEN` (`ShiftRepository.findFirstByStatus`) and sets it as the new
  `OrderAdjustment.shift` — the shift open at authorization time, not `order.shift`. Trivial to
  make correct because `idx_shifts_single_open` guarantees at most one candidate. No open shift
  at adjustment time throws the same `NoOpenShiftException` order creation already uses (already
  wired to `409` in `OrderController`) — a void/refund is cash-drawer activity same as a sale, so
  it needs an active till the same way.

**Decisions made**
- **The global constraint has a load-bearing side effect on `OrderService`, not just
  `ShiftService`**: `OrderService.createOrder()` looks up an open shift scoped to `o.cashier =
  currentUser`, not "any open shift" — so once `idx_shifts_single_open` is live, only *one*
  cashier system-wide can be mid-shift (and therefore able to sell) at any given moment. That's
  the intended "one shift, one till" model, but it broke two pre-existing
  `OrderIntegrationTests` concurrency tests (`concurrentOrdersForLastPortion_exactlyOneSucceeds`,
  `concurrentOrdersForLastSharedExtraUnit_acrossDifferentLines_exactlyOneSucceeds`) that opened
  shifts for *two different* cashiers to race two simultaneous orders — that setup is no longer
  reachable at all (the second `openShift` call now itself returns `409`). Fixed by racing two
  concurrent requests from the *same* cashier/shift instead — the thing actually under test is
  the conditional-decrement race guard on `daily_meal_options`/`daily_component_stock`, which two
  requests on one shift exercise identically to two requests on two shifts.
- `orderCount` in the summary DTO counts *all* orders on the shift (`OrderRepository
  .countByShiftId`), not just cash ones — `PaymentMethod` only has a `CASH` value today so the
  two are numerically identical, but "how many orders did this shift ring up" reads as the more
  natural definition than "how many cash orders," and doesn't need revisiting if a second payment
  method is ever added.
- `ShiftService.openShift()`'s try/catch is narrowly scoped to `DataIntegrityViolationException`
  around the `saveAndFlush()` call specifically — not a broader catch around the whole method —
  so a genuine unrelated persistence failure isn't silently reinterpreted as "shift already
  open."
- Chose `saveAndFlush()` over `save()` deliberately after confirming Hibernate's `IDENTITY`
  generator normally forces an immediate `INSERT` at `persist()` time anyway (it needs the
  generated key back right away) — `saveAndFlush()` isn't masking a batching issue, it's making
  the "the exception surfaces inside this try block" guarantee explicit and independent of that
  Hibernate implementation detail.
- New `ShiftCloseCashAttributionIntegrationTests` class (rather than folding into
  `ShiftIntegrationTests`) specifically for the two scenarios that need real orders/adjustments
  (expected-cash math, cross-shift attribution) — mirrors why Stage 2.6 needed the full
  meal-catalog/order-creation setup `OrderAdjustmentIntegrationTests` carries and
  `ShiftIntegrationTests` never did. Every other Stage 2.5 scenario (global constraint,
  ownership, variance authorization flows, already-closed) needs no orders at all: with an empty
  shift, `expectedCash == openingFloat`, so sending any other `countedCash` is enough to produce
  a controllable nonzero variance without touching the menu/order machinery.

**Tests** — 19 new (`ShiftIntegrationTests` 17, `ShiftCloseCashAttributionIntegrationTests` 2):
opening a second shift while one is open is `409` for both the same cashier (Stage 1.1's
original case) and a *different* cashier (Stage 2.5's widened case); a zero-variance close
succeeds with no `varianceAuthorization` and leaves the three variance columns absent from the
response; closing another cashier's shift is `403`, closing an already-`CLOSED` shift is `409`;
`GET /shifts/{id}/summary` on a fresh shift reflects `openingFloat` only, and is `403` for a
non-owner non-admin; a nonzero variance with no `varianceAuthorization` is `400`; `OTHER` with no
`note` is `400`; a complete valid authorization succeeds with `closingCash`/`expectedCash`
/`variance`/`varianceReasonCode`/`varianceNote`/`varianceAuthorizedById` all persisted and
returned; a token reused across two different shift closes is `401`; an expired token is `401`
even though unused; a request that fails the `OTHER`-without-`note` check still burns the token
(retry needs a fresh authorize call). Separately: expected-cash math verified end-to-end against
2 real cash sales plus a same-shift `WHOLE_ORDER` void and a same-shift `EXTRAS_ONLY` refund; and
cross-shift attribution verified by closing shift A, opening shift B, refunding a shift-A order
while B is open, and confirming shift A's `adjustmentsTotal`/`expectedCash` are untouched while
shift B's absorb the refund. Full suite: 81 tests, 0 failures — including both fixed
`OrderIntegrationTests` concurrency tests passing unchanged in intent (same race, same-cashier
setup) after the global single-open-shift constraint made their original two-cashier setup
impossible.

**Verified live** — migrations applied against the real dev SQL Server DB via the Flyway CLI
directly, same out-of-band pattern as every prior stage (`spring.flyway.enabled: false` under the
`dev` profile). Checked for pre-existing stray `OPEN` shifts before applying `V40` (none existed
in the dev DB, so the filtered unique index's `CREATE INDEX` succeeded without needing a data
cleanup step first — a real deployment with duplicate `OPEN` rows would need one).

---

## Stage 3 — Backend: Polish Existing Functionality — ✅ Complete

Three sub-stages, each deliberately reusing Phase 1/2 infrastructure rather than introducing
anything new: card payment on the existing order-creation path, a third `order_adjustments`
action alongside Stage 2.6's void/refund, and a reusable session token layered onto Stage 2.6's
single-use one.

### 3.1 — Card Payment

**What was built**
- `PaymentMethod` widened to `CASH | CARD`. `CreateOrderRequest` gained `paymentMethod`
  (defaults to `CASH` when omitted, so Stage 1.3 clients keep working unmodified) and
  `cardReference` (free text — approval code/last-4, whatever the physical card machine
  printed; the only audit trail without real gateway integration).
- `orders.amount_tendered`/`change_due` made nullable and `orders.card_reference` added
  (`V42__widen_payment_method_card.sql`) — a `CARD` order stores neither tender nor change
  (always the exact total) and both are omitted (`@JsonInclude(NON_NULL)`) from the response
  rather than serialized as `null`.
- `OrderService.createOrder()` branches once, right where the old unconditional
  `amountTendered` check used to live: `CARD` requires `cardReference` (`400` if
  missing/blank) and skips the tender/change math entirely; `CASH` keeps the exact Stage 1.3
  behavior. Stock decrement, meal-period validation, and extras validation run identically
  before this branch regardless of payment method — this stage never touches them.
- No changes to Stage 2.5's cash-drawer formula. `OrderRepository
  .sumOriginalTotalByShiftIdAndPaymentMethod` already filters `WHERE payment_method = 'CASH'`,
  so `CARD` orders were invisible to `expectedCash` for free — confirmed by test, not by
  editing `ShiftService`.

**Decisions made**
- Widening `orders.payment_method`'s `CHECK` constraint reused the exact `V30` pattern
  (Stage 2.1): the original constraint was created inline/unnamed in `V23`, so `V42` looks it
  up dynamically via `sys.check_constraints` rather than guessing a generated name.
- Validation placed after the same stock-decrement phase the old `amountTendered` check ran
  after, not before it — matches the existing "Phase 1 validate/price, Phase 2 decrement,
  then payment check" structure, and stays safe because the whole method is one
  `@Transactional`: a `CARD` order missing its reference still rolls back any decrements that
  happened first.

**Tests** — `OrderCardPaymentIntegrationTests` (5): a valid `CARD` order succeeds with
`cardReference` echoed back and no `amountTendered`/`changeDue` in the response; missing or
blank `cardReference` is `400`; omitting `paymentMethod` entirely still defaults to `CASH`
with unchanged tender/change behavior (regression); a mixed shift (one cash sale, one card
sale) shows `cashSalesTotal`/`expectedCash` reflecting only the cash order via
`GET /shifts/{id}/summary`, with zero changes to `ShiftService`.

### 3.2 — Discount

**What was built**
- `OrderAdjustmentAction` gained `DISCOUNT`; `DiscountType` (`PERCENTAGE | FIXED_AMOUNT`) is
  new; `order_adjustments` gained `discount_type`/`discount_value`
  (`V43__add_discount_to_order_adjustments.sql`, widening the already-named
  `CK_order_adjustments_action` constraint from `V37` directly — no dynamic lookup needed this
  time).
- `CreateOrderAdjustmentRequest` gained `requestedAction` (missing or `CANCEL` → Stage 2.6's
  existing auto-derived-`VOID`/`REFUND` path, byte-for-byte unchanged; `DISCOUNT` → the new
  path) plus `discountType`/`discountValue`. `scope` is no longer `@NotNull` at the
  bean-validation level — it's required only for the `CANCEL` path now (a discount is always
  whole-order), enforced in the service instead.
- `OrderAdjustmentService.adjustOrder()` restructured around a shared prefix (authorization,
  `reasonCode = OTHER` + note check, same-day check, terminal-order check, open-shift lookup)
  that both paths still run, then branches: the old body moved into the `CANCEL` branch
  unchanged; a new `applyDiscount()` computes `amount` (`round(total * value / 100, 2)` for
  `PERCENTAGE`, `value` for `FIXED_AMOUNT`), rejects `400` on an out-of-range percentage
  (`(0, 100]`), a non-positive fixed amount, or an amount exceeding the order's *current*
  total, then subtracts it from `order.total` with **no** status transition, stock change, or
  SSE broadcast — exactly the "money handed back on an already-paid order" model the spec
  called out as the working assumption.
- No changes needed to Stage 2.5's `expectedCash` formula: `order_adjustments.amount` is
  summed regardless of `action`, so a `DISCOUNT` row is picked up by
  `OrderAdjustmentRepository.sumAmountByShiftId` the same way a `VOID`/`REFUND` row always
  was — confirmed by test.

**Decisions made**
- **Deviation from the spec's literal test-scenario wording, flagged rather than forced**: the
  Test Scenarios section lists "discount on an already-voided/refunded order → `400`", but the
  Business Logic section says step 2 reuses the exact same terminal-order check Stage 2.6
  already has (`order.status == VOIDED/REFUNDED` → reject) — and that shared check throws
  `OrderAlreadyAdjustedException`, which `OrderController` already maps to `409 CONFLICT` (the
  same status a second void/refund attempt gets). Rather than special-case `DISCOUNT` to throw
  a different exception/status than `CANCEL` hits for the identical condition, this
  implementation kept the check genuinely shared and let it report `409` for both — consistent
  behavior for "this order is terminal" beat matching one line of the spec's prose literally.
- The shared prefix — including token/session authorization — runs *before* the
  `requestedAction` branch, preserving the existing "a later validation failure still burns a
  single-use token" guarantee (Stage 2.6) for the discount path too, not just `CANCEL`.

**Tests** — `OrderDiscountIntegrationTests` (9): `PERCENTAGE` and `FIXED_AMOUNT` discounts each
compute and deduct the exact expected amount with the order status untouched; a discount
exceeding the current total is `400` with the order left unchanged; an out-of-range percentage
and a zero/negative fixed amount are each `400`; two discounts stacked on one order both
succeed as long as the cumulative amount stays within the total; a discount followed by a
`WHOLE_ORDER` refund produces a refund `amount` equal to the post-discount total, not the
original; a discount on an already-voided order is rejected (`409`, per the decision above);
and a shift-close scenario (cash sale + discount) shows the discount correctly reducing
`expectedCash` via `GET /shifts/{id}/summary` with zero changes to Stage 2.5's code.

### 3.3 — Management Menu / Cashup Summary Exposure

**What was built**
- New `authorization_sessions` table (`V44__create_authorization_sessions.sql`) and
  `AuthorizationSession` entity/repository — deliberately separate from Stage 2.6's
  `authorization_tokens`, since this token type is never consumed.
- `POST /admin/authorize-session` (`CASHIER`/`ADMIN`, same PIN-matching rule as
  `POST /admin/authorize`) mints a 5-minute, reusable `sessionToken`. `AdminAuthorizationService`
  had its PIN-matching logic extracted into a shared `matchAdmin()` so both `authorize()` and
  the new `authorizeSession()` use one implementation.
- `POST /orders/{id}/adjustments` now accepts *either* the existing single-use
  `authorizationToken` *or* a `sessionToken` — `OrderAdjustmentService` tries `sessionToken`
  first if present (validated via the new `AdminAuthorizationService.validateSessionToken()`,
  which checks existence/expiry but never deletes the row), falling back to the original
  `consumeToken()` path otherwise. Stage 2.6 clients that only ever send `authorizationToken`
  are unaffected.
- `GET /shifts/{id}/summary` gained an optional `sessionToken` query parameter. When present
  and valid, `ShiftService.getShiftSummary()` skips the usual owner-or-admin ownership check
  entirely (the terminal is already inside an admin-authorized context) and loads the shift by
  id alone; omitted, it falls back to the exact Stage 2.5 ownership check unchanged.

**Decisions made**
- Read the acceptance criteria as authoritative over one contradictory scope-section sentence:
  the spec's Scope section says viewing the cashup summary with a `sessionToken` needs "no new
  backend logic needed here at all," but its own Acceptance Criteria explicitly require
  bypassing the ownership check for a non-owner holding a valid session — which the endpoint's
  pre-existing `CASHIER`/`ADMIN`-role-only security rule can't do by itself. Implemented the
  bypass in `ShiftService` rather than leaving the contradiction unresolved.
- `validateSessionToken()` is intentionally structured never to mutate the row (no `used`
  flag, no deletion) — reusability across multiple actions within the 5-minute window is the
  entire point of this token type, unlike Stage 2.6's single-use one.

**Tests** — `ManagementSessionIntegrationTests` (5): authorizing a session then viewing the
caller's own cashup summary succeeds; a session token lets cashier B view cashier A's shift
summary (first proving the same call is `403` *without* a token, establishing the baseline
Stage 2.5 behavior is unchanged); one session token authorizes a void and then a discount on
two different orders without re-authorizing; an expired session token is `401` on both an
adjustment call and a summary view; and Stage 2.6's original single-use-token flow still
succeeds unmodified (regression).

**Full-suite verification** — all three sub-stages built and tested together against the real
dev SQL Server DB (migrations `V42`–`V44` applied cleanly via Spring Boot's own Flyway startup
during test runs, no manual intervention needed since none of the three involve a data
backfill). Combined with every pre-existing suite: **100 tests, 0 failures, 0 errors** — the
full Stage 1–2.6 regression suite passes unmodified, confirming none of the "explicit
assumption" flags above required touching already-shipped behavior.

---

## Stage 4 — Backend: Account Payment & Cash/Card Split — ✅ Complete

The core model change — orders have `payments[]`, not a single payment method — is a breaking
change to `POST /orders`'s request/response contract (Stage 1.3/3's `paymentMethod`/
`amountTendered`/`cardReference` fields are gone from both). Every existing order-creation test
across the whole suite had to move to the new contract as part of this stage, per its own
"full regression... required against the new shape" acceptance criterion.

**What was built**
- New `accounts` package: `Account`/`AccountPayment` entities, `GET /accounts` (lightweight
  till-picker list, optional `?active=` filter), `GET/POST /admin/accounts`,
  `PUT /admin/accounts/{id}` (edit/deactivate), `GET /admin/accounts/{id}/balance`, and
  `POST /admin/accounts/{id}/payments` (records money received, returns the updated balance).
  `outstandingBalance = totalCharged − totalReversed − totalPaid`, all three terms derived
  live from `OrderPayment`/`OrderAdjustment`/`AccountPayment` — no stored balance column, no
  credit-limit enforcement (explicitly out of scope).
- New `orders.OrderPayment` entity/table — 1 row per payment method used on an order (1 entry
  for a single-method order, up to 2 for a CASH+CARD split, always exactly 1 for ACCOUNT).
  Replaces `Order`'s old single `paymentMethod`/`amountTendered`/`changeDue`/`cardReference`
  fields entirely — those fields are gone from the `Order` entity and `OrderDto`; the
  `orders` table's matching columns are left in place, unused, per the spec's explicit
  "flag as future cleanup" instruction.
- `OrderService.attachPayments()` — new validation replacing the old single-method check:
  1–2 entries, at most one per method, `ACCOUNT` mutually exclusive with everything else,
  `SUM(payments[].amount)` must equal the order total exactly (`400` otherwise), then the
  same per-method field rules as before (`CASH` needs `amountTendered >= amount`, `CARD`
  needs `cardReference`, `ACCOUNT` needs an existing, active `accountId`). Runs in the same
  position the old check did — after the stock decrement — safe because the whole method is
  one transaction, so a rejected split still rolls back any decrements already applied.
- `OrderAdjustment` gained `refundMethod` (`CASH | ACCOUNT_BALANCE`) and `account`, computed
  automatically in `OrderAdjustmentService.adjustOrder()` — once, right after either branch
  (`CANCEL`'s VOID/REFUND or `DISCOUNT`) — by checking whether the order has an `ACCOUNT`
  payment. Applies uniformly to all three adjustment actions, exactly as specified: an
  account-paid order's adjustment always credits the account; everything else always pays
  out as cash, for the full amount, regardless of the original cash/card mix.
- `ShiftService.computeCashBreakdown()` reworked per Part D: `cashSalesTotal` now sums
  `OrderPayment.amount` (method `CASH`) instead of `Order.originalTotal` (method
  `CASH`) — same "immutable at sale time, immune to later adjustments" property, just
  sourced from the row that actually carries a per-method amount now. `adjustmentsTotal` is
  narrowed to `refundMethod = CASH` adjustments only, so an `ACCOUNT_BALANCE` payout
  contributes zero — both to keep `expectedCash`'s formula correct and to keep the DTO's
  three fields (`cashSalesTotal` + `openingFloat` − `adjustmentsTotal` = `expectedCash`)
  internally consistent with each other.
- Four migrations (`V45`–`V48`): `accounts`, `account_payments`, `order_payments` (with a
  backfill of existing Stage 1.3/3 orders as single-entry rows), and
  `order_adjustments.refund_method`/`account_id` (existing rows backfilled to `CASH`, the
  only value possible before `ACCOUNT` existed).

**Decisions made**
- **Deviated from the spec's literal backfill snippet**: `V47`'s migration snippet in the spec
  selects `orders.total` for the backfilled `order_payments.amount`, but `total` is the
  *current*, possibly-already-adjusted total — for any historical order that had a Stage
  2.6/3 void/refund/discount applied before this migration ran, backfilling from `total`
  would silently corrupt the cash formula it's meant to replace (the adjustment's own `amount`
  would still get subtracted in full, but the sale side would already reflect the reduction,
  double-counting it). Used `original_total` instead — the immutable snapshot Stage 2.6
  introduced for exactly this "what was actually collected at sale time" purpose, which is
  also what the formula this table replaces already used. No dev-DB orders were actually
  affected (every prior integration test suite cleans up its own data via `@AfterEach`), but
  the correct column matters for any real deployment with adjustment history.
- Accepted a bidirectional package dependency between `accounts` and `orders`, rather than
  forcing Stage 2.1's "one-way, no cycles" precedent here. `orders` needs `Account` (an
  `OrderPayment`/`OrderAdjustment` can reference one); `accounts.AccountService` needs
  `OrderPaymentRepository`/`OrderAdjustmentRepository` to compute a balance. Unlike
  `kitchen`/`board` reaching into `orders`' shared infrastructure (genuinely one-directional,
  many-subscribers-one-source), an account's balance is *fundamentally* derived from its
  orders — the coupling is the correct domain model here, not an accident of implementation
  convenience, and Java has no technical cycle restriction at the package level the way the
  earlier precedent was guarding against.
- Payment-split validation (size/duplicate-method/`ACCOUNT`-exclusivity/sum-equals-total) is
  entirely manual in `OrderService`, not bean-validation annotations — same reasoning as every
  prior stage's cross-field checks (`reasonCode = OTHER` needing a note, `scope` only required
  for `CANCEL`, etc.): these are relationships between array entries and the computed order
  total, not single-field constraints.
- `refundMethod`/`account` are computed once, after the `CANCEL`/`DISCOUNT` branch, rather than
  duplicated inside `adjustWholeOrder()`/`adjustExtrasOnly()`/`applyDiscount()` — all three
  already converge on building the same `OrderAdjustment` object before it's saved, so this
  stays a single, action-agnostic rule applied at that convergence point instead of three
  copies of the same account-lookup logic.

**Tests** — the entire existing order-creation-dependent suite moved to `payments[]` first
(`OrderIntegrationTests`, `OrderAdjustmentIntegrationTests`, `OrderCardPaymentIntegrationTests`,
`OrderDiscountIntegrationTests`, `ManagementSessionIntegrationTests`,
`ShiftCloseCashAttributionIntegrationTests`, plus the inline order-creation JSON in
`KitchenIntegrationTests`/`PublicBoardIntegrationTests`) — every call site now has to state the
payment `amount` as the order's *exact* total rather than a comfortably-large `amountTendered`,
since the sum-must-equal-total rule is new. Four new test classes for what pure regression can't
prove: `OrderPaymentSplitIntegrationTests` (7 — cash+card split succeeds; sum mismatch, two
same-method entries, three entries, and `ACCOUNT`+anything all `400`); `AccountIntegrationTests`
(7 — CRUD, till-picker filtering, an account order increasing `outstandingBalance`, an inactive
or nonexistent account rejected `400`, multiple partial `AccountPayment`s producing a correct
running balance, balance-on-unknown-account `404`); `RefundPayoutIntegrationTests` (5 — a
card-only and a cash+card-split refund both pay out as `CASH` for the full amount; an
account-order refund credits the account with zero shift-summary impact both before and after;
a discount on a card-only order reduces `expectedCash` — the "hands back physical cash despite a
card sale" case the spec's worked example calls out as intentional; and a single shift mixing
cash-only/card-only-refunded/split/account-refunded orders producing the exact expected-cash
figure by hand-calculation). One authoring bug caught by the mixed-shift test itself before
landing: an early draft used a `50.00` cash-only leg against a shared `100.00`-priced meal
option, tripping the new sum-must-equal-total check — fixed by matching the test's fixture
price rather than loosening the check.

**Full-suite verification** — all four parts built and tested together against the real dev SQL
Server DB (migrations `V45`–`V48` applied cleanly via Spring Boot's own Flyway startup during
test runs). Combined with every pre-existing suite, now migrated to the new contract:
**119 tests, 0 failures, 0 errors**.

---

## Stage 5 — Backend: Raw-Ingredient Inventory, Recipes & Planning-Time Deduction — ✅ Complete

New `inventory` package — an entirely new domain (raw materials/procurement) alongside the
existing selling-side packages, deliberately with **zero changes to `POST /orders` or any other
cashier/kitchen/public endpoint**. Ingredient deduction happens once, in a batch, at
daily-planning time (Part C) — never per sale.

**What was built**
- **Part A** — `Ingredient` (`name`, `unit` KG/LITRE/EACH, `countSheetCategory`
  PREP/BULK/DRYSTOCK/FVEG, `active`). `GET/POST/PUT /admin/ingredients`,
  `GET /admin/ingredients/{id}/stock`. `currentStock` is never a stored field — always
  `SUM(IngredientStockMovement.quantity)` for that ingredient, computed live in
  `IngredientService#getStock`.
- **Part B** — `Recipe` (one per `ComponentCatalog`, unique FK, `batchSize`) + `RecipeLine`
  (`ingredient`, `quantity`, in the ingredient's own unit). `GET/PUT
  /admin/components/{id}/recipe`; `PUT` replaces the recipe wholesale
  (`recipe.getLines().clear()` then re-add, relying on `orphanRemoval = true` — same pattern as
  `MealCatalogService#applyComponents`). A component with no recipe returns `404` on `GET` and
  is simply skipped by Part C's calculation — not an error state, just "nothing to track here."
- New `IngredientStockMovement` — the single append-only ledger backing every stock figure in
  Parts A/C/D/E/F: `ingredient`, `movementType`
  (RECEIVED/CONSUMED_FOR_PREP/WASTED/STOCK_TAKE_ADJUSTMENT), signed `quantity`, `costPerUnit`
  (RECEIVED only), `sourceType`/`sourceId` (polymorphic — not an FK, since it points at
  whichever of `Grv`/`WasteEntry`/`StockTake`/`IngredientRequirementConfirmation` caused it),
  `recordedBy`, `createdAt`. Exactly the `AccountPayment`/`OrderAdjustment` "current value = SUM
  of movements" pattern from Stages 2.5/4, applied to raw stock.
- **Part C** — `GET /admin/daily-planning/{date}/ingredient-requirements?period=X` sums, per
  ingredient, over every **unreviewed** `DailyMealOption` (via its meal's components' recipes ×
  `plannedPortions`) plus every unreviewed `DailyComponentStock` (via its own component's recipe
  × `bufferQuantity`) — consolidating an ingredient shared across different components (e.g.
  Cooking Oil used by both a Rice and a Chicken component recipe) into one line, not several.
  `POST .../confirm-ingredient-requirements` re-runs that same calculation server-side (never
  trusts the client's snapshot), writes one `CONSUMED_FOR_PREP` movement per submitted
  `{ingredientId, finalQuantity}` — using the chef's possibly-edited figure, not the calculated
  one — referencing a new `IngredientRequirementConfirmation` row for traceability, and marks
  every contributing `DailyMealOption`/`DailyComponentStock` row `ingredientsReviewed = true`
  (`V51` adds the flag to both existing tables) so a later re-fetch for the same date/period
  never sums them again. Insufficient stock is collected into a `shortfalls` response list and
  the deduction proceeds anyway — negative derived stock is allowed by design, this stage warns,
  it never blocks.
- **Part D** — `Grv` (`ingredient`, `quantity`, `costPerUnit`, `supplierName`, `note`,
  `receivedBy`). `POST/GET /admin/grv` (list filterable by `ingredientId`/date range). Creates a
  RECEIVED movement. Ingredient cost is never written onto `Ingredient` itself — "current cost"
  is whatever the most recent GRV's `costPerUnit` was, so cost history is fully preserved
  rather than overwritten.
- **Part E** — `WasteEntry` (`ingredient`, `quantity`, `reason`, `note`). `POST /admin/waste` →
  WASTED movement.
- **Part F** — `StockTake` (`ingredient`, `countedQuantity`, `variance`). `POST
  /admin/stock-takes` → `{ variance: countedQuantity - currentStock }`, and a
  STOCK_TAKE_ADJUSTMENT movement of exactly that variance (either sign) so derived stock
  reconciles to the physical count exactly — the same expected-vs-counted pattern Stage 2.5
  proved for cash, applied to raw stock.
- **Part G** — `PurchaseOrder` (`supplierName`, `status` DRAFT/SUBMITTED/RECEIVED) +
  `PurchaseOrderLine`. `GET/POST /admin/purchase-orders`, `PUT .../{id}` (status only).
  Deliberately no approval workflow and no FK to `Grv` — placing an order and receiving stock
  are independent actions in this stage.
- No new `SecurityRules` class — every endpoint in this stage lives under `/admin/**`, already
  blanketed to `ADMIN`-only by the existing `AdminSecurityRules`.
- Eleven migrations (`V49`–`V59`): `ingredients`, `recipes`/`recipe_lines`,
  `daily_meal_options`/`daily_component_stock`'s `ingredients_reviewed` flags,
  `ingredient_stock_movements`, `grv`, `waste_entries`, `stock_takes`,
  `ingredient_requirement_confirmations`, `purchase_orders`/`purchase_order_lines`, a seed of
  the spec's 21 ingredients with an initial GRV/movement each (`V58`), and the spec's eight
  component recipes plus four example meals (`V59`).

**Decisions made**
- **Migrations `V54`–`V56` (`waste_entries`, `stock_takes`, `ingredient_requirement_confirmations`)
  are not in the stage spec's literal Flyway listing**, which only sketches
  `ingredient_stock_movements` with a `source_type`/`source_id` pointing at "whichever record
  caused the movement." But the Waste and Stock Take endpoints accept `reason`/`note`/counted
  values that have no column on the movement row itself, and Part C's spec text explicitly says
  a confirmation batch is referenced "for traceability" — so each of GRV/Waste/StockTake/
  Confirmation needed its own dedicated source table, matching the shape `Grv` (which *is* in
  the spec's listing) already has. Added the three missing ones rather than cramming
  reason/note/variance onto the shared ledger row.
- **`recipes` (`V50`) is a new FK child of `component_catalog`, which broke a dozen pre-existing
  integration tests' teardown** (`AccountIntegrationTests`, `OrderIntegrationTests`,
  `KitchenIntegrationTests`, `PublicBoardIntegrationTests`, `MenuIntegrationTests`,
  `ManagementSessionIntegrationTests`, `ShiftCloseCashAttributionIntegrationTests`, and four
  `orders` payment/discount/refund test classes), all of which unconditionally call
  `componentCatalogRepository.deleteAll()` in their `@AfterEach`. Once `V59` seeds a recipe onto
  a `component_catalog` row, every one of those calls starts throwing
  `DataIntegrityViolationException` the moment it runs against a database that still has that
  row — not a one-off, since seed data is permanent and the tests' failure has nothing to do
  with which specific component they created themselves. Fixed at the root rather than worked
  around: each of those twelve `tearDown()` methods (plus this stage's own two that already
  needed it) now calls `recipeRepository.deleteAll()` immediately before
  `componentCatalogRepository.deleteAll()` — the same "children before parents" convention
  those methods already follow for every other table, just extended to cover the new child.
  Verified by running the full suite twice back to back: the first run seeds+consumes the data,
  the second proves `V59` tolerates the ingredients/components it depends on having been wiped
  by the first run's own test teardown (see the next bullet) — 137/137 both times. Commenting
  the affected tests out instead was considered and rejected: it would have silently dropped
  regression coverage on core Phase 1–4 flows (split/card payments, discounts, refunds, shift
  close) to dodge a schema-consequence that has zero production impact (there is no `DELETE`
  endpoint for components; `active` is how one is retired).
- **`V59`'s ingredient/component lookups are guarded with `IF NOT EXISTS` rather than assumed
  present**, for the same reason as the point above: `V58`'s 21 ingredients and V27's
  Rice/Chicken components are exactly as vulnerable to a blanket test-suite wipe as any other
  row in those tables, and `V59` isn't guaranteed to run immediately after `V58` in the same
  session — in dev, `V58` can already have been applied (and its rows since wiped) long before
  `V59` is first written or run. Each of the ~23 single-row lookups also uses
  `SELECT TOP 1 id ... ORDER BY id` instead of a bare subquery, so a hypothetical duplicate name
  fails soft (picks one) instead of crashing migration with "subquery returned more than 1
  value" — the same class of fragility the `IF NOT EXISTS` guards are already there to survive.
- `RecipeLine.quantity` is divided by `batchSize` at calculation time with a scale-6
  `HALF_UP` rounding (`DailyPlanningIngredientService#accumulate`) rather than deferring
  division — the spec's formula is explicitly per-line, and every seeded/tested recipe quantity
  divides batch sizes cleanly, so this never surfaces a rounding artifact in practice while
  still being safe for recipes that don't.
- `IngredientRequirementConfirmation` stores `planningDate` + `mealPeriod` rather than nothing —
  even though no endpoint reads it back in this stage, it is the thing Part C's spec text says
  the resulting movements "reference... for traceability," so it needed to actually hold that
  context rather than being an empty marker row.

**Tests** — four new classes, 18 tests: `IngredientLedgerIntegrationTests` (6 — zero-stock on
creation, GRV increases stock and preserves cost history across multiple entries, waste
decreases stock, stock-take reconciles in both directions, ingredient update/deactivate,
GRV/waste against an unknown ingredient `400`); `RecipeIntegrationTests` (4 — no-recipe `404`,
multi-line recipe create matches on `GET`, `PUT` replaces lines wholesale rather than merging,
unknown `ingredientId` in a recipe line `400`); `DailyPlanningIngredientRequirementsIntegrationTests`
(4 — the spec's rice/chicken worked example, split across two different daily meal options plus
a component-stock buffer so Cooking Oil and Salt each get contributions from three sources via
two different components and must consolidate to one line apiece; confirming with an edited-down
quantity deducts the edited figure and excludes the now-reviewed option from the next fetch,
while a newly-added option still surfaces correctly; confirming against insufficient stock
returns a populated `shortfalls` list and still deducts, going negative, with no exception; a
component with no recipe is excluded entirely); `PurchaseOrderIntegrationTests` (4 — draft
creation and listing, DRAFT→SUBMITTED→RECEIVED status transitions, unknown purchase order `404`,
unknown ingredient in a line `400`).

**Full-suite verification** — all parts built and tested against the real dev SQL Server DB
(migrations `V49`–`V59` applied cleanly via Spring Boot's own Flyway startup during test runs).
Combined with every pre-existing suite, confirming zero regressions on any Phase 1–4
cashier/kitchen/public endpoint, run twice back to back to prove the twelve teardown fixes and
`V59`'s existence guards both hold up across a full wipe/reseed cycle: **137 tests, 0 failures,
0 errors, both runs**.

---
