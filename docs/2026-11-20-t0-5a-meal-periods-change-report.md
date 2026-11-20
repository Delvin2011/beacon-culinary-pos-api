# T0-5a editable meal periods: change report

Branch `feature/t0-5a-meal-periods-locations`, from `feature/t0-4a-site-settings` at `d8ef2d4`.
Not pushed or merged.

Before T0-5a, meal periods (Breakfast, Lunch, All Day) came from migrations V17/V28 and the API was
read-only. Now an admin creates, edits, deactivates and activates them in the app, so a new
customer can set its own (multi-tenancy readiness analysis, 19 Nov 2026, sections A.1, C.8 and
H.20). With no change made, behaviour is as before.

- Contract (the frontend builds against it): `docs/2026-11-20-t0-5-meal-periods-locations-api-contract.md`
- **Part 1, locations found by role, is postponed.** "Main Store" and "Kitchen" are still found by
  name in code.

## 1. Summary

| Area | Result |
|---|---|
| Admin API | `GET/POST /admin/meal-periods`, `PUT /admin/meal-periods/{id}`, `POST …/{id}/deactivate` and `/activate`, `GET /admin/meal-periods/history`. ADMIN only. |
| Public API | `GET /meal-periods` keeps its shape; lists active periods only, in `sortOrder`. |
| Storage | V114: `meal_periods.active`, `sort_order`, constraints; insert-only `meal_period_changes` |
| Rules | Unique active name; `HH:mm`, no overnight; no overlap (touching allowed); All Day protected; no deactivation while items are dated today or later |
| Effects | A change applies at once to the menu, ordering and planning; offline tills at their next snapshot; offline `PERIOD_CLOSED` uses the times at `soldAt` |
| Tests | Full suite: 1118 tests, 0 failures, 0 errors, 1 skipped (section 8) |

## 2. Commits

| Step | Commit | What |
|---|---|---|
| 1 | `a258eba` | API contract |
| 2 | `8afb03b` | V114 migration |
| 3 | `5ddee83` | `active` and `sortOrder`; inactive periods never open, not listed or offered, no new plan items; name lookups prefer the active period |
| 4 | `f27ecdf` | Admin API, rules, lock, history |
| 5 | `330a17f` | Offline `PERIOD_CLOSED` at the times in force at `soldAt` |
| 6 | `234c357` | Effects through the menu, ordering, the snapshot and the reports |
| 7 | see `git log` | Docs: this report, notes in older docs |

## 3. Decisions

| | Decision | Why |
|---|---|---|
| A | Branch from `feature/t0-4a-site-settings`, migration V114 | `main` (local, and `origin/main`) lacks T0-1/2/3 and T0-4a. The shared test database already has V112/V113; a branch without them fails Flyway's validation, and V112/V113 would arrive after V114 when merged (out of order). Merge order: `t0-123-Merge` → T0-4a → T0-5a. |
| B | Reports keep a sale's period **by id** and show its **current** name | Reports find the period through the daily option, by id. Snapshotting names would mean changing the report views. A rename relabels past reports; the history records it. |
| C | Offline `PERIOD_CLOSED` uses the period's times **at `soldAt`** | As T0-4a does for VAT: a time change made after a till's snapshot no longer flags sales that were valid when made. |
| D | Deactivation is refused while any option or extra is dated today or later | Never breaks today's trading and leaves no planned item unsellable; the admin moves or deletes planned items first. |
| — | Own history table, not T0-4a's | `site_setting_changes` is keyed by setting key and checked against the settings registry; periods need before and after values per field. Same insert-only pattern. |

## 4. Migration `V114__t0_5a_meal_periods.sql`

