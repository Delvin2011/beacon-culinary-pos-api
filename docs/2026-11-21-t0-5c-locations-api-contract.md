# Stock locations API contract (T0-5c)

Branch `feature/infrastructureUpdatesBE` (V115). Status: **implemented; the frontend builds against it.**
Background: multi-tenancy readiness analysis (19 Nov 2026) sections A.1, C.8 and H.20. The model and the
rules in prose: `docs/2026-11-21-stock-locations.md`.

Until T0-5c the code assumed exactly two locations, "Main Store" and "Kitchen", by name. Now there is one
**primary** location and any number of **secondary** locations, and every flow finds its location by the job it
does. With only the two seeded locations, nothing a user sees changes; the responses gain a few fields.

## 1. The rules

| Flow | Location | Chosen by |
|---|---|---|
| GRV (receiving) | the primary | always; no choice |
| Stock request, ISSUE | from the primary **to** the requesting secondary | the requester picks the secondary; omitted = the production location |
| Stock request, WASTE | any active location (primary included) | the requester |
| Stock request, ORDER | none | — |
| Daily planning issue sheet | from the primary to the **production location** | assignment (default Kitchen) |
| Light-meal sale and void (online, and O4 offline sync) | the **source of the line's station** (KITCHEN, BAR, NONE) | assignment per station (default Kitchen for all) |
| Light-meal stock on the meal page, meals list, till menu, dashboard | the source of the meal's station | assignment |
| Waste (direct) | any active location | the user |
| Stock take | any active location | the user |
| Legacy stock take (`POST /admin/stock-takes`) | the primary | always |

- **Exactly one primary**: renamable, never deactivated, never deleted. Enforced in the database (filtered unique
  index, a check that the primary is active, and a trigger that refuses any statement leaving the table without
  exactly one primary).
- **Secondaries**: can be added, renamed, reordered, deactivated and reactivated.
- **Never** secondary → primary or secondary → secondary. Transfers between locations (IBT) are a later phase.
- Today only light meals deduct stock at sale, and light meals are always at the KITCHEN station, so in practice
  only the KITCHEN station's source receives sale deductions. The BAR and NONE assignments are stored and used the
  moment beverages become stock-tracked.
- Names are unique among **active** locations, ignoring case, at most 50 characters.
- At most `stock.locations.max-active` locations active at once, the primary included (site setting, default 10,
  allowed 2–100; becomes a tier limit later). Lowering it below the current count blocks only new ones.

## 2. Errors

Every coded error is `{ "error": "<message>", "code": "<CODE>", "details": { ... } }` (`details` omitted when
empty).

| Status | Code | When | details |
|---|---|---|---|
| 400 | `LOCATION_INVALID` | name blank or longer than 50; sortOrder outside 0–9999; assignments body empty | `fields: { name?, sortOrder?, productionLocationId? }` |
| 404 | `LOCATION_NOT_FOUND` | `/admin/locations/{id}` with an unknown id | `locationId` |
| 400 | `LOCATION_NOT_FOUND` | an unknown `locationId` in an ISSUE request or the assignments body | `locationId` |
| 400 | `LOCATION_INACTIVE` | an ISSUE request or an assignment names a deactivated location | `locationId, locationName` |
| 400 | `ISSUE_LOCATION_NOT_SECONDARY` | an ISSUE request names the primary | `locationId, locationName` |
| 400 | `ASSIGNMENT_NOT_SECONDARY` | an assignment names the primary | `role, locationId, locationName` |
| 409 | `LOCATION_NAME_TAKEN` | create, rename or activate would duplicate an active name | `locationId, name` (the other location) |
| 409 | `LOCATION_LIMIT_REACHED` | create or activate past the maximum | `maxActive, active` |
| 409 | `LOCATION_DEACTIVATE_REFUSED` | see 4.4 | `locationId, reasons[]` and one entry per reason |

Waste and stock take keep their pre-T0-5c messages (400, no code): `locationId is required.`,
`locationId does not exist.`, `locationId does not reference an active location.` A WASTE request without a
location keeps `locationId is required for WASTE requests.`

## 3. Pickers (any signed-in role)

### `GET /locations[?includeInactive=true]`

Active locations in display order (sortOrder, then id); `includeInactive=true` adds deactivated ones (for filters
on history screens). Use this for the waste, stock take and stock request pickers.

