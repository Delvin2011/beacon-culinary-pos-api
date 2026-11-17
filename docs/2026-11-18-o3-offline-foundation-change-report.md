# O3 offline foundation: change report

**Branch:** `feature/o3-offline-foundation`, from `main` at `b0d1431` (O1, O2 and O2b merged). Not pushed or merged.
**Contract:** `docs/2026-11-17-o3-offline-foundation-api-contract.md` (the frontend builds against it).
**Tests:** 883 of 883 pass on the full suite. `main` had 825; O3 adds 58.
**Database:** one migration, `V110__o3_offline_foundation.sql`, applied to the test database (`BeaconculinaryTest`) only.
**Switch:** `pos.offline.enabled` defaults to `false`. Nothing changes for tills until it's turned on and `POS_OFFLINE_GRANTSIGNINGKEY` is set.

## 1. What changed

A POS till can now prepare for an outage of up to 6 hours. Offline selling and sync themselves are O4.
- **Grant:** while online, the owner of the till's open shift gets a signed offline grant. It lasts 18 hours and doesn't depend on their session.
- **Snapshot:** the till downloads today's menu, prices, portions, extras, VAT rate, receipt settings, tenders and accounts. It refreshes the snapshot cheaply with `ETag`/304.
- **Heartbeat:** every minute, the till reports how many sales it holds unsynced. Managers see each till's state in `GET /admin/tills`.
- **Blocking:** a till with unsynced sales blocks Close Day for that day, and the normal close of that shift. Close Day can be overridden; the override is audited and needs a recent password sign-in and a reason. Force close stays possible.

## 2. Commits

| Step | Commit | What |
|---|---|---|
| 1 | `af20c7b` | API contract |
| 2 | `3d8e10a` | V110, `pos.offline` settings, grant signer |
| 3 | `d351422` | `POST /pos/offline/grant`, revocation hooks, `nextOfflineSeq` |
| 4 | `fc3ce31` | `GET /pos/offline/snapshot`, shared day menu |
| 5 | `62c675b` | `POST /pos/offline/status`, offline state in admin tills and till status |
| 6 | `07c6fd5` | `OFFLINE_SALES_PENDING` on Close Day (with override) and shift close |
| 7 | (this commit) | This report; contract brought in line with the build (sections 5, 8, 9, 10.3 and 12) |

## 3. Tasks

| Task | Status | Where |
|---|---|---|
| Settings `pos.offline.*`, validated at startup | Done. `grant-max-hours` 18, `status-stale-minutes` 10. Signing key required when enabled; never printed | `OfflineProperties` |
| Offline fields on `GET /pos/till-status` | Done, for every browser | `TillStatusService` |
| 1. Grant | Done. Owner only, also when an ADMIN is covering (`NOT_SHIFT_OWNER`). Each refusal has its own code. One active grant per till and shift; reissue replaces it under a lock | `OfflineGrantService`, `OfflineAccess` |
| Grant storage and signing | `offline_grants`. HS256 with a dedicated key and `kid`. Verification still reads expired grants, for O4 | `OfflineGrantStore`, `OfflineGrantSigner` |
| Revocation | On shift close, force close, till revoke, browser revoke and cashier deactivation. Revoked rows are kept | `ShiftService`, `ShiftAdminService`, `DeviceService`, `StaffService` |
| Offline numbering | `offline_sequences`. `nextOfflineSeq` = highest seen for the till and day, plus 1. `recordSeen` for O4 only ever raises it | `OfflineSequenceStore` |
| 2. Snapshot | Done: every period, every status, beverages, extras, VAT, receipt settings, tenders. Accounts with id, number and name only (POPIA), left out when disabled. `ETag`/304 | `OfflineSnapshotService`, `OfflineController` |
| Reuse of menu logic | `DailyPlanningService.dayMenu` uses the same listing rule (`AlwaysOnMenuService.listed`), order and entity prices as `GET /menu/today` | `DailyPlanningService` |
| 3. Heartbeat | Done. Till cookie only, JSON only, accepted when disabled. Stores the latest report, the per-shift pending list and the clock skew | `OfflineStatusService`, `OfflineStatusStore` |
| Admin tills | `offline.state`: NEVER_REPORTED, OK, PENDING or UNKNOWN (stale) | `AdminTillsService` |
| 4. Close Day blocker and override | `OFFLINE_SALES_PENDING` per till, fresh or stale. Override needs a recent password sign-in and a reason, lifts only that blocker, and writes `day_close_overrides` (insert-only) in the same transaction as the Z | `DayCloseRules`, `CloseDayService` |
| Shift close blocked | 409 `OFFLINE_SALES_PENDING`, checked before the variance approval is used. Force close isn't blocked | `ShiftService`, `OfflinePending` |
| 5. Disabled state | Grant and snapshot return 403 `OFFLINE_DISABLED`; status reports are still accepted | `OfflineAccess` |
| 6. Contract | Committed first; corrected in step 3 (order of checks) and step 7 (sections 5, 8, 9, 10.3 and 12) | the contract |

## 4. Snapshot size

Measured with the test fixture: **1,680 bytes** for 4 options, 1 extra and 1 account, about 300 bytes per option. Extrapolated, 100 options and 50 accounts come to about **35 KB**; a 304 is a few hundred bytes.

## 5. Tests (58 new)

