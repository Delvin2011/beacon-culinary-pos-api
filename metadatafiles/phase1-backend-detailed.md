# Phase 1 Backend Development Spec — Canteen POS (Set-Meal Model)

Spring Boot modular monolith, SQL Server + Flyway, JWT/PIN auth. Follows the existing project conventions: feature-module packages, per-module `SecurityRules` beans aggregated centrally, sequential Flyway migrations.

This document covers **Stages 1.1–1.4**. Each stage is independently deployable and testable — build and ship them in order.

---

## Stage 1.1 — Auth, Roles & Shift Foundation

### Scope
- `users` table: `id`, `name`, `pin_hash`, `role` (`CASHIER`/`ADMIN`), `active`
- PIN-based login (4–6 digit PIN, BCrypt-hashed like a password) issuing the same JWT access/refresh pattern as before
- `shifts` table: cashier opens a shift with an opening float amount, closes it later. **No reconciliation math yet** — that's Stage 2.5. Closing just records a `closed_at` timestamp and status.

### Entities
```
User            id, name, pin_hash, role, active
Shift           id, cashier_id (FK users), opening_float, opened_at, closed_at (nullable), status (OPEN/CLOSED)
```

### Endpoints
```
POST /auth/pin-login          Public — { "cashierId": 4, "pin": "1234" } → access token + refresh cookie
POST /shifts/open             CASHIER/ADMIN — { "openingFloat": 500.00 } → creates OPEN shift for current user
POST /shifts/{id}/close       CASHIER/ADMIN, own shift only — sets CLOSED
GET  /shifts/current          CASHIER/ADMIN — returns the caller's currently open shift, or 404 if none
```

### Business rules
- A cashier cannot open a second shift while one is already `OPEN` — reject with `409`.
- A cashier can only close their own shift (or `ADMIN` can close any) — `403` otherwise.
- Orders in Stage 1.3 will require an open shift; enforce that dependency there, not here.

### Flyway migrations
```sql
-- V__create_users_pos.sql (or next sequential version — adapt to existing user table if reusable)
ALTER TABLE users ADD pin_hash NVARCHAR(255) NULL;
ALTER TABLE users ADD role NVARCHAR(20) NOT NULL DEFAULT 'CASHIER'
    CHECK (role IN ('CASHIER','ADMIN'));

-- V__create_shifts.sql
CREATE TABLE shifts (
    id BIGINT IDENTITY PRIMARY KEY,
    cashier_id BIGINT NOT NULL FOREIGN KEY REFERENCES users(id),
    opening_float DECIMAL(10,2) NOT NULL,
    opened_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    closed_at DATETIME2 NULL,
    status NVARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED'))
);
CREATE INDEX idx_shifts_cashier_status ON shifts(cashier_id, status);
```

### Acceptance criteria
- [ ] A seeded cashier can log in with their PIN and receive a valid access token.
- [ ] An incorrect PIN returns `401` without revealing whether the cashier ID exists.
- [ ] Opening a shift while one is already open returns `409`.
- [ ] `GET /shifts/current` returns `404` when no shift is open, and the shift details when one is.
- [ ] A cashier cannot close another cashier's shift (`403`); `ADMIN` can.

### Test scenarios
- Login success / wrong PIN / unknown cashier ID (all three, distinct assertions)
- Open shift → `GET /shifts/current` reflects it
- Attempt second open while first is open → `409`
- Close own shift → status becomes `CLOSED`, `closed_at` populated
- Cashier A attempts to close Cashier B's shift → `403`

---

## Stage 1.2 — Meal & Component Catalog + Daily Planning

### Scope
Two distinct layers now, deliberately separated:
1. **Catalog (reference data)** — predefined meal and extra-portion prices, agreed with stakeholders upstream. Admin-managed, rarely changes, independent of any specific day.
2. **Daily planning** — the chef picks from the catalog and declares quantities for a specific date + meal period. The chef never enters a price — it's inherited (and snapshotted) from the catalog.

Meal periods are fixed reference data, seeded once, not created per option: **Breakfast 07:00–10:00**, **Lunch 12:00–14:30**.

Extras are decoupled from a single dish. A customer can order an extra portion of **any component that has stock declared for today's meal period**, regardless of which meal option they bought — e.g. someone who orders "Potatoes & Beef" can still add an extra Chicken, as long as Chicken has a daily stock entry for that period, priced at Chicken's own predefined extra-portion price.