```json
[
  { "id": 1, "name": "Main Store", "kind": "PRIMARY",   "sortOrder": 10 },
  { "id": 2, "name": "Kitchen",    "kind": "SECONDARY", "sortOrder": 20 },
  { "id": 7, "name": "Coffee Station", "kind": "SECONDARY", "sortOrder": 30 }
]
```

New: `kind`, `sortOrder`, the order, and inactive locations left out by default.

- **ISSUE request picker**: offer only `kind = SECONDARY`; the primary is always the supplier.
- **Waste and stock take pickers**: offer all.

### `GET /stock-takes[?locationId=&status=]`

Stock takes, filtered on the server; both parameters optional and combinable (`status` is `SUBMITTED`, `APPROVED`,
`REJECTED` or `CANCELLED`). A STOCK_CLERK sees only their own. Each stock take is for one location (`locationId`,
`locationName`); its lines' expected quantity and variance are at that location. For a location filter, fill the
picker from `GET /locations?includeInactive=true` so past counts at a deactivated location can still be found.

Of the history lists, only this one takes a location: `GET /admin/waste` filters by ingredient and dates, and
`GET /stock-requests` by status and type.

## 4. Admin (`ADMIN` only; everyone else 403)

### 4.1 `GET /admin/locations`

All locations, active and inactive, in display order.

```json
[
  {
    "id": 2, "name": "Kitchen", "kind": "SECONDARY", "active": true, "sortOrder": 20,
    "stockSummary": { "ingredientsWithStock": 41, "ingredientsNegative": 2 },
    "assignedTo": ["PRODUCTION", "STATION_BAR", "STATION_KITCHEN", "STATION_NONE"],
    "createdAt": null, "createdByName": null,
    "updatedAt": "2026-11-21T08:10:00Z", "updatedByName": "Site Admin"
  }
]
```

- `stockSummary` counts ingredients whose ledger figure at the location isn't zero; `ingredientsNegative` is how
  many of those are below zero. (Counts, because units differ.)
- `assignedTo` is empty for the primary. `createdAt` / `createdByName` are null for the two seeded locations.

### 4.2 `POST /admin/locations` → 201

```json
{ "name": "Coffee Station", "sortOrder": 30 }
```

Creates a SECONDARY, active. Without `sortOrder` it goes last (highest + 10). Refusals: `LOCATION_INVALID`,
`LOCATION_NAME_TAKEN`, `LOCATION_LIMIT_REACHED`. Returns the location as in 4.1.

### 4.3 `PUT /admin/locations/{id}`

```json
{ "name": "Central Store", "sortOrder": 10 }
```

Renames and, when `sortOrder` is sent, reorders. Works for the primary. The name check runs only while the location
is active (activating checks it again). Same values again: 200, nothing recorded.

### 4.4 `POST /admin/locations/{id}/deactivate`

Already inactive: 200, unchanged. Otherwise refused with **409 `LOCATION_DEACTIVATE_REFUSED`** when any of these
holds; every one that holds is listed in `details.reasons`, in this order:

| Reason | Means | Extra details |
|---|---|---|
| `PRIMARY` | it's the primary | — |
| `HAS_STOCK` | any ingredient's figure there isn't zero (negative counts too) | `ingredientsWithStock`, `stock: [{ ingredientId, name, unit, quantity }]` (largest first, at most 50) |
| `ASSIGNED` | production or a station uses it | `assignedTo: ["PRODUCTION", "STATION_KITCHEN", ...]` |
| `OPEN_STOCK_REQUESTS` | requests at it are REQUESTED or PARTIALLY_ACTIONED | `openStockRequestIds` |
| `OPEN_STOCK_TAKES` | stock takes at it are SUBMITTED | `openStockTakeIds` |

```json
{
  "error": "Coffee Station can't be deactivated: it still has stock on hand; it has open stock requests.",
  "code": "LOCATION_DEACTIVATE_REFUSED",
  "details": {
    "locationId": 7,
    "reasons": ["HAS_STOCK", "OPEN_STOCK_REQUESTS"],
    "ingredientsWithStock": 1,
    "stock": [{ "ingredientId": 31, "name": "Coffee beans", "unit": "KG", "quantity": 2.5000 }],
    "openStockRequestIds": [118]
  }
}
```

