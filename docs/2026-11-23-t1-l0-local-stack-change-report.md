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
| The SSE streams (kitchen, bar, public board) sent no headers until the first event (found by the smoke check on the running stack) | A screen showed "not connected" until the first order; a gateway that waits for the first byte (Front Door's origin response timeout) would close quiet streams; the stack couldn't show the stream open | Every stream opens with an SSE comment, `:connected` (`common/SseStreams`), so the headers go out at once. Clients ignore comments (the frontend's parser reads only `data:` lines) |
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
| 7 | `fa35eca` | This report, the local stack doc, README |
| 8 | `56fb0f7` to `bbdc295` (10 commits) and this one | Running the stack on this machine; the fixes it led to (below) |

Fixes found by running the stack (step 8), each shown as a diff and approved first:

| Commit | Fix |
|---|---|
| `56fb0f7` | An empty stray file (`devstack/scripts/_c`) committed in step 4 removed |
| `c97aa59` | A one-row sqlcmd result stays an array (PowerShell unrolled it into a string) |
| `6e4147f` | Docker's standard error no longer stops the scripts in Windows PowerShell 5.1 (`Invoke-Docker`); a failed database set-up is dropped, never left half-made; a regression test with a real native program |
| `99fc824`, `953e532` | Smoke check: JSON bodies parsed (operator precedence); a single failure reported instead of stopping (strict mode) |
| `57cdf4c` | Streams open with `:connected` (above); smoke matches the event's `orderId`; `BarStationIntegrationTests.everyStream_opensWithAConnectedComment_beforeAnyEvent` |
| `40a87e7` | `SQL_PASSWORD` checked against SQL Server's rule for `sa` before anything starts (a weak one left the container unhealthy) |
| `87ca265` | Smoke check sells a kitchen item, adding an always-on light meal ("Smoke check item") when the demo data has none (its set meals are only planned) |
| `951f4ef` | Smoke check holds the stream for the full time (it stopped at the blank line after an event, so a first run reported "held" after 0 s; that run was not counted) |
| `bbdc295` | Secret scan skips its own patterns and the script-test fixtures; `.gitleaksignore` for one checked dummy token |

## Tests

| Test | Result |
|---|---|
| `RefreshCookiesTest` (4: prefix on issue and clear, bad values refused, existing behaviour unchanged) | Pass |
| `AuthLogoutIntegrationTests` (6, unchanged expectations) | Pass |
| `RequestContextFilterTest` (6: forwarded host from a trusted proxy only, chains, unsafe values, no host) | Pass |
| `RequestContextIntegrationTests`, `RequestLogFieldsIntegrationTests`, `ProductionLogFormatTest`, `ClientIpResolverTest`, the login-protection classes | Pass |
| `DevstackScriptsTest` (2): every devstack script parses; `devstack.tests.ps1`, 35 cases: name rule, settings defaults and refusals, the port suffix, a new customer remembered, dropping `master` refused, `reset.ps1` refusing a database that isn't the stack's and doing nothing without `RESET` | Pass. A mutant `reset.ps1` without its two checks failed both reset cases |
| Secret scan (`secret-scan.ps1`) over every line the branch adds since `main` (44,018), the example files, and gitleaks over the branch's 134 commits | No secrets found. The patterns were checked against planted fake secrets (all caught) and code that only names a secret (none flagged). gitleaks' one finding was an unsigned dummy token in `OfflineGrantSignerTest` (`{"alg":"none"}`, empty signature), listed in `.gitleaksignore` by its exact fingerprint |
| Full suite (`mvnw test`, after the stream fix) | 1186 tests, 0 failures, 0 errors, 0 skipped (1179 before T1-L0, plus 7 new) |

**On the running stack** (Docker Desktop 4.85.0, Docker 29.6.2, Compose 5.3.1, Windows PowerShell 5.1, 2026-11-23):

| Check | Result |
|---|---|
| `docker compose config`; `caddy validate` | Valid |
| First-time set-up on this machine, following the doc: Docker Desktop, `stack.env`, the hosts line, `up.ps1`, trusting Caddy's root, `status.ps1` | Works; `up.ps1` 71 s with the images present (the first, with downloads, longer); `status.ps1` "All good". The run found the script problems listed above, each fixed |
| `smoke.ps1` (full, 122 s) | 7 of 7: health through the proxy with `X-Request-Id`; the refresh cookie `Secure; HttpOnly; SameSite=None; Path=/api/auth/refresh`; refresh with it; our request id echoed; the log line `host=beaconculinary.pos.localhost`; the order event through the proxy in 0.09 s; the stream open for 120 s and the next event in 0.07 s |
| Reset | After `status.ps1 -Snapshot`, `beaconculinary` had 2 sales, 3 shifts, the smoke item, sessions and sign-in attempts; `testcustomer` one count-sheet category fewer. `reset.ps1` (41 s), then `status.ps1 -Compare`: both "same as the snapshot" (collation, Flyway 116, the row count of every table) |

**Not done:** the clean set-up from a fresh worktree with its own `STACK_NAME` (new volumes) and Caddy's root removed
first. The first-time set-up above was on this machine, where Docker had just been installed and nothing of the
stack existed, following only the doc; a second, separate run would repeat it. (Windows Sandbox can't run Docker,
so a truly clean machine isn't possible here.)

## Open items

- **Front Door, before production:** `COOKIE_PATHPREFIX=/api` and `SECURITY_LOGINPROTECTION_TRUSTEDPROXIES` with Front
  Door's ranges. The SSE keepalive is done (follow-up 1); keep `APP_SSE_KEEPALIVEINTERVAL` well under Front Door's
  4-minute idle timeout.
- **T1-L0-FE:** the frontend's `Dockerfile` (contract in the doc, section 3).
- **T1-1:** resolving the customer from `X-Forwarded-Host` (trusted proxies only), then serving both databases.

## Follow-ups (after T1-L0 was approved)

| # | Commit | What |
|---|---|---|
| 1 | `4f3faf5` | **SSE keepalive.** Every open stream (kitchen/orders, bar, board) gets `:keepalive` every `app.sse.keepalive-interval` (`APP_SSE_KEEPALIVEINTERVAL`, default 25s, 0 = off), so Front Door's 4-minute idle timeout doesn't drop quiet screens. `common/SseChannel` (one per kind of stream; a stream leaves it on completion, timeout, error or a failed write) and `common/SseKeepalive` (one scheduled job); the three broadcasters delegate to their channel. Not activity: no request is made, so no session, till or device is touched, and tills don't open streams. Off in the suite (`LoginProtectionTestDefaults`). `smoke.ps1 -QuietHoldMinutes N` |
| 2 | (this) | **`devstack/scripts/plan-today.ps1`**: today's demo meals made sellable through the API as an admin (plan if missing, confirm the ingredient requirements, record the actual portions), plus the always-on light meal "Smoke check item". Idempotent; refuses a customer that isn't the stack's, a database that isn't on the stack's SQL Server, or one the backend isn't serving |
| 3 | (this) | Wording: "OptimumSCS" meaning Beacon Culinary's production database corrected to that in the go-live checklist, the new-customer database doc and the T0-7 change report (OptimumSCS is the vendor; Beacon Culinary is customer 1). Not changed: the comment in `db/baseline/B116__baseline.sql` says "(OptimumSCS, dev, test)", but editing it would change the baseline's checksum and break validation of every database built from it |

**Tests:**

| Test | Result |
|---|---|
| `SseKeepaliveIntegrationTests` (3, keepalive 200 ms): quiet kitchen, bar and board streams get `:connected` and 3+ `:keepalive` comments and nothing else (no `data:` or `event:` line, which is what makes clients ignore them); a stream the client has left leaves its channel and gets no more bytes; the latest session and device "last seen" don't change while keepalives flow | Pass |
| `SseKeepaliveTest` (4): 0 schedules nothing; 25 s schedules one job, cancelled on shutdown; a negative interval is refused; empty channels send nothing | Pass |
| Full suite | 1193 tests, 0 failures, 0 errors, 0 skipped (1186 + 7) |
| `smoke.ps1 -QuietHoldMinutes 6` on the stack | 8 of 8 in 484 s; the quiet stream open 360 s through Caddy with 14 keepalives, longest silence 25 s, 0 events |
| `plan-today.ps1` on the stack | Run 1: the four demo meals and four Lunch extras `PLANNED` to `READY` (2 stock requests). With today's Lunch plan removed: re-planned, confirmed and `READY` (20 each). Each second run: nothing changed (same counts). Refused `-Customer testcustomer` (not being served), `othercustomer` (not the stack's), `master` (reserved). A first version read the meal periods' list as one item (Windows PowerShell 5.1 returns a JSON array as one object), found on the stack before it was committed |