### Entities
```
MealPeriod             id, name, start_time, end_time                       -- fixed, seeded
MealCatalog            id, name, description, price, active                 -- predefined meal + price
ComponentCatalog       id, name, extra_price, active                        -- predefined component + extra price
MealCatalogComponent   id, meal_catalog_id (FK), component_catalog_id (FK)  -- informational composition, for menu display

DailyMealOption        id, meal_period_id (FK), meal_catalog_id (FK), option_date,
                        name (snapshot), description (snapshot), price (snapshot),
                        planned_portions, portions_remaining
DailyComponentStock    id, component_catalog_id (FK), meal_period_id (FK), option_date,
                        extra_price (snapshot), buffer_quantity, buffer_remaining
```

Snapshotting `name`/`description`/`price` onto `DailyMealOption` (and `extra_price` onto `DailyComponentStock`) at creation matters for the same reason order lines snapshot prices: a later catalog price change must never silently rewrite an already-planned or already-sold day.

### Endpoints
```
GET    /meal-periods                                Public — fixed list (Breakfast, Lunch)

GET    /admin/meal-catalog                          ADMIN
POST   /admin/meal-catalog                          ADMIN — { name, description, price, componentIds[] }
PUT    /admin/meal-catalog/{id}                      ADMIN

GET    /admin/component-catalog                      ADMIN
POST   /admin/component-catalog                       ADMIN — { name, extraPrice }
PUT    /admin/component-catalog/{id}                  ADMIN

POST   /admin/daily-options                            ADMIN — { mealPeriodId, optionDate, mealCatalogId, plannedPortions }
                                                        → snapshots name/description/price from catalog

POST   /admin/daily-component-stock                     ADMIN — { componentCatalogId, mealPeriodId, optionDate, bufferQuantity }
                                                        → snapshots extra_price from catalog

GET    /menu/today?period=LUNCH                         Public/Cashier — today's options AND today's available
                                                        extras for that period:
                                                        { "options": [...], "availableExtras": [...] }
```

### Business rules
- Meal catalog and component catalog are the "agreed price with stakeholders" layer. Changing a catalog price never retroactively changes an already-created `DailyMealOption`/`DailyComponentStock` (already snapshotted).
- `POST /admin/daily-options` looks up the catalog entry, copies `name`/`description`/`price` onto the new row, and initializes `portions_remaining = planned_portions`. The chef supplies only `mealCatalogId`, `mealPeriodId`, `optionDate`, `plannedPortions` — no price entry.
- `POST /admin/daily-component-stock` similarly snapshots `extra_price` and initializes `buffer_remaining = buffer_quantity`. It is **not** linked to a specific `DailyMealOption` — it's a standalone daily pool for that component.
- **There is no replenishment.** Once `portions_remaining` or `buffer_remaining` hits zero for a given day, that option/extra is sold out until the next day's planning — there is no mechanism to top it up mid-service. Getting the planned quantity right is a chef/ops responsibility, refined over time by watching sales trends, not something the system compensates for.
- `GET /menu/today` returns the day's meal options as before, plus a separate `availableExtras` list — every component with daily stock for that period. This is what powers a shared "add extras" picker that isn't limited to whichever dish the customer chose.
- Time-filtering now uses `MealPeriod.start_time`/`end_time` directly (both periods always have fixed windows, so no `all_day` flag is needed at this stage).

