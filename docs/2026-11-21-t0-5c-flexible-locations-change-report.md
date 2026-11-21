# T0-5c flexible stock locations: change report

Branch `feature/infrastructureUpdatesBE` (built on the T0-5a work, as agreed; not branched from main). Migration
V115. Contract: `docs/2026-11-21-t0-5c-locations-api-contract.md`. Model: `docs/2026-11-21-stock-locations.md`.

## What changed

Before T0-5c, five services found "Main Store" and "Kitchen" by name constants (GrvService, StockRequestService,
DailyPlanningIngredientService, LightMealStockService, LegacyStockTakeService), seeded by V60. Now:

- **One primary location** (Main Store) and any number of **secondary** locations, managed by admins at
  `/admin/locations`. The database keeps exactly one active primary.
- **Assignments** for the production location and each station's sale-time source, all Kitchen by default.
- Every flow asks `StockLocations` for its location by job: GRV and the supplying side of issues use the primary;
  planning uses production; light-meal sales and voids use the line's station's source (online and O4 offline);
  waste, stock take and the requesting side of issues use the location the user chose.
- ISSUE requests now name their requesting secondary (omitted: production, as before). The primary can't request.
- A site setting `stock.locations.max-active` (default 10) caps active locations.
- Every location change is recorded in `location_changes` (insert-only).

With only the two seeded locations, behaviour is unchanged; responses gain fields (contract section 5).

## Steps and commits

| Step | Commit | What |
|---|---|---|
| 1 | `7868ff9` | V115: kinds, sort order, audit, one-primary constraints, assignments, history, ISSUE backfill |
| 2 | `fdf118a` | StockLocations resolver; GRV, planning, legacy stock take by job |
| 3 | `710069f` | ISSUE requests: primary → chosen secondary; coded refusals |
| 4 | `2cb386e` | Light-meal stock per station source, online and offline; STOCK_AFTER_COUNT per location |
| 5 | `efa918c` | `/admin/locations`, assignments, history; `stock.locations.max-active` |
| 6 | `8679e58` | Pickers active-only with kind and order; ingredient stock view for N locations |
| 7 | `26597a7` | Tests |
| 8 | (this) | Docs |

## Decisions taken during the work

1. **Production location** = where daily planning's issue sheet sends ingredients (planning never deducts stock
   itself; it raises an ISSUE).
2. **ISSUE without `locationId`** goes to the production location, so the current frontend keeps working; an edit
   without one keeps the request's current location.
3. **Assignments in their own table** (`location_assignments`), not T0-4a site settings: settings take defaults from
   configuration and have no foreign keys, and a location id differs per database. The maximum *is* a site setting.
4. **Offline sales** deduct from the station's source as assigned at sync time.
5. **Legacy stock take** counts at the primary.
6. **Deactivation refusals** list every reason at once (`details.reasons`).
7. **Bar station tests.** Only light meals deduct stock, and light meals are always at the KITCHEN station
   (`MealCatalogService.resolveStation`), so no real sale deducts from the BAR source today. Agreed: the third
   location is tested as the KITCHEN station's source (online and offline), and the BAR mapping at the service.

## Found, not changed

- Set-meal ingredients are issued to the production location and never deducted there at sale; stock takes correct
  it. `deductForSale`'s comment no longer says they "deducted at planning confirmation"; the class Javadoc of
  LightMealStockService still says set meals "deduct at planning confirmation" (left as is).
- The readiness analysis (19 Nov 2026) isn't in the repository; the work followed the prompt and the code.

## Tests

- New: `FlexibleLocationsIntegrationTests` (16), one offline test in `O4OfflineSyncIntegrationTests`, and
  `support/LocationFixture` (puts locations back before and after; locations can't be deleted once they have
  history, so test-created ones are deactivated).
- Adjusted: the three settings tests that count the registry (15 → 16).
- Full suite: **1136 tests, 0 failures, 0 errors, 1 skipped** (`StaffManagementIntegrationTests`, skipped before
  T0-5c too). The T0-2 golden outputs are unchanged.

## Files

**Created**
- `src/main/resources/db/migration/V115__t0_5c_flexible_locations.sql`
- `src/main/java/com/beaconculinary/api/inventory/`: `LocationKind`, `LocationRole`, `LocationAssignment`,
  `LocationAssignmentRepository`, `StockLocations`, `LocationRuleException`, `LocationAdminController`,
  `LocationAdminService`, `LocationAdminDtos`, `LocationChangeStore`
- `src/test/java/com/beaconculinary/api/inventory/FlexibleLocationsIntegrationTests.java`
- `src/test/java/com/beaconculinary/api/support/LocationFixture.java`
- `docs/2026-11-21-t0-5c-locations-api-contract.md`, `docs/2026-11-21-stock-locations.md`, this report

**Modified**
- `inventory/`: `Location`, `LocationRepository`, `LocationDto`, `LocationStockDto`, `LocationController`,
  `GrvService`, `LegacyStockTakeService`, `DailyPlanningIngredientService`, `IngredientRequirementsResponseDto`,
  `StockRequestService`, `StockRequest`, `CreateStockRequestRequest`, `LightMealStockService`,
  `MealStockReviewDto`, `IngredientService`, `WasteService`, `StockTakeService`
- `offline/sync/OfflineSaleRecorder`
- `common/GlobalExceptionHandler`
- `settings/`: `SiteSetting`, `SiteSettingDefaults`, `SiteSettings`
- Tests: `SiteSettingRegistryTest`, `SiteSettingsAdminIntegrationTests`, `SiteSettingsCacheTest`,
  `O4OfflineSyncIntegrationTests`
- Docs: `README.md`, `docs/2026-11-11-go-live-checklist.md`, `docs/2026-11-19-t0-4-site-settings-api-contract.md`,
  `docs/2026-11-20-site-settings.md`, `docs/2026-11-20-t0-5-meal-periods-locations-api-contract.md`
