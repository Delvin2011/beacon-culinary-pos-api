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

## 7. Tokens are bound to their customer (T1-5)

Since T1-5, a token carries the customer it was issued at and is refused at any other with 401 `TENANT_MISMATCH`
(`docs/2026-11-25-customer-bound-credentials.md`). T1-3's start-up warning is gone. Multi mode is fit to leave the local
machine, subject to Stage A (Front Door, Key Vault).

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
| `CORS_ALLOWED_ORIGINS` | `https://*.pos.optimumscs.com` (a pattern: every customer). The browser sends its `Origin` on a sign-in POST even on its own site, and behind the gateway the backend sees `http` and its own port, so it treats the request as cross-origin; without the pattern, sign-in at any address not listed answers 403 "Invalid CORS request" |

## 9. The local stack

- **Mode:** multi is the default (`TENANCY_MODE`), and `up.ps1 -TenancyMode single` still works.
- **Caddy:** its single-mode 503 guard matches nothing in multi mode.
- **Invite links:** T1-7: on the customer's primary host, `https://<name>.pos.localhost/invite#t=...` (section 11).
  With an HTTPS port other than 443 the link has no port; open it with the port added.
- **Smoke accounts:** each customer other than beaconculinary has `smoke@<name>.test` with `SMOKE_PASSWORD`.
  - It's made when its database is: by `up.ps1` and `new-customer.ps1`, and again after `reset.ps1`.
  - `FirstAdminCommand`'s link is captured and never shown, and the invite is accepted through the gateway. An ADMIN
    also needs a PIN, so it gets a random one that is never stored.
  - It's made only for `*.pos.localhost` addresses.
- **`smoke.ps1`** checks beaconculinary and testcustomer in one run:
  - at each: health, branding with its own name (T1-7), sign-in, refresh, request id, log line host, a sale and the
    kitchen stream;
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
| `CustomerBrandingIntegrationTests` (2, T1-7) | Two customers, two names (one from its receipt settings, one from the customer directory), no sign-in, `logoUrl` null, the cache header; suspended, unavailable and unknown answer as in section 2 |
| `CustomerLinksIntegrationTests` (1, T1-7) | An invite issued at alpha links to `https://alpha.pos.localhost`, at bravo to `https://bravo.pos.localhost` |
| `NoBeaconInMainTest` (2, T1-7) | No customer's name in `src/main` (the package and main class excepted) |

## 11. The customer's details and its links (T1-7)

Each customer's public details come from that customer, and every link the backend builds is on that customer's own
address. Nothing in the app names a customer: `NoBeaconInMainTest` fails on "beacon" anywhere in `src/main` except
the package `com.beaconculinary` (and its `COM_BEACONCULINARY` form) and `BeaconCulinaryApiApplication`, whose rename
is a separate change, and the applied migrations' demo data.

**`GET /api/public/branding`** (backend path `/public/branding`; `PublicBrandingController`), no sign-in:

```
200  Cache-Control: public, max-age=300
{ "name": "Acme Foods", "logoUrl": null }
```

- **`name`** (`BusinessName`, the same name the X, Z and Cashier readings print):
  1. the customer's own receipt settings' `businessName` (`receipt_settings.business_name`, set in `/admin/receipt-settings`);
  2. while that's empty, its name in the customer directory (`tenants.display_name`, given at `register --name`);
  3. otherwise `""`. In single mode there is no directory, so it's the receipt settings' name or `""`.

  Browsers and caches may keep an answer for 5 minutes (the cache header), so a saved change can take that long to
  show; a directory change also waits for the directory's 30 s snapshot.
- **`logoUrl`** is always `null` for now (no logo upload yet).
- **Suspended, unavailable and unknown addresses** never reach it: they get section 2's answers (503
  `CUSTOMER_SUSPENDED`, 503 `CUSTOMER_UNAVAILABLE`, an empty 404), with `Cache-Control: no-store`.
- There is no platform-wide name: `app.business.name` (`APP_BUSINESS_NAME`) is gone, so one customer's name is never
  shown for another.

**Links** (`InviteLinks`): `https://<the customer's primary host>/invite#t=<token>`, the primary host from the
customer directory (`tenant_hosts.is_primary`; change it with `add-host --primary`). The staff screen's
`issuedInvite.link` is this link. In single mode there is no customer directory, so `link` is `null` and the frontend
builds the link from its own address. `app.public-base-url` (`APP_PUBLIC_BASE_URL`) is gone; `FirstAdminCommand`
takes `--base-url`, or prints the bare token without it. Invite links are the only links built from settings; the
`Location` headers of new orders and shifts are built from the request itself, so they are already on the
customer's address.
