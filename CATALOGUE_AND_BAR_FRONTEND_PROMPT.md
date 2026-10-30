# Catalogue Categories, Light Meals and Beverages — Frontend

Two parts, matching the backend (already built and tested). **Part A** (categories and light meals) can ship alone.
**Part B** (beverages, the bar display) follows. The backend contract is `CATALOGUE_AND_BAR.md` in the backend repo;
everything below has been checked against the actual endpoints.

> **Ship Part A's meal form with (or before) the backend release.** The backend now **requires `categoryId`** on
> meal create *and* update, so today's meal form will get a 400 until it sends one.

## Ground rules

**Permissions.** U2 (the permissions matrix) is not merged. Use these role checks for menus, routes and buttons:

| Permission | Roles |
|---|---|
| `MENU_MANAGE` | `ADMIN` |
| `DAILY_PLANNING` | `ADMIN`, `HEAD_CHEF` |
| `BAR_DISPLAY` | `BARISTA` (new role), `ADMIN` |

**Errors.** Rule errors come back as `{ "error": "<message>", "code": "<CODE>" }` — branch on `code`, show `error`.
Field validation errors come back as a map: `{ "categoryId": "categoryId is required" }`.

**Groups.** Every meal sits in a category, and the category decides its group:

| `group` | `availability` | Meal period | `station` |
|---|---|---|---|
| `SET_MEALS` | `PLANNED` — planned daily with portions, as today | Breakfast, Lunch | `KITCHEN` |
| `LIGHT_MEALS` | `ALWAYS` — unlimited, "Out today" switch, never planned | All Day | `KITCHEN` |
| `BEVERAGES` (Part B) | `ALWAYS` | none (`null`) | `BAR` or `NONE` |

**Unlimited.** On menu options, `plannedPortions`, `actualPortions` and `portionsRemaining` are `null` for always-on
items. `null` means unlimited: hide the counter, never cap the quantity.

---

# PART A — Categories and light meals

## 1. Catalogue Management — Meals tab

### Meal form
- Replace any "light meal" checkbox with one **required Category** dropdown, grouped: *Set meals* · *Light meals* ›
  Burgers, Wraps, Sandwiches, Chips & sides….
- Source: `GET /admin/catalog/categories` (a tree; see §2). Only nodes with **`assignable && active`** are
  selectable. "Set meals" itself is selectable; the "Light meals" (and later "Beverages") group rows are not.
- Send **`categoryId` on both `POST /admin/meal-catalog` and `PUT /admin/meal-catalog/{id}`** — it is required on edit too.
- Next to the category show **Planned daily** or **Always available**. Before saving, derive it from the selected
  category's `group` (`SET_MEALS` → Planned daily, otherwise Always available); after saving, use the response's
  `availability`.
- Always-available meals need **no components, portions or planning** (components stay optional). Show the note:
  "Extras aren't available on this meal. Create an add-on as its own meal (for example 'Cheese Burger')."
- Don't send `station` for set meals or light meals (it is always `KITCHEN`; anything else → `400 STATION_INVALID`).
- Errors:
  - `400 CATEGORY_INVALID` — the category isn't an active leaf (shouldn't happen if the dropdown follows the rule above).
  - `409 MEAL_HAS_PLANNED_OPTIONS` — moving a meal to another **group** while it's planned today or later. Show the
    server's message. A price/name change within the same group is fine.

### Meals list
- Load with `GET /admin/meal-catalog?group=SET_MEALS|LIGHT_MEALS&categoryId={id}` (both optional). Filter chips:
  *All · Set meals · Light meals*, plus a subcategory filter. The list includes inactive meals (`active: false`).
