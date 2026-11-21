# T0-5d stock locations screens: frontend prompt

Prompt for the frontend work that builds on T0-5c (backend branch `feature/infrastructureUpdatesBE`, V115).
Contract: `docs/2026-11-21-t0-5c-locations-api-contract.md`. Model: `docs/2026-11-21-stock-locations.md`.

```text
ROLE AND RULES
You are implementing T0-5d (stock locations screens) in the canteen POS frontend. Build against
docs/2026-11-21-t0-5c-locations-api-contract.md (I will give it to you). Background:
docs/2026-11-21-stock-locations.md.

Working rules (mandatory):
1. PLAN FIRST: files to create/modify, screens, behaviour, tests and risks. Then STOP and wait for approval.
2. Show the diff before modifying any existing file and wait for confirmation.
3. Branch `feature/t0-5d-locations-screens` from main. Commit per step; do not push or merge.
4. New docs named YYYY-MM-DD-descriptive-name.md. Summarise after each step; list every file created or
   modified at the end.
5. Branch on error codes (and details.reasons) only, never on message text. Where the API returns a message
   without a code, show the message as it is. List anything missing from the contract as a question.

THE MODEL (from the contract)
- One PRIMARY location (today "Main Store"): receives every GRV, supplies every issue. Renamable, never deactivated.
- Any number of SECONDARY locations (today "Kitchen", plus new ones). They request stock from the primary.
- Assignments: the production location, and the source location for each sales station (KITCHEN, BAR, NONE).
  Each must be an active secondary. All default to Kitchen.
- Today only light meals deduct stock at sale, and light meals are always prepared in the kitchen, so only the
  KITCHEN station's source takes sale deductions. BAR and NONE are stored for when bar items are stock-tracked.

TASKS
1. Admin → Stock locations (ADMIN only; GET/POST/PUT /admin/locations…)
   - A list in sort order: name, Primary or Secondary, Active or Inactive, the stock-on-hand summary
     (stockSummary: "{ingredientsWithStock} ingredients in stock", and "{ingredientsNegative} below zero" when
     > 0), and what it is assigned to (assignedTo).
   - Add a secondary location (name required, at most 50 characters); rename any location, the primary included;
     deactivate and activate.
   - Reorder: there is no bulk endpoint. Moving a row is a PUT /admin/locations/{id} with its name and a new
     sortOrder (0–9999); moving up/down swaps two rows (two PUTs). Reload the list after, and say so if the
     second PUT fails.
   - Create/rename/activate refusals: LOCATION_INVALID (show details.fields next to the field),
     LOCATION_NAME_TAKEN ("Another active location is already called {details.name}.").
   - Deactivate: one code, LOCATION_DEACTIVATE_REFUSED, with every reason in details.reasons. Show all of them,
     in plain words:
       PRIMARY              → "It's the primary location: every delivery is received here."
       HAS_STOCK            → "This location still holds stock: {details.ingredientsWithStock} ingredients"
                              and the lines from details.stock (name, quantity, unit; at most 50 are sent).
                              Say how to clear it: waste it, or count it to zero with a stock take.
       ASSIGNED             → one line per details.assignedTo:
                              PRODUCTION "It's the production location", STATION_KITCHEN "It supplies the
                              kitchen station", STATION_BAR "It supplies the bar station", STATION_NONE "It
                              supplies items handed over at the till". Link to the assignments section.
       OPEN_STOCK_REQUESTS  → "It has open stock requests" (details.openStockRequestIds, linked).
       OPEN_STOCK_TAKES     → "It has stock takes waiting for review" (details.openStockTakeIds, linked).
   - The maximum: LOCATION_LIMIT_REACHED (create or activate) → "This site can have at most
     {details.maxActive} active locations, the primary included. Deactivate one first." The limit is the site
     setting stock.locations.max-active (group STOCK in Admin → Settings).
   - A "Changes" panel from GET /admin/locations/history (newest first, paged; per location with ?locationId=):
     who, when, and what changed (created, renamed/reordered, deactivated, activated, assigned to a job).

2. Assignments (same screen, a section; GET/PUT /admin/locations/assignments)
   - The production location: "Where the daily planning issue sheet sends ingredients (from {primary name})."
   - The source location for each sales station: Kitchen, Bar, Handed over at the till. Explanation: "Sales at
     this station take their stock from here." Under Bar and Handed over at the till add: "Bar items and items
     handed over at the till don't use stock yet; this applies once they do."
   - Only active secondary locations can be chosen (from GET /admin/locations, kind SECONDARY and active).
   - Save sends only what changed. Errors: ASSIGNMENT_NOT_SECONDARY, LOCATION_INACTIVE, LOCATION_NOT_FOUND
     (reload the list and ask to choose again), LOCATION_INVALID (nothing to save).

3. Stock screens (non-admin roles use GET /locations: active locations in sort order, with kind)
   - GRV: shows "Received into {name of the PRIMARY from GET /locations}"; no location choice.
   - Stock requests:
     - ISSUE: the requesting location is chosen from active SECONDARY locations; the supplier is always shown
       as the primary ("From {primary} to {chosen}"). No other combinations offered. Codes:
       ISSUE_LOCATION_NOT_SECONDARY, LOCATION_INACTIVE, LOCATION_NOT_FOUND. Lists and details show
       locationName for ISSUE too (it was empty before).
     - WASTE: a picker of all active locations (primary included), as today.
     - ORDER: no location.
     - Approving an ISSUE past the primary's stock returns a 400 message naming the primary; show it as is.
   - Waste and stock take: a location picker of all active locations. Their errors are messages without a code.
   - Stock views: GET /admin/ingredients/{id}/stock → byLocation lists every active location in sort order, plus a
     deactivated one only while it still holds stock (mark it "inactive"); mark the primary. Show all of them,
     however many.
   - Variance: a stock take is for one location; show its locationName and its expected/actual/variance. History
     filters by location use GET /locations?includeInactive=true.
   - Daily planning ingredient requirements: label currentStock with primaryLocation.name and kitchenStock with
     productionLocation.name.
   - Light-meal stock review: label kitchenAvailable with sourceLocationName and mainStoreAvailable with the
     primary's name.

4. Remove every hard-coded "Main Store" or "Kitchen" used as a location (labels, filters, defaults, column
   headers, test fixtures that assume ids 1 and 2). Use names and kinds from the API. List each place. ("Kitchen"
   as a station or display name, e.g. the kitchen display screen, stays.)

TESTS
- Locations: list (with summary and assignedTo), add, rename, reorder (both PUTs; the second failing),
  activate, the maximum, and a deactivate refusal for each reason, including several reasons at once.
- Assignments: only active secondaries offered; save; each error code.
- GRV shows the primary; ISSUE requests offer only secondaries and show the primary as supplier; WASTE requests,
  waste and stock take pickers offer all active locations.
- Three locations shown on the ingredient stock view; an inactive one with stock is marked.
- Planning and light-meal review labels come from the API.
- No hard-coded location names left (a test or a documented search).
- The screen and its menu entry are hidden for non-ADMIN (STOCK_ADMIN gets 403 from /admin/locations).

QUESTIONS ALREADY KNOWN (answer in your plan)
- Default requesting location for an ISSUE: non-admins can't read the assignments (ADMIN only). Proposal:
  preselect when there is exactly one active secondary, otherwise require a choice. (If the field is left out the
  server uses the production location.)
- There is no stock list across all ingredients by location; per-location figures exist per ingredient and per
  stock take. Confirm the screens that need more.

FIRST REPLY: the plan only.
```

## Open point

Rule 3 branches from the frontend's `main`, while the T0-5c endpoints exist only on the backend branch
`feature/infrastructureUpdatesBE`. Change it if the frontend should follow the same infrastructure-branch pattern.