To clear stock: waste it, or count it to zero with a stock take. (Moving it to another location is IBT, later.)
A deactivated location disappears from the pickers; its history (waste, stock takes, requests, movements) keeps
its name.

### 4.5 `POST /admin/locations/{id}/activate`

Already active: 200. Refusals: `LOCATION_NAME_TAKEN` (another active location took the name meanwhile),
`LOCATION_LIMIT_REACHED`.

### 4.6 `GET /admin/locations/assignments`

```json
{
  "primary":    { "id": 1, "name": "Main Store" },
  "production": { "id": 2, "name": "Kitchen" },
  "stations": {
    "KITCHEN": { "id": 2, "name": "Kitchen" },
    "BAR":     { "id": 2, "name": "Kitchen" },
    "NONE":    { "id": 2, "name": "Kitchen" }
  }
}
```

`primary` is for display; it isn't an assignment.

### 4.7 `PUT /admin/locations/assignments`

```json
{ "productionLocationId": 2, "stations": { "BAR": 7 } }
```

Each part is optional; what is sent is replaced, the rest stays. Each location must be an active SECONDARY:
`ASSIGNMENT_NOT_SECONDARY`, `LOCATION_INACTIVE`, `LOCATION_NOT_FOUND`. An empty body is `LOCATION_INVALID`.
Returns the assignments as in 4.6. Takes effect for the next sale, planning confirmation or sync; nothing already
recorded moves.

### 4.8 `GET /admin/locations/history[?locationId=&page=0&size=50]`

Newest first; `size` at most 200.

```json
{
  "content": [
    {
      "id": 12, "locationId": 7, "action": "ASSIGN", "role": "STATION_BAR", "previousLocationId": 2,
      "before": null, "after": { "name": "Coffee Station", "sortOrder": 30, "active": true },
      "changedAt": "2026-11-21T09:02:11Z", "changedBy": 10010, "changedByName": "Site Admin"
    },
    {
      "id": 11, "locationId": 7, "action": "UPDATE", "role": null, "previousLocationId": null,
      "before": { "name": "Coffee Bar", "sortOrder": 30, "active": true },
      "after":  { "name": "Coffee Station", "sortOrder": 30, "active": true },
      "changedAt": "2026-11-21T09:01:40Z", "changedBy": 10010, "changedByName": "Site Admin"
    }
  ],
  "page": 0, "size": 50, "totalElements": 2, "totalPages": 1
}
```

Actions: `CREATE`, `UPDATE` (name or order), `DEACTIVATE`, `ACTIVATE`, `ASSIGN` (one row per role changed, on the
location that now has it; `previousLocationId` is the one it replaced). `before` is null for CREATE and ASSIGN.

### 4.9 The maximum

`stock.locations.max-active` is an ordinary site setting (`GET/PUT /admin/settings`, group `STOCK`, not sensitive),
with its own history there.

## 5. Changes to existing responses

All additions; no field was removed or renamed.

| Endpoint | Change |
|---|---|
| `GET /locations` | `kind`, `sortOrder`; active only unless `includeInactive=true`; display order |
| `POST/PUT/GET /stock-requests` | ISSUE: `locationId` accepted (the requesting secondary); `locationId`/`locationName` now filled for ISSUE (were null) |
| `GET /admin/daily-planning/{date}/ingredient-requirements` | `primaryLocation`, `productionLocation` (`{id, name, kind, sortOrder}`). `currentStock` is at the primary, `kitchenStock` at production |
| `POST /admin/daily-planning/{date}/confirm-ingredient-requirements` | No new fields. Each shortfall's `resultingStock` is the **primary's** stock minus `finalQuantity`: what the issue sheet can't be filled from. Label shortfalls with `primaryLocation.name` from the requirements load |
| `GET /admin/meal-catalog/{id}/stock-review` | `sourceLocationId`, `sourceLocationName` (null when not tracked). `kitchenAvailable` is at that source, `mainStoreAvailable` at the primary |
| `GET /admin/ingredients/{id}/stock` | `byLocation[]` gains `kind` and `active`; display order; lists active locations plus any inactive one with non-zero stock |
| Error message on approving an ISSUE past stock | `exceeds available <primary name> stock.` (unchanged text with the default name) |

The field names `kitchenStock`, `kitchenAvailable` and `mainStoreAvailable` are kept so existing screens keep
working; label them with the location names the responses now carry.
