# WS1-1 Phase 2 (enforcement): backend change report

Handover report for WS1-1 Phase 2 in POS_BE. It continues `docs/2026-11-11-ws1-1-phase0-1-change-report.md` and `docs/2026-11-11-ws1-1-pre-merge-followups-report.md`.

- **Branch:** `feature/ws1-1-phase2-enforcement`, created from `feature/ws1-1-login-protection` because Phase 1 is not merged. Not pushed, not merged.
- **Size:** 12 commits; 37 files changed, 3,023 insertions, 116 deletions.
- **Date:** 2026-11-13
- **Secrets:** none appear in this report. Test credentials are generated at run time and never written down.

## 1. Summary

- **Enforce mode now exists.** `application.yaml` still defaults to `monitor`, and tests default to `off`. The owner switches enforcement on per environment with `LOGIN_PROTECTION_MODE=enforce`.
- **Refusals happen before any credential check.** A locked or blocked attempt gets **HTTP 429** with `Retry-After` and `{code, message, retryAfterSeconds}`: `ACCOUNT_LOCKED` for account locks, `RATE_LIMITED` for short blocks and the burst limit.
- **Account locks escalate:** 15 min, then 60 min, then until an admin unlocks. Unknown emails and cashier IDs are treated exactly like real accounts, so responses reveal nothing.
- **Per-IP burst limit** (Bucket4j) on `/auth/**` and the manager-PIN endpoints.
- **Admin unlock:** `POST /admin/staff/{id}/unlock`. The staff page shows account locks (`locks`) and short-term blocks (`blocks`), so admins can see who needs an unlock. **Admins can't unlock themselves.**
- **Refresh** returns 401 while the account is locked.
- **PINs must be exactly 6 digits**, in every mode.
- **Alerts** carry the stable log marker `LOGIN_PROTECTION_ALERT`.
- **Recovery runbook** for the owner, for when no admin can sign in.
- **Fail open:** an error inside protection code never blocks a login; it is logged at ERROR and the request continues.

## 2. Commits (oldest first)

| Commit | Step | Message |
|---|---|---|
| `8ed1321` | 2-G | docs: login protection API contract for the frontend |
| `7067743` | 2-E | auth: PINs must be exactly 6 digits |
| `01e772d` | 2-A, 2-F | auth: enforce mode for login protection |
| `bbc4fe5` | 2-D | auth: refuse refresh while the account is locked |
| `103d813` | 2-B | auth: per-IP burst limit on sign-in endpoints |
| `cf8e726` | 2-C | staff: admin unlock and lock state |
| `f8c19da` | 2-H | test: enforce-mode, unlock and burst-limit tests |
| `af5843d` | 2-I | docs: login protection Phase 2 guide and recovery runbook |
| `5c534b9` | follow-up | staff: admins can't clear their own sign-in locks |
| `4a7a0a7` | follow-up | docs: no self-unlock in the contract, guide and runbook |
| `b6a946b` | follow-up | staff: list short-term sign-in blocks in staff records |
| `9238901` | follow-up | docs: staff record blocks in the contract (2.8) and guide |

## 3. Decisions taken

| # | Decision |
|---|---|
| C1 | The 429 body is a new `LoginProtectionErrorDto {code, message, retryAfterSeconds}`. The existing `ErrorDto` and all other endpoints are unchanged. |
| C2 | Migration **V104** adds nullable `lock_level` and `locked_until` to `login_attempts`. They hold the lock state of unknown identifiers and an audit copy for real accounts. |
| C3 | LOCK-rule counting also restarts after each lock, so every lock needs fresh failures. Counting always restarts after a success or an unlock. |
| C4 | `block-duration` is removed. Blocks last until the failures in their window drop below the threshold. |
| C5 | Refresh is refused while locked, as approved. The risk is documented: anyone who knows a cashier ID or admin email can deliberately lock that person out. |
| C6 | 6-digit PINs are enforced now, before any monitor-mode evidence. All seed PINs and staff-management PINs are 6 digits. |
| C7 | Unlock and clear-on-reset run in the **caller's** transaction, not a separate one, which would wait on the users row the caller already wrote. |
| C8 | The alert log marker changes the wording of the existing monitor alert lines. Responses are unaffected. |
| Follow-up B | **No self-unlock:** unlocking your own row returns 409 `SELF_UNLOCK`; setting your own PIN or password saves it but keeps the lock. |
| Follow-up (frontend request) | **Short-term blocks in staff records:** `blocks.PIN`, `blocks.MANAGER_PIN` and `blocks.CHANGE_PASSWORD`, each `{blocked, blockedUntil}`, next to `locks`. The field names are exactly as the frontend proposed. Without them, the staff page couldn't tell who had a PIN block at a till, so Unlock showed on every row. |
| Dependency | Bucket4j pinned to **8.15.0** (August 2025) rather than the latest, 8.22.0, which had been out about a week. Caffeine 3.1.8, managed by Spring Boot. |