- Each meal now has: `category { id, name, sortOrder }`, `group`, `availability`, `station`, `outToday`.
- Rows with `availability === "ALWAYS"` show an **Out today** switch:
  - `POST /admin/meal-catalog/{id}/out-today` with `{ "out": true }` or `{ "out": false }` → returns the meal.
  - When out, the row shows **"Out today · Back tomorrow"** — it clears itself at midnight (Johannesburg).
  - Who can toggle: `ADMIN` any always-on item · `HEAD_CHEF` light meals only · `BARISTA` beverages only.
    Others get `403 OUT_TODAY_NOT_PERMITTED`; a set meal gets `400 OUT_TODAY_NOT_APPLICABLE` (never show the switch there).
- The Head Chef can **view** the catalogue (read-only) and use the Out today switch on light meals; nothing else.

## 2. Categories screen (a button on the Meals tab; `MENU_MANAGE`)
- `GET /admin/catalog/categories` returns:
  ```json
  [{ "id": 2, "name": "Light meals", "group": "LIGHT_MEALS", "sortOrder": 2, "active": true,
     "topLevel": true, "assignable": false, "itemCount": 3,
     "children": [{ "id": 3, "name": "Burgers", "group": "LIGHT_MEALS", "sortOrder": 1, "active": true,
                    "topLevel": false, "assignable": true, "itemCount": 2, "children": [] }] }]
  ```
  `itemCount` = **active** meals; a group row's count includes its children.
- **Top-level groups are fixed** (`topLevel: true`): show them read-only, no rename/reorder/deactivate.
- **Add a subcategory** under Light meals: `POST /admin/catalog/categories { parentId, name, sortOrder }` → `201`.
- **Rename / reorder / deactivate**: `PUT /admin/catalog/categories/{id} { name, sortOrder, active }`.
  **Always send all three fields** (a missing `active` defaults to `true`). There is no bulk reorder: moving a row
  means one `PUT` per row whose `sortOrder` changes.
- Inactive subcategories stay in the tree (`active: false`): show them dimmed with a "Reactivate" action.
- No delete.
- Errors: `409 CATEGORY_HAS_ITEMS` (deactivating one with active meals — show the message, it names the count),
  `409 CATEGORY_NAME_TAKEN` (same name under that parent, case-insensitive), `400 CATEGORY_FIXED`, `400 CATEGORY_INVALID`.

## 3. Daily Planning
- **Periods:** `GET /meal-periods` now returns `accepts` and `plannable`. Only offer periods with `plannable: true`
  (Breakfast, Lunch). Where All Day used to be, show a quiet line:
  "All Day meals and beverages are always available. Manage them in Catalogue Management."
  (`GET /admin/daily-planning/{date}?period=` also returns `plannable`.)
  - Old All Day plans from earlier days still exist; if anyone needs to see them, keep All Day viewable read-only for past dates.
- **Meal picker:** call **`GET /admin/meal-catalog?periodId={periodId}`** — the server returns only meals that period
  can plan. Without `periodId` it doesn't filter. Hide inactive meals client-side.
- **"Always available today" panel:** `GET /admin/meal-catalog?group=LIGHT_MEALS` (and `BEVERAGES` in Part B, shown
  read-only to the Head Chef), each with its Out today switch (endpoint and permissions as in §1), so the chef can mark
  "out of beef patties" in two taps.
- Errors on `POST`/`PUT /admin/daily-options`: `400 ALWAYS_ON_ITEM_NOT_PLANNABLE`, `400 MEAL_NOT_ALLOWED_IN_PERIOD`.
- The ingredient-requirement review is unchanged.

## 4. The till — All Day tab
- Load with `GET /menu/today?period=All Day` (unchanged call; it now returns the light meals). Each option adds:
  `mealCatalogId`, `group`, `category { id, name, sortOrder }`, `period { id, name }` (or `null`), `outToday`.
- **The server returns this list in id order** — group it into **sections by `category.name`**, sections ordered by
  `category.sortOrder`, tiles by name. Tile: name and price, **no portions counter** (`portionsRemaining: null`).
