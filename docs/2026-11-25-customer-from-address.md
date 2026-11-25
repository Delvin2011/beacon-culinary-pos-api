# The customer from the address (T1-3)

In **multi-customer mode**, every request's customer comes from the address it was sent to,
`https://<customer>.pos.localhost` locally and `https://<customer>.pos.optimumscs.com` in Azure. It is worked out
before anything else touches a database. T1-2's routing data source then sends every database access to that
customer's own database (`docs/2026-11-24-routing-data-source.md`). **Single mode** (one database,
`spring.datasource.*`) is unchanged.

## 1. How a request's customer is found

`tenancy/web/CustomerResolutionFilter` (multi mode only) does this, in order:

1. **Which host:** `X-Forwarded-Host` when the request comes from a trusted proxy (`security.login-protection.trusted-proxies`,
   the same list and the same code as the request log's `host=`, `RequestContextFilter.rawHost`), otherwise the
   `Host` header. A client can't choose its customer with the header.
2. **Normalised** as the platform database stores hosts (`HostNames`): lower case, no port, no trailing dot.
3. **Looked up** in the customer directory (its 30-second snapshot), then answered as in section 2.
4. **An ACTIVE customer** is current for the whole request (`CustomerContext`), cleared in `finally`. Its slug is the
   request's `tenantId` in every log line, including the request line.

Async dispatches (the kitchen, bar and board streams) go through the filter too, so the customer is set and cleared
there as well. A stream belongs to the customer it was opened at (`SseChannel`, T1-2), and it never receives another
customer's events.

## 2. The answers (the contract with the frontend)

These apply on every path, `/health` and `/public/*` included. No answer names a customer, or says which customers
exist.

| Address | Status | Body |
|---|---|---|
| Malformed host (or none) | 400 | empty |
| Unknown host | 404 | empty |
| Customer ARCHIVED | 404 | empty |
| Customer SUSPENDED | 503 | `{"error":"This service is temporarily unavailable.","code":"CUSTOMER_SUSPENDED"}` |
| Customer PROVISIONING or MIGRATION_FAILED | 503 | `{"error":"This service is temporarily unavailable.","code":"CUSTOMER_UNAVAILABLE"}` |
| Customer ACTIVE | the application's answer | |

- **Caching:** all of these carry `Cache-Control: no-store`.
- **The frontend's side:** after an empty 404 it calls `/health` once to confirm "this address isn't set up". While a
  customer is suspended it calls only `/health`, about once a minute. `/health` answers 200 again once the directory
  sees the customer ACTIVE, within 30 s.
- **On the way out:** T1-2's exceptions are answered the same way. `UnknownCustomerException` gets the empty 404, and
  `CustomerNotServedException` gets its status's answer (a customer suspended during a request).
- **A bug:** `NoCurrentCustomerException` is a database access with no customer. It's logged at ERROR, with the
  request id, and left to the usual 500.

## 3. The backend's own addresses and /health

- **`app.tenancy.platform-hosts`** (`APP_TENANCY_PLATFORMHOSTS`) lists the backend's own addresses. The default is
  `localhost,127.0.0.1`; the local stack adds `backend`, the container name that `up.ps1` and `status.ps1` probe from
  inside the stack.
- **At those addresses**, `/health` answers **platform health**: the platform database, plus the customer pools as
  counts (`customerPools: {open, failing}`), with no names. Every other path answers 404 with an empty body, so no
  customer data is reachable there.
- **At a customer's address**, `/health` answers for **that customer's database** (`CustomerHealthCheck`): 200
  `{status: UP}`, or 503 `{status: DOWN}` (no code) when its database doesn't answer within 2 s. The answer is cached
  for **5 s per customer**, so tills polling every few seconds cost at most one query per customer per 5 s, and one
  slow database never holds up another customer's answer. Pool counts and names aren't shown here.

## 4. Where the filter runs

| Order | Filter | Reads a database? |
|---|---|---|
| HIGHEST_PRECEDENCE + 10 | `RequestContextFilter` (request id, request log line) | no |
| HIGHEST_PRECEDENCE + 11 | **`CustomerResolutionFilter`** (multi mode) | the directory only (memory) |
| -100 (Spring Security) | the sign-in burst limit, device resolution (the `deviceToken` cookie), the JWT filter and sessions | yes: the current customer's |

Device resolution, login protection, the burst limit (now keyed per customer and IP), sessions and the streams all
run after the customer is set. Tests: `CustomerFilterOrderIntegrationTests`.

## 5. Single and multi mode

| | Single (`app.tenancy.mode=single`) | Multi (`multi`) |
|---|---|---|
| Customer filter | not registered | registered |
| `tenantId` | `default` | the customer's slug |
| `/health` | the one database | platform health at the backend's own addresses; the customer's database at its address |
| The local stack | `up.ps1 -TenancyMode single`: `ACTIVE_CUSTOMER` only; Caddy answers 503 for other addresses | **the default**: every customer at its own address |

## 6. Sign-in when the database fails (both modes)

- **Before:** `POST /auth/login` answered **401** and logged nothing, because Spring Security wraps a database
  failure in `InternalAuthenticationServiceException`, which counts as "unauthorised". The failure was also recorded
  as a failed attempt, so staff thought their password was wrong.
- **Now:** password and PIN sign-in answer **503**
  `{"error":"Sign-in is unavailable right now. Try again shortly.","code":"DATABASE_UNAVAILABLE"}`, logged at ERROR
  with the request id. The frontend treats any 5xx on sign-in as "server unavailable".
- **Nothing counts against anyone:** no attempt is recorded, it never counts towards login protection or a lockout,
  and the burst limit gives the token back.
- **Unchanged:** wrong passwords and PINs answer exactly as before.
- **What counts as a database failure:** `DataAccessException`, `TransactionException` or JPA `PersistenceException`,
  or Spring Security's wrapper around one.

## 7. Temporary limitation until T1-5

Tokens don't carry their customer yet. A token issued at customer A could be presented at customer B, where the
same user id may belong to someone else. Multi mode logs a WARN at start-up, *"multi mode without customer-bound
tokens: local development only"*, until T1-5 removes it. **Multi mode must not be used outside the local stack before
T1-5.**

## 8. Front Door (T1-0c)

Front Door forwards the address the browser used as `X-Forwarded-Host`, and the backend believes it only from a
trusted proxy. T1-0c must set the following:

| Setting | Value |
|---|---|
| `security.login-protection.trusted-proxies` (`SECURITY_LOGINPROTECTION_TRUSTEDPROXIES`) | Front Door's backend ranges (the `AzureFrontDoor.Backend` service tag), and nothing else |
| Check `X-Azure-FDID` | Equal to this Front Door profile's id (a network rule or a filter), so another profile's traffic is refused |
| `app.tenancy.platform-hosts` (`APP_TENANCY_PLATFORMHOSTS`) | The Container App's own address (`<app>.<env>.azurecontainerapps.io`), which Front Door's health probe uses, plus `localhost` |
| Front Door health probe | `GET /health` at the Container App's own address (platform health) |
| Routes | `*.pos.optimumscs.com/api/*` to the backend with forwarding path `/`; the origin host header is the origin's own address, and the browser's host arrives in `X-Forwarded-Host` |
| `COOKIE_PATHPREFIX` | `/api` (T1-L0) |

## 9. The local stack

- **Mode:** multi is the default (`TENANCY_MODE`), and `up.ps1 -TenancyMode single` still works.
- **Caddy:** its single-mode 503 guard matches nothing in multi mode.
- **Invite links:** in multi mode the frontend builds them from its own address.
- **Smoke accounts:** each customer other than beaconculinary has `smoke@<name>.test` with `SMOKE_PASSWORD`.
  - It's made when its database is: by `up.ps1` and `new-customer.ps1`, and again after `reset.ps1`.
  - `FirstAdminCommand`'s link is captured and never shown, and the invite is accepted through the gateway. An ADMIN
    also needs a PIN, so it gets a random one that is never stored.
  - It's made only for `*.pos.localhost` addresses.
- **`smoke.ps1`** checks beaconculinary and testcustomer in one run:
  - at each: health, sign-in, refresh, request id, log line host, a sale and the kitchen stream;
  - while each customer's order is placed, the other customer's kitchen stream receives nothing;
  - `nobody.pos.localhost` (a forged Host through Caddy) answers 404 with an empty body on `/api/health` and
    `/api/public/board/today`.
- **`status.ps1`** shows the mode and, in multi mode, the customers whose pools are open.
- **`check-all.ps1`:**
  - stops the stack's containers before the backend tests, so the machine doesn't run out of memory; `stack up`
    starts them again;
  - prints the commit and free memory, and fails if HEAD moves during the run.

## 10. Tests

| Test | Proves |
|---|---|
| `CustomerResolutionFilterTest` (8) | Host; X-Forwarded-Host from a trusted proxy only; case, port and trailing dot; malformed or missing gets 400; platform hosts; the contract on three paths with no name; exceptions mapped; async dispatches |
| `CustomerAddressIntegrationTests` (3) | The contract on an API path, `/health` and `/public/*`, every status; `tenantId` on the request line, nothing left on the thread; a stream |
| `CustomerHealthIntegrationTests` (2) | Each customer's own database, one unreachable while the other is fine; the 5 s cache |
| `SuspendAndRecoverIntegrationTests` (1) | `TenantRegisterCommand suspend` gives 503 `CUSTOMER_SUSPENDED` within the refresh time, `activate` gives 200; the other customer unaffected |
| `CustomerFilterOrderIntegrationTests` (4) | A till cookie from alpha unknown at bravo; attempts and sessions in the customer's own database; a stream at alpha never gets bravo's events |
| `MultiCustomerOfflineSyncIntegrationTests` (2) | A full offline sale (grant, snapshot) lands only at its customer; the same sale at bravo is bravo's own; alpha's grant means nothing at bravo |
| `SignInUnavailableIntegrationTests` (3, single mode) | 503 `DATABASE_UNAVAILABLE`, an ERROR line with the request id, nothing recorded, no lockout or burst; wrong password and PIN unchanged |
| `MultiModeStartupIntegrationTests` (updated) | The backend's own address serves `/health` only; the start-up WARN |