## 4. Behaviour by mode

| | `off` | `monitor` (default) | `enforce` |
|---|---|---|---|
| Attempts recorded | No | Yes | Yes |
| Would-be block | n/a | Flagged and logged; request continues | **Refused with 429** before the credential check; recorded as `LOCKED` or `RATE_LIMITED` (not counted as a failure) |
| Locks written | No | No | Yes: `user_auth_state` (real accounts); attempt row (unknown identifiers) |
| Burst limit | Off | Logs only | 429 `RATE_LIMITED` |
| Refresh while locked | Normal | Normal | 401 |
| 6-digit PINs | **Enforced** | **Enforced** | **Enforced** |
| Unlock endpoint | Works | Works | Works |

## 5. Rules in enforce mode

| Rule | Counted per | Threshold | Effect | Code |
|---|---|---|---|---|
| `PASSWORD_ACCOUNT` | lower-cased email (known or unknown) | 5 in 15 min | Lock 15 min, then 60 min, then admin unlock | `ACCOUNT_LOCKED` |
| `PIN_PAIR` | cashierId + client IP | 5 in 15 min | Window block | `RATE_LIMITED` |
| `PIN_ACCOUNT_CEILING` | cashierId, any source | 20 in 60 min | Lock (escalating) and alert | `ACCOUNT_LOCKED` |
| `MANAGER_PIN_CALLER` | calling user | 5 in 15 min | Window block | `RATE_LIMITED` |
| `MANAGER_PIN_GLOBAL` | all callers | 20 in 60 min | Alert only | none |
| `CHANGE_PASSWORD_USER` | user | 5 in 15 min | Window block (sign-in unaffected) | `RATE_LIMITED` |
| Burst | client IP | 60 burst, refilled 60 per minute | Refused | `RATE_LIMITED` |

**Retry-After:**
- account locks: until `locked_until`
- window blocks: until the oldest counted failure leaves the window
- burst: until the bucket refills
- a level-3 lock: no header, and `retryAfterSeconds: null`

## 6. HTTP contract

The authoritative document is `docs/2026-11-11-login-protection-api-contract.md`. The 429 bodies:

```json
{"code":"ACCOUNT_LOCKED","message":"Too many failed attempts. Try again later.","retryAfterSeconds":900}
{"code":"ACCOUNT_LOCKED","message":"Too many failed attempts. Ask a manager to unlock sign-in.","retryAfterSeconds":null}
{"code":"RATE_LIMITED","message":"Too many attempts. Try again later.","retryAfterSeconds":42}
```

**Unchanged:**
- wrong password or PIN: 401, empty body
- wrong manager PIN: 401 `{"error":"Invalid PIN."}`
- wrong old password on change-password: 401, empty body

**New or changed:**
- 400 `{"pin":"PIN must be 6 digits"}`
- `POST /admin/staff/{id}/unlock` (optional `{"scope": …}`) returns the staff record; 400 for an unknown scope, 404 for an unknown user, **409 `{"error":…, "code":"SELF_UNLOCK"}` on your own row**
- staff records gain `locks.PASSWORD` and `locks.PIN`: `{locked, lockedUntil, requiresAdminUnlock}`
- staff records gain `blocks.PIN`, `blocks.MANAGER_PIN` and `blocks.CHANGE_PASSWORD`: `{blocked, blockedUntil}`
  - `PIN` is true for a block on any till; `blockedUntil` is when the last one ends (UTC)
  - a block clears with an unlock, a successful sign-in, or the 15 minutes passing
  - everything is false or null in monitor and off mode
- CORS exposes `Retry-After`

## 7. Changes by area

### Enforce mode (2-A) and alerts (2-F)

- **`LoginAttemptService`**
  - New enforce branch: refuses in `begin`, writes locks in `finish` when a LOCK rule is reached.
  - Monitor and off paths unchanged.
  - New methods: `isAccountLocked`, `unlock`, `activeAccountLocks`, `selfUnlockRefused`.
  - Alert lines carry `LOGIN_PROTECTION_ALERT`.
