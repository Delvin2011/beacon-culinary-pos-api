# WS1-1 Phase 0 and Phase 1: change report

Handover report for the WS1-1 login protection task (Phase 0: code hygiene; Phase 1: foundation in monitor-only mode).

- **Repository:** POS_BE (Spring Boot 3.4.1, Java 17, SQL Server, Flyway)
- **Branch:** `feature/ws1-1-login-protection`, created from `main` at `f9cc9f2`. Not pushed, not merged.
- **Size:** 12 commits; 37 files changed, 2,426 insertions, 21 deletions.
- **Date:** 2026-11-11
- **Secrets:** none appear in this report. Credential values are shown as [REDACTED].

## 1. Result

| Check | Result |
|---|---|
| Full existing suite with `mode=off` | 513 tests: 0 failures, 0 errors, 1 skipped (pre-existing assumption, see §8). BUILD SUCCESS. |
| New monitor-mode integration tests | 10 of 10 pass |
| New unit tests | 26 of 26 pass (IP resolver 12, rule evaluator 9, properties 5) |
| New store and retention integration tests | 4 of 4 pass |
| Suite-default check (`mode=off` records nothing) | 1 of 1 passes |
| Migration V103 | Applied cleanly to the test database (`BeaconculinaryTest`). Not run against any other database. |
| User-facing behaviour | Unchanged: same status codes, response bodies, exceptions and dummy-hash timing path. Nothing blocks a login. |

### Acceptance criteria

| Criterion | Status |
|---|---|
| Flyway clean disabled | Done (`pom.xml`, `cleanDisabled=true`) |
| No credentials in committed config files (seed migrations excepted and documented) | Done for `pom.xml` and `application-dev.yaml`. Seeds documented in the go-live checklist. |
| Swagger off in prod | Done (`application-prod.yaml`) |
| Go-live checklist and seed-account proposal written | Done |
| `mode=monitor`: one row per attempt on the four paths, correct outcome, IP fields and `would_block`; behaviour identical | Done, covered by `LoginProtectionMonitorIntegrationTests` |
| `mode=off`: nothing recorded; full suite passes | Done |
| Migration applies cleanly to the test database | Done |
| Docs and monitor query pack written | Done |

## 2. Commits (oldest first)

| Commit | Step | Message |
|---|---|---|
| `9791f79` | 0-E, 0-F | docs: go-live checklist and seed-account proposal |
| `ff32913` | 1-A | db: V103 login_attempts and user_auth_state |
| `3885bab` | 1-B, 1-C | auth: login protection properties and ClientIpResolver |
| `e29cec3` | 1-D, 1-G | auth: LoginAttemptService, rule evaluation and retention job |
| `b890359` | 0-A | build: disable flyway clean and remove hard-coded plugin credentials |
| `87c864f` | 0-B | config: remove literal dev datasource credentials; add .env.example |
| `8782c8e` | 0-C | config: disable Swagger/OpenAPI in prod profile |
| `dc6ed0e` | 0-D | config: move server.port to top level; fix dev flyway location |
| `91769ca` | 1-B | config: login protection monitor by default, off in tests |
| `b51e362` | 1-E, 1-F | auth: record attempts on password, PIN, manager PIN and change-password |
| `4435745` | 1-H | test: login protection monitor-mode integration tests |
| `9abb788` | 1-I, 1-J | docs: login protection guide and monitor query pack |

The commits are not in step-number order. New-file steps were committed while the diffs for existing files waited for confirmation (working rule 2). Some steps share a commit because their classes depend on each other:
- 1-B and 1-C: the properties class validates proxy entries using `ClientIpResolver`, and the resolver reads the properties.
- 1-D and 1-G: one integration test covers both the store and the retention job.

## 3. Decisions taken during planning

All were approved as recommended.

