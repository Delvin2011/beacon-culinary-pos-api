# Light Meal Stock — Review, Request and Deduct on Sale: Frontend

Adds a **Review** button to every light meal in Catalogue Management, a low-stock warning with **Mark out today**,
and a **Request stock** dialog for a batch of 10, 20 or any number. The backend is done; its contract is
`LIGHT_MEAL_STOCK.md` (backend repo). Read it first.

It follows the same idea as the **Review** button in Daily Planning for Breakfast and Lunch. Reuse that screen's look
and components wherever possible.

Needs Part A of the categories / light meals / beverages frontend (`CATALOGUE_AND_BAR_FRONTEND_PROMPT.md`).

## 0. What the backend gives you (summary)
| Need | Call | Notes |
|---|---|---|
| Chips on the meals list | `GET /admin/meal-catalog` (optionally `?group=LIGHT_MEALS`) | each row adds `lowStockUnits`, `stockStatus`, `unitsAvailable` |
| Review panel | `GET /admin/meal-catalog/{id}/stock-review` | see §2 for the shape |
| Mark out / back in | `POST /admin/meal-catalog/{id}/out-today { "out": true \| false }` | returns the meal (with `outToday`, `stockStatus`) |
| Request stock | `POST /stock-requests { "requestType": "ISSUE", "lines": [{ "ingredientId", "quantity" }] }` | `201` with the request (`id`, `status`, `lines`) |
| Low-stock level | `POST`/`PUT /admin/meal-catalog` with `lowStockUnits` | optional; 0–10000 |
| Till chips | `GET /menu/today` | always-on options add `stockStatus`, `unitsAvailable` |

`stockStatus` is one of `OK`, `LOW`, `OUT`, `NOT_TRACKED`. It is `null` on set meals and planned till options.
`unitsAvailable` is `null` unless the meal is tracked. Beverages always come back `NOT_TRACKED`.

## Permissions (temporary role checks until U2)
| Action | U2 code | Roles today |
|---|---|---|
| See the Review button and panel | `MENU_VIEW` + `STOCK_VIEW` | ADMIN, HEAD_CHEF |
| Mark out today / Back in stock (light meals) | `MENU_MANAGE` or `DAILY_PLANNING` | ADMIN, HEAD_CHEF |
| Request stock | `REQUEST_ISSUE` | ADMIN, HEAD_CHEF, STOCK_ADMIN, STOCK_CLERK |
| Edit the low-stock level (meal form) | `MENU_MANAGE` | ADMIN |

Hide what the user can't do. If the server still refuses (`403`), show a plain message.