- **`LoginProtectionStore`**
  - New queries: account state, lock history, Retry-After lookups, active locks, `isAccountLocked`.
  - New writes: `unlock` (caller's transaction) and `saveAttemptWithAccountLock` (its own transaction).
  - `applyLock` also restarts at level 1 after an unlock.
  - `AttemptRow` gains the lock columns.
- **`LoginProtectionProperties`:** enforce allowed; `block-duration` removed; `burst.*` added.
- **New classes:** `LoginBlockedException`, `LoginBlockedExceptionHandler` (writes the 429 and `Retry-After`), `LoginProtectionErrorDto`.
- **`AuthService.pinLogin`:** looks the cashier up before the check, for every attempt, so real and unknown IDs are told apart without a timing difference.
- **V104:** `login_attempts.lock_level` (CHECK 1–3) and `login_attempts.locked_until`, both nullable.

### Refresh (2-D)

`AuthService.refreshAccessToken` returns 401 while the user has an active PASSWORD or PIN lock (enforce mode only; fails open).

### Burst limit (2-B)

- **`LoginBurstFilter`:** Bucket4j, one bucket per client IP, kept in a Caffeine cache (at most 10,000 addresses, dropped after 10 minutes idle). Not a `@Component`. At most one WARN per IP per minute; no `login_attempts` row per refusal.
- **`SecurityConfig`:** registers the filter before `JwtAuthenticationFilter`; CORS exposes `Retry-After`.
- **`pom.xml`:** `com.bucket4j:bucket4j_jdk17-core:8.15.0` and `com.github.ben-manes.caffeine:caffeine`.

### Admin unlock (2-C) and no self-unlock

- **`StaffController`:** `POST /admin/staff/{id}/unlock`.
- **`StaffService`:**
  - unlock, with the optional scope
  - a new PIN clears `PIN`; a new password clears `PASSWORD` and `CHANGE_PASSWORD`
  - lock state in the list and detail
  - **self-unlock refused** (409), and **your own reset keeps your lock**
- **`StaffDto`:** gains `locks` and `blocks`. **`StaffRequests`:** gains `Unlock`. **New:** `AccountLockDto`, `AccountBlockDto`.

### Short-term blocks in staff records (follow-up)

- **`LoginProtectionStore.activeBlocks`:** a read-only query over the last 15 minutes of `login_attempts`, joined to `user_auth_state`. Only failures after the last success and last unlock count, as in the sign-in check. Per cashier and till for PIN, per user otherwise, the 5th-most-recent failure marks an active block; that time plus the window is `blockedUntil`. For PIN, the latest end across tills wins.
- **`LoginAttemptService.activeBlocks`:** enforce mode only; returns nothing in monitor and off mode, and on a read error (logged).
- **`StaffService`:** the staff list, detail and every staff-management response build `blocks` next to `locks`.

### PIN length (2-E)

`PinLoginRequest` and `AuthorizeRequest` use `\d{6}`, with the message "PIN must be 6 digits".

## 8. Alerts (`LOGIN_PROTECTION_ALERT type=…`)

| Type | When |
|---|---|
| `PIN_ACCOUNT_CEILING` | A cashier's PIN reaches the ceiling (enforce: locked; monitor: would lock) |
| `MANAGER_PIN_GLOBAL` | 20+ wrong manager PINs across all tills in 60 min |
| `ADMIN_UNLOCK_REQUIRED` | A level-3 lock was written |
| `UNLOCK` | Any unlock (`reason=ADMIN_UNLOCK`, `PIN_RESET` or `PASSWORD_RESET`; `by=` the admin) |
| `SELF_UNLOCK_REFUSED` | An admin tried to clear their own lock |

No PIN, password or plain email appears in any log line. Emails are logged only as a short hash.

## 9. Tests

| Test class | Type | Result |
|---|---|---|
| `LoginProtectionEnforceIntegrationTests` | integration, enforce | 10 of 10 |
| `LoginUnlockIntegrationTests` | integration, enforce | 9 of 9 |
| `LoginSelfUnlockIntegrationTests` | integration, enforce | 4 of 4 |
| `LoginStaffBlocksIntegrationTests` | integration, enforce | 5 of 5 |
| `LoginAttemptServiceBlocksTest` | unit | 2 of 2 |
| `LoginBurstFilterIntegrationTests` | integration, enforce | 2 of 2 |
| `LoginBurstFilterTest` | unit | 5 of 5 |
| `LoginAttemptServiceFailOpenTest` | unit | 2 of 2 |
| Phase 1 tests (monitor, transaction, store, off, properties, rules, IP resolver, advice) | mixed | all pass |
| `StaffManagementIntegrationTests` | integration | 10 of 10 |
| `AuthPinLoginIntegrationTests` | integration | 3 of 3 (wrong PINs changed to 6 digits) |

**What the tests verify:**
- every rule refuses on the right attempt
- refusal happens before any credential check (even the right password gets 429 while locked)
- exact Retry-After values, using a test clock
- escalation 15 → 60 min → admin unlock
- blocks ending by themselves
- unknown identifiers indistinguishable from real accounts
- refresh refused while locked
- unlock of one scope or all, and resets clearing locks
- self-unlock refused
- short-term blocks in staff records: the exact end time, the latest of two tills, clearing by unlock, sign-in or time, the manager-PIN and change-password blocks, and nothing shown in monitor or off mode
- burst 429 with CORS headers, the bounded cache, and one WARN per interval
- fail-open behaviour

**Safety nets in the tests:**
- every database connection uses a 2-second lock timeout, so a self-block fails a test instead of hanging
- the enforce, unlock and self-unlock tests fail on **any** ERROR logged by protection code, because fail-open would otherwise hide a bug

**Bug found and fixed during testing:** for a user who had been unlocked but had never signed in, the check crashed and failed open, so protection was silently off for exactly those users. Fixed, with a regression test (`afterAnUnlock_failuresAreStillCountedAndRefused`).

**Full suite: not yet completed.** Two runs were stopped because the machine ran low on memory. The last got through 431 tests with 1 failure, `RefundPayoutIntegrationTests`, which had stalled for 2.6 hours mid-test; it passes when rerun on its own (5 of 5).

## 10. Files

### Created (18)

- `docs/2026-11-11-login-protection-api-contract.md`
- `docs/2026-11-11-login-protection-recovery-runbook.md`
- `src/main/resources/db/migration/V104__login_attempt_lock_columns.sql`
- `src/main/java/com/beaconculinary/api/auth/protection/`: `LoginBlockedException.java`, `LoginBlockedExceptionHandler.java`, `LoginBurstFilter.java`, `LoginProtectionErrorDto.java`
- `src/main/java/com/beaconculinary/api/staff/AccountLockDto.java`, `AccountBlockDto.java`
- `src/test/java/com/beaconculinary/api/auth/protection/`: `LoginAttemptServiceBlocksTest.java`, `LoginAttemptServiceFailOpenTest.java`, `LoginBurstFilterIntegrationTests.java`, `LoginBurstFilterTest.java`, `LoginProtectionEnforceIntegrationTests.java`, `LoginProtectionTestClient.java`, `LoginSelfUnlockIntegrationTests.java`, `LoginStaffBlocksIntegrationTests.java`, `LoginUnlockIntegrationTests.java`

### Modified (19)

- `pom.xml`
- `docs/2026-11-11-login-protection.md`
- `src/main/java/com/beaconculinary/api/`: `admin/AuthorizeRequest.java`, `auth/AuthService.java`, `auth/PinLoginRequest.java`, `auth/SecurityConfig.java`
- `src/main/java/com/beaconculinary/api/auth/protection/`: `LoginAttempt.java`, `LoginAttemptService.java`, `LoginProtectionProperties.java`, `LoginProtectionStore.java`
- `src/main/java/com/beaconculinary/api/staff/`: `StaffController.java`, `StaffDto.java`, `StaffRequests.java`, `StaffService.java`
- `src/test/java/com/beaconculinary/api/auth/AuthPinLoginIntegrationTests.java`
- `src/test/java/com/beaconculinary/api/auth/protection/`: `LoginProtectionMonitorIntegrationTests.java`, `LoginProtectionPropertiesTest.java`, `LoginValidationAdviceScopeIntegrationTests.java`, `LoginValidationAttemptAdviceTest.java`

No applied migration (V1–V103) was edited. Flyway `clean` was never run. History was not rewritten.

## 11. Local state (developer machine)

- **The local app is stopped.** It last ran from this branch in **enforce** mode (`LOGIN_PROTECTION_MODE=enforce` set for that run only). Started without it, it runs in monitor mode.
- **The local database `Beaconculinary181026` is at V104.** It holds test rows from manual checks, including 55 failed attempts on admin 10010 from 07:20–07:27 UTC on 2026-11-13, made in monitor mode, so no lock was written.

## 12. Open items

1. **Complete one full-suite run**, when memory allows.
2. **Merge** Phase 1 (`feature/ws1-1-login-protection`), then Phase 2. Push only on the owner's instruction.
3. **Release order:** deploy the frontend (6-digit PINs) with or before the backend, because the PIN rule applies in every mode.
4. **Azure switch-on:**
   1. Deploy in monitor mode.
   2. Set `trusted-proxies` (monitor query 3).
   3. Review the monitor data.
   4. Set up the log alert on `LOGIN_PROTECTION_ALERT`.
   5. Set `LOGIN_PROTECTION_MODE=enforce`.
5. **Proposed, not done:** log the monitor-mode WARN once per would-be lock rather than per attempt. The 2026-11-13 test produced 51 warnings for one person.
6. **Proposed, not done:** a note in the contract on telling the two kinds of 401 apart (wrong PIN versus expired token; change-password's empty 401).
7. **Frontend:** send the updated contract (`docs/2026-11-11-login-protection-api-contract.md`, including section 2.8 `blocks`) and `docs/2026-11-13-ws1-1-phase2-frontend-updates.md` to the frontend agent.
8. **Out of scope (unchanged):** device registration (WS1-1b), seed-account bootstrap and prod guard, `Role.USER`, template leftovers, public login tiles.
