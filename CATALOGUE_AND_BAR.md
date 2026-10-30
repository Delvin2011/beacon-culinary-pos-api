# Catalogue categories, light meals and beverages — contract additions

Part A: a category tree, light meals (always available, unlimited, "Out today"), period rules.
Part B: beverages, the bar station and the status engine. Migrations: `V96__catalog_categories_and_light_meals.sql`,
`V97__beverages_and_bar_station.sql`, `V98__seed_barista.sql`.

Until the permissions matrix (U2) lands, permissions are role checks:
`MENU_MANAGE` = ADMIN · `DAILY_PLANNING` = ADMIN, HEAD_CHEF · `BAR_DISPLAY` = BARISTA, ADMIN.

## Groups, availability, stations
| Group | Planned? (`availability`) | Period | `station` |
|---|---|---|---|
| `SET_MEALS` | `PLANNED`: planned daily with portions, as today | Breakfast, Lunch | `KITCHEN` |
| `LIGHT_MEALS` | `ALWAYS`: unlimited, "Out today" switch, never planned | All Day | `KITCHEN` |
| `BEVERAGES` | `ALWAYS` | none (`null`) | `BAR` (hot, default) or `NONE` (cold, default for Cold beverages) |

## Category tree — `/admin/catalog/categories` (ADMIN; GET also HEAD_CHEF)
- `GET` → the tree: `[{ id, name, group, sortOrder, active, topLevel, assignable, itemCount, children: [...] }]`.
  `itemCount` = active meals (a group row includes its children). `assignable` = a meal can sit here.
- `POST { parentId, name, sortOrder? }` → `201`, a subcategory under **Light meals** or **Beverages** only.
- `PUT /{id} { name, sortOrder, active }` → rename, re-sort, (de)activate a subcategory. No delete.

Seeded: `Set meals` (a leaf) · `Light meals` › Burgers, Wraps, Sandwiches, Chips & sides · `Beverages` › Hot beverages, Cold beverages.

| Code | HTTP | When |
|---|---|---|
| `CATEGORY_INVALID` | 400 | parent isn't Light meals/Beverages; or a meal's `categoryId` isn't an active leaf |
| `CATEGORY_FIXED` | 400 | changing a top-level group |
| `CATEGORY_NAME_TAKEN` | 409 | same name under the same parent (case-insensitive) |
| `CATEGORY_HAS_ITEMS` | 409 | deactivating a subcategory that has active meals |

## Meal catalogue — `/admin/meal-catalog`
- `POST`/`PUT` require **`categoryId`** (a leaf). Optional **`station`**: beverages only (`BAR`/`NONE`), else `400 STATION_INVALID`.
- Responses add `category { id, name, sortOrder }`, `group`, `availability`, `station`, `outToday`.
- `GET` filters: `?group=`, `?categoryId=`, `?periodId=` (the planning picker: only meals that period accepts; none for All Day).
- Moving a meal to another **group** while it has planned options today or later → `409 MEAL_HAS_PLANNED_OPTIONS`.
- Price change on an always-on item: **today's** option follows (name, description, price). Placed orders keep their line price.
- **Out today:** `POST /admin/meal-catalog/{id}/out-today { "out": true|false }` → the meal. Clears itself the next day.
  ADMIN: any always-on item · HEAD_CHEF: light meals · BARISTA: beverages; otherwise `403 OUT_TODAY_NOT_PERMITTED`.
  A set meal → `400 OUT_TODAY_NOT_APPLICABLE`.

## Periods and planning
- `GET /meal-periods` adds `accepts` (`SET_MEALS`/`LIGHT_MEALS`) and `plannable` (false for All Day).
  `GET /admin/daily-planning/{date}?period=` adds `plannable`.
- `POST`/`PUT /admin/daily-options`: `400 ALWAYS_ON_ITEM_NOT_PLANNABLE` for a light meal or beverage;
  `400 MEAL_NOT_ALLOWED_IN_PERIOD` when the meal's group isn't what the period accepts (e.g. a set meal under All Day).
  An edit is checked only when it changes the meal or period, so older All Day options keep working.
- `PUT /admin/daily-options/{id}/actual` on an always-on option → `400 ALWAYS_ON_ITEM_NOT_PLANNABLE`.

