# T1-L0 local stack: change report

**Branch:** `feature/infrastructureUpdatesBE` (as instructed, instead of `feature/t1-l0-local-stack`). Not pushed.
**Doc:** `docs/2026-11-23-local-stack.md`.

## What it gives

A gateway (Caddy) on `https://<customer>.pos.localhost` sending `/api/*` to the backend (prefix removed) and the rest
to the frontend, as Front Door will; the backend from this repository's `Dockerfile`; SQL Server with collation
`SQL_Latin1_General_CP1_CI_AS` (a container, or your own server); two customer databases (`beaconculinary` with
every migration and the demo data, `testcustomer` the T0-7 way); scripts to start, stop, check, reset and add
customers; a smoke check through the gateway; and a secret scan.

## Where the code contradicted the prompt (fixed, with approval)

| Found | Effect | Fix |
|---|---|---|
| The refresh cookie's path was fixed at `/auth/refresh` | Behind a gateway that removes `/api`, the browser calls `/api/auth/refresh` and never sends the cookie: staying signed in silently fails. Front Door can't rewrite `Set-Cookie`, so production has the same problem | `cookie.path-prefix` (`COOKIE_PATHPREFIX`, default empty; `/api` in the stack and later in Azure). A malformed value stops start-up |
| The request log line had no host | The smoke check couldn't show the forwarded host; T1 needs it to see which customer a request was for | `host=` on the request line and a `host` JSON field: `X-Forwarded-Host` when the peer is a trusted proxy (the login-protection list), otherwise `Host`; anything but a plain host name is logged as `invalid` |

Other facts the design follows: the frontend repository isn't in the sibling folder on this machine
(`FRONTEND_REPO` is a setting) and has no container file yet (T1-L0-FE); its API address is a build-time variable
(`NEXT_PUBLIC_AUTH_API_URL`); the `Dockerfile` copies a jar built beforehand (`up.ps1` builds it).

## Decisions

- **`beaconculinary` by running every migration** (the Flyway 10.20.1 container, `db/migration` only), not by
  restoring a `.bacpac`: reproducible, no SqlPackage, never behind the migrations, no copy of real data.
- **`devstack/` in this repository**: it depends on this repository's `Dockerfile` and migrations.
- **Single-customer mode guard**: Caddy answers 503 for any customer address other than the active one.
- **No `server.forward-headers-strategy`**: Spring's framework strategy would believe `X-Forwarded-For` from any
  client; the backend keeps trusting forwarded headers only from the configured proxies.
- **`sa` in the container**: local only; production uses a dedicated login.
- **Secrets**: every value in `devstack/stack.env` (git-ignored, names in `stack.env.example`); scripts pass
  passwords to containers as `docker run -e NAME` (value from the process environment, never on a command line) and
  remove them afterwards; the SQL health check reads the password inside the container.

## Commits

| Step | Commit | What |
|---|---|---|
| 1 | `fe1e38c` | `cookie.path-prefix` for the refresh cookie |
| 2 | `d37d69d` | `host=` in the request log line from a trusted proxy's `X-Forwarded-Host` |
| 3 | `676e4ae` | `devstack/compose.yaml`, `Caddyfile`, `stack.env.example`, `README.md`; `.gitignore` |
| 4 | `6d9a490` | `up`, `down`, `status`, `reset`, `new-customer` and `_common.ps1` |
| 5 | `5a72821` | `smoke.ps1` |
| 6 | `ed8c4e9` | `devstack.tests.ps1`, `DevstackScriptsTest`, `secret-scan.ps1` |
| 7 | (this) | This report, the local stack doc, README |

## Tests

| Test | Result |
|---|---|
| `RefreshCookiesTest` (4: prefix on issue and clear, bad values refused, existing behaviour unchanged) | Pass |
| `AuthLogoutIntegrationTests` (6, unchanged expectations) | Pass |
| `RequestContextFilterTest` (6: forwarded host from a trusted proxy only, chains, unsafe values, no host) | Pass |
| `RequestContextIntegrationTests`, `RequestLogFieldsIntegrationTests`, `ProductionLogFormatTest`, `ClientIpResolverTest`, the login-protection classes | Pass |
| `DevstackScriptsTest` (2): every devstack script parses; `devstack.tests.ps1`, 35 cases: name rule, settings defaults and refusals, the port suffix, a new customer remembered, dropping `master` refused, `reset.ps1` refusing a database that isn't the stack's and doing nothing without `RESET` | Pass. A mutant `reset.ps1` without its two checks failed both reset cases |
| Secret scan (`secret-scan.ps1 -SkipGitleaks`) over every line the branch adds since `main` (43,221) and the example files | No secrets found. The patterns were also checked against planted fake secrets (all caught) and code that only names a secret (none flagged) |
| Full suite (`mvnw test`, after step 6) | 1185 tests, 0 failures, 0 errors, 0 skipped (1179 before T1-L0, plus 6 new) |

**Not yet run (needs Docker Desktop, not installed on the machine this was built on):**
- The stack itself: `docker compose config`, Caddy's configuration, `up.ps1`, `status.ps1`, `smoke.ps1`.
- The clean set-up check: Caddy's root removed from the Windows store, a fresh worktree of the branch with a new
  `stack.env` and another `STACK_NAME` (new volumes), then only the doc's steps: hosts line, `up.ps1`, trust the
  root, `smoke.ps1`. (Windows Sandbox can't run Docker, so a truly clean machine isn't possible here; this is the
  closest.)
- The reset check: `status.ps1 -Snapshot` after the first `up.ps1`; a sale and a new user in both databases;
  `reset.ps1`; `status.ps1 -Compare` must report both "same as the snapshot".
- gitleaks (the scan's third part runs it in a container).

## Open items

- **Front Door, before production:** `COOKIE_PATHPREFIX=/api`, `SECURITY_LOGINPROTECTION_TRUSTEDPROXIES` with Front
  Door's ranges, and a keepalive on the SSE streams (Front Door closes idle connections after about 4 minutes).
- **T1-L0-FE:** the frontend's `Dockerfile` (contract in the doc, section 3).
- **T1-1:** resolving the customer from `X-Forwarded-Host` (trusted proxies only), then serving both databases.
