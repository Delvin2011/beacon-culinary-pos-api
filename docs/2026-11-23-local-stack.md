# The local stack (T1-L0)

The production layout on one Windows machine, so each multi-tenancy slice (T1-1 to T1-10) can be built and proven
end to end before anything goes to Azure. Everything lives in `devstack/` in this repository.

```
browser ──https://beaconculinary.pos.localhost──► Caddy (gateway, :443)
                                                   ├─ /api/*  ─► backend:8080   (/api removed)  ─► SQL Server
                                                   └─ /*      ─► frontend:3000  (or npm run dev on the host)
```

| Part | Here | Production (later) |
|---|---|---|
| Gateway | Caddy in a container, `*.pos.localhost`, its own local certificate authority | Azure Front Door, `*.pos.optimumscs.com`, managed certificate |
| Routing | `/api/*` to the backend with the prefix removed; the rest to the frontend | The same two routes (backend route: forwarding path `/`) |
| Headers to the backend | `X-Forwarded-For`, `X-Forwarded-Host`, `X-Forwarded-Proto` | The same, set by Front Door |
| Backend | This repository's `Dockerfile`, no published port | Container Apps, reached only through Front Door |
| Settings and secrets | `devstack/stack.env` (git-ignored) | Key Vault secrets and Container Apps settings |
| Database | SQL Server 2022 container (or your own SQL Server), collation `SQL_Latin1_General_CP1_CI_AS` | Azure SQL, same collation |
| Customers | `beaconculinary` and `testcustomer`, both served at once, each at its own address (multi mode, T1-3), each with its own database login | One database per customer |
| Platform database | `pos_platform`: the customers, their hosts and where their databases are (T1-1, `docs/2026-11-24-platform-database.md`) | Its own Azure SQL database |

## 1. What to install (once)

| What | Why | Check |
|---|---|---|
| **Docker Desktop** (WSL 2 engine, the default) | Runs SQL Server, the backend, the frontend and Caddy | `docker version` shows a Server section |
| **PowerShell 7** (`winget install Microsoft.PowerShell`) | The scripts (they also run in Windows PowerShell 5.1) | `pwsh -v` |
| **Java 17** | Builds the backend jar; runs the new-customer commands | `java -version` |
| **Git** | | |
| Node.js 20 (optional) | Only for the frontend's hot-reload mode | `node -v` |

Docker Desktop needs virtualisation on and may ask for a restart. Start it once and wait for "Engine running".

## 2. First-time set-up

All commands from the repository root, in PowerShell.

**1. Settings.** Copy the example and fill it in:

```powershell
Copy-Item devstack\stack.env.example devstack\stack.env
notepad devstack\stack.env
```

| Setting | What to put |
|---|---|
| `SQL_PASSWORD` | A new password for the SQL container's `sa` (8+ characters with upper and lower case, a digit and a symbol). Used nowhere else |
| `JWT_SECRET` | 32+ random characters, for this stack only: `[Convert]::ToBase64String((1..48 \| % { Get-Random -Max 256 }))` |
| `SMOKE_EMAIL`, `SMOKE_PASSWORD` | An ADMIN account in `beaconculinary` that signs in with a password. The demo admin from the migrations is fine locally (the tests use the same account) |
| `FRONTEND_REPO` | Only if the frontend repository isn't in the folder next to this one, e.g. `C:\Users\you\Documents\Dev\Beacon\POS_FE\beacon-culinary-pos-front-end` |
| `PLATFORM_DB_PASSWORD` | (T1-1) A new password for the platform database's own login, `pos_platform` |
| `SECRET_BEACONCULINARY_DB_LOGIN`, `SECRET_BEACONCULINARY_DB_PASSWORD` | (T1-1) beaconculinary's own database login: a name such as `pos_beaconculinary` (never `sa`) and a new password |
| `SECRET_TESTCUSTOMER_DB_LOGIN`, `SECRET_TESTCUSTOMER_DB_PASSWORD` | (T1-1) The same for testcustomer, e.g. `pos_testcustomer` |
| `SECRET_<NAME>_DB_MIGRATION_LOGIN`, `SECRET_<NAME>_DB_MIGRATION_PASSWORD` | (T1-4) For beaconculinary and testcustomer: each database's migration login, a name of its own such as `pos_beaconculinary_migrate` (not its app login, never `sa`), and a new password. Only the migration command uses it |

