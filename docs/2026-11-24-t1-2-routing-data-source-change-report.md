# T1-2 routing data source: change report

**Branch:** `feature/t1-2-routing-data-source`, from `feature/infrastructureUpdatesBE`. Not pushed, not merged.
**Doc:** `docs/2026-11-24-routing-data-source.md`.

## What it gives

In multi-customer mode, every database access goes to the current customer's own database, through a routing data
source built from the customer directory and `SecretResolver` (T1-1). A database access with no current customer is
an error (`NoCurrentCustomerException`), never a fallback. **Single mode, the default, is unchanged.** The full
existing suite passes in it, and the local stack stays in single mode.

## Steps

| Step | Commit | What |
|---|---|---|
| 1 | `1550ff4` | The customer context: `CurrentCustomer`, `CustomerContext` (`runAs`, `callAs`, `open` scopes that restore), `tenantId` in the logs, no customer switch inside a transaction |
| 2 | `cb0a232` | `CustomerPools` and `CustomerRoutingDataSource`. Lazy pools, made once under concurrency, from the directory and `SecretResolver`. Collation check; ACTIVE customers only; pools closed when idle, when the customer is no longer served, or when its database moves |
| 3 | `152b627` | Multi-mode start-up with no customer connection (`MultiCustomerDataConfig`): the routing `dataSource`, Hibernate without boot metadata at version 12, no start-up Flyway run, the pool sweep. `DemoAccountGuard` reports per customer on its pool's first connection. The test-only customer header and its guard |
| 4 | `3bba295` | In-memory customer data kept per customer: the SSE streams, the site settings cache, the dashboard cache |
| 5 | `34a9aa7` | Isolation tests across two customer databases, for every D.10 path, plus the shared multi-customer fixture |
| 6 | `59d5e6f` | Background work per customer (`CustomerJobs`). `/health` probes the platform database and counts customer pools |
| 7 | this commit | Docs and settings (`application.yaml`, `.env.example`), and this report |

## Decisions

| Decision | Why |
|---|---|
| Multi-mode wiring as beans that exist only in multi mode (`MultiCustomerDataConfig`): a `HibernatePropertiesCustomizer` and a no-op `FlywayMigrationStrategy`, not an environment post-processor as first planned | The existing multi-mode test sets the mode through an initializer, which runs after environment post-processors; beans work however the mode is set |
| `hibernate.boot.allow_jdbc_metadata_access=false`, and the database version set to **12** (`app.tenancy.database-major-version`) | Hibernate 6.6.4 (Spring Boot 3.4.1) would otherwise connect at start-up. 12 is what Azure SQL Database reports, so it's what production gets today (question 2, approved) |
| In-memory customer data kept per customer in this slice (question 1, approved) | `SiteSettings`, `DashboardCache` and the three SSE channels were app-wide; in multi mode they would show one customer's settings, figures or live orders to another |
| `/health` in multi mode: the platform database probe, plus `customerPools: {open, failing}`, counts only (question 3, approved) | Public endpoint polled by tills: no customer names, and no connection to customer databases |
| The test-only mechanism lives in `src/test` only | It can't be in the jar. A guard test checks `target/classes` and `src/main`, and a multi-mode app without it ignores the header |
| `CustomerContext` refuses a customer switch inside an open transaction | The transaction's connection belongs to the first customer |
| Per-customer settings loads use one lock per customer | One customer's slow database mustn't hold up others' settings |

## Where the code differed from the prompt or the plan