### Flyway migrations
```sql
-- V__seed_meal_periods.sql
CREATE TABLE meal_periods (
    id BIGINT IDENTITY PRIMARY KEY,
    name NVARCHAR(50) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL
);
INSERT INTO meal_periods (name, start_time, end_time) VALUES
    ('Breakfast', '07:00', '10:00'),
    ('Lunch', '12:00', '14:30');

-- V__create_meal_catalog.sql
CREATE TABLE meal_catalog (
    id BIGINT IDENTITY PRIMARY KEY,
    name NVARCHAR(100) NOT NULL,
    description NVARCHAR(255) NULL,
    price DECIMAL(10,2) NOT NULL,
    active BIT NOT NULL DEFAULT 1
);

-- V__create_component_catalog.sql
CREATE TABLE component_catalog (
    id BIGINT IDENTITY PRIMARY KEY,
    name NVARCHAR(50) NOT NULL,
    extra_price DECIMAL(10,2) NOT NULL,
    active BIT NOT NULL DEFAULT 1
);

-- V__create_meal_catalog_components.sql
CREATE TABLE meal_catalog_components (
    id BIGINT IDENTITY PRIMARY KEY,
    meal_catalog_id BIGINT NOT NULL FOREIGN KEY REFERENCES meal_catalog(id),
    component_catalog_id BIGINT NOT NULL FOREIGN KEY REFERENCES component_catalog(id)
);

-- V__create_daily_meal_options.sql
CREATE TABLE daily_meal_options (
    id BIGINT IDENTITY PRIMARY KEY,
    meal_period_id BIGINT NOT NULL FOREIGN KEY REFERENCES meal_periods(id),
    meal_catalog_id BIGINT NOT NULL FOREIGN KEY REFERENCES meal_catalog(id),
    option_date DATE NOT NULL,
    name NVARCHAR(100) NOT NULL,
    description NVARCHAR(255) NULL,
    price DECIMAL(10,2) NOT NULL,
    planned_portions INT NOT NULL CHECK (planned_portions >= 0),
    portions_remaining INT NOT NULL CHECK (portions_remaining >= 0)
);
CREATE INDEX idx_dmo_date_period ON daily_meal_options(option_date, meal_period_id);

-- V__create_daily_component_stock.sql
CREATE TABLE daily_component_stock (
    id BIGINT IDENTITY PRIMARY KEY,
    component_catalog_id BIGINT NOT NULL FOREIGN KEY REFERENCES component_catalog(id),
    meal_period_id BIGINT NOT NULL FOREIGN KEY REFERENCES meal_periods(id),
    option_date DATE NOT NULL,
    extra_price DECIMAL(10,2) NOT NULL,
    buffer_quantity INT NOT NULL CHECK (buffer_quantity >= 0),
    buffer_remaining INT NOT NULL CHECK (buffer_remaining >= 0)
);
CREATE INDEX idx_dcs_date_period ON daily_component_stock(option_date, meal_period_id);
```

### Acceptance criteria
- [ ] Admin creates a meal-catalog entry with a price and two linked components; it becomes selectable for daily planning.
- [ ] Chef creates a daily option by selecting a catalog meal and entering only `plannedPortions` — the resulting row's name/description/price match the catalog exactly at that moment.
- [ ] Changing a catalog meal's price afterward does not alter any already-created `DailyMealOption`.
- [ ] Chef declares daily component stock (e.g. Chicken, 40 units) for a meal period independently of which option(s) use that component.
- [ ] `GET /menu/today?period=LUNCH` returns both today's lunch options and the full list of components with stock for lunch that day — including components not part of the composition of any option the customer might choose.
- [ ] Once an option's `portions_remaining` or a component's `buffer_remaining` reaches zero, it stays at zero for the rest of the day — no endpoint exists to increase it.
- [ ] Non-admin attempting any `/admin/**` catalog or planning endpoint gets `403`.

### Test scenarios
- Create meal-catalog entry + component-catalog entries, link via `meal_catalog_components`
- Create daily option from catalog → snapshotted fields match catalog exactly at creation time
- Update catalog price after a daily option exists → daily option's snapshotted price unchanged
- Create daily component stock independent of any specific option
- `GET /menu/today` extras list includes a component not tied to the composition of any of today's options (proving it's shared, not option-scoped)
- Cashier role attempts catalog/admin endpoints → `403`

---

## Stage 1.3 — Order & Payment Core (Cash Only)

### Scope
Single atomic endpoint: build + price + pay + decrement, in one request, inside one transaction. No server-side draft state (matches earlier decision). Cash payment only — card is out of scope until a later phase.

### Entities
```
Order        id, order_number (sequential, human-readable, e.g. daily-reset "042"), shift_id (FK), cashier_id (FK),
             status (CONFIRMED — only status value in this stage), payment_method (CASH), amount_tendered,
             change_due, subtotal, total, created_at
OrderLine    id, order_id (FK), daily_meal_option_id (FK), unit_price (snapshot), quantity, line_total
OrderLineExtra   id, order_line_id (FK), daily_component_stock_id (FK), price_delta (snapshot), quantity, line_total
```
Note: an extra is attached to a specific order line (so it prints clearly on the KOT/receipt as "with extra Chicken" under that dish), but it no longer needs to belong to that dish's own composition — see the validation rule below.

### Endpoint
```
POST /orders     CASHIER/ADMIN, requires an OPEN shift for the caller
```