| # | Question | Decision |
|---|---|---|
| 1 | No test profile exists. How is `mode=off` set for tests? | A test-only `EnvironmentPostProcessor` (`LoginProtectionTestDefaults`), registered in `spring.factories`. Its property source sits just after Spring's "Inlined Test Properties", so it beats `application.yaml`, `.env` and environment variables, while `@TestPropertySource` and `@SpringBootTest(properties=…)` still override it. |
| 2 | `@Valid` failures never reach the service. How is VALIDATION recorded? | A new `@ControllerAdvice` scoped to `AuthController` and `AdminController`, at highest precedence. It records the attempt, then delegates to the existing `GlobalExceptionHandler.handleValidationErrors`, so the response is identical. |
| 3 | Foreign keys vs `DELETE /users/{id}` (hard delete, reachable by admins) | `login_attempts.user_id`: no FK. `user_auth_state.user_id`: FK with `ON DELETE CASCADE`. `unlocked_by`: no FK (SQL Server rejects a second cascade path to `users`). |
| 4 | When does the lock escalation level reset? | After a success for that scope, or after `escalation-reset` (24 h) with no new lock. |
| 5 | Which outcomes count as failures? | `BAD_CREDENTIAL`, `UNKNOWN_USER`, `DISABLED`. Not `VALIDATION`, `LOCKED`, `RATE_LIMITED`. |
| 6 | 0-B: the dev profile's literal datasource pointed at a different database than `.env` | Removed the literal values; the dev profile now inherits `SPRING_DATASOURCE_*` from `.env` like the default profile. |
| 7 | 0-D: moving `server.port` makes `SERVER_PORT` take effect | Moved. Nothing in the repo sets `SERVER_PORT`; the default stays 8080. Azure app settings still need checking (§8). |
| 8 | Dev Flyway location typo | Fixed (`db.migration` → `db/migration`). Safe because Flyway is disabled in that profile. |

Other design choices:
- **Admin-unlock encoding:** `lock_level = 3` with `locked_until = 9999-12-31 23:59:59.9999999`, so "locked" is always the single test `locked_until > now`.
- **Clock:** all timestamps are UTC `DATETIME2`, written with `UtcTimestamps.nowUtc(clock)` from the existing `Clock` bean, which matches the V84 convention.
- **Deviation from the plan:** `ClientIpResolver` stops at the first malformed `X-Forwarded-For` entry and uses the peer address, rather than skipping the entry. Skipping would let a forged left-hand entry be treated as the client.

## 4. Phase 0: code hygiene

### 0-A Flyway Maven plugin (`pom.xml`)

- Removed the hard-coded `<url>`, `<user>` and `<password>` ([REDACTED]). Flyway reads `FLYWAY_URL`, `FLYWAY_USER` and `FLYWAY_PASSWORD` from the environment natively.
- `<cleanDisabled>` changed from `false` to `true`.
- New `docs/2026-11-11-running-flyway-locally.md`: how to set the variables in PowerShell and Git Bash, safe commands (`info`, `validate`, `migrate`), and what never to do (`clean`, editing applied migrations, pointing at shared Azure databases).

### 0-B Dev datasource credentials (`application-dev.yaml`, `.env.example`)

