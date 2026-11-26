# T1-4 customer migrations: change report

**Branch:** `feature/t1-4-customer-migrations`, from `feature/infrastructureUpdatesBE` (`33d007b`). Not pushed, not
merged. **Doc:** `docs/2026-11-26-customer-migrations.md`.

## What it gives

- **An operator command, `MigrateCustomersCommand`,** updates customer databases in multi mode.
  - It runs one customer at a time, as each database's own migration login.
  - A failure marks only that customer MIGRATION_FAILED, and the others go on.
  - Each run is recorded in `provisioning_runs`.
  - Options: `--customer`, `--dry-run`. It is safe to run twice and with a second copy at the same time.
- **Two logins per customer database.** The running app has read, write and execute only; only the command has
  `db_owner`.
- **The version check:** the app refuses a customer whose database is behind its schema (503 `CUSTOMER_UNAVAILABLE`)
  and serves it again by itself once the command has run, with no restart.
- **The local stack** runs the command before the backend starts.
- **Single mode is unchanged.**

## Steps

| Step | Commit | What | Lines |
|---|---|---|---|
| 1 | `4cae233` | Platform `V2` (the migration login's secret names); `register` options; the directory carries them | +141 / -25 |
| 2 | `7ca6bff` | `MigrateCustomersCommand` and its tests; a test-only V117 | +756 / -3 |
| 3 | `cde7fc8` | The version check (behind: 503 and recheck; ahead: WARN); the multi-mode tests run as a restricted app login | +445 / -13 |
| 4 | `fd5e254` | Local stack: two logins, the command in `up.ps1`, `reset.ps1` and `new-customer.ps1`, schema in `status.ps1`; the stack's Flyway container removed | +192 / -67 |
| 5 | this commit | This doc and the customer migrations doc; the platform, routing and local stack docs | see `git show --stat` |

## Decisions (approved)

| Decision | Why |
|---|---|
| Default run: ACTIVE and MIGRATION_FAILED. `--customer` also takes SUSPENDED and PROVISIONING, status untouched. Never ARCHIVED | A new customer is built by `NewDatabaseCommand` and then registered; a suspended one can be brought up to date before it is reactivated |
| One customer at a time, no parallelism | Few customers, seconds each, a readable log, no burst on a shared elastic pool |
| App login: `db_datareader`, `db_datawriter`, `GRANT EXECUTE` | Everything the app does (application locks, views, insert-only history tables with triggers); no temporary objects. Proved by every multi-mode test running as it |
| Behind: 503 `CUSTOMER_UNAVAILABLE`, ERROR, refused without connecting for `schema-recheck` (30 s), then checked again | It comes back by itself after the command; a customer that's behind costs no connection per request |
| Ahead: served, with a WARN | Migrations stay backward compatible (the rule in the doc, section 4) |
| A second copy: Flyway's own lock | No lock of our own needed (behaviour below) |
| The stack's Flyway container is replaced by the command | One path for every customer database; an empty beaconculinary gets every migration from the command |
| A refused customer (no migration login, missing secret) changes no status | Nothing was tried; exit code 4 still flags it |

## Where the code differed from the prompt

| Found | Resolution |
|---|---|
| `up.ps1` had no step that migrated **existing** databases; only a new beaconculinary got migrations (the Flyway container). In multi mode nothing updated a customer database once it existed | The command now does both; the container step is deleted |
| "Two copies: one waits or skips": Flyway on SQL Server locks **per migration**, not per run | The copies take turns: each migration is applied by exactly one, and both end SUCCEEDED or UNCHANGED (tested) |
| The plan had the command run on this machine (`java -cp`), like `TenantRegisterCommand` | The platform database stores the addresses as the stack sees them (`sql`). The command runs in a throwaway Java container on the stack network, like a job in Azure; passwords only as `-e NAME` |
| Spring Boot's start-up log and comment said the local stack migrated every database before the backend | Corrected to name the migration command |
| `TenancyModeIntegrationTests`, **run on its own**, fails at "no `HikariPool-` line": its own context starts inside the capture. Pre-existing (same failure without T1-4) | Not changed (out of scope); passes in the suite, as `check-all.ps1` runs it |
| `NewDatabaseCommand` says "existing databases are migrated by the app" | True in single mode; in multi mode the migration command does it. Left as is |

## Tests

- **New:**
  - `MigrateCustomersCommandIntegrationTests` (7);
  - `CustomerAppLoginIntegrationTests` (2);
  - `CustomerSchemaBehindIntegrationTests` (1);
  - `CustomerPoolsIntegrationTests` +2;
  - `PlatformMigrationIntegrationTests` +1;
  - `TenantRegisterCommandIntegrationTests` +1;
  - `devstack.tests.ps1` +7 cases.
- **Changed:** `MultiCustomerFixture`, where alpha and bravo now use a restricted app login, so every multi-mode test
  runs as it; and the platform version pinned at 2.
- **The local stack (multi mode):**
  - `up.ps1`: both customers `UNCHANGED` at 116;
  - `status.ps1`: "All good", with each schema version and last update;
  - `smoke.ps1`: 19 of 19;
  - the manual failure check: testcustomer FAILED (`Login failed for user '[login]'`), then MIGRATION_FAILED with
    503 while beaconculinary answered 200, then `UNCHANGED` and ACTIVE again.
- **`check-all.ps1`:** the table and the commit hash are in the final report.

## Open items

| For | Item |
|---|---|
| **T1-0b** (Key Vault) | A Key Vault `SecretResolver` for the command too. The app's identity may read only `<slug>-db-login` and `-db-password` (and the platform login); only the migration job's identity may read `<slug>-db-migration-*` |
| **T1-0e** (pipeline) | The command as a pipeline step or Container Apps job **before** the new app version is deployed, from the same image; its exit code gates the release; one run at a time. Its platform login needs write access to `tenants`, `tenant_databases` and `provisioning_runs` |
| **T1-6** (jobs) | While a customer's database is behind or MIGRATION_FAILED, its scheduled jobs fail per run and log an ERROR each time (the other customers' runs go on). T1-6 may skip such customers quietly |
| Provisioning | A new customer: `NewDatabaseCommand` with an owner login, then both logins created and registered. Writing that as one provisioning flow (with Azure SQL users) is still to do |
| T1-11 (Beacon's move) | Beacon production stays single mode (one login, Spring's Flyway) until it moves; then it gets both logins and the command |
| Review | The backward-compatibility checklist is manual; a CI check could flag `DROP`, `sp_rename` or `ALTER COLUMN` in new migrations later |
| Frontend (from T1-5) | Treat 401 `TENANT_MISMATCH` like `SESSION_ENDED` |
