# Login protection (WS1-1)

Protects the four credential checks against guessing: password login, PIN login, the manager PIN, and change-password. A per-address burst limit also covers every `/auth/**` request and the manager-PIN endpoints.

**Phase 1** recorded and evaluated every attempt in **monitor** mode. **Phase 2** adds **enforce** mode:
- locked or blocked attempts get HTTP 429 before any credential check
- account locks escalate
- admins can unlock
- PINs must be exactly 6 digits

`application.yaml` still defaults to `monitor`. Enforcement is switched on per environment by the owner (see "Switching enforcement on").

**Frontend contract:** [2026-11-11-login-protection-api-contract.md](2026-11-11-login-protection-api-contract.md).
**Recovery:** [2026-11-11-login-protection-recovery-runbook.md](2026-11-11-login-protection-recovery-runbook.md).

## Modes

Setting: `security.login-protection.mode` (environment variable `LOGIN_PROTECTION_MODE`).

| Mode | What happens |
|---|---|
| `off` | Nothing is checked or recorded, and no extra queries run. The burst limit is off. The test suite uses this by default. |
| `monitor` | Default in `application.yaml`. Every attempt is written to `login_attempts`. A would-be block sets `would_block = 1` and `rule_triggered`, and logs a WARN. The request always continues unchanged. No lock is written. The burst limit only logs. |
| `enforce` | Locked or blocked attempts get **429** before the credential check, and are recorded as `LOCKED` or `RATE_LIMITED`. A failure that reaches a LOCK rule writes the lock. The burst limit refuses requests with 429. |

**Fail open:** in every mode, an error inside protection code (for example the database briefly unavailable) is logged at ERROR and the request continues as if protection were off. Protection never blocks a login because of its own failure.

## Rules (decision D3)

Failures are the outcomes `BAD_CREDENTIAL`, `UNKNOWN_USER` and `DISABLED`. Refused attempts (`LOCKED`, `RATE_LIMITED`) and `VALIDATION` don't count.

**When counting starts:**
- Only failures after the user's latest **success** or **unlock** for that method count.
- For LOCK rules, counting also restarts at each lock, so every lock needs fresh failures.

With a threshold of 5, the 5th failure triggers the rule and the **6th** attempt is refused (enforce) or flagged (monitor).

| Rule | Method | Counted per | Default | Effect (enforce) | Code |
|---|---|---|---|---|---|
| `PASSWORD_ACCOUNT` | PASSWORD | lower-cased email, including unknown emails | 5 in 15 min | **Lock** 15 min, then 60 min, then until an admin unlocks | `ACCOUNT_LOCKED` |
| `PIN_PAIR` | PIN | cashierId + client IP | 5 in 15 min | **Block** that pair until the failures drop below 5 in the window | `RATE_LIMITED` |
| `PIN_ACCOUNT_CEILING` | PIN | cashierId, any source | 20 in 60 min | **Lock** the cashier's PIN sign-in (escalating), plus an alert | `ACCOUNT_LOCKED` |
| `MANAGER_PIN_CALLER` | MANAGER_PIN | calling user (JWT subject) | 5 in 15 min | **Block** that caller (window-based) | `RATE_LIMITED` |
| `MANAGER_PIN_GLOBAL` | MANAGER_PIN | all callers | 20 in 60 min | Alert only, never blocks | none |
| `CHANGE_PASSWORD_USER` | CHANGE_PASSWORD | user | 5 in 15 min | **Block** change-password (window-based); sign-in unaffected | `RATE_LIMITED` |

**Locks** (LOCK rules):
- Stored per user and method in `user_auth_state`.
- Escalate 15 min → 60 min → admin unlock.
- The level drops back after a success, an unlock, or 24 h (`escalation-reset`) without a new lock.
- **Unknown emails and cashier IDs** get the same locks, stored on the triggering `login_attempts` row (`lock_level`, `locked_until`, from V104). Responses are identical to a real account's, so they reveal nothing.

**Blocks** (BLOCK rules):
- Nothing is stored. A block lasts until enough failures leave the window.
- Retry-After is the time until the oldest counted failure is 15 minutes old.

When both PIN rules apply, the account lock wins.

**Refresh:** in enforce mode, `POST /auth/refresh` returns 401 while the user has an active PASSWORD or PIN lock. Access tokens already issued keep working until they expire (15 minutes).

## Burst limit

A Bucket4j token bucket per client IP on `/auth/**`, `/admin/authorize` and `/admin/authorize-session`. CORS preflight (`OPTIONS`) is never counted.

