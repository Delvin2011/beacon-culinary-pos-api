# T1-10 change report: the cross-customer test suite

Branch `feature/t1-10-cross-customer-tests`, from `feature/infrastructureUpdatesBE` (`7f3f8b5`). Not pushed or merged.
The suite, its coverage map and the rule for new features: docs/2026-11-27-cross-customer-tests.md.

## 1. What changed

- **One named suite.** Every multi-mode test is tagged `cross-customer` (on `MultiCustomerIntegrationTest`, inherited,
  and on the two multi-mode classes that don't extend it). `./mvnw test -Dgroups=cross-customer` runs only them;
  check-all runs them first as step 0 "cross-customer tests", with the count, and fails if none ran.
- **Gaps filled.** `CrossCustomerAreasIntegrationTests` (14), area by area, with the same names and ids at both
  customers; `CrossCustomerScenariosIntegrationTests` (4), end to end with bravo working at the same time;
  `CrossCustomerDeviceLogLinesTest` (2); the sign-in rate limit per customer (`LoginBurstFilterTest`, 1).
- **Two leaks fixed** (log state only; no data exposed): L1 and L2, below.
- **smoke.ps1** has two more cross-customer checks through the proxy (23 checks in multi mode, from 21).

## 2. Leaks found and fixed

| | Commit | Where | Before | After | Test (failed before, passes after) |
|---|---|---|---|---|---|
| L1 | `1fa3212` | `devices/DeviceUnboundRequestFilter` | "Logged once per user per 5 minutes" keyed by user id only: cashier 7 at alpha suppressed cashier 7's line at bravo | Keyed by customer and user (`CustomerContext.scopeKey()`) | `CrossCustomerDeviceLogLinesTest#anUnboundTillRequest_isLoggedAtEachCustomer_evenForTheSameUserId` (1 line, expected 2) |
| L2 | `ef715b0` | `devices/DeviceAlerts` | Refusals and invalid till tokens counted per address across customers: 3 at alpha and 2 at bravo raised bravo's alert with `count=5` | Counted per customer and address; the alert line is unchanged | `CrossCustomerDeviceLogLinesTest#repeatedDeviceEvents_fromOneAddress_areCountedAtEachCustomer` (two alerts, expected none) |

Every other piece of in-memory state was already per customer: the sign-in rate limit, the dashboard cache, the
settings snapshots, the streams, the health cache, the jobs' state and the customer pools.

## 3. Decisions

| | Decision |
|---|---|
| D1 | The tag is on the base class, so every multi-mode test (including future ones) is in the suite, a few not strictly about isolation among them (the app login's rights). |
| D2 | L1 and L2 are counted per customer (not per address across customers). |
| D3 | Surefire's own `-Dgroups=cross-customer`; no Maven profile, no `pom.xml` change. |
| - | L1, L2 and the rate limit are tested as plain unit tests run as alpha and then bravo: the shared multi-mode context has device trust off, and turning it on would cost a second Spring context. |
| - | Scenario 4 puts alpha in the state a failed update leaves (`MIGRATION_FAILED`) instead of running `MigrateCustomersCommand`: that needs a pending migration and migration logins, which the shared fixture doesn't have; `MigrateCustomersCommandIntegrationTests` proves the command leaves exactly that state, and no other customer. |
| - | smoke.ps1's second new check asks for an order by id at the other customer instead of comparing public boards: boards show only order numbers, which both customers number from 1. The AREA test compares whole boards. |
| - | There are no PLU codes in this system (`plu_code` is always NULL in a report view); the menu tests use the same item names with different prices. |

## 4. Tests

| | Count |
|---|---|
| Cross-customer suite before | 60 tests, 14 classes |
| Cross-customer suite after | 81 tests, 17 classes (plus one method of `LoginBurstFilterTest`) |
| Each new test run on its own | 21 of 21 pass (no order dependence) |

The scenarios: a day's trading at alpha while bravo trades (receipts, the kitchen stream, shift readings of 150.00
and 350.00, `Location` on alpha's address); an offline sync at both with the same client order id; alpha suspended
mid-day (503 `CUSTOMER_SUSPENDED` on health, sign-in, a sale, the API, the board and the stream; nothing written;
bravo's six sales all 201; alpha trades again once reactivated); a failed update at alpha (the same, 503
`CUSTOMER_UNAVAILABLE`).

## 5. Lines added and removed

| | Added | Removed |
|---|---|---|
| Main code | 9 | 5 |
| Tests | 1196 | 0 |
| Devstack (check-all.ps1, smoke.ps1) | 43 | 10 |
| Docs | see the step 6 commit | |

## 6. Files

Main: `devices/DeviceUnboundRequestFilter`, `devices/DeviceAlerts`.

Tests: `tenancy/crosscustomer/CrossCustomerAreasIntegrationTests` (new), `tenancy/crosscustomer/CrossCustomerScenariosIntegrationTests`
(new), `tenancy/crosscustomer/CrossCustomerDeviceLogLinesTest` (new), `auth/protection/LoginBurstFilterTest`,
`support/tenancy/MultiCustomerIntegrationTest`, `ops/MigrateCustomersCommandIntegrationTests`,
`tenancy/routing/CustomerPoolsIntegrationTests`.

Devstack: `devstack/scripts/check-all.ps1`, `devstack/scripts/smoke.ps1`.

Docs: `2026-11-27-cross-customer-tests.md` (new), this report (new), `2026-11-25-customer-from-address.md`,
`2026-11-23-local-stack.md`.

## 7. Commits

| Step | Commit | What |
|---|---|---|
| 1 | `21484a9` | The tag and check-all's step |
| 2a | `1fa3212` | L1 |
| 2b | `ef715b0` | L2 |
| 3 | `780f710` | The area tests and the rate limit |
| 4 | `b9cc640` | The scenarios |
| 5 | `51c1669` | smoke.ps1 |
| 6 | this commit | Docs |

`check-all.ps1` runs on the commit that adds this report; its result and hash are in the final report.

## 8. Open items

- `DashboardCache`: any new order empties every customer's cached dashboard, and all customers share one
  10,000-entry cap. Not a leak (no figures cross), but a busy customer costs the others their cache.
- The suite runs twice in check-all (step 0, then inside the full suite): a few minutes. If that grows, the full suite
  could exclude the tag (`-DexcludedGroups=cross-customer`).
- Scenario 4 doesn't run the migration command itself (see Decisions); a fixture with migration logins would let it.

## 9. Follow-ups (branch `feature/t1-10-stack-follow-ups`)

The frontend's stack e2e (its docs/2026-11-25-customer-responses.md, section 8) asked for two things in the stack.

| Step | Commit | What |
|---|---|---|
| 1 | `840c81c` | Offline selling on in the stack: `POS_OFFLINE_ENABLED`, and the grant key from `stack.env` (`OFFLINE_GRANT_SIGNING_KEY`; only its name is in the example; `up.ps1` checks it is Base64 of 32+ bytes and not `JWT_SECRET`'s); smoke checks `offlineEnabled` at each customer (25 checks in multi mode) |
| 2 | `ea8ae9e` | The frontend image reports its commit: `up.ps1` passes `POS_APP_BUILD_ID` (the commit's time when clean, so Docker reuses the build) |
| 3 | `0ed5796` | check-all steps 11 "stack frontend build" ("rebuild the stack" when the served build isn't `FRONTEND_REPO`'s HEAD, or either side has uncommitted changes) and 12 "frontend stack e2e" (a skip fails; skipped when step 11 failed): 13 steps |
| 4 | `7b63f85` | Docs |
| 1 fix | this commit | compose takes the grant key with an empty default (`:-`, not `:?`): with `:?`, a `stack.env` without the key made every compose command fail, so check-all couldn't stop the stack before the backend tests and the machine ran short of memory. `up.ps1` and the backend still refuse to start without a valid key |

Lines: devstack +122 −4; docs +31 −5 (step 4) and this commit's. No main or test code changed.
