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
`V14__add_pin_auth_to_users.sql` adds PINs for them plus a third, PIN-only cashier:

| Role | Email | Password | PIN |
|---|---|---|---|
| Cashier | `cashier@canteen.local` | `Cashier@123` | `1234` |
| Admin | `admin@canteen.local` | `Admin@123` | `9999` |
| Cashier B | `cashierb@canteen.local` | `CashierB@123` | `5678` |

"Cashier B" exists only so tests (and manual QA) have two real cashiers to exercise
shift-ownership rules with — see [Shifts](#shifts-shifts) below.

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
{ "cashierId": 3, "pin": "9999" }
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
| `users` | Account records — name, email, password hash, `pin_hash`, `active`, `role` (`USER` \| `CASHIER` \| `ADMIN`). |
| `addresses` | One-to-many addresses per user (`user_id` FK). Not currently exposed via REST. |
| `profiles` | 1:1 extension of `users` (bio, phone, DOB, loyalty points). Not currently exposed via REST. |
| `shifts` | A cashier's till session — `cashier_id`, `opening_float`, `opened_at`, `closed_at` (nullable), `status` (`OPEN`/`CLOSED`). No reconciliation math yet — that's a later stage. |
| `meal_periods` | Fixed reference data — Breakfast (07:00–10:00), Lunch (12:00–14:30). Seeded directly by its own creation migration, not by the app. |
| `meal_catalog` | Predefined, admin-managed meal + price, agreed upstream — rarely changes. `active` lets one be retired without deleting history. |
| `component_catalog` | Predefined, admin-managed extra-portion component + price (e.g. "Chicken", extra ₹12). |
| `meal_catalog_components` | Informational composition of a `meal_catalog` entry (which components it's *usually* made of) — display only, not a pricing/stock relationship. |
| `daily_meal_options` | A specific date + meal period's dish, created from a `meal_catalog` entry. **Snapshots** `name`/`description`/`price` at creation time and tracks `planned_portions`/`portions_remaining`. A later catalog price change never rewrites an already-planned day. |
| `daily_component_stock` | A specific date + meal period's extra-portion pool for one component, created from a `component_catalog` entry (snapshots `extra_price`, tracks `buffer_quantity`/`buffer_remaining`). **Not** linked to any specific `daily_meal_options` row — any dish's line can draw from it. |

No replenishment table/endpoint exists anywhere in this schema — once
`portions_remaining`/`buffer_remaining` hits zero for a day, it stays zero by design.

---

## 🧩 Core Business Logic

### Authentication (`auth`)
- Stateless JWT auth (`SessionCreationPolicy.STATELESS`) — no server-side sessions.
- `POST /auth/login` authenticates via Spring Security's `AuthenticationManager` (BCrypt-hashed passwords), then issues a short-lived **access token** (15 min) in the response body and a long-lived **refresh token** (7 days) as an `HttpOnly`, `Secure` cookie scoped to `/auth/refresh`.
- `POST /auth/pin-login` — PIN-based login for cashiers/admins (`{ "cashierId": 4, "pin": "1234" }`), issuing the same access-token/refresh-cookie pair as `/auth/login`. Compares the submitted PIN against a pre-computed dummy BCrypt hash when `cashierId` doesn't exist, so an unknown ID and a wrong PIN are both indistinguishable `401`s.
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
`ShiftIntegrationTests`, `UserCashiersIntegrationTests`, plus `BeaconCulinaryApiApplicationTests`)
against a real SQL Server database via `MockMvc` and the actual `/auth/login`/`/auth/pin-login`
flows. `MenuIntegrationTests` builds its own catalog fixtures through the real admin
endpoints (no catalog seed data exists yet) and covers snapshot-on-create, catalog price
changes not retroacting onto already-created daily options, and cross-dish extras
visibility. A `Clock` bean (overridden with a `MutableClock` in tests where needed) makes
shift-close-timestamp assertions deterministic. There's currently no `POST /orders` test
coverage — that endpoint doesn't exist until Stage 1.3. Since Flyway isn't wired into the
app's own startup (`spring.flyway.enabled: false` in `application-dev.yaml` — see
[Database](#3-database)), running the tests requires the migrations to already be applied
via `./mvnw flyway:migrate` first.

---

## 🧠 Learn More
This project started from [Spring Boot: Mastering REST API Development](https://codewithmosh.com/p/spring-boot-building-apis), a course on building e-commerce REST APIs with Spring Boot — the JWT auth, security-rules-per-module pattern, and Flyway setup all originate there, even though the e-commerce domain itself has since been replaced.