- **Defaults:** a burst of 60, refilled at 60 per minute. That's generous for one canteen whose tills share a NAT address.
- **Enforce:** an empty bucket gets 429 `RATE_LIMITED` with `Retry-After`. **Monitor:** a WARN only. **Off:** skipped.
- **No login_attempts row** for a burst refusal. At most one WARN per address per minute.
- **Bounded memory:** buckets live in a cache of at most 10,000 addresses, each dropped after 10 minutes idle.
- **Per instance:** each app instance has its own buckets.
- **Placement:** registered in `SecurityConfig` before `JwtAuthenticationFilter`, deliberately not as a `@Component`.

## Admin unlock

- **`POST /admin/staff/{id}/unlock`** (ADMIN). Optional body `{"scope":"PASSWORD|PIN|MANAGER_PIN|CHANGE_PASSWORD"}`; no body unlocks all four. It:
  - sets `lock_level = 0` and `locked_until = NULL`
  - records `unlocked_by` and `unlocked_at`
  - ends window blocks too, because failures before `unlocked_at` no longer count
- **Credential resets also unlock:** setting a new PIN clears `PIN`; setting a new password clears `PASSWORD` and `CHANGE_PASSWORD`.
- **No self-unlock:** an admin can't clear their own locks.
  - Unlocking yourself returns 409 `SELF_UNLOCK`.
  - Setting your own PIN or password through staff management saves it, but the lock stays.
  - Another admin must unlock you, or the lock must expire. Otherwise an admin whose password is locked could sign in by PIN and unlock it, so the lock would protect nothing against someone who knows the PIN.
  - An environment with a single active admin therefore needs the recovery runbook if that admin reaches level 3.
- **Lock state on the staff page:** `GET /admin/staff` and `/{id}` return:
  - `locks.PASSWORD` and `locks.PIN`, each `{locked, lockedUntil, requiresAdminUnlock}`: account locks
  - `blocks.PIN`, `blocks.MANAGER_PIN` and `blocks.CHANGE_PASSWORD`, each `{blocked, blockedUntil}`: short-term window blocks

  `blocks` is worked out from `login_attempts` on each request, with the same counting as the sign-in check (failures in the window, after the last success or unlock). `blocks.PIN` covers a block on any till, and `blockedUntil` is when the last one ends. Both objects are shown in enforce mode only.
- **Transactions:** unlock writes run in the staff-management transaction, never in a separate one. That transaction has already written the users row, and a separate one would wait on it.
- **Everyone locked out:** see the recovery runbook.

## Alerts

WARN lines with the stable marker `LOGIN_PROTECTION_ALERT`, for an Azure log alert:

| `type=` | When |
|---|---|
| `PIN_ACCOUNT_CEILING` | A cashier's PIN reaches the ceiling (enforce: locked; monitor: would lock) |
| `MANAGER_PIN_GLOBAL` | 20+ wrong manager PINs across all tills in 60 min |
| `ADMIN_UNLOCK_REQUIRED` | A level-3 lock was written (enforce) |
| `UNLOCK` | Any unlock: `reason=ADMIN_UNLOCK`, `PIN_RESET` or `PASSWORD_RESET`, with `by=` the admin |
| `SELF_UNLOCK_REFUSED` | An admin tried to unlock themselves, or reset their own PIN or password while locked (`reason=` as above). Worth a look: it may be someone using that admin's PIN. |

The log lines never contain a PIN or password. Emails appear only as `sha256:` plus 12 hex characters.

Suggested Azure query: `traces | where message has "LOGIN_PROTECTION_ALERT"`. Alert on any `ADMIN_UNLOCK_REQUIRED` or `MANAGER_PIN_GLOBAL`, and on bursts of `PIN_ACCOUNT_CEILING`.

## PIN length

`POST /auth/pin-login`, `/admin/authorize` and `/admin/authorize-session` accept exactly 6 digits; anything else gets 400 `{"pin":"PIN must be 6 digits"}`. This applies in every mode. Staff management already required 6 digits, and every seed PIN is 6 digits. `pin_non_standard` is therefore always 0 from Phase 2 on.

## Settings

All under `security.login-protection`. Invalid values stop the app at startup.

| Key | Default | Meaning |
|---|---|---|
| `mode` | `off` if unset; `monitor` in `application.yaml` | See above |
| `password-account.max-failures` / `.window` | 5 / 15m | `PASSWORD_ACCOUNT` |
| `pin-pair.max-failures` / `.window` | 5 / 15m | `PIN_PAIR` |
| `pin-account-ceiling.max-failures` / `.window` | 20 / 60m | `PIN_ACCOUNT_CEILING` |
| `manager-pin-caller.max-failures` / `.window` | 5 / 15m | `MANAGER_PIN_CALLER` |
| `manager-pin-global.max-failures` / `.window` | 20 / 60m | `MANAGER_PIN_GLOBAL` |
| `change-password-user.max-failures` / `.window` | 5 / 15m | `CHANGE_PASSWORD_USER` |
| `lock-durations` | `[15m, 60m]` | First and second lock; exactly two values. The third needs an admin. |
| `escalation-reset` | 24h | Lock level resets after this long without a lock |
| `trusted-proxies` | empty | IPs or CIDRs whose `X-Forwarded-For` is trusted |
| `burst.capacity` | 60 | Requests per address in one burst |
| `burst.refill-tokens` / `burst.refill-period` | 60 / 1m | Refill rate |
| `burst.max-tracked-clients` | 10000 | Bound on addresses tracked |
| `burst.idle-expiry` | 10m | A bucket is dropped after this long unused |
| `burst.warn-interval` | 1m | At most one burst WARN per address per interval |
| `retention-days` | 90 | `login_attempts` rows older than this are deleted (D5) |
| `retention-batch-size` | 5000 | Rows per delete statement |
| `retention-cron` | `0 30 3 * * *` | Daily at 03:30, Africa/Johannesburg |