| Class | Tests | What |
|---|---|---|
| `OfflineGrantSignerTest` | 9 | Sign and verify, `kid`, tampered payload, other key, other `kid`, other `typ`, expired grant still read, garbage, no key |
| `OfflinePropertiesTest` | 6 | Defaults; warn < max; grant ≥ max; key required when enabled (missing, short, not Base64); ranges; `toString` hides the key |
| `O3OfflineGrantIntegrationTests` | 19 | Success with a verifiable signature; tampered grant; accounts off; each refusal (disabled, no till, no sign-in, other till or back-office session, ended session, no shift, ADMIN covering, other role); replacement; revocation on close, force close, till, browser, deactivation; unrelated staff edit; `nextOfflineSeq` |
| `O3OfflineSnapshotIntegrationTests` | 7 | Every period (Breakfast at 12:30), PLANNED item, beverage, extra, VAT, receipt settings, tenders, limits, size; accounts without phone or email; accounts off; `ETag`/304 and a change; trading day around midnight in Johannesburg; any POS user on the till; refusals |
| `O3OfflineStatusIntegrationTests` | 8 | Stored and replaced, skew; no sign-in and while disabled; unregistered and revoked till; validation; non-JSON; admin tills states including stale; just under the stale limit; till-status fields |
| `O3OfflineBlockingIntegrationTests` | 9 | Close Day blocked per till (other day ignored); stale still blocks; cleared report; override recorded; override needs password and reason; override lifts only its blocker; override with nothing to override; shift close blocked (fresh, stale) and force close allowed; another till's report doesn't block |

The O3 tests switch offline on and off on the shared settings bean, so they need no extra Spring context. The blocking tests reuse the end-of-day tests' context.

## 6. Files

**Created (30)**
- `docs/2026-11-17-o3-offline-foundation-api-contract.md`
- `docs/2026-11-18-o3-offline-foundation-change-report.md`
- `src/main/resources/db/migration/V110__o3_offline_foundation.sql`
- `src/main/java/com/beaconculinary/api/offline/`: `OfflineAccess.java`, `OfflineConfig.java`, `OfflineController.java`, `OfflineDtos.java`, `OfflineException.java`, `OfflineExceptionHandler.java`, `OfflineGrantService.java`, `OfflineGrantSigner.java`, `OfflineGrantStore.java`, `OfflinePending.java`, `OfflineProperties.java`, `OfflineSequenceStore.java`, `OfflineSnapshotService.java`, `OfflineStatusController.java`, `OfflineStatusDtos.java`, `OfflineStatusService.java`, `OfflineStatusStore.java` (17)
- `src/main/java/com/beaconculinary/api/endofday/`: `CloseDayRequest.java`, `DayCloseOverrides.java`, `InvalidCloseDayRequestException.java` (3)
- `src/test/java/com/beaconculinary/api/offline/`: `OfflineGrantSignerTest.java`, `OfflinePropertiesTest.java`, `O3OfflineGrantIntegrationTests.java`, `O3OfflineSnapshotIntegrationTests.java`, `O3OfflineStatusIntegrationTests.java`, `O3OfflineBlockingIntegrationTests.java` (6)
- `src/test/java/com/beaconculinary/api/support/O3Tills.java`

**Modified (19)**
- `.env.example`, `src/main/resources/application.yaml`
- `src/main/java/com/beaconculinary/api/sessions/SessionGuard.java`
- `src/main/java/com/beaconculinary/api/shifts/ShiftService.java`, `ShiftAdminService.java`
- `src/main/java/com/beaconculinary/api/devices/DeviceService.java`
- `src/main/java/com/beaconculinary/api/staff/StaffService.java`
- `src/main/java/com/beaconculinary/api/menu/DailyPlanningService.java`, `DailyMealOptionRepository.java`, `DailyComponentStockRepository.java`
- `src/main/java/com/beaconculinary/api/tills/PosSecurityRules.java`, `TillStatusDto.java`, `TillStatusService.java`, `AdminTillDto.java`, `AdminTillsService.java`
- `src/main/java/com/beaconculinary/api/endofday/DayCloseRules.java`, `CloseDayService.java`, `EndOfDayController.java`
- `src/test/java/com/beaconculinary/api/support/LoginProtectionTestDefaults.java`

**Deleted:** none.

## 7. Before turning offline on

1. Set `POS_OFFLINE_GRANTSIGNINGKEY` (Base64, at least 32 random bytes; Key Vault in WS2). The server won't start with `POS_OFFLINE_ENABLED=true` and no valid key.
2. Apply V110 to the target database (Flyway does it on start).
3. Turning it on and off is safe: status reports are accepted either way, and turning it off revokes nothing.
4. Rotating the key: change `POS_OFFLINE_GRANTKEYID` with the key. Grants signed with the old key then fail verification, so issue new ones before an outage (the till does at sign-in).

## 8. Risks and notes for O4

- **A broken till blocks Close Day** until it reports again or an admin overrides; that is intended, and each override is recorded.
- **A till's report isn't verified:** a compromised till could report nothing pending. O4's sync is where the difference shows.
- **Grant requests don't count as activity** for the session idle timer, and neither do snapshot refreshes; only POS writes that already counted do.
- **Key rotation:** only the current key verifies grants. O4 should add a second, verify-only key before anyone rotates `POS_OFFLINE_GRANTSIGNINGKEY`, or sales made under old grants can't be synced (contract section 12).
- **O4 must:** accept sales made before a grant's revocation or expiry; raise `offline_sequences` with `recordSeen`; record offline ACCOUNT sales up to the cap without the online approval and flag them; record offline sales as already collected, off the kitchen screens.