**Request:**
```json
{
  "amountTendered": 100.00,
  "lines": [
    {
      "dailyMealOptionId": 12,
      "quantity": 1,
      "extras": [
        { "dailyComponentStockId": 34, "quantity": 1 }
      ]
    }
  ]
}
```

### Business logic — single `@Transactional` method, in this order
1. Verify the caller has an `OPEN` shift; reject `409` if not (a cashier can't sell without a shift open).
2. For each line, verify the `dailyMealOptionId` is valid for **today** and its meal period is currently active — reject `400` otherwise (same meal-period guard as before).
3. For each `extras[]` entry, verify the `dailyComponentStockId` exists for **today's date and the same meal period as the line's option** — reject `400` if it's for a different meal period (e.g. pulling a breakfast extra into a lunch order). **It does not need to belong to the same dish** — extra Chicken is valid on a Potatoes & Beef line as long as Chicken has daily stock for that period.
4. Compute `unitPrice = option.price`, `lineTotal = unitPrice * quantity`; extras add `dailyComponentStock.extraPrice * quantity` on top.
5. **Atomic decrement, conditional update, check rows-affected, roll back + `409` on failure** (same anti-oversell pattern as before):
   - `daily_meal_options.portions_remaining -= line.quantity WHERE portions_remaining >= line.quantity`
   - `daily_component_stock.buffer_remaining -= extra.quantity WHERE buffer_remaining >= extra.quantity`
6. Compute `total`; validate `amountTendered >= total` (reject `400` if short); compute `changeDue = amountTendered - total`.
7. Assign the next sequential `order_number` for the day (reset daily — see migration note below).
8. Persist `Order`, `OrderLine`s, `OrderLineExtra`s with snapshotted prices.
9. Return `201` with the full order, including `changeDue`.

### Flyway migrations
```sql
-- V__create_orders.sql
CREATE TABLE orders (
    id BIGINT IDENTITY PRIMARY KEY,
    order_number INT NOT NULL,
    order_date DATE NOT NULL DEFAULT CAST(SYSUTCDATETIME() AS DATE),
    shift_id BIGINT NOT NULL FOREIGN KEY REFERENCES shifts(id),
    cashier_id BIGINT NOT NULL FOREIGN KEY REFERENCES users(id),
    status NVARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    payment_method NVARCHAR(10) NOT NULL DEFAULT 'CASH' CHECK (payment_method IN ('CASH')),
    amount_tendered DECIMAL(10,2) NOT NULL,
    change_due DECIMAL(10,2) NOT NULL,
    subtotal DECIMAL(10,2) NOT NULL,
    total DECIMAL(10,2) NOT NULL,
    created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);
CREATE UNIQUE INDEX idx_orders_date_number ON orders(order_date, order_number);

-- V__create_order_lines.sql
CREATE TABLE order_lines (
    id BIGINT IDENTITY PRIMARY KEY,
    order_id BIGINT NOT NULL FOREIGN KEY REFERENCES orders(id),
    daily_meal_option_id BIGINT NOT NULL FOREIGN KEY REFERENCES daily_meal_options(id),
    unit_price DECIMAL(10,2) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    line_total DECIMAL(10,2) NOT NULL
);

-- V__create_order_line_extras.sql
CREATE TABLE order_line_extras (
    id BIGINT IDENTITY PRIMARY KEY,
    order_line_id BIGINT NOT NULL FOREIGN KEY REFERENCES order_lines(id),
    daily_component_stock_id BIGINT NOT NULL FOREIGN KEY REFERENCES daily_component_stock(id),
    price_delta DECIMAL(10,2) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    line_total DECIMAL(10,2) NOT NULL
);
```
Note: `order_number` reset-per-day is simplest handled by computing `MAX(order_number) + 1 WHERE order_date = today` inside the same transaction, guarded by the unique index above to catch a race (retry on constraint violation).

### Acceptance criteria
- [ ] A valid order with one line, no extras: correct `lineTotal`/`subtotal`/`total`/`changeDue`.
- [ ] A valid order with extras: extras priced and summed correctly, and only from the same line's option.
- [ ] Selling the last portion succeeds; selling one more after that returns `409` with no data written.
- [ ] Two simultaneous requests for the last portion: exactly one succeeds, one gets `409`.
- [ ] `amountTendered` less than `total` is rejected with `400`, no order created.
- [ ] Order attempted with no open shift → `409`.
- [ ] Order for an option outside its meal-period window → `400`.
- [ ] Extra requested from a `dailyComponentStockId` with no stock row for today's date + that meal period → `400`.
- [ ] Extra belonging to a **different dish than the one on that line, but the same meal period**, is accepted (e.g. extra Chicken on a Potatoes & Beef line) — this is the behavior change from the original design.
- [ ] Extra belonging to a component only stocked for a **different meal period** (e.g. a breakfast-only component on a lunch order) is rejected → `400`.

### Test scenarios
- Happy path: single line, no extras
- Happy path: single line with an extra from its own dish's usual composition
- Happy path: single line with an extra from a **different dish's** component, same meal period (proves cross-dish extras work)
- Happy path: multi-line order across two different options
- Insufficient stock (portions) → `409`, rollback verified (no orphan order)
- Insufficient stock (component buffer) → `409`, rollback verified
- Concurrency race on last portion (fire two threads/requests) → exactly one `201`, one `409`
- Concurrency race on the last unit of a shared component's buffer, ordered as an extra against two different lines/dishes simultaneously → exactly one succeeds, one `409` (this is the scenario most likely to be missed, since the two competing requests aren't for the "same" line item)
- Underpayment → `400`
- No open shift → `409`
- Meal-period window closed → `400`
- Extra from a component stocked only for a different meal period → `400`
- `changeDue` math correctness across several tendered-amount cases

