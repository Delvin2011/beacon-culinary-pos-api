# Cross-customer tests (T1-10)

One named suite proves that no customer can see, change or receive anything of another's. It runs on every release:
check-all's first step, and again inside the full suite.

## 1. The rule

**Every new feature that stores, caches, streams or links anything adds its row to the map below and its test to the
suite, in the same change.** That means a table or column, an in-memory cache or counter, a stream, a scheduled job, a link
the backend builds, or anything kept outside the customer database (files, temp storage, the platform database). A
row that can't apply says so, with the reason.

How to write one:

- **Multi mode only.** Extend `support/tenancy/MultiCustomerIntegrationTest` (customers alpha and bravo, at
  `alpha.pos.localhost` and `bravo.pos.localhost`); the tag comes with it. A test that can't extend it (its own
  throwaway customers, or a plain unit test run as each customer with `CustomerContext.runAs`) carries
  `@Tag(MultiCustomerIntegrationTest.TAG)` itself.
- **Make a leak visible.** Both customers start from the same baseline, so their ids line up: the first shift, order
  and user have the same id at both. Use the same names at both (emails, cashier names, till names, item names,
  client order ids) with different values. Asking for alpha's id at bravo must give bravo's own record or a 404,
  never alpha's.
- **In-memory state is keyed by customer.** Anything kept in memory across requests (a cache, a counter, a "logged
  recently" set, a stream registry) uses `CustomerContext.scopeKey()` in its key, and its test runs the same key as
  alpha and then as bravo.
- **Stand alone.** Make your own rows under a unique marker and put back any shared row you change, so the test passes
  when run on its own.

## 2. Running it

| What | Command |
|---|---|
| The suite alone | `./mvnw test -Dgroups=cross-customer` (add `-DfailIfNoTests=true` so a lost tag can't pass) |
| One class | `./mvnw test -Dtest=CrossCustomerAreasIntegrationTests` |
| Everything | `devstack/scripts/check-all.ps1`: step 0 "cross-customer tests" (with its count), then the full suite, the frontend, the local stack's smoke check and the frontend's stack e2e (below) |

The suite shares the test database with the full suite: run one at a time. It ran 60 tests before T1-10 and 81 after.

## 3. The coverage map

Test classes (all in `src/test/java/com/beaconculinary/api/`):

| Code | Class |
|---|---|
| AREA | `tenancy/crosscustomer/CrossCustomerAreasIntegrationTests` |
| E2E | `tenancy/crosscustomer/CrossCustomerScenariosIntegrationTests` |
| LOGS | `tenancy/crosscustomer/CrossCustomerDeviceLogLinesTest` |
| BURST | `auth/protection/LoginBurstFilterTest#enforce_eachCustomerHasItsOwnBucket_forTheSameAddress` |
| ISO | `tenancy/MultiCustomerIsolationIntegrationTests` |
| FILT | `tenancy/web/CustomerFilterOrderIntegrationTests` |
| TOK | `tenancy/web/CustomerBoundTokensIntegrationTests` |
| OFF | `tenancy/web/MultiCustomerOfflineSyncIntegrationTests` |
| SUS | `tenancy/web/SuspendAndRecoverIntegrationTests` |
| ADDR | `tenancy/web/CustomerAddressIntegrationTests` |
| JOBS | `tenancy/CustomerJobsIntegrationTests` |
| MIG | `ops/MigrateCustomersCommandIntegrationTests` |
| BEH | `tenancy/web/CustomerSchemaBehindIntegrationTests` |
| POOL | `tenancy/routing/CustomerPoolsIntegrationTests` |
| BRAND | `tenancy/web/CustomerBrandingIntegrationTests` |
| LINK | `tenancy/web/CustomerLinksIntegrationTests` |

Two columns are answered once for every area:

- **Outside the customer database:** AREA `platformDatabase_holdsOnlyTheDirectory_whateverTheCustomersDo`. After both
  customers trade, the platform database still has only its five tables (the directory, provisioning runs and
  Flyway's history), and their rows are unchanged. The backend writes no files or temp storage: `src/main` has no
  file-writing code (checked for T1-10).
- **Log lines:** ADDR `anActiveCustomersRequest_carriesItsSlug_andNothingIsLeftOnTheThread` (every request line carries
  its own customer's `tenantId`). Areas with their own log state have their own entries.

"n/a" means the path can't apply, for the reason given.

| Area | Read | Write | List / search / export | Credential from the other | Cache or stream | Background job | Link |
|---|---|---|---|---|---|---|---|
| Password and PIN sign-in | AREA `signIn_alphasPasswordAndPin_meanNothingAtBravo_…` | FILT `signInAttempts_areRecordedInTheCustomersOwnDatabase` | AREA `loginTiles_listOnlyTheCustomersOwnCashiers` | TOK `anAccessTokenFromAlpha_isRefusedAtBravo_andWorksAtAlpha` | BURST (rate limit per customer) | JOBS `theRealJobs_workInEachCustomersDatabase` (retention) | n/a: none built |
| Refresh and sessions | FILT `aSessionStartedAtAlpha_livesInAlphasDatabase` | FILT (same) | n/a: no session list | TOK `aRefreshCookieFromAlpha_isRefusedAtBravo_andWorksAtAlpha`; smoke | n/a: not cached | JOBS `theRealJobs_…` (session sweep) | n/a |
| Devices (tills) | FILT `aTillRegisteredAtAlpha_isNotRecognisedAtBravo` | AREA `tills_withTheSameNameAndSerial_areEachCustomersOwn` | AREA (same: `/admin/devices`) | FILT (the till cookie); AREA (same) | LOGS `anUnboundTillRequest_…`, `repeatedDeviceEvents_…` (leaks L1, L2) | n/a: no device job | n/a |
| Shifts | AREA `shifts_closingAlphasLeavesBravosOpen_andNeitherReadsTheOther` | AREA (same) | AREA (same: `/admin/shifts`) | TOK | n/a: not cached | n/a: none | n/a |
| Sales, orders, payments, receipts | AREA `sales_theSameClientOrderId_isANewSaleAtEach_…`; OFF | AREA (same); E2E 1 | AREA (same: `/orders/today`, reprint); smoke (order by id) | TOK | FILT `aStreamOpenedAtAlpha_neverReceivesBravosEvents`; E2E 1 | n/a: none | E2E 1 (`Location` on alpha's address) |
| Manager PIN and authorisations | AREA `managerAuthorisation_alphasPinAndToken_meanNothingAtBravo` | ISO `requiresNew_adminAuthorization_consumesOnlyTheCurrentCustomersToken` | n/a: no list | AREA (same) | n/a: not cached | n/a | n/a |
| Menus, prices, meal periods (no PLU codes exist: an item is its name and price) | AREA `menu_theSameItem_hasEachCustomersOwnPrice_…` | ISO `requiresNewTransactionTemplate_alwaysOnMenu_…`; AREA (meal periods) | AREA (same: `/menu/today`, `/admin/meal-periods`) | TOK | n/a: not cached | n/a | n/a |
| Locations and stock requests | AREA `locationsAndStockRequests_areEachCustomersOwn` | AREA (same) | AREA (same) | TOK | n/a: not cached | n/a | n/a |
| Staff, roles, invites | AREA `staff_theSameEmail_isEachCustomersOwnPerson` | AREA (same) | AREA (same) | TOK `anInviteTokenFromAlpha_meansNothingAtBravo` | n/a: not cached | n/a | LINK `anInvite_linksToTheCustomersOwnPrimaryHost` |
| Site settings, receipt settings, branding | BRAND `eachAddress_getsItsOwnCustomersName_…` | AREA `settings_aChangeAtAlpha_isNotSeenAtBravo_evenOnceBravosAreCached` | n/a: single records | TOK | AREA (same: the settings snapshot) | n/a | n/a |
| Reports and dashboard | ISO `theDashboardsReadOnlyTemplate_readsTheCurrentCustomersDatabase`; E2E 1 (shift readings) | n/a: reports don't write (a Z is a customer's own close, its own database) | AREA `reportsAndDashboard_alphasSale_isNotInBravosFigures_evenFromTheCache` | TOK | AREA (same: the dashboard cache) | n/a: no job | n/a |
| Kitchen, bar, board, order streams | AREA `publicBoardAndBarStream_showOnlyTheCustomersOwn` | n/a: streams don't write | n/a | n/a: the board needs no sign-in; the others: TOK | FILT (kitchen); ADDR `aStream_isOpenedAsItsCustomer_…`; AREA (bar, board); E2E 1 | n/a | n/a |
| Offline grant, snapshot, sync | AREA `offlineSnapshot_hasEachCustomersOwnItemsAndPrices` | OFF `aSaleSyncedAtAlpha_landsOnlyInAlpha_…`; E2E 2 | n/a: the snapshot is the read | OFF `alphasGrant_fromBravosOwnTill_…`, `alphasTillAndGrant_atBravo_…` | n/a: not cached | n/a | n/a |
| Background jobs | JOBS `aJob_runsOncePerActiveCustomer_inThatCustomersContext` | JOBS `theRealJobs_…` | n/a | n/a: jobs take no requests | JOBS (skip and last-run state per customer) | JOBS `aSuspendedCustomer_isSkipped…`; `aFailureForOneCustomer_…_isLoggedWithItsSlug` (logs) | n/a |
| Database updates (T1-4) | BEH `aCustomerBehind_isRefused_…_andOthersAreUnaffected` | MIG `aFailureAtOneCustomer_doesNotStopTheNext_…` | n/a | n/a: run by an operator | POOL `aDatabaseBehindTheApp_…` | MIG (the command) | n/a |
| Suspend and reactivate | SUS `suspendingBravo_is503Suspended_…_andAlphaIsUnaffected`; E2E 3 | E2E 3 (nothing written while refused) | n/a | n/a | POOL `aSuspendedCustomer_hasItsPoolClosed_andIsRefused` | JOBS `aSuspendedCustomer_isSkipped…` | n/a |

End to end (E2E), each run as alpha while bravo works at the same time in its own thread:

1. `aDaysTradingAtAlpha_whileBravoTrades_eachSeesOnlyItsOwn`: sign-in, shift, sales, receipts, the kitchen stream and
   the shift reading.
2. `anOfflineSaleSyncedAtAlpha_whileBravoSyncs_eachLandsOnlyInItsOwnDatabase`: the same client order id at both.
3. `alphaSuspendedMidDay_isRefusedEverywhere_bravoUnaffected_andAlphaRecovers`.
4. `aFailedDatabaseUpdateAtAlpha_makesAlphaUnavailable_whileBravoIsServed`: alpha in the state
   `MigrateCustomersCommand` leaves after a failed update (`MIGRATION_FAILED`; MIG proves the command leaves it and
   no other customer). Running the command itself here would need a pending migration and migration logins, which
   the shared fixture doesn't have.

## 4. Through the proxy (smoke.ps1)

At beaconculinary and testcustomer through Caddy: each customer's own name from `/api/public/branding`; one
customer's access token (`TENANT_MISMATCH`) and, from T1-10, its refresh cookie refused at the other; a sale's kitchen
event never reaching the other customer's stream; from T1-10, the first customer's order asked for by id at the
second never being its own; an unknown address answering an empty 404.

**In a real browser (check-all steps 11 and 12).** check-all first checks that the stack serves the frontend commit
under test, then runs the frontend's `e2e/stack-cross-customer.spec.ts` against it in multi mode. Each address shows
its own name, kitchen, device storage and sessions, and an offline sale syncs only into its own customer. The
frontend's docs/2026-11-25-customer-responses.md, section 8, has the details. Offline selling is on in the stack for it.

## 5. Leaks found by T1-10

Both were log state kept in memory for all customers; no data was exposed. Each was fixed in its own commit, with its
test failing first.

| | Where | What | Fix |
|---|---|---|---|
| L1 | `devices/DeviceUnboundRequestFilter` | "Logged once per user per 5 minutes" was keyed by user id only: cashier 7 at alpha suppressed cashier 7's line at bravo | Keyed by customer and user |
| L2 | `devices/DeviceAlerts` | Repeated refusals and invalid till tokens were counted per address across customers: 3 at alpha and 2 at bravo raised bravo's alert with `count=5` | Counted per customer and address |