1. `meal_periods.active` (BIT, default 1) and `sort_order` (INT): Breakfast 10, Lunch 20, All Day 30
   (today's order).
2. Existing data: an All Day row's leftover times are cleared (never read). Any other row that breaks
   a rule (timed without times or start ≥ end, a duplicate name, two All Day rows) **stops the
   migration** with a message naming it, rather than being changed silently.
3. Constraints: `CK_meal_periods_times` (All Day has no times; a timed period starts before it ends);
   `UX_meal_periods_active_name` (unique among active; the column's collation ignores case);
   `UX_meal_periods_one_all_day`.
4. `meal_period_changes`: action (`CREATE`, `UPDATE`, `DEACTIVATE`, `ACTIVATE`), old and new name,
   times, sort order and active, who and when; "before" required except for CREATE; index per
   period, newest first; trigger `trg_meal_period_changes_insert_only`.

Overlap can't be a constraint; the service checks it under `sp_getapplock 'meal_periods'`. User
columns have no foreign key (users are hard-deleted), as in V105 and V113. Applied to the test
database only; no applied migration was edited.

## 5. Rules

- **Fields (400 field map, every bad field at once):** name required, trimmed, at most 50
  characters; times `HH:mm` (or `HH:mm:00`); end after start (no overnight periods in v1); sort
  order 0–9999, optional (default: last + 10); a non-object body is `{"body": …}`.
- **Name:** unique among active periods, ignoring case (400 on `name`). An inactive period may share
  a name; activating it checks again.
- **Overlap (409 `PERIOD_OVERLAP`):** active timed periods may not share any time; touching ends are
  allowed (10:00–12:00 and 12:00–14:30). Details name the other period.
- **All Day (409 `ALL_DAY_PROTECTED`):** no times; never deactivated (light meals are created under
  it every day). It can be renamed and reordered. The API can't create a second one or change
  `accepts`.
- **Deactivate (409 `PERIOD_IN_USE`):** refused while options or extras are dated today (the site's
  trading day) or later; details give the counts and the first date.
- **Planning (400 `MEAL_PERIOD_INACTIVE`):** no new item under an inactive period, and no item moved
  to one; an item already under it keeps it.
- **Order of checks:** 404 → `ALL_DAY_PROTECTED` → field map → under the lock: name, overlap, in use.
- **History:** every change records the values before and after; an unchanged request, or a repeated
  deactivate or activate, records nothing.

## 6. Behaviour changes

| Change | Before | After |
|---|---|---|
| `GET /meal-periods` | Every period, by id | Active periods only, by `sortOrder` then id (same order until a period is added or reordered). Same fields. |
| "Active now" | Times only | Also false while the period is inactive (menu/today, ordering, offline check) |
| Planning | Any period | `MEAL_PERIOD_INACTIVE` for an inactive period. Extras: the period is now checked before the component, so a request with both ids wrong reports the period. |
| Name lookups (`?period=`, daily plan, ingredient requirements) | `findByNameIgnoreCase` (would throw with two rows of one name) | The active period of that name, else the newest inactive one |
| Offline snapshot `periods` | Every period | Active periods only (still in start-time order) |
| Offline `PERIOD_CLOSED` | The period's current times | Its times at `soldAt`, from `meal_period_changes` (with no history: the current times, as before) |
| Reports and dashboard | Period names as seeded | The current name; a rename relabels past figures (they stay under the same period id) |

## 7. Files

**Created**

- `docs/2026-11-20-t0-5-meal-periods-locations-api-contract.md`, this report
- `src/main/resources/db/migration/V114__t0_5a_meal_periods.sql`
- `src/main/java/com/beaconculinary/api/menu/`: `MealPeriodAdminController`,
  `MealPeriodAdminService`, `MealPeriodRules`, `MealPeriodChangeStore`, `MealPeriodHistory`,
  `MealPeriodAdminDtos`, `MealPeriodValidationException`, `MealPeriodRuleException`,
  `MealPeriodExceptionHandler`
- Tests (`menu/`): `MealPeriodFixture`, `MealPeriodTest`, `MealPeriodSchemaIntegrationTests`,
  `MealPeriodActiveIntegrationTests`, `MealPeriodRulesTest`, `MealPeriodAdminIntegrationTests`,
  `MealPeriodHistoryIntegrationTests`, `MealPeriodEffectsIntegrationTests`

**Modified (main code)**

- `menu/MealPeriod`: `active`, `sortOrder`; the open rule in one place (`isOpen`)
- `menu/MealPeriodRepository`: active list in order; name lookup preferring the active period
- `menu/MealPeriodService`: public list active only, sorted
- `menu/DailyPlanningService`: `MEAL_PERIOD_INACTIVE`; name lookup
- `inventory/DailyPlanningIngredientService`: name lookup
- `offline/OfflineSnapshotService`: active periods only
- `offline/sync/OfflineSaleRecorder`: `PERIOD_CLOSED` at `soldAt`

**Modified (tests)**

- `offline/O4OfflineSyncIntegrationTests`: one test (`PERIOD_CLOSED` at `soldAt`); meal periods
  restored around each test

**Modified (docs, step 7)**: the O3 contract (`periods` active only), the O4 contract
(`PERIOD_CLOSED`), the go-live checklist (item 15: meal periods at onboarding), the README, the
contract's status line.

## 8. Tests

| Test | What it covers |
|---|---|
| `MealPeriodTest` (unit) | Open from start to end inclusive; All Day always; inactive never |
| `MealPeriodRulesTest` (unit) | Field messages, every bad field at once, no overnight, All Day without times, overlap (touching allowed; inactive and All Day never overlap) |
| `MealPeriodSchemaIntegrationTests` | V114: seeded sort orders, each constraint, the insert-only trigger, "before" required except on CREATE |
| `MealPeriodActiveIntegrationTests` | Public list (active only, sorted, same shape), `MEAL_PERIOD_INACTIVE`, name lookup preference, snapshot |
| `MealPeriodAdminIntegrationTests` | Every endpoint and rule: ADMIN only, create, update, unchanged = no record, All Day, in use, activate rechecks, history paging |
| `MealPeriodHistoryIntegrationTests` | Values at a time (last change at or before; else the "before" of the first later change; a later CREATE; same-instant changes by id); `wasOpenAt` |
| `MealPeriodEffectsIntegrationTests` | Through the admin API: a time change applies at once to menu/today, ordering and the snapshot; a selling period can't be deactivated; a deactivated period leaves the lists, menu and snapshot; All Day protected; past sales keep their period in reports under the current name |
| `O4OfflineSyncIntegrationTests` (added) | `PERIOD_CLOSED` uses the times at `soldAt` |

**Full suite:** 1118 tests, 0 failures, 0 errors, 1 skipped. The skip is the existing `StaffManagementIntegrationTests`
assumption (another active admin in the shared test database), as in T0-4a.

## 9. Risks and follow-ups

- **Period names in the frontend:** `GET /menu/today?period=`, the daily plan and the ingredient
  requirements take a period **name**. A frontend that hard-codes "Lunch" breaks on a rename; it must
  take names from `GET /meal-periods` (contract section 8). Moving those endpoints to ids is a later
  change.
- **Renames relabel the past:** reports and the dashboard show the current name; the dashboard's
  sales-mix group key is derived from it.
- **Shortening a period during service** stops selling its items at once (by design; the contract
  asks the screen to warn).
- **Tills already offline** keep their old periods until the next snapshot; their sales are checked
  against the times then.
- **Readiness analysis:** it lives on `docs/multi-tenancy-analysis`, not on this branch, so it is not
  edited here. For meal periods, A.1 ("no write API") and H.20 step 2 are done; C.8 (meal periods as
  required seed) now means "seeded defaults the customer edits".
- **Part 1 (locations found by role)** is still open; go-live checklist item 15 notes it.
- **Merging:** depends on `t0-123-Merge` and `feature/t0-4a-site-settings`.
