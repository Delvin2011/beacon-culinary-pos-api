# Stock locations

Branch `feature/infrastructureUpdatesBE` (T0-5c, V115). Prepares for multi-tenancy (readiness analysis of
19 Nov 2026, sections A.1, C.8 and H.20): each customer names and adds their own stock locations. The HTTP contract
is `docs/2026-11-21-t0-5c-locations-api-contract.md`.

## The model

Stock is a ledger: every movement (`ingredient_stock_movements`) names an ingredient, a location and a signed
quantity. Stock on hand is the sum per ingredient and location, worked out when asked, never stored. That was
already true for any number of locations; what changed is how each flow picks its location.

**One primary location** (today's Main Store). It receives every GRV and supplies every issue. It can be renamed
but never deactivated or deleted, and there is always exactly one: the database refuses a second primary, a
deactivated primary, and any statement that leaves none.

**Any number of secondary locations** (today's Kitchen, plus new ones such as a Coffee Station). They request stock
from the primary, and production and the sales stations draw from them. They can be added, renamed, reordered,
deactivated and reactivated.

**Assignments** (`location_assignments`) say which secondary does which job:

| Job | Meaning | Default |
|---|---|---|
| `PRODUCTION` | where daily planning's issue sheet sends ingredients | Kitchen |
| `STATION_KITCHEN` | where sales prepared in the kitchen deduct | Kitchen |
| `STATION_BAR` | where sales prepared at the bar deduct | Kitchen |
| `STATION_NONE` | where sales handed over at the till deduct | Kitchen |

A sale deducts from the source of the station on the order line (the station is copied onto the line at sale),
online and when an offline sale syncs. There are no per-item overrides yet.

Code finds a location only through `StockLocations`: `primary()`, `production()`, `sourceFor(station)` and
`chosen(id)`. No code looks a location up by name.

## The rules

| | Primary | Secondary |
|---|---|---|
| Receives GRVs | always | never |
| Requests an issue | never | yes, from the primary |
| Supplies an issue | always | never |
| Production or a station source | no | yes (if active) |
| Waste, stock take | yes | yes |
| Renamed, reordered | yes | yes |
| Deactivated | never | when it holds no stock (no figure other than zero), isn't assigned, and has no open requests or stock takes |

- Names are unique among active locations, ignoring case.
- At most `stock.locations.max-active` active locations, the primary included (site setting, default 10).
- Every change (create, rename or reorder, deactivate, activate, assignment) goes into `location_changes`, which is
  insert-only. Changing nothing records nothing.
- A deactivated location keeps its history and its name on old records. It leaves the pickers, and the ingredient
  stock view shows it only while it still has stock, which can't happen through the API because deactivating
  needs zero stock.

## With only the two seeded locations

Behaviour is as before T0-5c: GRVs land at Main Store, issues go Main Store → Kitchen, light meals deduct at the
Kitchen, waste and stock take are at the location chosen. The existing tests and the T0-2 golden outputs are
unchanged. V115 refuses to run if Main Store or Kitchen is missing, inactive or duplicated, rather than guessing.

## Known limits

- **Only light meals deduct stock at sale**, and light meals are always at the KITCHEN station. So today only the
  KITCHEN station's source receives sale deductions. The BAR and NONE assignments are ready for when beverages are
  stock-tracked.
- **Set meals.** Planning issues their ingredients to the production location; nothing deducts them there when
  they are sold. The production location's stock is corrected by stock takes (as before T0-5c).
- **Offline sales** deduct from the station's source as assigned when the sale syncs, not when it was sold.
- **Legacy stock take** (`POST /admin/stock-takes`, replaced by `/stock-takes`) always counts at the primary.

## What inter-location transfers (IBT) will add later

T0-5c deliberately allows only primary → secondary issues. IBT will add:

- transfers secondary → secondary and secondary → primary (returns), as a request with a source and a
  destination chosen by the user, approved like an issue, written as a paired `ISSUED`/`RECEIVED` movement;
- a way to empty a location before deactivating it, other than waste or a stock take to zero;
- per-location rules on who may request and who approves (today the stock roles act on every location);
- possibly per-item station sources (a light meal drawing from a different location than its station).

None of this needs a schema change to the ledger: movements already name their location.