## 1. Catalogue Management: the Review button
- On the **Meals** tab, **each light meal row** (`group === "LIGHT_MEALS"`) gets a **Review** button. Set meals and
  beverages do not get one (planning has its own review; beverages aren't tracked).
- Show the row's **stock chip** next to it, from `stockStatus` and `unitsAvailable` on the list. No extra calls per row:
  - `OK`: green, "14 available"
  - `LOW`: amber, "8 available"
  - `OUT`: red, "No stock"
  - `NOT_TRACKED`: grey, "No recipe"
- **Review** opens a **side panel** (not a new page) loaded from `GET /admin/meal-catalog/{id}/stock-review`.
- Reload the list (or patch the row from the response) after Mark out today, a meal edit, or closing the panel after
  Refresh, so the chip doesn't go stale.

## 2. The Review panel
Response:
```json
{
  "mealId": 12, "name": "Chicken Burger", "tracked": true, "status": "OK",
  "unitsAvailable": 14, "limitingIngredient": "Chicken patties",
  "lowStockUnits": 10, "outToday": false,
  "lines": [
    { "ingredientId": 3, "name": "Chicken patties", "uom": "EACH", "perUnit": 1.0000,
      "kitchenAvailable": 14.0000, "mainStoreAvailable": 120.0000, "unitsPossible": 14, "status": "OK" }
  ]
}
```
Errors: `400 { code: "STOCK_REVIEW_NOT_APPLICABLE" }` for a set meal (shouldn't happen from the UI), `404` unknown meal.

**Header:** meal name, category and price (from the list row you opened it from; the review doesn't repeat them), and
`status` as a large chip. A summary line: **"Can make 14 units. Limited by Chicken patties."** (`unitsAvailable`,
`limitingIngredient`). If `outToday` is true, add an "Out today" tag.

**Table (ingredients for one unit), one row per `lines[]` entry, in the order given:**
| Ingredient | Needed per unit | In Kitchen | Main Store | Units possible | Status |
|---|---|---|---|---|---|
- `uom` is an enum. Display it as `EACH` → "each", `KG` → "kg", `LITRE` → "L", `LOAF`, `ROLL`, `PACK`, `DOZEN`,
  `BOX`, `PAIR` → lower-case word.
- Quantities: up to four decimals, trailing zeros dropped (`0.0200` → "0.02 kg", `14.0000` → "14 each").
- Kitchen **and** Main Store amounts can be **negative** (the ledger runs ahead of reality). Show negatives in red,
  with the sign.
- Highlight the limiting ingredient's row (`name === limitingIngredient`). Show each row's `status` as a chip.
- A **Refresh** button re-loads the review (for after stock arrives).

**Not tracked** (`tracked: false`; `lines` is empty and `unitsAvailable`/`limitingIngredient` are `null`): replace the
table with "No recipe is defined for this meal, so stock isn't tracked. Add components with recipes to track it."
Hide the banner and the action buttons.

## 3. Warning and actions
When `status` is `LOW` or `OUT`, show a banner above the table:
- **OUT:** "The Kitchen doesn't have enough to make one unit." (red)
- **LOW:** "Only 8 units can be made. The low-stock level for this meal is 10." (amber; `unitsAvailable`,
  `lowStockUnits`)
- The banner carries two buttons: **Mark out today** and **Request stock**.

**Mark out today:** `POST /admin/meal-catalog/{id}/out-today { "out": true }`. While `outToday` is true the button
reads **Back in stock** (`{ "out": false }`) with the text "Out for today. Back automatically tomorrow." Use the
returned meal to update the panel and the list row. `403 OUT_TODAY_NOT_PERMITTED` → "You can't change this meal's
availability." Hide the button without the permission.

The warning is advice only: **a meal that is LOW or OUT can still be sold** unless someone marks it out.

**Request stock** also shows outside the banner (e.g. in the panel footer) when the status is `OK`, since a chef may
top up early.

## 4. The Request stock dialog (batch prompt)
Opens from the panel, using the review data already loaded (no extra call). Hide the button without
`REQUEST_ISSUE`.
1. **"How many more units do you want to make?"** Quick picks **10 · 20 · 50 · Custom** (a whole number 1–1000).
2. **A table** of quantities: `units × perUnit` for each line (the **full batch, on top of what's in the Kitchen**).
   Each quantity is **editable**. Beside each, show the Kitchen and **Main Store** amounts. If the Main Store has less
   than the quantity, show an amber note: "Main Store has only 3 kg. The approver can only issue what is there."
   (This is a warning only; approval caps the issue.)
3. A toggle **"Only what's missing"** switches the quantities to `max(0, units × perUnit − kitchenAvailable)`. Zero rows
   are dropped. Default off. Changing the units or the toggle recalculates and **overwrites** any edits; say so in the
   help text.
4. A title line, "Batch of 20 × Chicken Burger", shown in the dialog only. **Stock requests have no note field yet**,
   so nothing about the batch is sent; don't add a `note` to the request body.
5. **Submit request** → `POST /stock-requests`:
   ```json
   { "requestType": "ISSUE", "lines": [ { "ingredientId": 3, "quantity": 20 }, ... ] }
   ```
   - One line per ingredient with a quantity above 0. Round to **4 decimals** before sending (the minimum is 0.0001).
   - No `locationId`: an Issue is always Main Store → Kitchen.
   - `201` → "Request submitted. An approver will review it.", with a link to the request in the Stock Requests
     screen (by the returned `id`). Close the dialog.
   - Errors: `400` validation comes back as `{ "<field>": "<message>" }` (e.g. `lines[0].quantity`) or
     `{ "message": "..." }`; show a plain message and **keep the dialog open**.
- Block submit when no line has a quantity above 0.

Worked example (Chicken Burger, Kitchen patties 14 / buns 20 / lettuce 1.0 kg / sauce 2.0 L), 20 units: full batch →
patties 20, buns 20, lettuce 0.4, sauce 0.6. With "Only what's missing" → patties 6 only.

## 5. Meal form (light meals)
- Add a **Low-stock level (units)** field to the create/edit form for light meals: whole number 0–10000, default
  **10**, help text "Warn when the Kitchen can make fewer than this many." Send it as `lowStockUnits` on `POST` and
  `PUT /admin/meal-catalog`. Pre-fill it on edit from the meal's `lowStockUnits`. Leaving it out of a `PUT` keeps the
  current value. A validation error comes back as `{ "lowStockUnits": "lowStockUnits must be between 0 and 10000" }`.
- Hide the field for set meals and beverages.
- Components and recipes stay optional. Add a short note on light meals: "Add components with recipes so stock can be
  tracked and deducted on every sale."

## 6. Daily Planning: the "Always available today" panel
Show the stock chip on each light meal, with a **Review** link that opens the same panel. The planning endpoint
doesn't carry stock, so take the chips from **one** `GET /admin/meal-catalog?group=LIGHT_MEALS` call (match by meal
id), not per row. The **Out today** switch stays.

## 7. The till (optional, recommended)
`GET /menu/today` options carry `stockStatus` and `unitsAvailable` (`null` on planned options). On light meal tiles
show a small **amber "Low"** chip for `LOW` or a **red "No stock"** chip for `OUT`. **The tile stays tappable** (warn
only). No chip for `OK`, `NOT_TRACKED` or `null`. Refresh the menu after each order and about every 60 seconds so the
chips stay current. A manager-marked **Out** item (`outToday: true`) is still greyed out as before.

## What changes for orders (no UI work, for awareness)
- Selling a light meal now deducts its ingredients from Kitchen stock; a **void** (order still Pending) puts them
  back; a **refund** doesn't. Complimentary orders deduct too. Nothing in the order screens changes.
- Stock movement lists may now show `CONSUMED` and `CONSUMPTION_REVERSED` rows with source `ORDER_LINE`. If a
  movements/ledger screen maps types or sources to labels, add "Sold (light meal)" and "Void returned" (fall back to
  the raw value for anything unknown).

## Acceptance
- [ ] Every light meal has a **Review** button and a stock chip; set meals and beverages have no Review button.
- [ ] The panel shows the per-unit ingredients, Kitchen and Main Store amounts, units possible, the limiting
      ingredient and an overall status that match the backend.
- [ ] LOW and OUT show the banner with **Mark out today** and **Request stock**; Mark out today toggles to Back in
      stock; a meal that is LOW or OUT is still sellable.
- [ ] The Request stock dialog offers 10 / 20 / 50 / Custom, computes units × per-unit quantities, lets the chef edit
      them, warns about Main Store shortfalls, supports "Only what's missing", and submits an Issue request with
      `lines[].quantity` through the existing endpoint.
- [ ] A meal with no recipe shows "Stock not tracked" and no actions.
- [ ] The meal form has the low-stock level field (light meals only), sent as `lowStockUnits`.
- [ ] Buttons respect permissions (role checks above until U2).
- [ ] Negative Kitchen and Main Store stock show in red.
- [ ] (Optional) the till chips appear and do not block adding items.

## Test scenarios (the backend's test data)
- Open Review for Chicken Burger with Kitchen patties 14 → "Can make 14 units. Limited by Chicken patties.", status OK.
- Sell burgers until patties are 8 → amber LOW banner; to 0 → red OUT banner; selling another still works at the till;
  the review shows −1 in red.
- Void a Pending order of 3 burgers → Refresh shows patties back up by 3. Refund a collected one → no change.
- Request stock, choose 20 → patties 20, buns 20, lettuce 0.4, sauce 0.6; turn on "Only what's missing" → patties 6
  only.
- Edit the patties quantity to 30 and submit → the request has 30; the Main Store note appears if the Main Store holds
  less.
- Mark out today from the banner → the till tile greys out; Back in stock → it returns.
- Open Review on a light meal with no recipe → "Stock not tracked", no buttons.
- Head Chef → all three actions work. Stock Clerk → no Review button (no catalogue access); Cashier → none.
- Edit the low-stock level to 15 → a status of 14 becomes LOW.

## Not in this change
Stock tracking for beverages, automatic stop-selling at zero, editing recipes from the Review panel, a note on stock
requests, and cost figures.