Phase 1's `block-duration` is gone: blocks now last as long as their window requires.

## Client IP

`client_ip` is `request.getRemoteAddr()` unless the direct peer is listed in `trusted-proxies`. Then it is the right-most `X-Forwarded-For` entry that is not itself a trusted proxy. The search stops at the first malformed entry, and host names are never resolved. `server.forward-headers-strategy` is not enabled.

Until the Azure ingress is configured in `trusted-proxies`, every till may share one `client_ip`. That affects `PIN_PAIR` and the burst limit.

## Tables

All timestamps are UTC, written from the application clock.

**`login_attempts`** (V103, V104): one row per attempt (burst refusals excepted).
- `method`, `identifier` (lower-cased email, cashierId, or caller/user id), `user_id` (null for unknown users)
- `client_ip`, `remote_addr`, `forwarded_for`, `user_agent`; `device_id` is unused (D6)
- `outcome`: `SUCCESS`, `BAD_CREDENTIAL`, `UNKNOWN_USER`, `DISABLED`, `VALIDATION`, and in enforce `LOCKED`, `RATE_LIMITED`
- `would_block`: monitor, a rule would have refused this attempt; enforce, this attempt **was** refused
- `rule_triggered`: the rule that refused or flagged it, or (enforce, `would_block = 0`) the LOCK rule this failure triggered
- `lock_level`, `locked_until` (V104): set on the failure that triggered a lock; for unknown identifiers this is the lock itself
- `pin_non_standard`: successful PIN logins only (D4 evidence)

**`user_auth_state`** (V103): one row per user and method (`scope`).
- `last_success_at`
- `lock_level` (0–3), `locked_until`, `locked_at`, `lock_reason`, `unlocked_by`, `unlocked_at`
- "Admin unlock required" is `lock_level = 3` with `locked_until = 9999-12-31 23:59:59.9999999`, so "locked" is always `locked_until > now`
- Rows are deleted with their user (`ON DELETE CASCADE`)

Attempt rows and locks are written in their own transaction (`REQUIRES_NEW`), so a failure is kept even when the request rolls back. No sign-in path writes the users row before that write; the transaction tests verify it.

## Switching enforcement on

1. **Deploy** this version. It keeps `monitor`; V104 runs automatically and only adds two nullable columns.
2. **Set `trusted-proxies`** to the Azure ingress range, found with query 3 of the [monitor query pack](2026-11-11-login-protection-monitor-queries.md). Check that `client_ip` now differs per till.
3. **Review the monitor data:** query 2's `would_have_blocked_a_success` should be 0; query 5 (PIN length) should list nobody.
4. **Ship the frontend** that handles 429 (see the contract), and confirm the cross-origin `Retry-After` header and body are read.
5. **Set the Azure log alert** on `LOGIN_PROTECTION_ALERT`.
6. **Set `LOGIN_PROTECTION_MODE=enforce`** and restart.
7. **Watch** the alerts and `LOCKED` / `RATE_LIMITED` rows for the first days. If something goes wrong, set `monitor` and restart (runbook, option A).

## Reading the data

Use [the query pack](2026-11-11-login-protection-monitor-queries.md):
- **Ingress:** query 3 shows whether traffic arrives from one proxy address.
- **False positives:** query 2's `would_have_blocked_a_success`. In enforce, look for genuine users among `LOCKED` and `RATE_LIMITED` rows.
- **Attacks:** query 4 lists identifiers with many failures. The alerts carry the same signal.

## Running the tests

The suite runs with protection off (`LoginProtectionTestDefaults`, registered in `src/test/resources/META-INF/spring.factories`). Tests opt in with `@TestPropertySource`:

| Mode | Tests |
|---|---|
| `monitor` | `LoginProtectionMonitorIntegrationTests`, `LoginValidationAdviceScopeIntegrationTests` |
| `enforce` | `LoginProtectionEnforceIntegrationTests`, `LoginUnlockIntegrationTests` (test clock; fail on any protection ERROR), `LoginBurstFilterIntegrationTests` |