Every T1-1 password: 8+ characters, 3 of upper case, lower case, digits and symbols, no single quote, each one
different. Leave the rest blank for the defaults (written next to each setting). `stack.env` is git-ignored: never commit it,
never paste its values into a ticket or chat.

**2. Hosts file.** Chrome and Edge already send every `*.localhost` name to this machine; PowerShell, curl and Java
need the names in the hosts file. As administrator, add one line to `C:\Windows\System32\drivers\etc\hosts`:

```
127.0.0.1 beaconculinary.pos.localhost testcustomer.pos.localhost
```

(One command, in an administrator PowerShell: `Add-Content $env:windir\System32\drivers\etc\hosts "`n127.0.0.1 beaconculinary.pos.localhost testcustomer.pos.localhost"`.)
Each customer made later with `new-customer.ps1` gets its name added to that line.

**3. Start it.**

```powershell
.\devstack\scripts\up.ps1
```

The first run takes a few minutes: it downloads the images, builds the jar and the backend image, starts SQL
Server, creates `beaconculinary` (all 114 migrations, V1 to V116, with the demo data) and `testcustomer` (the T0-7
procedure: baseline, starter template, first admin). **The first admin's invite link is printed in this window
only:** to sign in to `testcustomer` later, open it before it expires (72 hours), or run `reset.ps1 -Customer
testcustomer` for a new one. Don't record this window (no `Start-Transcript`; delete any transcript policy makes).

**4. Trust Caddy's certificate (once).** Caddy made its own certificate authority on the first start; it is kept in
the `caddy-data` volume. Trust its root for your Windows user (no administrator rights; Windows asks you to confirm):

```powershell
docker cp beacon-devstack-caddy-1:/data/caddy/pki/authorities/local/root.crt $env:TEMP\caddy-local-root.crt
Import-Certificate -FilePath $env:TEMP\caddy-local-root.crt -CertStoreLocation Cert:\CurrentUser\Root
Remove-Item $env:TEMP\caddy-local-root.crt
```

Chrome, Edge, PowerShell and curl use the Windows store. Firefox doesn't by default: set
`security.enterprise_roots.enabled` to `true` in `about:config`.

**To remove it** (when you stop using the stack, or after `down.ps1 -Volumes` made a new authority):

```powershell
Get-ChildItem Cert:\CurrentUser\Root | Where-Object Subject -like '*Caddy Local Authority*' | Remove-Item
```

The root can sign a certificate for any name, and only this machine trusts it. Its key stays in the Docker volume:
don't copy the volume elsewhere.

**5. Check.**

```powershell
.\devstack\scripts\status.ps1      # what runs, the databases, hosts file, certificate: "All good."
.\devstack\scripts\status.ps1 -Snapshot   # records the starting state, for checking a reset later
.\devstack\scripts\smoke.ps1       # the end-to-end check (about 2 minutes; -Quick: about 10 seconds)
```

Then open `https://beaconculinary.pos.localhost/` (with the frontend) or `https://beaconculinary.pos.localhost/api/health`.

## 3. Daily use

| Command | Does |
|---|---|
| `up.ps1` | Builds the jar, starts everything, waits until healthy. `-SkipBuild` reuses the last jar |
| `down.ps1` | Stops everything; databases and the certificate authority are kept |
| `status.ps1` | Containers, databases (collation, Flyway version, row counts), the platform database and its customers (T1-4: each one's schema version and last update), health, hosts file, certificate |
| `smoke.ps1` | The end-to-end check (section 6) |
| `reset.ps1` | Drops and recreates the stack's databases in their starting state, the platform database included, and registers the customers again; you type `RESET`. `-Customer <name>` for one |
| `new-customer.ps1 <name>` | A new clean customer database, as a real new customer gets, registered in the platform database; needs `SECRET_<NAME>_DB_LOGIN`, `_PASSWORD`, `_DB_MIGRATION_LOGIN` and `_DB_MIGRATION_PASSWORD` in `stack.env` (section 4) |
| `plan-today.ps1` | Makes today's demo meals sellable in the active customer's database, to ring up a sale by hand (below) |
| `down.ps1 -Volumes` | Deletes everything, including the SQL data and the certificate authority; you type `DELETE` |
| `secret-scan.ps1` | Looks for secrets in what this branch commits |
| `check-all.ps1` | Everything before a merge: backend tests, frontend checks, the stack and the smoke check (below) |

After a backend change: `up.ps1` again (it rebuilds the jar and the image and recreates the backend container).

**Selling by hand.** The till sells only `READY` items. The demo data plans its meals only for the day the migrations
ran, and has no light meals, so on any other day there is nothing to sell. `plan-today.ps1` does what the kitchen
would, through the API as an admin (`SMOKE_EMAIL` / `SMOKE_PASSWORD`):

1. Plans today's demo meals if they are missing: Breakfast (07:00-10:00) Rice & Beef and Chicken & Potatoes;
   Lunch (12:00-14:30) Rice & Chicken and Potatoes & Beef, with the extras Rice, Chicken, Potatoes and Beef.
   `-Portions` each (default 20).
2. Confirms the period's ingredient requirements (this also creates the stock request a real kitchen gets).
3. Records the actual portions, which puts them on sale (`READY`).
4. Adds the always-on light meal "Smoke check item" (R1.00) if missing: set meals are on the till only during their
   period, the light meal at any hour.

Run it again as often as you like: what is already on sale is left alone. It runs only against the stack's own
database (one of the stack's customers, on the stack's SQL Server, and the one the backend is serving), and says
which meals a database doesn't have (a new customer's starts without them). A reset takes the plan away again.

**Frontend.** Three modes (`FRONTEND_MODE` in `stack.env`, or `up.ps1 -Frontend <mode>`):

- `container` (default): built from the frontend repository's `Dockerfile` (T1-L0-FE provides it). Since T1-0d the
  app calls `/api` on its own origin and no API address is built in, so it takes **no build argument**: one image,
  `beacon-devstack/frontend`, for every customer, and switching customer reuses it. Its build runs
  `npm run check:build`, which fails if a backend address got into the build. If the repository has no
  `Dockerfile` yet, `up.ps1` says so and starts without it (the API works; pages answer 502).
- `host`: hot reload. Run `npm run dev` yourself in the frontend repository, with no API setting (remove any old
  `NEXT_PUBLIC_AUTH_API_URL` from its `.env.local`: nothing reads it). Caddy sends pages to
  `host.docker.internal:3000` and `/api` straight to the backend, which runs with `COOKIE_PATHPREFIX=/api`, so the
  refresh cookie's path is `/api/auth/refresh`. Open `https://beaconculinary.pos.localhost/`, not `localhost:3000`,
  so the browser sees the same origin and cookies as in production.
- `none`: the API only.

**What the frontend container must do (T1-L0-FE, T1-0d):** a `Dockerfile` at the repository root; no API address
built in (no build argument); serves on port 3000 on all interfaces (`PORT`, `HOSTNAME=0.0.0.0`); Next's
`output: 'standalone'` server.

**Before merging a slice: run `check-all.ps1`.** Until the GitHub pipelines exist, this is the gate:

```powershell
.\devstack\scripts\check-all.ps1            # everything, about 20 minutes
.\devstack\scripts\check-all.ps1 -Quick     # no e2e tests, a 5-second stream hold (check:build still runs)
```

It runs, in order, and carries on after a failure so you see everything at once: the backend's full test suite;
in the frontend repository (`FRONTEND_REPO`): lint, `tsc --noEmit`, Vitest, the production build, `npm run
check:build` (no backend address in that build; it runs with `-Quick` and `-SkipE2E` too) and the Playwright e2e
tests; then `up.ps1` (always: it is idempotent and rebuilds the backend, so the smoke check tests the code being
merged), `status.ps1` and `smoke.ps1`. It ends with a table (PASS, FAIL or SKIP, counts, duration) and one overall
result; the exit code is 1 if anything failed. Each step's full output is in `devstack/.logs/check-<time>/`
(git-ignored; the path is printed), with invite links masked. Skip parts with `-SkipBackend`, `-SkipFrontend`,
`-SkipE2E` or `-SkipStack`. The frontend needs its `node_modules` (`npm ci` once).

**Memory (T1-3).** The backend suite and the stack together ran the machine out of memory. `check-all.ps1` stops the
stack's containers before the backend tests (the suite uses your own SQL Server) and `stack up` starts them again.
It prints the commit it runs on and the free memory, and fails if HEAD moves during the run. Run it from a terminal;
a Claude Code background command can be stopped when memory runs low.

## 4. Multi mode (the default) and single mode

**Multi mode (T1-3, `TENANCY_MODE` blank or `multi`):** every customer at its own address at the same time,
`https://beaconculinary.pos.localhost` and `https://testcustomer.pos.localhost`. The backend works out the customer
from the address (`docs/2026-11-25-customer-from-address.md`) and uses that customer's own database. An address no
customer has answers 404 with an empty body.

**Single mode** serves one database, `ACTIVE_CUSTOMER` (default `beaconculinary`); any other `*.pos.localhost`
answers 503 "not the active customer" at Caddy:

```powershell
.\devstack\scripts\up.ps1 -TenancyMode single                         # this run only
.\devstack\scripts\up.ps1 -TenancyMode single -Customer testcustomer  # another customer
# or set TENANCY_MODE=single (and ACTIVE_CUSTOMER) in devstack\stack.env
```

The frontend image is the same for every customer, so switching reuses it. The databases stay as they are.

| Database | Built by | Contents |
|---|---|---|
| `beaconculinary` | Created empty, then every migration in `db/migration` by the migration command (T1-4) | As today's dev database: the demo menu, stock and accounts |
| `testcustomer` | `scripts/new-customer-database.ps1` (T0-7): `NewDatabaseCommand --starter`, then `FirstAdminCommand` | The baseline, the required data, the starter template, one admin (invite only) |
| `new-customer.ps1 <name>` | The same as `testcustomer` | The same, first admin `admin@<name>.test` |

The backend always runs with `APP_FLYWAY_INCLUDEBASELINE=true`: a full-history database ignores the baseline and a
T0-7 database needs it, so both validate. Every database exists before the backend starts, so the backend never
builds one itself.

**The platform database (T1-1).** `up.ps1` also creates `pos_platform`, gives each customer database its own
logins and registers every customer as `<name>.pos.localhost`; `status.ps1` lists them, and in multi mode the
customers the backend has served. Details: `docs/2026-11-24-platform-database.md`.

**Customer migrations (T1-4).** Each customer database has two logins: the backend's app login (read, write and
execute only) and a migration login (`db_owner`). Before the backend starts, `up.ps1` runs `MigrateCustomersCommand`
in a Java container on the stack network; it updates every customer database and prints a table (from, to, result).
A customer it couldn't update answers 503 `CUSTOMER_UNAVAILABLE` until fixed; `status.ps1` shows each schema version
and last update. How to prove a failure by hand: `docs/2026-11-26-customer-migrations.md`, section 7.