- `outToday: true` → tile greyed with an **Out** label, can't be added.
- **No extras** on any line whose option has `group !== "SET_MEALS"` (the server answers `400 EXTRAS_NOT_ALLOWED`).
- Light meals and set meals can share a cart; light meals go to the kitchen display as today.
- **`400 ITEM_UNAVAILABLE` on submit** (message like "'Beef Burger' is not available right now." — it names the item
  but not its id): refetch the menu, remove cart lines whose option is now `outToday` or no longer on the menu, show
  "Beef Burger is out today", and keep the rest of the cart for resubmission.
- The till isn't pushed out-today changes: **refetch the menu when the tab gains focus** and after any order error.
- Breakfast and Lunch tabs are unchanged.

## Part A acceptance
- [ ] The meal form has a required, grouped Category dropdown (create **and** edit send `categoryId`); no light-meal checkbox.
- [ ] The Categories screen adds, renames, reorders and deactivates subcategories, with the item-count guard; groups are read-only.
- [ ] Always-available meals have an Out today switch that works from Catalogue and from the Planning panel, with "Back tomorrow".
- [ ] Planning offers only plannable periods; the picker uses `periodId`.
- [ ] The till's All Day tab shows subcategory sections; out items are greyed; no extras on light meals; `ITEM_UNAVAILABLE` keeps the rest of the cart.
- [ ] Breakfast and Lunch behave exactly as before.

## Part A test scenarios
- Create "Chicken Burger" under Burgers: the form shows "Always available" and no components requirement.
- Edit an existing set meal and save: the request carries its `categoryId` and succeeds.
- Add a "Pizzas" subcategory; create a pizza under it; it appears on the All Day tab under its own heading.
- Mark Beef Burger out today in Planning (as Head Chef): the till tile greys after refocus; submitting a stale cart gives a friendly message and keeps the other items.
- Try to deactivate Wraps while it has items → blocked with the server's message.
- Plan a lunch: the picker offers set meals only; All Day isn't offered.

---

# PART B — Beverages and the bar

## 5. Catalogue Management — Beverages tab
- A new **Beverages** tab beside Components and Meals: `GET /admin/meal-catalog?group=BEVERAGES`, sections/filter
  chips per beverage subcategory (**Hot beverages**, **Cold beverages**, plus any added later, e.g. "Juices").
  The Meals tab no longer lists beverages (it uses `group=SET_MEALS` / `LIGHT_MEALS`).
- **Add and edit** use the same `POST`/`PUT /admin/meal-catalog` with:
  name, category (`categoryId` of a beverage subcategory), **price (VAT-inclusive, like all prices)**, active, and
  **Prepared by: Bar / Counter (no preparation)** → `station: "BAR"` / `"NONE"`. No components or recipe fields.
- Default for **Prepared by**: *Counter* when the category is "Cold beverages", *Bar* for any other beverage category.
  (If `station` is omitted the server applies the same default; `KITCHEN` → `400 STATION_INVALID`.)
- No per-item sort order: items sort by category, then name.
- An **Out today** switch per row, as for light meals.
- The Categories screen now also shows the Beverages group; subcategories can be added under it.

## 6. The till — Beverages tab
- Tabs: **Breakfast · Lunch · All Day · Beverages**.
- **Beverages only come from `GET /menu/today` with no `period`** (the full till menu: every open period, light meals
  and beverages, already sorted by group → category → name). Filter `group === "BEVERAGES"`; their `period` and
  `mealPeriodId` are `null`. (You may use this one call to fill every tab.)
- Sections per subcategory (Hot, Cold…) of big tiles; tap to add one, quantity stepper; out items greyed; no extras.
- Beverages can share a cart with meals.
- **Confirmation after the order is placed**, from the `POST /orders` response:
  - `stations` contains `KITCHEN` → "Meals → Kitchen"; contains `BAR` → "Drinks → Bar" (only the parts that apply).
  - Lines with `station: "NONE"` (cold drinks) → "Hand over now: Coke 330ml".
  - `status === "COLLECTED"` and `stations` empty (cold drinks only) → **"Ready now"** and nothing else.
