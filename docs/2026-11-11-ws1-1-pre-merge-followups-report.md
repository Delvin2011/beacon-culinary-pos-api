# WS1-1 pre-merge follow-ups: change report

Handover report for the four follow-up items requested before merging `feature/ws1-1-login-protection`. It continues `docs/2026-11-11-ws1-1-phase0-1-change-report.md`.

- **Branch:** `feature/ws1-1-login-protection`. Not pushed, not merged.
- **Date:** 2026-11-11
- **Production code:** none changed in this round. Tests and test support only.
- **Secrets:** none appear in this report.

## 1. Result

| Item | Result |
|---|---|
| 1. Skipped test | Root cause found and fixed. `StaffManagementIntegrationTests.deactivatedStaffCantLogIn_andTheLastAdminCantBeDeactivated` now **runs and passes** on the shared test DB and on a fresh database. |
| 2. REQUIRES_NEW transaction safety | All four paths are safe; no fix needed. 7 new tests, including a negative control, all pass. |
| 3. Validation advice scope | 10 new tests (6 integration, 4 unit), all pass. Out-of-scope responses are identical to `main` and record nothing. |
| 4. PR description | Written as text, outside the repo. Nothing pushed or opened. |
| Full suite on a fresh, empty database | **530 tests: 0 failures, 0 errors, 0 skipped.** BUILD SUCCESS. Flyway applied V1–V103 from empty. |

## 2. Commits

| Commit | Message |
|---|---|
| `1ca60be` | test: clean up staff created by login protection tests |
| `ff5c42b` | test: transaction-ordering tests for REQUIRES_NEW attempt writes |
| `2107c3a` | test: scope tests for the login validation advice |

Approvals for this round:
- plan and the two diffs to existing test files: approved
- creating and dropping a temporary local test database: approved
- committing `docs/2026-11-11-ws1-1-phase0-1-change-report.md`: **not yet** (still uncommitted, as is this report)

## 3. Item 1: skipped test

### Root cause

The last-admin test skips itself unless the seeded admin is the only active admin. A read-only query of the shared test DB (`BeaconculinaryTest`) showed:
- three active admins: the seeded "Canteen Admin" and two "WS1 Monitor Admin" rows, one per earlier run of `LoginProtectionMonitorIntegrationTests`
- 30 active "WS1 Monitor…" staff in total

So the WS1-1 monitor test caused the skip. `StaffManagementIntegrationTests` already deletes its own staff in an `@AfterEach`.

### Fix

- **New `src/test/java/com/beaconculinary/api/support/TestStaffCleanup.java`:**
  - `delete(jdbc, ids)`: deletes the user's `authorization_tokens` and `authorization_sessions` rows, then the user. `user_auth_state` cascades; `login_attempts` has no FK. If anything else still refers to the user, it sets `active = 0` instead.
  - `deleteByNamePrefix(jdbc, prefix)`: removes leftovers by name prefix, with LIKE wildcards escaped.
  - It only ever runs against a database whose name contains "test" (enforced by `TestDatabaseEnvironmentPostProcessor`).
- **`LoginProtectionMonitorIntegrationTests` (modified):**
  - tracks every staff member it creates, including admins created for the manager-PIN tests
  - deletes them in `@AfterEach`
  - on its first test in a JVM, sweeps leftovers named "WS1 …"
- **`LoginProtectionStoreIntegrationTests` (modified):** adds `@AfterEach` to clear `login_attempts` and `user_auth_state`. It creates no users, but it left a lock row on the first user.
- **Other new test classes:**
  - `LoginProtectionOffIntegrationTests` creates nothing
  - the unit tests don't touch the database
  - the new `LoginProtectionTransactionIntegrationTests` uses the same tracking, cleanup and sweep, with the "WS1 Txn" name prefix

### Verification

