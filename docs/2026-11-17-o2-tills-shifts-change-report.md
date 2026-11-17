# O2 tills, per-till shifts, sessions and lock: change report

**Branch:** `feature/o2-tills-shifts`, from `feature/o1-resilience` (O1 isn't merged into `main` yet). Not pushed or merged.
**Contract:** `docs/2026-11-17-o2-tills-shifts-api-contract.md` (the frontend builds against it).
**Tests:** 775 of 775 passing on the full suite at `73b9162`. O2 started from O1's 668; it adds 107 tests.
**Database:** one migration, `V107__o2_tills_shifts_sessions.sql`, applied to the test database (`BeaconculinaryTest`) only.

## 1. What changed, in one paragraph

Up to three registered tills (T1, T2, T3) can now trade at once, each with its own shift, float and drawer. The till comes only from the `deviceToken` cookie; every other browser shares one "unregistered till", which behaves exactly as the single till did before O2. A cashier has one open shift and one active POS session. Every sign-in now creates a server-side session whose ID (`sid`) is in the tokens, so a replaced, locked, idle or force-closed session stops working on its next refresh or POS write. A cashier can lock a till and resume it; only the shift owner or an ADMIN can sign in on a till that has an open shift. An ADMIN can force-close an abandoned shift, with or without a cash count, and every such action is audited. Reports and the Z name the till, and new Zs have a per-till section.

## 2. Commits

| Step | Commit | What |
|---|---|---|
| 1 | `ecf4159` | API contract |
| 2 | `5182a18` | V107: till codes, shift and order till columns, per-till and per-cashier open-shift indexes, `user_sessions`, `shift_cash_count_events`, new sign-in outcomes |
| 3 | `1277c3e` | Till codes (lowest free T&lt;n&gt;) and the active-till limit |
| 4 | `3d43264` | Shifts, sales and adjustments per till; `require-registered` |
| 5 | `011874c` | Sessions, `sid`, sign-in rules (SHIFT_OPEN_ON_OTHER_TILL, TILL_LOCKED, REPLACED), session checks on POS writes, logout and revoke end sessions |
| 6 | `3aac5bd` | `GET /pos/till-status`, `POST /pos/lock`, idle expiry and the session sweep |
| 7 | `96c5170` | Force close, uncounted shifts, late count, confirmed "no count" at sign-off, audit |
| 8 | `73b9162` | Till columns on shift reports, Tills section in X and Z (definition 2), uncounted shown blank |
| 9 | this report | |

## 3. Rules R1–R14

| Rule | Status | Where |
|---|---|---|
| R1 Till codes, unique among active tills, lowest free, editable by ADMIN | Done | `DeviceService`, `TillCodes`, `UX_devices_active_till_code` |
| R2 `pos.tills.max-active` (3), 409 TILL_LIMIT_REACHED | Done; a browser joining an existing till doesn't count | `DeviceService.register` |
| R3 One open shift per till and per cashier; NULL till keeps today's behaviour | Done; SQL Server's NULL handling verified by test | V107, `OpenShiftConflicts`, `O2TillShiftIndexIntegrationTests` |
| R4 `pos.tills.require-registered` | Done for shift open, sales, adjustments and CASHIER/ADMIN PIN sign-in, in every device-trust mode | `CurrentTill`, `DeviceGate` |
| R5 The request's till decides the shift; owner or ADMIN only; `orders.device_id` | Done, for sales **and adjustments** (C1) | `OrderService`, `OrderAdjustmentService` |
| R6 409 SHIFT_OPEN_ON_OTHER_TILL at shift open | Done; ADMIN not exempt here (C9) | `ShiftService.openShift` |
| R7 Sessions, `sid`, refresh refused once ended, POS writes check the session, logout ends it | Done; check = one primary-key lookup per POS write, no cache | `sessions/*` |
| R8 One active POS session per user; SHIFT_OPEN_ON_OTHER_TILL at sign-in | Done; ADMIN exempt at sign-in (C9); STATION and BACK_OFFICE not limited (C2) | `SessionService.start` |
| R9 Lock; only owner or ADMIN sign in on a till with an open shift (TILL_LOCKED) | Done; also ends any other POS session on that till (C8) | `PosController`, `SessionService` |
| R10 Idle lock (frontend, 5 min) and server expiry (30 min) | Done; lazy check on refresh and writes, sweep records it | `SessionService`, `SessionSweepJob` |
| R11 Force close with recent password, ends owner's sessions, variance approval | Done; the admin's password sign-in is the approval; uncounted path with late count or confirmed no-count | `ShiftAdminService`, `ShiftSignoffService` |
| R12 `GET /pos/till-status` before sign-in | Done; details only for a registered till | `TillStatusService` |
| R13 Day close across tills; till columns; per-till Z section | Done; new Zs are definition 2, stored Zs unchanged | `EndOfDayStatements`, `DayCloseRules` |
| R14 Cash refunds book to the processing till's shift; cross-till refunds reported | Done; "of which other tills' sales" in the Tills section | `ReportFacts.cashRefundsOfOtherTills` |

## 4. Decisions

Approved before building (from the plan review):

| | Decision |
|---|---|
| C1 | TILL_IN_USE applies to voids and refunds as well as sales. |
| C2 | A third session kind, STATION, for KITCHEN, BARISTA and HEAD_CHEF PIN sign-ins; not limited by R8. |
| C4 | Everyone signs in once after the deploy (tokens without `sid` are refused). No grace setting. Deploy after day close and tell site managers. |
| C8 | A POS sign-in on a till ends any other POS session on that till (REPLACED). The shift stays open; sales record who rang them. |
| C9 | ADMIN is exempt from SHIFT_OPEN_ON_OTHER_TILL at sign-in, not from one-open-shift-per-cashier at shift open. |
| Force close | Without a count: `cashCount` UNCOUNTED, variance empty, reason required; sign-off needs a late count (`POST /admin/shifts/{id}/count`) or `confirmNoCount` with a reason; both audited; Close Day treats a confirmed no-count as signed off. |
| R13 | Till columns and a per-till Z section built now; existing Zs unchanged. |

Made while building (each shown as a diff and approved):

| Step | Decision |
|---|---|
| 2 | Shifts keep the till's code and name as they were at open (`till_code`, `till_name`), so later edits don't change history. Audit rows keep the admin's name. |
| 3 | The suite pins `pos.tills.max-active` high (tests share one database); the till-code tests set it back to 3. |
| 4 | An adjustment's till checks (require-registered, NO_OPEN_SHIFT, TILL_IN_USE) come before the manager-PIN token is used, so a refusal no longer burns it. 409s that had no code gained `SHIFT_ALREADY_OPEN` and `SHIFT_ALREADY_CLOSED`. `ShiftRepository.findFirstByStatus` kept (four existing tests use it), marked "never to choose where money goes". |
| 5 | A POS write without a bearer token (an internal call, such as a test calling the service directly) isn't session-checked; over HTTP these endpoints authenticate only through the bearer token. |
| 6 | Idle activity = sign-in and POS writes only (refresh, polling and reads don't count). The lazy idle check doesn't write (it often runs in a transaction that rolls back); the once-a-minute sweep records IDLE and EXPIRED. `pos.session.sweep-enabled` added (off in tests). |
| 7 | The recent-password check moved from `DeviceService` to `auth/RecentPasswordCheck` (same 30-minute `reauth-max-age`, same message for till management). The close snapshot is shared between a cashier's close and a force close (`ShiftCloses`). |
| 8 | `ReportValues.money(null)` turned a missing count into 0.00; uncounted values now show blank in the X/Z shifts table, Cash-up, the Cashier reading and their cross-check. Cash-up & Variance moved to definition 5 (new column). |

Contract corrections made during the build: `/pos/till-status` is not rate-limited (7.1; the burst limit covers only sign-in and manager-PIN approval); the missing-note error on a no-count sign-off is `{"error": ...}` like other sign-off errors (10.3); `pos.session.sweep-enabled` added (2); section 11 matches what the reports show.

## 5. Tests

| Class | Tests | Covers |
|---|---|---|
| `shifts/O2TillShiftIndexIntegrationTests` | 14 | V107 rules directly: per-till and per-cashier indexes, the NULL till's single open shift, closed shifts don't count, till-code uniqueness, session end fields, audit is insert-only |
| `tills/TillCodesTest` | 5 | Lowest free code, normalising, validation |
| `devices/O2TillCodeIntegrationTests` | 10 | T1/T2/T3, **a fourth registration refused**, joins at the limit, revoked code reused, editing and clashes |
| `shifts/O2PerTillShiftIntegrationTests` | 19 | **Three tills, three cashiers selling at once**; SHIFT_ALREADY_OPEN; **SHIFT_OPEN_ON_OTHER_TILL**; ADMIN not exempt at open; **NULL-till legacy behaviour**; NO_OPEN_SHIFT per till; TILL_IN_USE for sales and adjustments; **refund on another till**; **concurrent shift opens** (one till, two cashiers; one cashier, two tills) |
| `shifts/O2RequireRegisteredIntegrationTests` | 8 | **require-registered on**: open, sale, adjustment, PIN sign-in refused without a till; kitchen PIN and password sign-in unaffected (**off** is the rest of the suite) |
| `sessions/O2SessionIntegrationTests` | 18 | `sid`; **REPLACED sessions can't refresh or sell** (but can collect an O1 replay); STATION and BACK_OFFICE unlimited; SHIFT_OPEN_ON_OTHER_TILL and TILL_LOCKED at sign-in, recorded, never failures; ADMIN exemption; logout; revoke; tokens without `sid` |
| `tills/O2LockIdleTillStatusIntegrationTests` | 14 | Till status; **lock, resume by the owner, TILL_LOCKED for others, ADMIN override**; **idle expiry**; activity rules; the sweep |
| `shifts/O2ForceCloseIntegrationTests` | 12 | **Force close** counted and uncounted, variance approval, REAUTH_REQUIRED, refusals; late count; confirmed no-count; Close Day with an uncounted shift; audit |
| `reports/O2TillReportsIntegrationTests` | 7 | **Day close across tills**: till columns, the Tills section with refunds of other tills' sales, uncounted blank everywhere, Close Day labels, admin views, Z definition 2 |

Existing tests changed (each approved): `ReportsIntegrationTests` (two Cash-up assertions by column position, definition 5), `EndOfDayIntegrationTests` (definition 5), `LoginProtectionTestDefaults` (pins the till limit, require-registered and the sweep for the suite).

Full-suite runs after each step: 682 → 697 → 724 → 742 → 756 → 768 → 775, all passing.

## 6. Deployment notes

1. **Deploy after day close**, with no shift open. Any shift still open attaches to the unregistered till.
2. **Everyone signs in again** after the deploy (tokens without a session are refused). Tell site managers beforehand.
3. Active tills already registered get T1, T2, … in registration order. If more than three are active, they keep working; only new registrations are refused.
4. Settings (all optional; defaults in brackets): `POS_TILLS_MAXACTIVE` (3), `POS_TILLS_REQUIREREGISTERED` (false), `POS_SESSION_IDLELOCKMINUTES` (5), `POS_SESSION_IDLEEXPIRYMINUTES` (30), `POS_SESSION_SWEEP_ENABLED` (true). Recommended for production: device-trust `required` and `require-registered=true`.
5. **The frontend must ship with it** (contract section 13): till status and lock screens, `SESSION_ENDED` handling (no refresh retry), the bearer token on logout, the new 409s, till codes on the device screens, force close and the uncounted flows, and Z snapshots of definition 1 and 2.
6. Apply V107 in each environment through the usual Flyway run; it was applied to the test database only.

## 7. Known limitations and follow-ups

- **Reads after a session ends.** Only refresh and POS writes check the session. A replaced or locked session can still read (menus, reports it's allowed) until its access token expires, at most 15 minutes.
- **Idle means no POS writes.** A cashier signed in for 30 minutes without a sale, adjustment or shift action is signed out (IDLE) on the next refresh or write, even if the screen was in use. The frontend's 5-minute auto-lock normally ends the session first.
- **Idle is recorded within a minute.** Refresh and writes refuse an idle session at once; the database row says IDLE after the next sweep.
- **Refresh isn't bound to the till** it was issued on. A copied refresh token works from another browser while its session is active. Candidate for O3.
- **Two different people signing in on the same till at the same instant** could both end up active there (the sign-in lock is per user). The next sign-in on that till ends both; it doesn't affect which shift money goes to.
- **Shifts opened before O2** show as "Unregistered till" in reports, since they have no till.
- **`/pos/till-status` isn't rate-limited.** It reveals owner names only to a registered till.
- **Follow-up:** the WS1-1b device contract's backlog still lists "This till: {name}" and tying sessions to a till as future work; both are delivered by O2 (`/pos/till-status`, sessions). A one-line pointer there is pending your approval.
- **Out of scope** (later steps): offline grants and offline unlock values (O3; a grant can hang off `user_sessions.id`), the sync endpoint (O4), account caps (O5).

## 8. Every file created or modified

**Created (39)**

| Area | Files |
|---|---|
| Docs | `docs/2026-11-17-o2-tills-shifts-api-contract.md`, `docs/2026-11-17-o2-tills-shifts-change-report.md` |
| Migration | `src/main/resources/db/migration/V107__o2_tills_shifts_sessions.sql` |
| `auth` | `RecentPasswordCheck.java` |
| `sessions` | `SessionEndReason.java`, `SessionEndedException.java`, `SessionExceptionHandler.java`, `SessionGuard.java`, `SessionKind.java`, `SessionService.java`, `SessionStore.java`, `SessionSweepJob.java` |
| `shifts` | `OpenShiftConflicts.java`, `ShiftAdminController.java`, `ShiftAdminRequests.java`, `ShiftAdminService.java`, `ShiftCashCount.java`, `ShiftCashCountEvents.java`, `ShiftCloses.java` |
| `tills` | `CurrentTill.java`, `PosController.java`, `PosSecurityRules.java`, `Till.java`, `TillCodes.java`, `TillConfig.java`, `TillException.java`, `TillExceptionHandler.java`, `TillProperties.java`, `TillStatusDto.java`, `TillStatusService.java` |
| Tests | `devices/O2TillCodeIntegrationTests.java`, `reports/O2TillReportsIntegrationTests.java`, `sessions/O2SessionIntegrationTests.java`, `shifts/O2ForceCloseIntegrationTests.java`, `shifts/O2PerTillShiftIntegrationTests.java`, `shifts/O2RequireRegisteredIntegrationTests.java`, `shifts/O2TillShiftIndexIntegrationTests.java`, `tills/O2LockIdleTillStatusIntegrationTests.java`, `tills/TillCodesTest.java` |

**Modified (38)** (paths under `src/main/java/com/beaconculinary/api/` unless shown)

| Area | Files |
|---|---|
| `auth` | `AuthController.java`, `AuthService.java`, `Jwt.java`, `JwtService.java`, `SignIn.java`, `protection/LoginOutcome.java` |
| `devices` | `DeviceDtos.java`, `DeviceException.java`, `DeviceGate.java`, `DeviceRequests.java`, `DeviceService.java`, `DeviceStore.java` |
| `endofday` | `DayCloseRules.java`, `EndOfDayController.java`, `ShiftSignoffService.java` |
| `orders` | `Order.java`, `OrderAdjustmentService.java`, `OrderDto.java`, `OrderMapper.java`, `OrderService.java` |
| `oversight` | `ShiftActivityDto.java`, `ShiftActivityService.java`, `ShiftListRowDto.java` |
| `reports` | `EndOfDayStatements.java`, `ReportFacts.java`, `ReportValues.java`, `definitions/CashUpVarianceReport.java`, `definitions/ZReadingReport.java` |
| `shifts` | `OpenShiftDto.java`, `Shift.java`, `ShiftController.java`, `ShiftDto.java`, `ShiftMapper.java`, `ShiftRepository.java`, `ShiftService.java` |
| Config | `src/main/resources/application.yaml` |
| Tests | `src/test/java/com/beaconculinary/api/reports/EndOfDayIntegrationTests.java`, `src/test/java/com/beaconculinary/api/reports/ReportsIntegrationTests.java`, `src/test/java/com/beaconculinary/api/support/LoginProtectionTestDefaults.java` |

**Deleted (1):** `src/main/java/com/beaconculinary/api/shifts/ShiftAlreadyOpenException.java` (replaced by `SHIFT_ALREADY_OPEN`).

Not touched and not committed: `app-run.log` (modified before O2 started) and `docs/2026-11-13-ws1-1b-device-frontend-prompt.md` (untracked before O2 started).
