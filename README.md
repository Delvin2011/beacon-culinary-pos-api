# Canteen POS — Backend

A Spring Boot modular-monolith backend for a canteen Point-of-Sale system. Originally
scaffolded from [a Spring Boot e-commerce course](https://codewithmosh.com/p/spring-boot-building-apis),
it has since been repurposed: the online-store domain (product catalog, shopping carts,
Stripe checkout) has been removed in favor of a menu catalog and an in-person POS order
flow.

Phase 1 is being built stage-by-stage against `metadatafiles/phase1-backend-detailed.md`
— a **set-meal canteen model**: the chef plans a fixed set of dishes per day per meal
period (Breakfast/Lunch), with a shared pool of extras that depletes once per day (no
mid-service top-up). This replaced an earlier, more general à-la-carte menu model
(categories/items/size-variants/modifier groups) that this repo was originally built
towards — see [`PHASE1_PROGRESS.md`](PHASE1_PROGRESS.md) for why that was retired rather
than adapted.

**Stages complete so far:**
- **Stage 1.1 — Auth, Roles & Shift Foundation:** PIN-based cashier login
  (`POST /auth/pin-login`, alongside the original email/password login) and shift
  open/close/current.
- **Stage 1.2 — Meal & Component Catalog + Daily Planning:** admin-managed catalog of
  meals/extras with predefined prices, and a chef-facing daily-planning layer that
  snapshots those prices per date + meal period and tracks remaining portions/stock.

**Not yet started:** Stage 1.3 (cash order/payment core — `POST /orders` doesn't exist
right now) and Stage 1.4 (receipts/order visibility). See
[`PHASE1_PROGRESS.md`](PHASE1_PROGRESS.md) for the full log of what's been built at each
stage and the decisions made along the way.

Explicitly out of scope for Phase 1: card/electronic payment, kitchen status
screens/WebSocket push, cash-drawer reconciliation math, refunds/voids/discounts,
replenishment of any kind, and offline/reporting/costing features.

---

## 🚀 Getting Started

### 1. Clone the Repository

```bash
git clone https://github.com/mosh-hamedani/spring-api-finished.git
cd spring-api-finished
```

### 2. Configure Environment Variables
- Rename the ``.env.example`` file to ``.env``.
- Update the following environment variable inside .env:

#### JWT_SECRET

Generate a secure random key using:

```bash
openssl rand -base64 32
```

If ``openssl`` is not available, go to [generate-random.org](https://generate-random.org), click on **Strings > API Tokens**, and generate a secure token.

### 3. Database

The app targets SQL Server. Point `spring.datasource.url` (see
`src/main/resources/application-dev.yaml`) at a running instance, then apply the
Flyway migrations:

```bash
./mvnw flyway:migrate
```

This creates the current schema (users/shifts/meal-catalog/daily-planning) and seeds the
dev users (see [Seed data & test credentials](#-seed-data--test-credentials) below).
Catalog/menu data isn't seeded yet — create it through the admin endpoints (see
[Example API Flow](#-example-api-flow)).

---

## ▶️ Running the Project

This is a Maven project. To start the application, run:

```bash
./mvnw spring-boot:run
```

If you're on Windows:

```bash
mvnw.cmd spring-boot:run
```

Once running, the application will be available at:

```arduino
http://localhost:8080
```

---

## 📚 API Documentation

Swagger UI is available at:

```bash
http://localhost:8080/swagger-ui.html
```

---

## 🔑 Seed data & test credentials

`V13__seed_menu_and_users.sql` seeds two users for local/dev use, and
`V14__add_pin_auth_to_users.sql` adds PINs for them plus a third, PIN-only cashier.
`V34__reset_dev_credentials_to_654321.sql` then resets every password/PIN below to the
same `654321` value for now:

| Role | Email | Password | PIN |
|---|---|---|---|
| Cashier | `cashier@canteen.local` | `654321` | `654321` |
| Admin | `admin@canteen.local` | `654321` | `654321` |
| Cashier B | `cashierb@canteen.local` | `654321` | `654321` |
| Kitchen Station | — (PIN-only kiosk) | *no password login* | `654321` |

"Cashier B" exists only so tests (and manual QA) have two real cashiers to exercise
shift-ownership rules with — see [Shifts](#shifts-shifts) below. "Kitchen Station"
(`V32__seed_kitchen_kiosk_user.sql`, Stage 2.1) is a single shared kiosk account for the
kitchen display, not a per-staff-member login.

**Kitchen Station has no working password — it can only log in via `POST /auth/pin-login`.**
Its `password` column is deliberately filled with a dummy/unusable BCrypt hash (the same one
`AuthService` uses for its constant-time PIN-miss comparison), so `POST /auth/login` will
always reject it regardless of what's typed as the password. Use the PIN endpoint instead:
`POST /auth/pin-login { "cashierId": <id>, "pin": "654321" }`.

**`cashierId` (and every other user id) is a DB auto-increment value, not something the
migrations pin down — it can differ between environments.** Don't hardcode the ids from this
table across environments; look them up per-environment instead: `GET /users` (any
authenticated user) or `GET /users/cashiers` (public, but `CASHIER`/`ADMIN` only — it excludes
`KITCHEN`, so the kitchen id needs `GET /users` or a DB lookup). For example, local dev seeds
Kitchen Station at id `5`, but a from-scratch/production database can easily land it at a
different id depending on what else was seeded first.

`meal_periods` is seeded directly by its own creation migration (`V17`) — Breakfast
(07:00–10:00) and Lunch (12:00–14:30) always exist. `meal_catalog`/`component_catalog`
entries are **not** seeded yet — the spec defers that full dataset to after Stage 1.3.
Create them yourself via the admin endpoints (see [Example API Flow](#-example-api-flow)).

---

## 🧪 Example API Flow

### 1. Log In as Admin

```bash
POST /auth/pin-login
```

**Request body**:
```json
{ "cashierId": 3, "pin": "654321" }
```

**Response body**:
```json
{ "token": "your-json-web-token" }
```

(Cashier ID `3` is the seeded Admin — see [Seed data](#-seed-data--test-credentials).
Email/password login via `POST /auth/login` still works too.)

### 2. Build the Catalog (admin)

```bash
POST /admin/component-catalog
Authorization: Bearer your-json-web-token
```
```json
{ "name": "Chicken", "extraPrice": 12.00 }
```

```bash
POST /admin/meal-catalog
Authorization: Bearer your-json-web-token
```
```json
{
  "name": "Potatoes & Chicken",
  "description": "Classic combo",
  "price": 50.00,
  "componentIds": [1]
}
```

This is reference data — agreed prices that rarely change. The chef never sets a price
when planning a day; it's always inherited from here.

### 3. Plan Today's Menu (admin)

```bash
POST /admin/daily-options
Authorization: Bearer your-json-web-token
```
```json
{ "mealPeriodId": 2, "optionDate": "2026-07-23", "mealCatalogId": 1, "plannedPortions": 30 }
```

Snapshots `name`/`description`/`price` from the `mealCatalogId` at this instant, and
initializes `portionsRemaining = plannedPortions`. `mealPeriodId` refers to the fixed
`meal_periods` reference data (`GET /meal-periods` to look up Breakfast=1/Lunch=2, or
whatever a fresh seed assigns).

```bash
POST /admin/daily-component-stock
Authorization: Bearer your-json-web-token
```
```json
{ "componentCatalogId": 1, "mealPeriodId": 2, "optionDate": "2026-07-23", "bufferQuantity": 25 }
```

Declares a component's daily extra-portion pool, **independent of any specific dish** —
any line at that meal period can add an extra Chicken, not just dishes whose composition
happens to include it.

### 4. Browse Today's Menu (public)

```bash
GET /menu/today?period=LUNCH
```

Returns `{ "options": [...], "availableExtras": [...] }` — today's lunch options, and
every component with declared stock for lunch that day, whether or not it belongs to any
of those options' compositions.

Placing an order against this menu isn't implemented yet — that's Stage 1.3.

---

## 🗄️ Database Schema

Schema is managed with Flyway migrations under `src/main/resources/db/migration` (SQL
Server / T-SQL). `V10` drops the old e-commerce tables (products, carts, checkout
orders); `V11`–`V13` created and seeded an earlier menu/order model that has since been
retired; `V14`–`V15` add Stage 1.1 (PIN auth + shifts); `V16` drops that retired
menu/order model; `V17`–`V22` create the current Stage 1.2 catalog/daily-planning schema:

| Table | Purpose |
|---|---|
| `users` | Account records — name, email, password hash, `pin_hash`, `active`, `role` (`USER` \| `CASHIER` \| `ADMIN` \| `KITCHEN`). |
| `addresses` | One-to-many addresses per user (`user_id` FK). Not currently exposed via REST. |
| `profiles` | 1:1 extension of `users` (bio, phone, DOB, loyalty points). Not currently exposed via REST. |
| `shifts` | A cashier's till session — `cashier_id`, `opening_float`, `opened_at`, `closed_at` (nullable), `status` (`OPEN`/`CLOSED`). No reconciliation math yet — that's a later stage. |
| `meal_periods` | Fixed reference data — Breakfast (07:00–10:00), Lunch (12:00–14:30). Seeded directly by its own creation migration, not by the app. |
| `meal_catalog` | Predefined, admin-managed meal + price, agreed upstream — rarely changes. `active` lets one be retired without deleting history. |
| `component_catalog` | Predefined, admin-managed extra-portion component + price (e.g. "Chicken", extra ₹12). |
| `meal_catalog_components` | Informational composition of a `meal_catalog` entry (which components it's *usually* made of) — display only, not a pricing/stock relationship. |
| `daily_meal_options` | A specific date + meal period's dish, created from a `meal_catalog` entry. **Snapshots** `name`/`description`/`price` at creation time and tracks `planned_portions`/`portions_remaining`. A later catalog price change never rewrites an already-planned day. |
| `daily_component_stock` | A specific date + meal period's extra-portion pool for one component, created from a `component_catalog` entry (snapshots `extra_price`, tracks `buffer_quantity`/`buffer_remaining`). **Not** linked to any specific `daily_meal_options` row — any dish's line can draw from it. |
| `orders` | A paid POS sale — `order_number` (per-day, per-shift `cashier_id`), pricing snapshot (`subtotal`/`total`/`amount_tendered`/`change_due`), and `status` (`PENDING` → `IN_PROGRESS` → `DONE` → `COLLECTED`, or `VOIDED`/`REFUNDED` — Stage 2.1 widened this from Phase 1's single `CONFIRMED` value). |
| `order_lines` / `order_line_extras` | Snapshotted line items and their extras — price/name captured at order time, immune to later catalog changes. |
| `order_status_events` | Stage 2.1 audit trail — one row per status transition, including the implicit `null → PENDING` at order creation. `from_status`/`to_status`/`changed_by`/`changed_at`. Powers the sold-out/timing reporting (Stage 2.4) and the void/refund audit (Stage 2.6) — nothing reads it yet besides the KDS write path itself. |

No replenishment table/endpoint exists anywhere in this schema — once
`portions_remaining`/`buffer_remaining` hits zero for a day, it stays zero by design.

---

## 🧩 Core Business Logic

### Authentication (`auth`)
- Stateless JWT auth (`SessionCreationPolicy.STATELESS`) — no server-side sessions.
- `POST /auth/login` authenticates via Spring Security's `AuthenticationManager` (BCrypt-hashed passwords), then issues a short-lived **access token** (15 min) in the response body and a long-lived **refresh token** (7 days) as an `HttpOnly`, `Secure` cookie scoped to `/auth/refresh`.
- `POST /auth/pin-login` — PIN-based login for cashiers/admins (`{ "cashierId": 4, "pin": "654321" }`), issuing the same access-token/refresh-cookie pair as `/auth/login`. Compares the submitted PIN against a pre-computed dummy BCrypt hash when `cashierId` doesn't exist, so an unknown ID and a wrong PIN are both indistinguishable `401`s.
- `POST /auth/refresh` reads the refresh-token cookie and mints a new access token.
- `GET /auth/me` returns the currently authenticated user.
- `JwtAuthenticationFilter` runs once per request, validates the `Authorization: Bearer` header, and populates the `SecurityContext` with the user's ID and a `ROLE_<role>` authority — no DB lookup on every request, the JWT claims (`email`, `name`, `role`) carry the identity.

### Authorization / Security Rules (`common`, per-feature `*SecurityRules`)
Each feature module contributes its own `SecurityRules` bean instead of one monolithic config; `SecurityConfig` collects and applies them all, defaulting anything unmatched to `authenticated()`:

| Rule | Access |
|---|---|
| `GET /meal-periods`, `GET /menu/today` | Public |
| `/admin/**` (includes `/admin/meal-catalog`, `/admin/component-catalog`, `/admin/daily-options`, `/admin/daily-component-stock`) | `ADMIN` only |
| `/shifts/**` | `CASHIER` or `ADMIN` |
| `/kitchen/**` | `KITCHEN` or `ADMIN` |
| `/public/board/**` | Public |
| `POST /users` | Public (registration) |
| `GET /users/cashiers` | Public |
| `POST /auth/login`, `POST /auth/pin-login`, `POST /auth/refresh` | Public |
| Everything else (`/users/{id}`, ...) | Authenticated |

### Meal & Component Catalog + Daily Planning (`menu`)
Two deliberately separate layers:
- **Catalog** (`MealCatalog`, `ComponentCatalog`, `MealCatalogComponent`) — admin-managed reference data. `GET/POST/PUT /admin/meal-catalog{,/{id}}` and `GET/POST/PUT /admin/component-catalog{,/{id}}`. A meal-catalog write is a full nested replace of its component links (clear + re-add), not a diff — same convention the old menu model used for its nested collections. `MealCatalogComponent` is a real entity (its own `id` + two `@ManyToOne`s), not a bare `@ManyToMany`, matching the spec's given migration exactly.
- **Daily planning** (`DailyMealOption`, `DailyComponentStock`) — chef-facing, per date + `MealPeriod`. `POST /admin/daily-options` looks up a `mealCatalogId` and **snapshots** its `name`/`description`/`price` onto the new row, initializing `portionsRemaining = plannedPortions`; the chef never enters a price. `POST /admin/daily-component-stock` does the same for a `componentCatalogId` (`extraPrice` snapshot, `bufferRemaining = bufferQuantity`) — and is **not** linked to any specific `DailyMealOption`; it's a shared pool for that component/period/date, orderable as an extra against any line regardless of that dish's usual composition.
- `MealPeriod` is now DB-backed reference data (`GET /meal-periods`, public), not a Java enum — seeded once via its own migration (Breakfast/Lunch), not created per option.
- `GET /menu/today?period=LUNCH` returns `{ options, availableExtras }` for `optionDate = today` (via an injected `Clock` bean) — filtered purely by date + period, **not** by whether the current wall-clock time falls inside that period's window. That "is this period active right now" check is Stage 1.3's concern, at order-creation time, not here.
- **No replenishment.** Once `portionsRemaining`/`bufferRemaining` hits zero for a day, there's no endpoint to top it up — by design, not an omission.
- Catalog write endpoints are admin-only via the global `/admin/**` rule (no per-endpoint security wiring needed); `/meal-periods` and `/menu/today` are public.

### Shifts (`shifts`)
- `POST /shifts/open` creates an `OPEN` shift for the caller with the given opening
  float; `409` if the caller already has one open (`ShiftRepository.findFirstByCashierIdAndStatus`
  enforces "at most one open shift per cashier").
- `POST /shifts/{id}/close` sets `status = CLOSED` and `closed_at` (via the injected
  `Clock` bean, the same overridable-clock convention used elsewhere for testability).
  Only the owning cashier or an `ADMIN` may close a shift — anyone else gets `403`, via Spring
  Security's `AccessDeniedException` thrown directly from the service (no bespoke
  exception/handler needed — `SecurityConfig`'s `accessDeniedHandler` already converts
  it to a 403).
- `GET /shifts/current` returns the caller's open shift, or `404` if none.
- No reconciliation math (expected vs. actual cash) happens on close yet — closing is
  just a status flip in this stage. Orders don't yet require an open shift either —
  that dependency is enforced starting in Stage 1.3.

### Kitchen Status Screen (`kitchen`, Stage 2.1)
- Order lifecycle is now `PENDING → IN_PROGRESS → DONE → COLLECTED`, with `VOIDED`/`REFUNDED`
  reserved for Stage 2.6. This stage only implements/exercises `PENDING → IN_PROGRESS → DONE`;
  `COLLECTED` is Stage 2.3's endpoint, and void/refund are Stage 2.6's.
- `GET /kitchen/orders` — today's `PENDING`/`IN_PROGRESS` orders, oldest first, full
  line/extra detail (`OrderSummaryDto`, `orders` package) for KOT-equivalent display.
- `PATCH /kitchen/orders/{id}/status` — `{ "status": "IN_PROGRESS" | "DONE" }`. Only the two
  forward, one-step transitions above are allowed; anything else (skip, backward, or
  targeting `COLLECTED`/`VOIDED`/`REFUNDED` from this endpoint) is `400`.
- `GET /kitchen/orders/stream` — SSE stream of order-created/status-change events, same
  `OrderSummaryDto` shape as the REST response wrapped in `{ eventType, order }`
  (`ORDER_CREATED` | `STATUS_CHANGED`), so later stages (e.g. the public display) can reuse
  this exact wire format instead of a second serialization.
- The audit trail (`OrderStatusEvent`) and the SSE fan-out (`OrderEventBroadcaster`) live in
  the `orders` package, not `kitchen` — they're generic order-lifecycle infrastructure that
  later stages build on directly, not a kitchen-specific concern. `OrderStatusEventPublisher`
  is the single choke point that writes the audit row and broadcasts together, so the two can
  never go out of sync; `POST /orders` (order creation) and the kitchen `PATCH` both go
  through it.
- `KITCHEN` is a single shared kiosk account (`Kitchen Station`, seeded by
  `V32__seed_kitchen_kiosk_user.sql`), not a per-staff-member login — it uses the existing
  PIN-login endpoint from Stage 1.1 unchanged.

### Public Display Board (`board`, Stage 2.2)
- `GET /public/board/today` and `GET /public/board/stream` — fully public, no auth, no role
  check (`PublicBoardSecurityRules` permits both explicitly). First genuinely unauthenticated
  surface in the app.
- Deliberately minimal response shape — `{ orderNumber, status }` per order, no line items, no
  internal order id, no cashier/customer data of any kind. Same three-status filter as the
  kitchen screen (`PENDING`/`IN_PROGRESS`/`DONE`) — `VOIDED`/`REFUNDED` (Stage 2.6) are excluded
  by construction, since they're simply never in that filter list, not by an explicit check.
- Doesn't duplicate Stage 2.1's status-tracking mechanism. `OrderStatusEventPublisher` (in
  `orders`) no longer calls any broadcaster directly — it writes the audit row and then
  publishes one Spring `ApplicationEvent` (`OrderStatusStreamEvent`) carrying the full order
  detail. `KitchenService` and `PublicBoardService` each have their own `@EventListener`
  reacting to that same event: kitchen relays it verbatim onto its own SSE channel, the board
  reduces it down to `{ orderNumber, status }` first. One publish call, two independent
  listeners, two separate SSE channels (`OrderEventBroadcaster` for kitchen,
  `PublicBoardEventBroadcaster` for the board) — so the two streams can't drift out of sync,
  and a later stage can add its own listener without ever touching `orders` again.
- `COLLECTED` (Stage 2.3) removing an order from the board isn't implemented yet — a `DONE`
  order just stays on the feed; that's explicitly the frontend's display-windowing concern for
  now, not the backend's.

### Users (`users`)
- `POST /users` self-registration: rejects duplicate emails, BCrypt-hashes the password, and defaults `role` to `USER`. (Cashier/admin accounts are provisioned via the seed migration or direct DB access, not self-registration.)
- `PUT /users/{id}`, `DELETE /users/{id}`, `POST /users/{id}/change-password` require authentication but currently rely on the global `authenticated()` fallback rather than an explicit "self or admin" ownership check.
- `GET /users/cashiers` — public, unauthenticated. Powers the `/pos/login` tile grid, which needs a cashier list *before* any token exists. Returns a plain (non-paginated) array of `CashierSummaryDto { id, name }` for `active = true` users with `role IN (CASHIER, ADMIN)` — deliberately excludes every other field (email, role, password hash) since it's public. Distinct from `GET /users`, which requires auth, only supports a `?sort=` param, and returns the full `UserDto`.
- `addresses` and `profiles` exist in the schema and on the `User` entity but are not yet exposed through dedicated REST endpoints.

### Admin (`admin`)
- `/admin/**` is gated to `ADMIN` role; currently exposes a placeholder `GET /admin/hello` endpoint for verifying the role-based guard.

---

## 🧪 Tests

`./mvnw test` runs the integration suite (`MenuIntegrationTests`, `AuthPinLoginIntegrationTests`,
`ShiftIntegrationTests`, `UserCashiersIntegrationTests`, `OrderIntegrationTests`,
`KitchenIntegrationTests`, `PublicBoardIntegrationTests`, plus
`BeaconCulinaryApiApplicationTests`) against a real SQL
Server database via `MockMvc` and the actual `/auth/login`/`/auth/pin-login` flows.
`MenuIntegrationTests` builds its own catalog fixtures through the real admin endpoints (no
catalog seed data exists yet) and covers snapshot-on-create, catalog price changes not
retroacting onto already-created daily options, and cross-dish extras visibility.
`KitchenIntegrationTests` covers the full `PENDING`/`IN_PROGRESS`/`DONE` transition matrix
(including the disallowed skip/backward/`COLLECTED` cases), cross-day queue scoping, role
enforcement, and an SSE assertion that a `PATCH` broadcasts a `STATUS_CHANGED` event to a
connected stream. A `Clock` bean (overridden with a `MutableClock` in tests where needed)
makes shift-close-timestamp and meal-period-window assertions deterministic. Since Flyway
isn't wired into the app's own startup (`spring.flyway.enabled: false` in
`application-dev.yaml` — see [Database](#3-database)), running the tests requires the
migrations to already be applied via `./mvnw flyway:migrate` first.

---

## 🧠 Learn More
This project started from [Spring Boot: Mastering REST API Development](https://codewithmosh.com/p/spring-boot-building-apis), a course on building e-commerce REST APIs with Spring Boot — the JWT auth, security-rules-per-module pattern, and Flyway setup all originate there, even though the e-commerce domain itself has since been replaced.