| Run | Outcome |
|---|---|
| Shared test DB: monitor and store tests, then `StaffManagementIntegrationTests` | Sweep removed all leftovers. After the run: 1 active admin (the seeded one), 0 "WS1" users. Staff tests 10 of 10 run, **0 skipped**; the last-admin test passed. |
| Fresh empty database (full suite) | Last-admin test ran and passed. Suite 530 tests, 0 skipped. |

**Fresh-database procedure:**
1. Created a local database, `BeaconculinaryTestWs11Clean` (the name contains "test").
2. Ran the full suite with `TEST_DATASOURCE_URL` pointing at it.
3. Dropped it.

No `flyway:clean` was used; Flyway only migrated the new empty database. That run also proves all 103 migrations apply from scratch.

## 4. Item 2: transaction safety of the REQUIRES_NEW writes

### Facts checked

- `AuthService` and `UserService` have no `@Transactional`.
- `AdminAuthorizationService.authorize` and `authorizeSession` are `@Transactional`.
- Each wired method is called only from its controller (`AuthController`, `AdminController`, `UserController`), never from inside another transaction.
- `spring.jpa.open-in-view` is false.
- Test database: `is_read_committed_snapshot_on = 0`, so plain locking READ COMMITTED. Shared locks are released at the end of each read.
- Password authentication writes nothing: `DaoAuthenticationProvider` has no password-upgrade service configured.

### Write order per path

`saveAttempt` runs in REQUIRES_NEW. **`markSuccess` is called inside `saveAttempt`, so it runs in the same REQUIRES_NEW transaction.**