| Found | Resolution |
|---|---|
| The T1 breakdown v2 and the readiness analysis (D.10) aren't in the repository or earlier sessions | Worked from the prompt's own D.10 list and the code; said so in the plan |
| `InviteService` also uses a `TransactionTemplate` (REQUIRED, not in the prompt's list) | Covered by the routing: same data source |
| In-memory customer data (above) | Fixed in step 4, with approval |
| `POST /auth/login` answers 401 and logs nothing when the database fails: Spring Security turns any error during sign-in into "unauthorised". The same happens in single mode today | Not changed (out of scope); open item below. The multi-mode tests use the public board, which reports a 500 |
| Plan named the doc 2026-11-25 | Named by today's date, 2026-11-24 |

## Every place that touches the database outside a request

| Place | What was done |
|---|---|
| `LoginAttemptRetentionJob` (daily 03:30) | Runs once per ACTIVE customer through `CustomerJobs`, failures isolated |
| `SessionSweepJob` (every minute) | Same |
| `DatabaseHealthCheck` (`health-db-check` thread) | Probes the platform database in multi mode |
| `DemoAccountGuard.reportAtStartup` (`ApplicationReadyEvent`) | Skipped in multi mode; reports per customer on its pool's first connection, using that connection |
| Spring Boot's Flyway run at start-up | A no-op in multi mode; customer migrations come in T1-4 (the local stack migrates them before the backend starts) |
| Hibernate's start-up metadata read | Off in multi mode |
| `CustomerPools` sweep, `CustomerDirectory` refresh | Platform database only |
| `@TransactionalEventListener`s (kitchen, bar, board, dashboard and settings caches) | Run on the request's thread after its commit, as its customer; nothing to change |
| `SseKeepalive` scheduler | No database access |
| Ops commands (`NewDatabaseCommand`, `FirstAdminCommand`, `TenantRegisterCommand`) | Separate processes with their own connections; not affected |

## Tests

New: 37 tests in 7 classes. Updated: `TenancyModeIntegrationTests` (step 3), `BackgroundLoggingIntegrationTests` (step 6).

| Class | Tests |
|---|---|
| `CustomerContextTest` | 7 |
| `CustomerPoolsIntegrationTests` | 9 |
| `MultiModeStartupIntegrationTests` | 1 |
| `TestCustomerMechanismGuardTest` | 2 |
| `CustomerScopedStateTest` | 3 |
| `MultiCustomerIsolationIntegrationTests` | 10 |
| `CustomerJobsIntegrationTests` | 5 |
| `TenancyModeIntegrationTests` (updated) | 4 |
| `BackgroundLoggingIntegrationTests` (updated) | 3 |

Throwaway databases only (`T07…Test`). None were left behind; a check after the multi-mode runs found none. No
`flyway:clean`, and no applied migration edited.

**check-all.ps1:** every step green, over two runs on 2026-11-24. The first full run was stopped by
the machine running low on memory, after its backend suite had passed; the second run (`-SkipBackend`) did every
other step.

| Step | Result | Counts | Run |
|---|---|---|---|
| backend tests | PASS | 1265 tests, 0 failures, 0 errors, 0 skipped (1228 before T1-2, plus 37 new) | 1 (22 min) |
| frontend lint | PASS | 0 errors, 0 warnings | 2 |
| frontend types | PASS | 0 type errors | 2 |
| frontend unit | PASS | 1204 passed of 1204 | 2 |
| frontend build | PASS | | 2 |
| frontend check:build | PASS | 648 files, no backend address | 2 |
| frontend e2e | PASS | 5 passed | 2 |
| stack up | PASS | up in 71 s (single mode) | 2 |
| stack status | PASS | all good | 2 |
| smoke | PASS | 7 of 7 checks | 2 |


## Open items

| For | Item |
|---|---|
| T1-3 | A filter that sets the customer from the request's host, placed where the test filter is: after the request log filter, before Spring Security and the device filters, which read the database |
| T1-3 | Map `CustomerNotServedException`, `UnknownCustomerException` and `NoCurrentCustomerException` to responses |
| T1-3 | Switch the local stack to multi mode |
| T1-4 | Customer database migrations per customer; fill `tenant_databases.last_migrated_version` |
| T1-6 | Daily jobs at each customer's own time zone; spreading job runs; other jobs as they come |
| Later | Any `@Async` or executor work must call `CustomerContext.runAs` on its own thread (none today) |
| Later | Sign-in answers 401 without a log line when the database fails (single and multi mode) |
| Later | `OfflineSyncService`'s per-sale template is covered by the same mechanism as `AlwaysOnMenuService`'s (tested); a full offline-sync run in multi mode isn't tested yet |
