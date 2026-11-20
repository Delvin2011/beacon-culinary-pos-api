# Beacon Culinary POS: backend

The backend for the Beacon Culinary canteen point-of-sale system. It serves the till (POS), the
kitchen and bar displays, the public order board, and the back office: staff, menu and daily
planning, stock, shifts and end of day, accounts, reports and the dashboard. Tills can keep
selling offline and sync when they reconnect.

Spring Boot 3.4 on Java 17, SQL Server (Azure SQL in the cloud), Flyway migrations, JWT sign-in.
Staff sign in by password or PIN depending on their role.

## Running it locally

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
   | `FLYWAY_URL`, `FLYWAY_USER`, `FLYWAY_PASSWORD` | The Flyway Maven plugin, for local or test databases only |
   | `POS_OFFLINE_ENABLED`, `POS_OFFLINE_GRANTSIGNINGKEY` | Offline selling |

2. **Database.** Create an empty database. The app applies the Flyway migrations
   (`src/main/resources/db/migration`) when it starts. The `dev` profile turns Flyway off, so
   there you apply them yourself: see `docs/2026-11-11-running-flyway-locally.md`.
   `flyway:clean` is disabled. Never point the plugin at production.

3. **Run.**

   ```bash
   ./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
   ```

   The API listens on `http://localhost:8080`. Outside the `prod` profile, the API reference is
   at `/swagger-ui.html`.

Staff accounts are managed in the app (`/admin/staff`). Don't use seeded or shared credentials
anywhere real: see the go-live checklist.

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
- **Till printing:** `2026-11-17-o2b-frontend-handoff.md` and
  `2026-11-17-o2b-receipt-data-api-contract.md` (receipts and kitchen tickets). There is no
  separate printer setup guide yet.
- **Logging:** there is no logging guide yet. Requests are logged by
  `common/LoggingFilter.java`; `/health` is left out (see the O1 resilience change report).
- **Operations:** `2026-11-11-running-flyway-locally.md`, and the login-protection runbook
  (`2026-11-11-login-protection-recovery-runbook.md`) and monitoring queries.
- **Change reports** (`*-change-report.md`): what each piece of work changed and how it was
  tested.
- **Feature notes** in the repository root (`DASHBOARD.md`, `REPORTS_R3.md`, `ACCOUNT_PAYMENTS.md`
  and others) and the frontend prompts that went with them.