---

## Stage 1.4 — Receipt & Basic Order Visibility

### Scope
- Receipt generation is best-effort and **never blocks** payment success (matches earlier decision).
- A simple "today's orders" read endpoint — not the full KDS (that's Phase 2) — just enough to prove the sale is real and visible.

### Endpoints
```
GET /orders/today                 CASHIER/ADMIN — today's orders, newest first
GET /orders/{id}                  CASHIER/ADMIN — single order detail, for reprint/lookup
POST /orders/{id}/mark-print-failed   CASHIER — logs that physical printing failed for this order (for audit/fallback tracking)
```

### Business rules
- `GET /orders/today` and `GET /orders/{id}` return full line/extra detail so the frontend can render a receipt-equivalent view even without a physical printer.
- `mark-print-failed` doesn't change order status — it's a lightweight audit log so a manager can later see how often hardware failed.

### Flyway migration
```sql
-- V__add_print_failure_flag.sql
ALTER TABLE orders ADD print_failed BIT NOT NULL DEFAULT 0;
```

### Acceptance criteria
- [ ] `GET /orders/today` returns all of today's orders with correct line/extra nesting.
- [ ] `GET /orders/{id}` returns full detail suitable for an on-screen receipt reprint.
- [ ] Marking a print failure sets `print_failed = true` and doesn't alter order status or totals.

### Test scenarios
- Create two orders → `GET /orders/today` returns both, correctly ordered
- `GET /orders/{id}` for a nonexistent ID → `404`
- Mark print failure → flag set, order otherwise unchanged

---

## Cross-cutting: Seed Data (add once, after Stage 1.3 migrations exist)
- 2 users: one `CASHIER`, one `ADMIN`, with documented PINs for local/dev use
- Meal periods seeded via migration (Breakfast 07:00–10:00, Lunch 12:00–14:30) — no separate seed step needed.
- 4+ `meal_catalog` entries with predefined prices (e.g. "Rice & Chicken" R45, "Potatoes & Beef" R50) and 4+ `component_catalog` entries with predefined extra prices (Rice R8, Chicken R12, Potatoes R8, Beef R15), linked via `meal_catalog_components`.
- Today's date seeded with 2 breakfast options and 2 lunch options (created from the catalog, with snapshotted name/description/price), at least one seeded with `portions_remaining = 0` to make the sold-out/no-sale path easy to test immediately (rather than a low-but-nonzero count, since there's no replenishment to test).
- Daily component stock seeded for all four components at lunch, including at least one component belonging to a *different* dish than the sold-out option above — so the cross-dish extras path is testable immediately, and at least one seeded at `buffer_remaining = 0` to test the extras sold-out path.

## Explicitly Out of Scope for Phase 1
- Card/electronic payment
- Kitchen status screens, public display, WebSocket/SSE push
- Cash drawer reconciliation math (shift close is just a status flip in 1.1)
- Refunds, voids, discounts, manager overrides
- Replenishment of any kind — depletion is permanent for the day by design, not a deferred feature
- Offline capability, recipe/costing layer, periodic stock counts, reporting