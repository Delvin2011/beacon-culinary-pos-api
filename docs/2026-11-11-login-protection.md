# Login protection (WS1-1)

Protects the four credential checks against guessing: password login, PIN login, the manager PIN, and change-password. **Phase 1 runs in monitor mode: it records and evaluates every attempt, but never blocks one.** What users see, the status codes and the response bodies are exactly as before.

## Modes

Setting: `security.login-protection.mode` (environment variable `LOGIN_PROTECTION_MODE`).

| Mode | What happens |
|---|---|
| `off` | Nothing is checked or recorded, and no extra queries run. The test suite uses this by default. |
| `monitor` | Default in `application.yaml`. Every attempt is written to `login_attempts`. Rules are evaluated before the credential check; a would-be block sets `would_block = 1` and `rule_triggered`, and logs a WARN. The request always continues unchanged. No lock is written; the lock that would have applied is logged. |
| `enforce` | Phase 2. Setting it now stops the app at startup with a clear message. |

If recording fails (for example the database is briefly unavailable), the error is logged and the login carries on.

## Rules (decision D3)

Failures are the outcomes `BAD_CREDENTIAL`, `UNKNOWN_USER` and `DISABLED`. A success resets the count for that user and method: only failures after `user_auth_state.last_success_at` are counted. With a threshold of 5, the **6th** attempt in the window is the first that would be blocked.

| Rule | Method | Counted per | Default | Effect |
|---|---|---|---|---|
| `PASSWORD_ACCOUNT` | PASSWORD | lower-cased email, including unknown emails | 5 in 15 min | Lock: 15 min, then 60 min, then until an admin unlocks |
| `PIN_PAIR` | PIN | cashierId + client IP | 5 in 15 min | Block that pair for 15 min |
| `PIN_ACCOUNT_CEILING` | PIN | cashierId, any source | 20 in 60 min | Lock the account's PIN (escalating) and alert |
| `MANAGER_PIN_CALLER` | MANAGER_PIN | calling user (JWT subject) | 5 in 15 min | Block that caller for 15 min |
| `MANAGER_PIN_GLOBAL` | MANAGER_PIN | all callers | 20 in 60 min | Alert only, never blocks |
| `CHANGE_PASSWORD_USER` | CHANGE_PASSWORD | user | 5 in 15 min | Block change-password for 15 min (login unaffected) |

When both PIN rules trigger, the ceiling is recorded. Escalation: the lock level returns to 0 after a success, or after `escalation-reset` (24 h) with no new lock.

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
| `block-duration` | 15m | Duration of BLOCK rules |
| `lock-durations` | `[15m, 60m]` | First and second lock; exactly two values. The third lock needs an admin. |
| `escalation-reset` | 24h | Lock level resets after this long without a lock |
| `trusted-proxies` | empty | IPs or CIDRs whose `X-Forwarded-For` is trusted |
| `retention-days` | 90 | `login_attempts` rows older than this are deleted (D5) |
| `retention-batch-size` | 5000 | Rows per delete statement |
| `retention-cron` | `0 30 3 * * *` | Daily at 03:30, Africa/Johannesburg |

## Client IP

`client_ip` is `request.getRemoteAddr()` unless the direct peer is listed in `trusted-proxies`. In that case it is the right-most `X-Forwarded-For` entry that is not itself a trusted proxy. The search stops at the first malformed entry, and host names are never resolved. `server.forward-headers-strategy` is not enabled.

The Azure ingress is not yet confirmed (D7), so `trusted-proxies` is empty and `client_ip` equals `remote_addr`. The raw `remote_addr`, `forwarded_for` and `user_agent` are stored so the monitoring data shows what the ingress sends; see query 3 in the query pack. Once confirmed, add the ingress address range to `trusted-proxies`. Until then, every till may share one `client_ip`, which affects `PIN_PAIR`.

## Tables (V103)

All timestamps are UTC, written from the application clock.