## Till menu — `GET /menu/today`
- **No `period`**: the full till menu — planned options of every period open now, plus light meals and beverages,
  sorted by group, category `sortOrder`, then item. **With `period`**: that period only, as before (no beverages).
- Today's always-on options are created on this call (and before every order): one per active light meal/beverage,
  `READY`, `period` All Day for light meals and `null` for beverages.
- Each option adds `mealCatalogId`, `group`, `category { id, name, sortOrder }`, `period { id, name }` (or `null`),
  `outToday`. **`plannedPortions`, `actualPortions`, `portionsRemaining` = `null` means unlimited.**
- An always-on item whose meal is inactive is left out; one marked out today is listed with `outToday: true`.

## Orders — `POST /orders`
| Code | HTTP | When |
|---|---|---|
| `ITEM_UNAVAILABLE` | 400 | a light meal/beverage that is out today or inactive (the message names it) |
| `EXTRAS_NOT_ALLOWED` | 400 | any extra on a light meal or beverage line |

Planned options are unchanged (portion guard and decrement); always-on options skip both.

## Stations and the status engine (Part B)
Each line snapshots its `station`. The order's stations = its lines' stations without `NONE`, one row each (`PENDING`).
**No stations (cold drinks only)** → the order is created **`COLLECTED`**: handed over at payment, never on a display
or the public board. A mistake there is a refund, not a void.

Each station moves forward one step (`PENDING → IN_PROGRESS → DONE`); the order's `status` follows:

| Station statuses | `orders.status` |
|---|---|
| all `PENDING` | `PENDING` |
| any `IN_PROGRESS`, or some `DONE` with others `PENDING` | `IN_PROGRESS` |
| all `DONE` | `DONE` |

- A skipped/backward step, or any step once the order is `COLLECTED`/`VOIDED`/`REFUNDED` → `400`. No row for that station → `404`.
- Void only while the order is `PENDING` (every station still `PENDING`); after that, the refund path — as today.
- `order_status_events`: station changes carry `station`; the order's own changes have `station = null`.

## Displays
| | Kitchen (KITCHEN, HEAD_CHEF, ADMIN) | Bar (BARISTA, ADMIN) |
|---|---|---|
| List | `GET /kitchen/orders` | `GET /bar/orders` |
| Step | `PATCH /kitchen/orders/{id}/status { status }` | `POST /bar/orders/{id}/status { status }` |
| Stream | `GET /kitchen/orders/stream` | `GET /bar/orders/stream` |

- Lists show today's orders whose **own station** is `PENDING`/`IN_PROGRESS` (finished work drops off, as today).
- Payload (list, step response, stream `order`): the summary with **only that station's lines** (`lines[].station`),
  `status` = **that station's status** (or the order's, once collected/voided/refunded), plus `orderStatus` (the order's)
  and `otherStations: [{ station, status }]`.
- Streams carry `ORDER_CREATED`, `STATUS_CHANGED` (station or order), `ORDER_UPDATED`, only for orders with that station.
- The public board is unchanged: the order's overall status only; cold-only orders never appear.

## Login tiles
`GET /users/login-tiles?screen=BAR` → active Baristas and Admins with a PIN. New role `BARISTA`
(PIN only; `GET /admin/staff/roles` lists it). Seeded locally: **Canteen Barista**, PIN `654321`. A Barista can't sell.

## Order reads
`POST /orders`, `GET /orders/{id}`, `GET /orders/today` add `lines[].station` and
`stations: [{ station, status, updatedAt, updatedBy { id, name } }]` (`[]` for a cold-only order).

## Known gap (stock) — tell the manager
Beverages are not planned, so **their ingredients (milk, coffee…) are never deducted** from Kitchen stock. Stock
takes will show shortfalls for them that look like losses. **Light meals are now deducted on sale:** see
`LIGHT_MEAL_STOCK.md`.

## Not in this change
- **R2 Meal & Extras Sales** joins meal periods, so **beverage lines (no period) are left out of it** and its
  `gross-agrees-with-sales-summary` check will show a mismatch once beverages sell. Fixed in the R2 prompt, with the
  category filter/column and "days offered" for always-on items. Planning vs Sales (R5) must ignore `ALWAYS` items;
  Order Flow Times (R5) per station.
- Meal bulk insert (meals are created by hand), extras and choices, bar printing, a Barista selling, stock for
  always-available items, editing the top-level groups.