**Smoke accounts (T1-3).** Each customer other than beaconculinary gets `smoke@<name>.test` with `SMOKE_PASSWORD`,
made by `up.ps1` and `new-customer.ps1` when its database is, and again after `reset.ps1`. The invite link is
captured, never shown. Because of it, the real first admin's `FirstAdminCommand` then needs `--force --reason`.

## 5. Logs

```powershell
docker compose -p beacon-devstack logs -f backend      # or caddy, sql, frontend
docker logs beacon-devstack-backend-1 --since 10m
```

The backend runs the default profile (text lines; the demo accounts can sign in). Each request line ends with
`host=`: the address the browser used, from Caddy's `X-Forwarded-Host` (`docs/2026-11-19-logging.md`). Search by
the `X-Request-Id` a response returned.

**Looking at the databases (SSMS).** The stack's databases are in the SQL container, not on your own SQL Server.
Connect with server `127.0.0.1,14333` (a comma before the port; `SQL_HOST_PORT` if you changed it), SQL Server
Authentication, login `sa`, the `SQL_PASSWORD` from `stack.env`, and **Trust server certificate** ticked (the
container's certificate is self-signed). Use `127.0.0.1`, not `localhost`: the port is open on the IPv4 loopback
only, and Windows tries `localhost` as IPv6 (`::1`) first, which is refused (error 1225). The databases are there
only while the stack runs, and `reset.ps1` / `down.ps1 -Volumes` replace or delete them.

## 6. The smoke check

`smoke.ps1` checks, through the gateway and as a browser would. In multi mode it checks beaconculinary and
testcustomer in one run (beaconculinary with `SMOKE_EMAIL`, testcustomer with its smoke account), each at its own
address; the 2-minute hold is at the first customer only. Also in multi mode: while one customer's order is placed,
the other customer's kitchen stream receives nothing, and `nobody.pos.localhost` (a forged Host through Caddy)
answers 404 with an empty body on `/api/health` and `/api/public/board/today`. And (T1-5) beaconculinary's token
presented at testcustomer answers 401 `TENANT_MISMATCH`.

1. `GET /api/health` is `UP`, with an `X-Request-Id` header.
2. Password sign-in sets the `refreshToken` cookie `Secure; HttpOnly; SameSite=Lax; Path=/api/auth/refresh` (T1-5), and
   `POST /api/auth/refresh` with it returns a new token (the cookie's path matches the address the browser calls).
3. Its own `X-Request-Id` comes back, and the backend's log line for that request shows
   `host=beaconculinary.pos.localhost`.
4. The kitchen stream (`/api/kitchen/orders/stream`) delivers the event of an order placed through the API within
   5 seconds, stays open 2 minutes, and then still delivers the next event (the order moved to `IN_PROGRESS`).
5. Only with `-QuietHoldMinutes 6` (not the default run): a second kitchen stream with no orders stays open through
   Caddy for 6 minutes on keepalives alone, never silent for 60 seconds or more.

The order is a real cash sale of one kitchen item on the till's open shift (the check opens one with a zero
float if none is open, and closes it again). The demo data has nothing the till can sell straight away (its set
meals are only planned, and it has no light meals), so the first run adds an always-on light meal, "Smoke check
item", through the admin API; later runs reuse it. The sale and the item stay in `beaconculinary` until a reset. Tokens, cookies
and passwords are never printed. Exit code 0 when all pass.

## 7. What the backend trusts from the gateway

| Header | Used for | Believed when |
|---|---|---|
| `X-Forwarded-For` | The client IP for login protection and till registration | The direct peer is in `security.login-protection.trusted-proxies` (the stack: `172.30.0.0/24`, the compose network) |
| `X-Forwarded-Host` | The `host=` field of the request log line (T1-1 will resolve the customer from it) | The same list |
| `X-Forwarded-Proto` | Not used: cookies are `Secure` by setting (`cookie.secure`, true outside `dev`) | |

Caddy ignores these headers when a client sends them and writes its own. `server.forward-headers-strategy` stays
off on purpose: Spring's framework strategy would believe `X-Forwarded-For` from anyone.

Two settings make the backend work behind a gateway that removes `/api`:
- `COOKIE_PATHPREFIX=/api` (`cookie.path-prefix`): the refresh cookie's path becomes `/api/auth/refresh`. Without
  it the browser never sends the cookie back and staying signed in fails.
- `SECURITY_LOGINPROTECTION_TRUSTEDPROXIES`: the gateway's addresses.

Production needs both, with Front Door's ranges (go-live checklist).

**Streams.** Caddy sends stream bytes at once (`flush_interval -1`) and sets no response timeout. Each stream opens
with a comment (`:connected`), so the headers go out at once, and every open stream gets another comment,
`:keepalive`, every 25 seconds (`APP_SSE_KEEPALIVEINTERVAL`, a duration; `0` turns it off). **Front Door needs
the keepalive:** it closes connections idle for about 4 minutes, which would drop a kitchen screen on a quiet
afternoon; keep the interval well under that. Clients ignore comments (the frontend reads only `data:` lines), and
a keepalive isn't activity: it is the server writing into an open response, so no session, till or device is
touched, and tills don't open streams. A stream the client has left gets no more keepalives. `smoke.ps1
-QuietHoldMinutes 6` proves a quiet stream survives through the gateway.

**Client IP.** Docker Desktop's port forwarding hides your browser's address: the backend sees the Docker gateway
as the client. Login protection still works per address; just don't read meaning into the IPs locally.

## 8. Using your own SQL Server instead of the container

Set `SQL_MODE=host`, `SQL_USER` and `SQL_PASSWORD` (a SQL login allowed to create databases) and, if not 1433,
`SQL_HOST_INSTANCE_PORT`. Your server needs TCP/IP on (SQL Server Configuration Manager) and SQL Server
authentication allowed. The containers reach it as `host.docker.internal`. The databases are still created with
`COLLATE SQL_Latin1_General_CP1_CI_AS`, whatever your server's default (often `Latin1_General_CI_AS`).
`reset.ps1` drops only the stack's databases (`beaconculinary`, `testcustomer` and those made with
`new-customer.ps1`); it never touches others such as `BeaconculinaryTest`. Don't point it at a server whose
`beaconculinary` database you want to keep.

## 9. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Browser: "Your connection is not private" | Caddy's root isn't trusted, or an old root is (after `down.ps1 -Volumes`). Section 2 step 4: remove, then import again. Restart the browser |
| `status.ps1`: name doesn't resolve | The hosts-file line is missing or misspelt (section 2 step 2). `ipconfig /flushdns` after editing |
| `up.ps1`: "Port 443 is in use" | IIS, IIS Express, Skype or another proxy holds it. Find it: `Get-NetTCPConnection -LocalPort 443 -State Listen \| Select OwningProcess` then `Get-Process -Id <id>`. Stop it, or set `HTTPS_PORT=8443` (and `HTTP_PORT=8080`): addresses become `https://beaconculinary.pos.localhost:8443` |
| `up.ps1`: "Port 14333 is in use" | Set `SQL_HOST_PORT` to another port |
| "Docker Desktop is not running" | Start it and wait for "Engine running" |
| 503 "not the active customer" | Single mode: that customer isn't the one being served (section 4) |
| 404 with an empty body | No customer has that address (multi mode): check the name, and `status.ps1` for the registered customers |
| 503 `CUSTOMER_SUSPENDED` | The customer is suspended in the platform database (`TenantRegisterCommand activate`) |
| 502 on pages, `/api` fine | No frontend: no `Dockerfile` in `FRONTEND_REPO` yet, or `FRONTEND_MODE=host` without `npm run dev` running |
| Backend not healthy | `docker compose -p beacon-devstack logs backend`. Common: a wrong `SQL_PASSWORD` after the SQL volume was created with another one (the volume keeps the first; `down.ps1 -Volumes` starts again) |
| Smoke: sign-in fails | `SMOKE_EMAIL` / `SMOKE_PASSWORD` don't match an ADMIN in the active customer's database |
| Smoke: "no light-meals category to add one to" | The active customer has no active light-meals category to put the smoke item in: run it against `beaconculinary` |
| "PLATFORM_DB_PASSWORD is not set" or "SECRET_..._DB_LOGIN is not set" | T1-1 settings missing from `stack.env` (section 2). The scripts check them before changing anything |
| "SECRET_..._DB_MIGRATION_LOGIN is not set" | T1-4 settings missing from `stack.env` (section 2) |
| 503 `CUSTOMER_UNAVAILABLE` | The customer is MIGRATION_FAILED, or its database is behind the backend's schema: read `up.ps1`'s migration table or `status.ps1`, fix the cause, run `up.ps1` again |

## 10. Files

| File | What |
|---|---|
| `devstack/compose.yaml` | The four services, the network (`172.30.0.0/24`) and volumes |
| `devstack/Caddyfile` | The gateway's routes |
| `devstack/stack.env.example` | The settings, names only |
| `devstack/scripts/*.ps1` | up, down, status, reset, new-customer, plan-today, smoke, check-all, secret-scan; `_common.ps1` is shared |
| `devstack/scripts/tests/devstack.tests.ps1` | Script tests without Docker (run by `DevstackScriptsTest`) |
| `devstack/.state/` | Git-ignored: customers made with `new-customer.ps1`, status snapshots |
| `src/main/resources/db/platform/` | The platform database's migrations (T1-1) |