- Removed the literal datasource `url`, `username` and `password` ([REDACTED]), with a comment that they come from `SPRING_DATASOURCE_*`.
- New `.env.example` with variable names only: `JWT_SECRET`, `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `TEST_DATASOURCE_URL`, `CORS_ALLOWED_ORIGINS`, `SERVER_PORT`, `LOGIN_PROTECTION_MODE`, `FLYWAY_URL`, `FLYWAY_USER`, `FLYWAY_PASSWORD`.
- Confirmed `.env` is git-ignored (`.gitignore:6`) and has never been committed. `.env.example` is not ignored.
- **Git history (report only, not rewritten):**
  - literal datasource passwords in `e91d1c8` (`pom.xml`, `application.yaml`), `e16b8b3` (`application-dev.yaml`) and `96a290b` (`application-dev.yaml`)
  - the JWT secret and the old Stripe keys were only ever placeholders
  - **action needed:** rotate that SQL password

### 0-C Swagger (`application-prod.yaml`)

- Added `springdoc.api-docs.enabled: false` and `springdoc.swagger-ui.enabled: false` for the `prod` profile.
- The default and dev profiles keep Swagger. `SwaggerSecurityRules` is unchanged; its paths simply return 404 in prod.

### 0-D `application.yaml` and `application-dev.yaml`

- `server.port` moved from under `spring:` (where it was ignored) to the top level: `server.port: ${SERVER_PORT:8080}`.
- Dev profile `spring.flyway.locations` corrected to `classpath:db/migration`.

### 0-E and 0-F Go-live checklist and seed accounts (report only)

New `docs/2026-11-11-go-live-checklist.md`. No migration or data was changed.

**Production blockers listed** (each with what, where, why, proposed fix, owner TBD):
1. seed accounts with one shared credential
2. credentials in config and git history
3. Flyway clean
4. Swagger exposure
5. `Role.USER` password-login path
6. `DELETE /users/{id}` hard delete
7. template leftovers: `HomeController`, `/admin/hello`, `Address` entity, legacy `/users` endpoints
8. public login-tile endpoints
9. access tokens ignoring deactivation for 15 minutes
10. production profile not confirmed

**Seed migrations:**

| Migration | Accounts |
|---|---|
| V13 | Canteen Cashier, Canteen Admin |
| V14 | PINs for the V13 accounts, plus Canteen Cashier B |
| V32 | Kitchen Station |
| V34 | Resets every credential to one shared value |
| V69 | Stock Clerk, Stock Admin |
| V89 | Head Chef |
| V98 | Canteen Barista |

No IDs are forced with `IDENTITY_INSERT`. V58 looks up the seeded admin by email to fill `created_by`.

**Test dependencies:** `AuthTestHelper` uses the seed accounts. Counts of test files: admin 48, cashier 32, stock admin 14, kitchen 13, cashier B 10, stock clerk 9, head chef 7, barista 2. `StaffManagementIntegrationTests` also uses seed emails directly.

**Options compared:**
- **(a) Move seeds to a dev/test-only Flyway location.** Rejected. V13, V14 and V89 mix schema changes with seed rows, so splitting them changes checksums. V58 needs the seeded admin. Business seed data is tangled in as well.
- **(b) Production bootstrap plus a prod-profile startup guard.** Recommended, about 2–3 days. The guard refuses to start while any seed account is active. A one-time bootstrap creates the first real admin and deactivates the seeds. A forced first-login password change needs a future `must_change_password` column.

## 5. Phase 1: foundation in monitor mode

### 1-A Migration `V103__login_protection.sql`

V103 was the next free number (V102 was the highest).

**`login_attempts`**
- Columns: `id` BIGINT IDENTITY PK; `attempted_at` DATETIME2 NOT NULL (default `SYSUTCDATETIME()` as a fallback); `method` VARCHAR(20) NOT NULL; `identifier` NVARCHAR(255); `user_id` BIGINT (no FK); `client_ip` VARCHAR(45); `remote_addr` VARCHAR(45); `forwarded_for` NVARCHAR(512); `user_agent` NVARCHAR(512); `device_id` NVARCHAR(100) (unused, D6); `outcome` VARCHAR(30) NOT NULL; `would_block` BIT NOT NULL DEFAULT 0; `rule_triggered` VARCHAR(50); `pin_non_standard` BIT.
- CHECK on `method`: PASSWORD, PIN, MANAGER_PIN, CHANGE_PASSWORD.
- CHECK on `outcome`: SUCCESS, BAD_CREDENTIAL, UNKNOWN_USER, DISABLED, LOCKED, RATE_LIMITED, VALIDATION.
- Indexes: (`attempted_at`); (`method`, `identifier`, `attempted_at`); (`method`, `user_id`, `attempted_at`); (`client_ip`, `attempted_at`).

**`user_auth_state`**
- Columns: `user_id` (FK to `users`, `ON DELETE CASCADE`); `scope` (CHECK: the four methods); `lock_level` INT NOT NULL DEFAULT 0 (CHECK 0–3); `locked_until`; `locked_at`; `lock_reason`; `unlocked_by` (no FK); `unlocked_at`; `last_success_at`.
- Primary key: (`user_id`, `scope`).

### 1-B Settings: `LoginProtectionProperties` (`security.login-protection.*`)

`@Validated`; an invalid value stops the app at startup.

| Key | Default |
|---|---|
| `mode` | `OFF` when unset; `application.yaml` sets `${LOGIN_PROTECTION_MODE:monitor}`; tests use `off` |
| `password-account` | 5 failures / 15 min |
| `pin-pair` | 5 / 15 min |
| `pin-account-ceiling` | 20 / 60 min |
| `manager-pin-caller` | 5 / 15 min |
| `manager-pin-global` | 20 / 60 min |
| `change-password-user` | 5 / 15 min |
| `block-duration` | 15 min |
| `lock-durations` | exactly two values: [15 min, 60 min]; the third lock needs an admin |
| `escalation-reset` | 24 h |
| `trusted-proxies` | empty; IPs or CIDRs only, validated |
| `retention-days` | 90 |
| `retention-batch-size` | 5000 |
| `retention-cron` | placeholder default `0 30 3 * * *`, Africa/Johannesburg |

`mode=enforce` fails startup with "not available until WS1-1 Phase 2". Durations must be positive.

Supporting classes: `LoginProtectionMode` (OFF, MONITOR, ENFORCE) and `LoginProtectionConfig` (`@EnableConfigurationProperties`).

### 1-C `ClientIpResolver`

- Uses `request.getRemoteAddr()` by default.
- When the peer is a trusted proxy, uses the right-most `X-Forwarded-For` entry that is not itself a trusted proxy.
- Stops at the first malformed entry and falls back to the peer address.
- Handles IPv4, IPv6, `IPv4:port` and `[IPv6]:port`.
- Never resolves host names; only IP literals reach Spring Security's `IpAddressMatcher`.
- `server.forward-headers-strategy` is not enabled.

### 1-D `LoginAttemptService` and supporting classes

**`LoginAttemptService.begin(method, identifier, userId)`**
- Runs before the credential check and returns a `LoginAttempt` handle.
- Evaluates the rules from `login_attempts` failure counts in each window, counting only failures after `user_auth_state.last_success_at` (a success resets the count).
- Runs the same queries whether or not the user exists, so it adds no timing difference.

**`LoginAttemptService.finish(attempt, outcome, userId[, pinNonStandard])`**
- Records one row. On a success by a known user, it also updates `last_success_at` with a single `MERGE … WITH (HOLDLOCK)`.

**Behaviour by mode**
- `off`: returns immediately; no queries.
- `monitor`: sets `would_block` and `rule_triggered`, and logs a WARN with the rule, user id, a short SHA-256 hash for emails, IP, counts and the would-be lock. It never writes lock columns, and the request always continues.
- Any error while checking or recording is logged and ignored; a login never fails because of protection.

**Escalation in monitor mode**
- Worked out from earlier would-be locks, because none is written.
- A lock "starts" at an attempt flagged with a LOCK rule whose previous attempt for the same key was not (SQL `LAG`).
- An attempt directly after a flagged attempt continues that lock instead of starting the next level.

**Other classes**
- **`LoginProtectionStore` (JdbcTemplate):** failure counts, lock-start count, latest rule, last success.
  - `saveAttempt` runs in `REQUIRES_NEW`, so a failure is kept even when the caller's transaction rolls back (this matters for `AdminAuthorizationService.authorize`).
  - `markSuccess` uses a MERGE.
  - `applyLock` (Phase 2 only, not called in monitor) is a single conditional `UPDATE … CROSS APPLY` that escalates 1 → 2 → 3 and does nothing while a lock is still running, with an insert fallback. It is tested.
- **`LoginRuleEvaluator`:** the D3 rules as pure functions, plus lock level and duration helpers.
- **`LoginRule`:** PASSWORD_ACCOUNT (LOCK), PIN_PAIR (BLOCK), PIN_ACCOUNT_CEILING (LOCK + alert), MANAGER_PIN_CALLER (BLOCK), MANAGER_PIN_GLOBAL (ALERT only), CHANGE_PASSWORD_USER (BLOCK). When both PIN rules trigger, the ceiling wins.
- **Small types:** `LoginMethod`, `LoginOutcome` (with `isFailure()`), `LoginDecision`, `LoginAttempt`, `LoginRequestInfo` (values truncated to column sizes).

### 1-E and 1-F Wiring into existing code

| File | Change |
|---|---|
| `auth/AuthService.java` `login` | When protection is on, looks the account up by email on every attempt. Calls `begin(PASSWORD, lower-cased email, accountId)` and catches `AuthenticationException` to record it, then rethrows the same exception. Outcomes: `DisabledException` → DISABLED; no account, or a PIN-only account with no password → UNKNOWN_USER; otherwise BAD_CREDENTIAL. Success is recorded after the tokens are issued. |
| `auth/AuthService.java` `pinLogin` | `begin(PIN, cashierId, cashierId)` before the lookup. Failure outcomes: UNKNOWN_USER, DISABLED or BAD_CREDENTIAL, then the same `BadCredentialsException` as before. The dummy-hash path is untouched. On success, `pin_non_standard = (PIN length != 6)`; the PIN is never stored or logged. |
| `admin/AdminAuthorizationService.java` `matchAdmin` | The identifier and `user_id` are the calling user (JWT subject). Records BAD_CREDENTIAL then throws the same `InvalidPinException`, or records SUCCESS. Covers `/admin/authorize` and `/admin/authorize-session`. |
| `users/UserService.java` `changePassword` | After the own-account check: BAD_CREDENTIAL before the same `AccessDeniedException`, or SUCCESS once the old password matches (recorded before the new password is validated). |
| `auth/protection/LoginValidationAttemptAdvice.java` (new) | Records VALIDATION for `LoginRequest`, `PinLoginRequest` and `AuthorizeRequest`, then returns the existing handler's response. |

Each of the three services gained a `LoginAttemptService loginAttempts` constructor field (Lombok `@AllArgsConstructor`). No test constructs these services by hand.

### 1-G Retention

- `LoginAttemptRetentionJob` runs daily at 03:30 Africa/Johannesburg.
- It deletes rows older than `retention-days` in batches (`DELETE TOP (n)`), up to 1,000 batches per run.
- Scheduling was not enabled before. New `common/SchedulingConfig` adds `@EnableScheduling`; this is the app's first scheduled job.
- With several instances, each runs the job. The delete is idempotent, so that is harmless.

### 1-H Tests

| Test | Type | Covers |
|---|---|---|
| `ClientIpResolverTest` | unit (12) | no proxies, trusted chain, spoofed header from an untrusted peer, injected left-hand entry, malformed entries, ports, IPv6, proxy-entry validation |
| `LoginRuleEvaluatorTest` | unit (9) | each rule's boundary (5 allows, the 6th would block), ceiling precedence, global alert only, configurable thresholds, escalation 15m → 60m → admin, failure outcomes |
| `LoginProtectionPropertiesTest` | unit (5) | defaults valid and off; enforce refused; invalid proxies; lock durations count and positivity; thresholds |
| `LoginProtectionStoreIntegrationTests` | integration (4) | `applyLock` escalation and no-op while locked; reset after success or 24 h; `markSuccess` upsert per scope; retention deletes only rows older than 90 days |
| `LoginProtectionOffIntegrationTests` | integration (1) | the suite default is off and records nothing |
| `LoginProtectionMonitorIntegrationTests` | integration (10), `mode=monitor` | see below |

Detail of the monitor tests:
- **PASSWORD:** unknown email (lower-cased, `user_id` null, 6th attempt flagged); known user (5 failures, 6th flagged but still succeeds with 200, then the reset).
- **PIN:** pair rule at the 6th attempt from one IP, then the ceiling at the 21st across IPs; unknown cashier; `pin_non_standard` false for a 6-digit PIN and true for a 4-digit PIN; VALIDATION with an unchanged 400 body.
- **MANAGER_PIN:** caller rule at the 6th, success, reset; global alert across 6 callers with no block.
- **CHANGE_PASSWORD:** rule at the 6th, unchanged 401 responses, then success.
- **Network fields:** `X-Forwarded-For` and `User-Agent` stored raw but not trusted without configured proxies.
- **Test data:** staff created with random credentials generated at run time. No PIN or password is written in the test code, and the seed accounts' counters are untouched. Both tables are emptied before each test.

### 1-I and 1-J Docs

- `docs/2026-11-11-login-protection-monitor-queries.md`: read-only T-SQL for failures per hour by method; would-block events by rule, including "would have blocked a success" (false positives); alert-only events; distinct `remote_addr` and `forwarded_for` values and user agents (to identify the Azure ingress); top identifiers by failures; PIN-length evidence; health checks.
- `docs/2026-11-11-login-protection.md`: modes, rules table, settings, client-IP logic, tables and lock encoding, outcomes per path, how to read the monitor data, what Phase 2 adds, how tests opt in.

## 6. Files

### Created (29)

| Path | Purpose |
|---|---|
| `.env.example` | Variable names, no values |
| `docs/2026-11-11-go-live-checklist.md` | Production blockers and seed-account proposal |
| `docs/2026-11-11-running-flyway-locally.md` | Flyway plugin usage |
| `docs/2026-11-11-login-protection.md` | Feature guide |
| `docs/2026-11-11-login-protection-monitor-queries.md` | Monitor query pack |
| `src/main/resources/db/migration/V103__login_protection.sql` | Tables and indexes |
| `src/main/java/com/beaconculinary/api/auth/protection/ClientIpResolver.java` | Client IP |
| `…/auth/protection/LoginAttempt.java` | Attempt handle |
| `…/auth/protection/LoginAttemptRetentionJob.java` | Daily purge |
| `…/auth/protection/LoginAttemptService.java` | Check and record |
| `…/auth/protection/LoginDecision.java` | Evaluation result |
| `…/auth/protection/LoginMethod.java` | Enum |
| `…/auth/protection/LoginOutcome.java` | Enum |
| `…/auth/protection/LoginProtectionConfig.java` | Enables the properties |
| `…/auth/protection/LoginProtectionMode.java` | Enum |
| `…/auth/protection/LoginProtectionProperties.java` | Settings |
| `…/auth/protection/LoginProtectionStore.java` | SQL |
| `…/auth/protection/LoginRequestInfo.java` | Network fields |
| `…/auth/protection/LoginRule.java` | D3 rules |
| `…/auth/protection/LoginRuleEvaluator.java` | Pure rule logic |
| `…/auth/protection/LoginValidationAttemptAdvice.java` | VALIDATION recording |
| `src/main/java/com/beaconculinary/api/common/SchedulingConfig.java` | `@EnableScheduling` |
| `src/test/java/com/beaconculinary/api/auth/protection/ClientIpResolverTest.java` | Unit |
| `…/auth/protection/LoginRuleEvaluatorTest.java` | Unit |
| `…/auth/protection/LoginProtectionPropertiesTest.java` | Unit |
| `…/auth/protection/LoginProtectionStoreIntegrationTests.java` | Integration |
| `…/auth/protection/LoginProtectionOffIntegrationTests.java` | Integration |
| `…/auth/protection/LoginProtectionMonitorIntegrationTests.java` | Integration |
| `src/test/java/com/beaconculinary/api/support/LoginProtectionTestDefaults.java` | Tests default to off |

### Modified (8)

| Path | Change |
|---|---|
| `pom.xml` | Flyway plugin credentials removed; `cleanDisabled=true` |
| `src/main/resources/application.yaml` | `server.port` moved to the top level; `security.login-protection` block added (mode monitor, trusted-proxies empty, retention 90) |
| `src/main/resources/application-dev.yaml` | Literal datasource credentials removed; Flyway location typo fixed |
| `src/main/resources/application-prod.yaml` | Swagger and API docs disabled |
| `src/main/java/com/beaconculinary/api/auth/AuthService.java` | Recording on password and PIN login |
| `src/main/java/com/beaconculinary/api/admin/AdminAuthorizationService.java` | Recording on manager PIN |
| `src/main/java/com/beaconculinary/api/users/UserService.java` | Recording on change-password |
| `src/test/resources/META-INF/spring.factories` | Registers `LoginProtectionTestDefaults` |

No applied migration (V1–V102) was edited. No history was rewritten. Flyway `clean` was never run.

## 7. Behaviour guarantees

- With `mode=off`, `begin` and `finish` return immediately, and the extra account lookup in password login is skipped. The code paths match `main`, apart from the added calls.
- With `mode=monitor`, responses, status codes, bodies, thrown exceptions and the PIN dummy-hash comparison are unchanged. Would-be blocks are only flagged and logged.
- The PIN, its length (beyond the `pin_non_standard` flag) and passwords are never stored or logged. Emails appear in logs only as a short SHA-256 hash; they are stored in `login_attempts.identifier` for counting.
- Recording failures cannot break a login. They are logged at ERROR and ignored.

## 8. Open items and risks

1. **Skipped test.** `StaffManagementIntegrationTests.deactivatedStaffCantLogIn_andTheLastAdminCantBeDeactivated` skips itself unless the seeded admin is the only active admin. The shared test database already has extra active admins from earlier runs, and `LoginProtectionMonitorIntegrationTests.managerPin_callerRuleAtSix…` adds one more per run. A possible fix: deactivate the admin that test creates. This is not done yet because it means editing a committed file without asking.
2. **Rotate the SQL password** that appears in git history (`e91d1c8`, `e16b8b3`, `96a290b`).
3. **Azure `SERVER_PORT`.** It now takes effect if set. Check the Azure app settings before deploying.
4. **Trusted proxies are empty** until the Azure ingress is confirmed (D7). Until then `client_ip` equals `remote_addr`, and every till may share one address, which affects `PIN_PAIR`. Use query 3 in the query pack to find the ingress, then set `trusted-proxies`.
5. **Extra load.** In monitor mode each attempt adds 2–4 indexed queries plus one insert on a second pooled connection (`REQUIRES_NEW`). That's acceptable for the expected load; check the logs during monitoring.
6. **Monitor escalation is an estimate.** Without real locks, attempts keep being authenticated, so the logged would-be lock level approximates what enforce mode would do.
7. **Dev profile database.** The dev profile now connects to whatever `.env` names. Its old literal values pointed at a different database name.

## 9. Next steps

1. Deploy the branch to Azure dev with `mode=monitor`, after review and merge by the team.
2. Run the query pack during the monitoring period: confirm the ingress and set `trusted-proxies`; check false positives (`would_have_blocked_a_success`); check PIN-length evidence.
3. **Phase 2 (out of scope here):**
   - `mode=enforce` with 429, `Retry-After` and `ErrorDto` codes `ACCOUNT_LOCKED` / `RATE_LIMITED` (D2)
   - locks written through `LoginProtectionStore.applyLock`
   - a Bucket4j per-IP burst filter
   - an admin unlock endpoint, and lock state in `StaffDto`
   - exactly 6 digits enforced for PINs
   - refresh refused while locked
4. **Separate tickets from the go-live checklist:**
   - seed-account bootstrap and startup guard
   - `Role.USER` login
   - `DELETE /users/{id}`
   - template leftovers
   - public login tiles
