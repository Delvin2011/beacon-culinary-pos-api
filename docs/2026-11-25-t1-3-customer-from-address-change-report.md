# T1-3 customer from the address: change report

**Branch:** `feature/t1-3-customer-from-address`, from `feature/infrastructureUpdatesBE`. Not pushed, not merged.
**Doc:** `docs/2026-11-25-customer-from-address.md`.

## What it gives

- **In multi mode, the customer comes from the address.** Every request's customer is worked out from the address it
  was sent to, before anything reads a database. The answers follow the contract with the frontend:
  - unknown or archived: 404, empty body;
  - suspended: 503 `CUSTOMER_SUSPENDED`;
  - provisioning or migration failed: 503 `CUSTOMER_UNAVAILABLE`;
  - malformed host: 400, empty body.
- **Both local customers work at their own addresses at the same time.** Multi mode is the stack's default.
- **Sign-in tells an outage from a wrong password,** in both modes. A database failure answers 503
  `DATABASE_UNAVAILABLE` and is never counted as a failed attempt.

## Steps

| Step | Commit | What | Lines |
|---|---|---|---|
| 1 | `5effd7d` | `CustomerResolutionFilter` and its config (contract, platform hosts, T1-2 exceptions mapped, async dispatches, start-up WARN); `RequestContextFilter` shares its host logic and logs the slug; T1-2's test-only header and guard test removed | +545 / -152 (of which -112 the removed files) |
| 2 | `2e24a97` | `/health` at a customer's address reports that customer's database (`CustomerHealthCheck`) | +201 / -1 |
| 3 | `2767d15` | Sign-in with the database failing: 503 `DATABASE_UNAVAILABLE`, nothing recorded, burst token given back; burst limit keyed per customer and IP | +218 / -2 |
| 4 | `344adde` | Tests: what runs before the controller, the full offline sync per customer, suspend and recover | +432 / -1 |
| 5 | `ec1013a` | The local stack: multi by default, smoke accounts, two-customer smoke, status, check-all memory and commit | +478 / -243 (+377 / -142 ignoring whitespace) |
| 6 | this commit | The customer-from-address doc, this report; the local stack, routing and logging docs; `.env.example` | see `git show --stat` |

## Decisions

| Decision | Why |
|---|---|
| Sign-in outage code: **503 `DATABASE_UNAVAILABLE`** (approved) | The frontend already treats any 5xx on sign-in as "server unavailable", and any 503 other than the two `CUSTOMER_*` codes as a server outage |
| **Platform hosts:** `app.tenancy.platform-hosts`, default `localhost,127.0.0.1`; the stack adds `backend`. At them: `/health` only (platform health), anything else 404, empty | `up.ps1` and `status.ps1` probe `http://backend:8080/health` inside the stack; Front Door's probe uses the Container App's own address (T1-0c) |
| **`/health` cache time: 5 s per customer**, 2 s probe timeout, one probe per customer at a time | The same as single mode's; many tills cost one query per customer per 5 s; one slow database never holds up another customer |
| The filter runs right after the request log filter (HIGHEST_PRECEDENCE + 11), also on async dispatches | Before Spring Security, the burst limit, device resolution and JWT, which all read the database; streams get and clear the customer too |
| The request line's `tenantId` comes from a request attribute | The customer filter has closed its scope (which puts `default` back) when the line is written |
| The burst limit is keyed per customer and IP (approved) | Customers behind the same network address don't share a limit |
| T1-2's test-only header removed (approved) | The real filter takes its place; tests send a `Host` header |
| Smoke accounts made with the databases, not by `smoke.ps1` (as instructed); `*.pos.localhost` only | `smoke.ps1` only checks. An ADMIN invite needs a PIN: a random one, never stored; another if refused or in use |
| `check-all.ps1` stops the stack before the backend tests | The full suite with the stack up ran the machine out of memory in T1-2 |

## Where the code differed from the prompt, and how it was resolved

| Found | Resolution |
|---|---|
| The silent 401 on sign-in: Spring Security wraps a database failure in `InternalAuthenticationServiceException`, which `AuthService` recorded as a **failed attempt** | Fixed in step 3; a test shows 10 failing tries record nothing and leave sign-in possible |
| The request line would have shown `tenantId=default` | A request attribute (above) |
| The first admin invite for an ADMIN needs a PIN as well as a password | The smoke account gets a random PIN, never stored |
| `smoke.ps1` read the whole log as its "log line" (an existing bug, `Invoke-Docker` returns one array): harmless with one customer, wrong with two | Fixed in step 5 |
| Invite links in multi mode: `APP_PUBLIC_BASE_URL` named one customer | Left empty in multi mode, so the frontend builds each link from its own address (`InviteLinks`) |
| A full offline sync test needs the tills, grant and orders to stay: the orders reference the grants | The test leaves them in its throwaway databases (dropped at JVM exit) |

## Open items

| For | Item |
|---|---|
| **T1-4** | Customer database migrations per customer (provisioning); fill `tenant_databases.last_migrated_version`; smoke accounts for new customers as part of provisioning checks |
| **T1-5** | Tokens bound to their customer; remove the start-up WARN; only then multi mode outside the local stack |
| **T1-6** | Daily jobs at each customer's own time zone; spreading job runs |
| **T1-0c** | Front Door: trusted proxies (`AzureFrontDoor.Backend`), the `X-Azure-FDID` check, the Container App's own address in `platform-hosts`, the health probe at `/health` there |
| Later | Any `DataAccessException` during sign-in answers 503 (also a bug such as a constraint violation); it was a silent 401 or an unlogged error before |