| Path | Outer transaction | Order of writes |
|---|---|---|
| Password login (`AuthService.login`) | None | Reads only (`findByEmail`, rule counts, authentication). Then REQUIRES_NEW: INSERT `login_attempts`, then on success MERGE `user_auth_state`, then commit. |
| PIN login (`AuthService.pinLogin`) | None | Reads only (rule counts, `findById`). Then REQUIRES_NEW: INSERT, then MERGE on success, then commit. |
| Change password (`UserService.changePassword`) | None | Reads (`findById`, rule counts). Then REQUIRES_NEW: INSERT and MERGE, **committed**. Only after that, `userRepository.save(user)` updates the users row in its own repository transaction. |
| Manager PIN (`/admin/authorize`, `/admin/authorize-session`) | `@Transactional` | Outer: reads only (rule counts on the outer connection; active-admins query). Outer suspended. REQUIRES_NEW: INSERT, then MERGE (FK check on the **caller's** users row), then commit. Outer resumes and INSERTs `authorization_tokens` or `authorization_sessions`. |

**Conclusion:** no REQUIRES_NEW write to `user_auth_state` ever follows an outer-transaction write to the same users row. The outer transaction holds no locks the REQUIRES_NEW transaction could wait on, so the undetectable self-block can't happen.

The risky case is the **first** `user_auth_state` row for a user. Only the INSERT branch of the MERGE checks the foreign key; an UPDATE of `last_success_at` doesn't.

### Tests: new `LoginProtectionTransactionIntegrationTests` (7, all pass)

**Configuration:** `security.login-protection.mode=monitor` and `spring.datasource.hikari.connection-init-sql=SET LOCK_TIMEOUT 2000`. Any lock wait fails after 2 seconds instead of hanging, and every test has `@Timeout(30 s)` as a backstop.

**How a self-block would show:** recording errors are logged and swallowed by design, so a block would appear as a **missing** `login_attempts` row and a **missing** `last_success_at`. Every path test asserts both exist, using a fresh user with no state row so the foreign-key check runs.

| Test | Checks |
|---|---|
| `lockTimeoutIsActiveOnPooledConnections` | `@@LOCK_TIMEOUT` is 2000 |
| `passwordLogin_recordsWithoutWaitingOnTheCaller` | 200; SUCCESS row and `last_success_at` (PASSWORD) |
| `pinLogin_recordsWithoutWaitingOnTheCaller` | 200; SUCCESS row and `last_success_at` (PIN) |
| `changePassword_recordsBeforeTheUsersRowIsWritten` | 200; SUCCESS row and `last_success_at` (CHANGE_PASSWORD); the new password then logs in, proving the users row was written afterwards |
| `authorize_recordsInsideTheOuterTransaction` | 200 with `authorizationToken`; caller's SUCCESS row and `last_success_at` (MANAGER_PIN) |
| `authorizeSession_recordsInsideTheOuterTransaction` | 200 with `sessionToken`; same records |
| `negativeControl_outerWriteToUsersRow_isDetected` | Deliberately broken ordering (below) |

**The negative control:**
1. Inside a `TransactionTemplate`, it updates a user's users row.
2. It then records a SUCCESS for that user, who has no state row yet.
3. The REQUIRES_NEW foreign-key check waits on the outer transaction. After about 2 seconds it fails with SQL Server error 1222 (lock request time-out), which is logged and swallowed.
4. The test asserts that it waited at least about 1.9 s, and that **no** state row and **no** attempt row exist (the whole REQUIRES_NEW transaction rolled back).
5. The outer transaction is then rolled back.

This proves the harness detects the dangerous ordering. In the run, that negative control produced the only lock time-out error; the five real paths never waited.

## 5. Item 3: validation advice scope

### Finding

- `AdminController` has only `POST /admin/authorize`, `POST /admin/authorize-session` (both `@Valid AuthorizeRequest`) and `GET /admin/hello` (no body). There are no other `AdminController` endpoints that can raise a validation error.
- Other `/admin/**` routes belong to different controller classes. `LoginValidationAttemptAdvice` is scoped with `assignableTypes = {AuthController, AdminController}`, so it never handles their exceptions.
- The advice records only when the request body is `LoginRequest`, `PinLoginRequest` or `AuthorizeRequest`, and always returns `GlobalExceptionHandler.handleValidationErrors(...)`.
- **Baseline:** `git diff main` is empty for `GlobalExceptionHandler`, `AdminController`, `AuthController`, `AuthorizeRequest`, `LoginRequest`, `PinLoginRequest`, `CatalogCategoryController` and `CreateCatalogCategoryRequest`. So the expected bodies below are exactly what `main` returns.

### New `LoginValidationAdviceScopeIntegrationTests` (6, all pass, `mode=monitor`)

"Nothing recorded" is checked by comparing the `login_attempts` row count before and after the request.

| Request | Status and exact body | Recorded |
|---|---|---|
| `POST /admin/catalog/categories` with `{}` (another `/admin` controller) | 400 `{"parentId":"parentId is required","name":"name is required"}` | nothing |
| `POST /admin/authorize` with malformed JSON | 400 `{"error":"Invalid request body"}` | nothing |
| `GET /admin/hello` | 200 `Hello Admin!` | nothing |
| `POST /admin/authorize-session` with `{"pin":"12"}` | 400 `{"pin":"PIN must be 4-6 digits"}` | one MANAGER_PIN VALIDATION row |
| `POST /admin/authorize` with `{"pin":"abc"}` | 400 `{"pin":"PIN must be 4-6 digits"}` | one MANAGER_PIN VALIDATION row |
| `POST /auth/login` without a password | 400 `{"password":"Password is required"}` | one PASSWORD VALIDATION row |

### New `LoginValidationAttemptAdviceTest` (unit, Mockito, 4, all pass)

- **Any other request type:** records nothing (`verifyNoInteractions`), and the response equals `GlobalExceptionHandler`'s. This guards against a `@Valid` endpoint being added to `AdminController` later.
- **`LoginRequest`:** records PASSWORD with the trimmed, lower-cased email.
- **`PinLoginRequest`:** records PIN with the cashierId.
- **`AuthorizeRequest`:** records MANAGER_PIN with the caller's id from the security context.
- In every case the response equals `GlobalExceptionHandler`'s.

## 6. Item 4: PR description

Written as text only, outside the repository:
`C:\Users\Admin\AppData\Local\Temp\claude\beb7e7dd5606f46b\cf9ba1c8-29be-412a-9309-0d1d9c9b1279\scratchpad\ws1-1-pr-description.md`

It covers:
- Phase 0 and Phase 1
- the rules table
- behaviour guarantees, including the verified transaction ordering
- deployment notes: `LOGIN_PROTECTION_MODE`, `SERVER_PORT` now taking effect, `FLYWAY_*`, `SPRING_DATASOURCE_*` for the dev profile, `SPRING_PROFILES_ACTIVE=prod`
- test results (530 on a fresh database) and the new test classes
- open items

Nothing was pushed and no PR was opened.

## 7. Files

### Created

| Path | Purpose |
|---|---|
| `src/test/java/com/beaconculinary/api/support/TestStaffCleanup.java` | Deletes (or deactivates) test-created staff; sweeps leftovers by name prefix |
| `src/test/java/com/beaconculinary/api/auth/protection/LoginProtectionTransactionIntegrationTests.java` | Transaction-ordering tests with lock timeout and negative control |
| `src/test/java/com/beaconculinary/api/auth/protection/LoginValidationAdviceScopeIntegrationTests.java` | Advice scope, integration |
| `src/test/java/com/beaconculinary/api/auth/protection/LoginValidationAttemptAdviceTest.java` | Advice scope, unit |
| `…\scratchpad\ws1-1-pr-description.md` (outside the repo) | PR description text |
| `docs/2026-11-11-ws1-1-pre-merge-followups-report.md` | This report (uncommitted) |

### Modified (diffs approved before the change)

| Path | Change |
|---|---|
| `src/test/java/com/beaconculinary/api/auth/protection/LoginProtectionMonitorIntegrationTests.java` | Tracks and deletes created staff; one-time leftover sweep |
| `src/test/java/com/beaconculinary/api/auth/protection/LoginProtectionStoreIntegrationTests.java` | `@AfterEach` clears `login_attempts` and `user_auth_state` |

### Databases touched

| Database | What happened |
|---|---|
| `BeaconculinaryTest` (shared test DB) | Read-only diagnostic queries. Test runs; the sweep deleted 30 leftover "WS1 Monitor" staff and their manager-PIN tokens. |
| `BeaconculinaryTestWs11Clean` (temporary, local) | Created, full suite run, dropped. Confirmed gone. |

No other database was used. No `flyway:clean` was run.

## 8. Branch state

- 15 commits on top of `main`: 12 from Phase 0/1 plus 3 from this round.
- Uncommitted:
  - `docs/2026-11-11-ws1-1-phase0-1-change-report.md` (awaiting your go-ahead)
  - this report
  - `app-run.log` (already modified before WS1-1 started; never committed)

## 9. Open items (unchanged from the Phase 0/1 report)

1. Rotate the SQL password committed in git history (`e91d1c8`, `e16b8b3`, `96a290b`).
2. Check `SERVER_PORT` in the Azure app settings; it now takes effect.
3. Confirm the Azure ingress and set `security.login-protection.trusted-proxies` (monitor query 3).
4. Review thresholds using the monitor data (query 2, `would_have_blocked_a_success`).
5. Seed-account bootstrap and prod startup guard before production.
6. Separate tickets: `Role.USER` login path, `DELETE /users/{id}` hard delete, template leftovers, public login tiles, access tokens ignoring deactivation.
7. Phase 2:
   - `enforce` mode with 429, `Retry-After` and `ACCOUNT_LOCKED` / `RATE_LIMITED`
   - locks written via `applyLock`
   - Bucket4j burst filter
   - admin unlock endpoint, and lock state in `StaffDto`
   - 6-digit PINs enforced
   - refresh refused while locked