**`login_attempts`**: one row per attempt.
- `method`, `identifier` (lower-cased email, cashierId, or caller/user id), `user_id` (null for unknown users)
- `client_ip`, `remote_addr`, `forwarded_for`, `user_agent`; `device_id` is unused (D6)
- `outcome`: `SUCCESS`, `BAD_CREDENTIAL`, `UNKNOWN_USER`, `DISABLED`, `VALIDATION`; `LOCKED` and `RATE_LIMITED` arrive with Phase 2
- `would_block`, `rule_triggered`
- `pin_non_standard`: on successful PIN logins only, 1 when the PIN is not 6 digits (D4 evidence). The PIN and its length are stored nowhere else.

There is no foreign key on `user_id`: the audit outlives a deleted user. Rows are written in their own transaction, so a failure is kept even when the surrounding request rolls back.

**`user_auth_state`**: one row per user and method (`scope`).
- `last_success_at`: the only column written in monitor mode
- `lock_level` (0–3), `locked_until`, `locked_at`, `lock_reason`, `unlocked_by`, `unlocked_at`: written in enforce mode only
- "Admin unlock required" is `lock_level = 3` with `locked_until = 9999-12-31 23:59:59.9999999`, so "locked" is always `locked_until > now`
- Rows are deleted with their user (`ON DELETE CASCADE`)

## Recorded outcomes per path

| Path | Outcomes |
|---|---|
| `POST /auth/login` | `SUCCESS`; `UNKNOWN_USER` (no such email, or a PIN-only user with no password); `DISABLED`; `BAD_CREDENTIAL`; `VALIDATION` |
| `POST /auth/pin-login` | `SUCCESS`; `UNKNOWN_USER`; `DISABLED`; `BAD_CREDENTIAL`; `VALIDATION` |
| `POST /admin/authorize`, `/admin/authorize-session` | `SUCCESS`; `BAD_CREDENTIAL` (no admin has that PIN); `VALIDATION`. `identifier` and `user_id` are the caller. |
| `POST /users/{id}/change-password` | `SUCCESS` (old password correct; recorded before the new password is validated); `BAD_CREDENTIAL`. Not recorded when someone tries to change another user's password. |

## Reading the monitor data

Use [the query pack](2026-11-11-login-protection-monitor-queries.md). During the monitoring period, check:

1. **Ingress:** query 3 shows whether all traffic arrives from one proxy address. If so, configure `trusted-proxies` and re-check that `client_ip` now varies per till.
2. **False positives:** in query 2, any `would_have_blocked_a_success` greater than 0 means a genuine user would have been locked out. Tune thresholds before enforcing.
3. **Attacks:** query 4 shows identifiers with many failures. Unknown emails and the `PIN_ACCOUNT_CEILING` / `MANAGER_PIN_GLOBAL` alerts are the main signals. The WARN logs contain the same events. Emails appear in logs only as a short SHA-256 hash.
4. **PIN length:** query 5 lists users who still log in with a 4- or 5-digit PIN.

## What Phase 2 adds

- `mode: enforce`. Blocked attempts get HTTP 429 with `Retry-After` and an `ErrorDto` code `ACCOUNT_LOCKED` or `RATE_LIMITED` (D2). A wrong credential stays 401 with an empty body.
- Locks written to `user_auth_state`, using the conditional statement already in `LoginProtectionStore.applyLock`
- A Bucket4j per-IP burst filter on `/auth/**` and the manager-PIN endpoints
- An admin unlock endpoint, and lock state in `StaffDto`
- PIN length enforced as exactly 6 digits
- Refresh refused while the account is locked

## Running the tests

The suite runs with protection off (`LoginProtectionTestDefaults`, registered in `src/test/resources/META-INF/spring.factories`). Tests that need it opt in with `@TestPropertySource(properties = "security.login-protection.mode=monitor")`; see `LoginProtectionMonitorIntegrationTests`.