- **`COLLECTED` is a new status in the UI** — give it a label/badge wherever order statuses are shown.

## 7. The bar display (new screen, route `/bar`, `BAR_DISPLAY`)
A mirror of the kitchen display.

**Data**
- List: `GET /bar/orders` → today's orders whose **bar part** is `PENDING` or `IN_PROGRESS`, oldest first:
  ```json
  { "orderId": 41, "orderNumber": 12, "status": "IN_PROGRESS", "orderStatus": "IN_PROGRESS",
    "createdAt": "2026-10-28T12:31:05",
    "lines": [{ "optionName": "Cappuccino", "quantity": 2, "station": "BAR", "extras": [] }],
    "otherStations": [{ "station": "KITCHEN", "status": "PENDING" }] }
  ```
  `lines` are **bar items only**; `status` is **the bar's own status**; `orderStatus` is the whole order's.
- Step: **`POST /bar/orders/{orderId}/status { "status": "IN_PROGRESS" | "DONE" }`** → the updated card.
  One step forward at a time; a skipped/backward step, or an order already voided/refunded → `400` (usually another
  screen moved it: refetch the list). No bar part → `404`.
- Stream: `GET /bar/orders/stream` (SSE) — authenticate it exactly as the kitchen display authenticates its stream.
  Each message: `{ "eventType": "ORDER_CREATED" | "STATUS_CHANGED" | "ORDER_UPDATED", "order": { …card… } }`,
  only for orders that have a bar part. Same reconnect behaviour as the kitchen display (refetch the list on reconnect).

**Behaviour**
- Columns **Pending / In progress**, and optionally **Done**. The list endpoint never returns done work (it drops off,
  like the kitchen today) — if you show a Done column, fill it from stream events and let cards fade out after ~1 minute.
- Card: order number, the bar items ("2 × Cappuccino"), time since paid (from `createdAt`, as the kitchen does),
  **Start** (when `PENDING`) and **Done** (when `IN_PROGRESS`).
