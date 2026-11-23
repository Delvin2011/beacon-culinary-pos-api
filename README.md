# Beacon Culinary POS: backend

The backend for the Beacon Culinary canteen point-of-sale system. It serves the till (POS), the
kitchen and bar displays, the public order board, and the back office: staff, menu and daily
planning, stock, shifts and end of day, accounts, reports and the dashboard. Tills can keep
selling offline and sync when they reconnect.

Spring Boot 3.4 on Java 17, SQL Server (Azure SQL in the cloud), Flyway migrations, JWT sign-in.
Staff sign in by password or PIN depending on their role.

## Running it locally

Two ways:

- **The local stack** (T1-L0): the production layout in Docker: a gateway on
  `https://<customer>.pos.localhost` sending `/api/*` here, SQL Server and two customer databases. One command
  (`devstack/scripts/up.ps1`) after a one-time set-up: `docs/2026-11-23-local-stack.md`. Use it for
  anything that depends on the gateway, cookies or several customers.
- **The app on its own**, against your own SQL Server, as below.

**You need:** Java 17 and a SQL Server instance. The Maven wrapper (`mvnw`, `mvnw.cmd`) comes
with the repository.

1. **Settings.** Copy `.env.example` to `.env`, which git ignores, and fill it in.
   `.env.example` describes each variable. Never commit real values.

   | Variable | What it's for |
   |---|---|
   | `JWT_SECRET` | Signs the sign-in tokens |
   | `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | The app's database |
   | `TEST_DATASOURCE_URL` | The test database (see Tests) |
   | `CORS_ALLOWED_ORIGINS` | The frontend's origin |
   | `SERVER_PORT` | HTTP port (default 8080) |
   | `LOGIN_PROTECTION_MODE` | Sign-in lockout mode |
   | `COOKIE_PATHPREFIX` | Behind a gateway that removes `/api` (the local stack, Front Door): `/api` |
   | `FLYWAY_URL`, `FLYWAY_USER`, `FLYWAY_PASSWORD` | The Flyway Maven plugin, for local or test databases only |
   | `POS_OFFLINE_ENABLED`, `POS_OFFLINE_GRANTSIGNINGKEY` | Offline selling |

2. **Database.** Create an empty database. The app applies the Flyway migrations
   (`src/main/resources/db/migration`) when it starts. The `dev` profile turns Flyway off, so
   there you apply them yourself: see `docs/2026-11-11-running-flyway-locally.md`.
   `flyway:clean` is disabled. Never point the plugin at production. An empty database started
   outside the `prod` profile replays every migration from V1, demo data included; a new
   customer's database is set up from the baseline instead (`docs/2026-11-22-new-customer-database.md`).

3. **Run.**

   ```bash
   ./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
   ```

   The API listens on `http://localhost:8080`. Outside the `prod` profile, the API reference is
   at `/swagger-ui.html`.

Staff accounts are managed in the app (`/admin/staff`); staff set their own password and PIN from
an invite link. The seeded demo accounts can't sign in under the `prod` profile, and a new
customer's first admin comes from the operator's first-admin command: see
`docs/2026-11-21-first-admin-and-invites.md`.

## Tests

```bash
./mvnw test
```

The integration tests run against a real SQL Server database and delete data, so they always
use their own database: `TEST_DATASOURCE_URL` when set, otherwise `BeaconculinaryTest` on the
local server. A database whose name doesn't contain "test" is refused. Create the empty database
once; Flyway builds it when the tests start.

## Documentation

All under `docs/`.

- **API contracts** (`*-api-contract.md`): login protection, device registration, resilience
  (O1), tills and shifts (O2), receipt data (O2b), offline foundation (O3) and offline sync (O4).
- **Go-live checklist:** `2026-11-11-go-live-checklist.md`. The production blockers, and the
  status of each.
- **Site settings:** `2026-11-20-site-settings.md`. Customer settings vs entitlements vs platform
  settings, and how to add one; API in `2026-11-19-t0-4-site-settings-api-contract.md`.
- **Meal periods:** `2026-11-20-t0-5-meal-periods-locations-api-contract.md`. Create, edit,
  deactivate and activate meal periods; the rules, effects and history.
- **Stock locations:** `2026-11-21-stock-locations.md` (one primary, any number of secondary
  locations, what each flow uses); API in `2026-11-21-t0-5c-locations-api-contract.md`.
- **First admin and invite links:** `2026-11-21-first-admin-and-invites.md` (the operator's
  first-admin command, staff links, retiring the demo accounts); API in
  `2026-11-21-t0-6-invites-api-contract.md`.
- **New customer database:** `2026-11-22-new-customer-database.md`. A new customer's database starts
  from the B116 baseline (no demo data) with `scripts/new-customer-database.ps1`; existing
  databases keep their full migration history.
- **Till printing:** `2026-11-17-o2b-frontend-handoff.md` and
  `2026-11-17-o2b-receipt-data-api-contract.md` (receipts and kitchen tickets). There is no
  separate printer setup guide yet.
- **Logging:** `2026-11-19-logging.md`. The fields on every line, the request line (with the host the
  browser used), formats per environment and how to find a request.
- **Local stack:** `2026-11-23-local-stack.md`. The production layout on a developer's machine
  (`devstack/`): set-up, daily use, switching customer, troubleshooting, and how each part maps to Azure.
- **Operations:** `2026-11-11-running-flyway-locally.md`, and the login-protection runbook
  (`2026-11-11-login-protection-recovery-runbook.md`) and monitoring queries.
- **Change reports** (`*-change-report.md`): what each piece of work changed and how it was
  tested.
- **Feature notes** in the repository root (`DASHBOARD.md`, `REPORTS_R3.md`, `ACCOUNT_PAYMENTS.md`
  and others) and the frontend prompts that went with them.
