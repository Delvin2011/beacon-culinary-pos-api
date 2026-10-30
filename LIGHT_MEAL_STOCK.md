# Light meal stock — review, request and deduct on sale — contract additions

Builds on `CATALOGUE_AND_BAR.md` Part A and replaces its "Known gap (stock)" **for light meals only**. Beverages
stay untracked. Set meals still deduct through planning and are unchanged. Migration: `V99__light_meal_stock.sql`.

## Data prerequisite — tell the manager
Tracking needs **recipes** on the light meals' components and **opening stock in the Kitchen** (approved issues,
or a stock take). Without them every light meal sale drives Kitchen stock negative.

## When a meal is tracked
A meal is **tracked** when it is a **light meal** (group `LIGHT_MEALS`) and its components have at least one recipe
line. Anything else is `NOT_TRACKED`: never warned about, never deducted. A meal's per-unit need for an ingredient is
`SUM over its components of (recipe line quantity ÷ recipe batch size)`. This is the same calculator the daily planning
review uses, so planning results are unchanged.

## Stock status
`unitsPossible` per ingredient = `floor(Kitchen stock ÷ per-unit)`, never below 0. `unitsAvailable` = the lowest
of those. Status, for the meal and for each line:

| `stockStatus` | When |
|---|---|
| `NOT_TRACKED` | not a light meal, or no recipe lines |
| `OUT` | `unitsAvailable = 0` |
| `LOW` | `unitsAvailable < lowStockUnits` |
| `OK` | otherwise |

**Warn, never block:** low, out or negative stock never stops a sale.

## Meal catalogue — `/admin/meal-catalog`
- `POST`/`PUT` take an optional **`lowStockUnits`** (0–10000; default 10 on create; left out on update = unchanged).
- Responses add `lowStockUnits`, `stockStatus` and `unitsAvailable`. These are set on always-available rows only
  (`null` on set meals). Beverages are `NOT_TRACKED`. `unitsAvailable` is `null` unless the meal is tracked. The list
  is computed with one ledger query for all rows.

## Stock review — `GET /admin/meal-catalog/{id}/stock-review`
ADMIN and HEAD_CHEF (MENU_VIEW + STOCK_VIEW until U2).
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
- `kitchenAvailable` and `mainStoreAvailable` are ledger sums and may be **negative** (show them in red).
- `limitingIngredient` is the ingredient with the fewest units (the first one in recipe order on a tie).
- Not tracked: `tracked: false`, `status: NOT_TRACKED`, `unitsAvailable`/`limitingIngredient` `null`, `lines: []`.
- A set meal → `400 STOCK_REVIEW_NOT_APPLICABLE`. Unknown id → `404`.

## Till menu — `GET /menu/today`
Each always-on option adds `stockStatus` and `unitsAvailable` (same meaning as above, for an optional chip). Planned
options have `null`. The whole menu is computed with one ledger query.

## Requesting stock from the review (frontend only)
No new endpoint. Build an Issue request (Main Store → Kitchen) with the existing
`POST /stock-requests { requestType: "ISSUE", lines: [{ ingredientId, quantity }] }` (STOCK_CLERK,
HEAD_CHEF, STOCK_ADMIN, ADMIN). Approval works as it does today.
- **Batch of N** (validate 1–1000): `quantity = N × perUnit` (min 0.0001, 4 dp), editable per line.
- **Only what's missing**: `max(0, N × perUnit − kitchenAvailable)`, leaving out lines that come to 0.
- Stock requests have no note/reason field, so "Batch of 20 × Chicken Burger" can't be stored yet.
  Show it in the dialog only.

Example, 20 Chicken Burgers: full batch → patties 20, buns 20, lettuce 0.4, sauce 0.6. Only what's missing →
patties 6 (14 on hand); buns, lettuce and sauce are covered.

## Mark out today
Unchanged: `POST /admin/meal-catalog/{id}/out-today { "out": true }`. Nothing stops selling at zero automatically.

## The ledger
| Event | `movement_type` | `quantity` | Location | `source_type` / `source_id` |
|---|---|---|---|---|
| Light meal sold (sale **or complimentary**) | `CONSUMED` | −(per-unit × line quantity), 4 dp | Kitchen | `ORDER_LINE` / order line id |
| That order **voided** (while `PENDING`) | `CONSUMPTION_REVERSED` | +the same | Kitchen | `ORDER_LINE` / same line id |

- Written in the order's (or void's) transaction: if a movement fails, the order or void fails too.
- The recipe is read at the time of sale. Later recipe edits never rewrite past movements.
- A line is reversed **at most once** per ingredient (a unique index enforces this).
- **Refund** (order already prepared) and **remove extras** → **no reversal**: the food was made.
- Set meal lines never write `ORDER_LINE` movements. They deduct through planning
  (`DAILY_PLANNING_CONFIRMATION` / `STOCK_REQUEST`). In a mixed order only the light meal lines deduct.
- No cost snapshot (`cost_per_unit` stays `null`). Cost and margin are not part of this change.

## Ripple
- **Consumption (R5):** `CONSUMED` now has two sources, `STOCK_REQUEST`/`DAILY_PLANNING_CONFIRMATION` (planned meals)
  and `ORDER_LINE` (light meals). Net light meal usage = `CONSUMED` + `CONSUMPTION_REVERSED` for `ORDER_LINE`.
- Stock takes of these ingredients now reconcile. R1/R2/R3 and POS Activity are unaffected.

## Not in this change
Beverage stock, automatic stop-selling at zero, cost and margin on these movements, deducting anywhere but the
Kitchen, any change to how set meals deduct.
