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