- When `otherStations` has `KITCHEN`, show a small chip **"Meal: Pending / In progress / Done"**. No actions on kitchen items.
- Stream handling: `ORDER_CREATED` → add the card, **chime + brief flash**. `STATUS_CHANGED` → upsert the card (this
  also fires when the *kitchen* moves its part — update the chip, don't chime); remove it when `status` is no longer
  `PENDING`/`IN_PROGRESS` (covers `DONE`, `VOIDED`, `REFUNDED`). `ORDER_UPDATED` → upsert (extras removed).
- Sound: browsers block audio until a user gesture — show an **"Enable sound"** button on first load.
- **Out today panel** (beverages with switches). A Barista **can't read `/admin/meal-catalog`**, so feed it from the
  public **`GET /menu/today`** (no period): filter `group === "BEVERAGES"`, use `mealCatalogId` and `outToday`; toggle
  with `POST /admin/meal-catalog/{mealCatalogId}/out-today { "out": … }` (allowed for `BARISTA`).

**Login**
- Tiles: **`GET /users/login-tiles?screen=BAR`** (active Baristas and Admins with a PIN).
- Sign in: `POST /auth/pin-login { "cashierId": <tile id>, "pin": "…" }` (same as the kitchen).
  PIN login does **not** check the screen, so after login check the user's role is `BARISTA` or `ADMIN`; otherwise
  sign out and show the standard "You don't have access to this screen" message. (`/bar/**` also returns `403` for others.)
- After login a `BARISTA` lands on `/bar` and sees no other menus (a Barista can't sell or open a shift).
- Staff management needs no new code: `GET /admin/staff/roles` now includes **Barista** (PIN only). Check any
  hard-coded role labels, landing routes or menus include `BARISTA`.
- Local test user: **Canteen Barista**, PIN `654321`.

## 8. The kitchen display
- Same endpoints and payload shape; each card now also has `orderStatus`, `otherStations`, and `lines[].station`.
- `lines` are **kitchen items only**; `status` is **the kitchen's own status**. Bar-only and cold-only orders never
  reach the kitchen (list or stream).
- When `otherStations` has `BAR`, show a chip **"Drinks: Pending / In progress / Done"**. No actions on bar items.
- If the kitchen finishes before the bar, its card gets `status: "DONE"` and drops off as usual (the order itself is
  still `IN_PROGRESS` until the bar finishes).
- A kitchen `STATUS_CHANGED` also arrives when the **bar** moves its part: update the chip.
- Meal-only orders look exactly as before (`otherStations: []`).

## 9. Order detail and Today's orders
- `POST /orders`, `GET /orders/{id}`, `GET /orders/today` add `lines[].station` and
  `stations: [{ station, status, updatedAt, updatedBy: { id, name } | null }]` (Kitchen first, then Bar; `[]` for cold-only).
- Show one chip per station: "Kitchen: Done · Bar: In progress".
- **Void** is offered only while `status === "PENDING"` (every station still Pending); otherwise only the refund path,
  as the server already decides by status. A cold-only order is `COLLECTED` at once, so a mistake there is a refund.

## 10. The public board
No code change. Check:
- Beverage orders appear with the **overall** status, like meals; a mixed order shows `IN_PROGRESS` as soon as either
  station starts and `DONE` only when both are done.
- Cold-only orders never appear. Refunding one sends a `REFUNDED` event for a number the board never showed —
  **ignore events for order numbers that aren't on the board**.

## Part B acceptance
- [ ] The Beverages tab manages beverages with price, station (Bar/Counter), out-today; no components fields.
- [ ] The till's Beverages tab works (from `/menu/today` without a period), mixes with meals, greys out items, and shows the right confirmation ("Ready now" for cold-only, "Hand over now" for cold lines).
- [ ] `COLLECTED` displays correctly everywhere.
- [ ] The bar display shows only bar items, starts and finishes them, chimes on new orders (after "Enable sound"), shows the meal chip, and runs its Out today panel from `/menu/today`.
- [ ] The kitchen display shows only kitchen items and the drinks chip; meal-only orders unchanged.
- [ ] PIN login on the BAR screen works for Baristas and Admins and refuses others.
- [ ] Order detail shows station chips; void only while Pending.
- [ ] The public board shows beverage orders by overall status and never cold-only orders.

## Part B test scenarios (backend test data)
Meals: Cappuccino (Hot, R28, Bar), Rooibos Tea (Hot, R18, Bar), Coke 330ml (Cold, R15, Counter),
Orange Juice 300ml (Cold, R18, Counter), Chicken Burger (Burgers, R55).
- Sell 2 × Cappuccino → bar display (chime) and board as Pending; not on the kitchen display; confirmation "Drinks → Bar".
- Sell 1 × Coke → "Ready now"; nothing on any display or the board; order shows Collected.
- Sell Chicken Burger + Cappuccino → both displays show their own items and the other's chip. Walk:
  Created → Bar starts → Bar done → Kitchen starts → Kitchen done; the board reads In progress from "Bar starts"
  and Done only at the end; the order detail chips follow each step.
- Sell Coke + Chicken Burger → kitchen only; confirmation "Meals → Kitchen · Hand over now: Coke 330ml".
- Mark Cappuccino out today from the bar display → its till tile greys out (after refocus).
- Try to void the mixed order after the bar starts → Void not offered (refund only).
- Log in on the bar screen as a Cashier → refused.

## Not in this change
Bulk insert (meals and beverages are created by hand), extras and choices, bar printing, a Barista selling
beverages, stock for always-available items, per-item sort order, editing the top-level category groups.
Known: the R2 Meal & Extras Sales report leaves beverages out until the R2 update — not a frontend bug.
