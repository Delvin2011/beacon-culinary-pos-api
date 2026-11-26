# The routing data source (T1-2)

In **multi-customer mode** (`app.tenancy.mode=multi`), every database access goes to the **current customer's own
database**. The connection comes from that customer's pool, built from the customer directory and the secret lookup
(T1-1, `docs/2026-11-24-platform-database.md`). **Single mode (the default) is unchanged**: one database from
`spring.datasource.*`, Spring Boot's own pool, Flyway at start-up. The full existing suite runs in single mode.

**The rule:** in multi mode, a database access with no current customer is an **error**
(`NoCurrentCustomerException`). There is never a fallback to a default database.

```
request / job ──► CustomerContext (id, slug)            ┌─► pool customer-alpha ─► alpha's database
                       │                                │
JPA, JdbcTemplate, ────┴─► CustomerRoutingDataSource ───┼─► pool customer-bravo ─► bravo's database
native SQL, AppLock,          │  no customer: error       │
REQUIRES_NEW, templates       │  not ACTIVE: error        └─  made on first use from the directory's location
                              ▼                               and the login/password from SecretResolver
                    CustomerDirectory (platform database, 30 s snapshot)
```

## 1. The current customer

`tenancy/context/CustomerContext` holds the current customer (`CurrentCustomer`: id and slug) for this thread.

| Use | How |
|---|---|
| A unit of work as customer X (jobs, tests) | `CustomerContext.runAs(customer, () -> ...)` or `callAs(customer, () -> value)` |
| A filter (T1-3: from the request's host) | `try (var scope = CustomerContext.open(customer)) { chain.doFilter(...); }` |
| Read it | `current()` (optional) or `require()` (`NoCurrentCustomerException` when none) |

- The previous customer (or none) comes back afterwards, also when the work throws.
- While a customer is set, log lines carry its slug as `tenantId` (otherwise `default`).
- **A thread doesn't inherit it.** Work handed to another thread (an executor, `@Async`) must call `runAs` there.
  Today only the health-check thread and the SSE keepalive scheduler do this, and neither reads a customer database.
- **No switch inside a transaction.** Switching to another customer while a transaction is open on the thread is
  refused, because that transaction's connection belongs to the first customer.

## 2. How a connection finds its database

`tenancy/routing/CustomerRoutingDataSource` is the application's `dataSource` bean in multi mode. Spring Boot's
Hikari pool backs off. On every new connection it does the following, in order:

| Case | Result |
|---|---|
| No current customer | `NoCurrentCustomerException` |
| Not in the directory, or the id doesn't match | `UnknownCustomerException` |
| Status other than ACTIVE (suspended, archived, provisioning, migration failed) | `CustomerNotServedException(slug, status)`, and the customer's pool is closed. T1-3 maps it to a response |
| ACTIVE | A connection from the customer's pool |

JPA, `JdbcTemplate` (about 33 classes), native queries (`ShiftRepository`, `AppLock`), REQUIRES_NEW transactions
(`AdminAuthorizationService`, `LoginProtectionStore`) and `TransactionTemplate`s (`AlwaysOnMenuService`,
`OfflineSyncService`, `InviteService`, the dashboard's read-only template) all take connections from this one bean.
They follow the current customer with no code change: a transaction's connection is taken when it begins, on the
thread that carries the customer.

## 3. Pools and their lifetime

`tenancy/routing/CustomerPools`, one Hikari pool per customer database, named `customer-<slug>`:

- **Made lazily**, on the customer's first connection, and **once** even under concurrent first use.
- **Built from the directory's location** (server, port, database, options). The login and password come from
  `SecretResolver`. The JDBC URL never holds credentials, and nothing logs them. A missing secret raises
  `MissingSecretException`, which names the secret, never a value.
- **First connection:**
  - the database's collation is compared with the expected one; a mismatch closes the pool and raises
    `CustomerDatabaseMismatchException`;
  - the schema version found is logged:
    `tenancy: customer alpha pool opened (database … on …), schema at version 116`;
  - T1-4: it is compared with the newest version the app contains. Behind: the customer isn't served (503
    `CUSTOMER_UNAVAILABLE`, an ERROR) and is checked again after `schema-recheck` (30 s). Ahead: served with a WARN
    (`docs/2026-11-26-customer-migrations.md`);
  - the first-connection listeners run (the demo-account report).
- **Closed:**
  - by a sweep every minute, when nobody has used the pool for `evict-after`;
  - at once, by the sweep or the next request, when the directory shows the customer is no longer ACTIVE or its
    database location changed. A moved database gets a new pool on its next use.
  - Changes reach the app within the directory's 30-second cache.

## 4. Start-up, JPA and Flyway in multi mode

The app starts **without connecting to any customer database**: there is no customer at start-up. A test proves it
with every customer database unreachable.

| Concern | Multi mode (`tenancy/routing/MultiCustomerDataConfig`) | Why |
|---|---|---|
| Hibernate dialect | `org.hibernate.dialect.SQLServerDialect`, explicit in `application.yaml` (both modes) | No connection to detect it |
| Hibernate metadata at boot | `hibernate.boot.allow_jdbc_metadata_access=false` (Hibernate 6.6's `JdbcSettings.ALLOW_METADATA_ON_BOOT`) | Hibernate would otherwise open a connection at start-up to read the database's metadata and version |
| Database version Hibernate assumes | `app.tenancy.database-major-version` (default **12**, which is what Azure SQL Database reports) | Without metadata Hibernate can't detect it; 12 is what production gets today, and every multi-mode test runs its SQL against the local SQL Server |
| Spring Boot's Flyway run | A no-op migration strategy; it logs `tenancy multi: customer database migrations are not run at start-up` | T1-4: the operator's `MigrateCustomersCommand` updates every customer database before the app starts (the local stack's `up.ps1` runs it); a customer whose database is behind isn't served |
| `spring.datasource.*` | Not used | Every connection is a customer's |

Spring Boot 3.4 reads no database type at boot unless `spring.jpa.database` is set, and `ddl-auto: none` is
explicit, so nothing else connects at start-up.

## 5. In-memory customer data

Data the app keeps in memory is kept **per customer** (`CustomerContext.scopeKey()`), so one customer's data is
never served to another. In single mode the key is always `""`, which gives the old behaviour.

| What | How |
|---|---|
| The kitchen, bar and board streams (`SseChannel`) | A stream belongs to the customer current when it opened. An event goes only to the current customer's streams, and to none when there is no customer. The keepalive reaches every stream |
| Site settings cache (`SiteSettings`) | One snapshot per customer, loaded under its own lock |
| Dashboard cache (`DashboardCache`) | The customer is part of every key |

These are keyed by IP address or user id, but they only decide alerts and log suppression and hold no customer data:
`DeviceAlerts` and `LoginBurstFilter` (IP address), and `DeviceUnboundRequestFilter` (user id; ids can repeat across
customers, which at worst suppresses one log line).

## 6. Background work and health

| Work | Multi mode |
|---|---|
| Scheduled jobs: login-attempt retention (daily, 03:30 in each customer's own time zone), session sweep (every minute) | `tenancy/jobs/CustomerJobs`: once per customer it can serve, inside its context, each failure logged as `job <name> failed for customer <slug>; the other customers' runs go on`. T1-6: section 6a |
| Demo-account report | Per customer, on its pool's first connection (not at start-up) |
| `GET /health` | `database` is the **platform** database probe. It adds `customerPools: {open, failing}`: counts from memory, with no customer named and no customer database touched. Single mode's answer is unchanged |
| Pool sweep, directory refresh | Platform database only |
| `@TransactionalEventListener`s (kitchen, bar, board, dashboard and settings caches) | Run on the request's thread after its commit, as its customer |
| Ops commands (`NewDatabaseCommand`, `FirstAdminCommand`, `TenantRegisterCommand`) | Separate processes with their own connections; not affected |

## 6a. Jobs per customer (T1-6)

**The jobs.** Only the first two touch customer databases; every job that does goes through `CustomerJobs`.

| Job | Kind | When, for each customer | A late run (after a restart) or a missed day (app down) |
|---|---|---|---|
| `login-attempt-retention` (`LoginAttemptRetentionJob`) | Daily: `CustomerJobs.daily` | 03:30 (`security.login-protection.retention-time`) in the customer's own time zone, plus its offset; checked by a tick every minute | Harmless: the delete is idempotent, and the next run deletes everything past the retention period |
| `session-sweep` (`SessionSweepJob`) | Frequent: `CustomerJobs.forEachActiveCustomer` | Every minute | n/a |
| Customer pool sweep (`MultiCustomerDataConfig`) | Not per customer | Every minute | Memory and the platform database only |
| SSE keepalive (`SseKeepalive`) | Not per customer | Every 25 s | Writes to open streams; no database |

**A customer's time zone.** `tenants.time_zone` in the platform database (an IANA id such as `America/New_York`), or
`site.time-zone` when it is empty. A value that isn't a valid zone is logged once (WARN) and the default is used. It
only times the daily jobs: the reports stay in South Africa Standard Time (`docs/2026-11-19-site-locale.md`, section
4). Until `TenantRegisterCommand` takes it, set it in the platform database:
`UPDATE tenants SET time_zone = 'Africa/Johannesburg' WHERE slug = '<slug>'`; the app sees it within the directory's
cache time.

**The spread.** Each customer has a fixed offset of 0 to 20 minutes, `floorMod(slug.hashCode(), 21)` (Java fixes
`String.hashCode`, so it never changes), so a 03:30 job runs between 03:30 and 03:50 and customers in one time zone
don't all start in the same minute. A daily time must be 23:39 or earlier, so a run stays on its own local date.

**Once per local date.** `CustomerJobs` keeps, in memory, the local date each daily job last ran for each customer. A
run is counted when it starts: a failing run is not retried until the next day. After a restart, a job whose time has
passed today runs once, late; a day the app is down is missed. So **a daily job must be harmless with both**; one that
isn't needs a durable record, which isn't built. With several app instances each runs it, as before.

**Daylight saving.** A local time that doesn't exist that day (clocks go forward) is moved forward by the gap, so the
day still runs; a time that occurs twice (clocks go back) is its first occurrence, and the date is then done, so it
never runs twice. (The South African zones have no daylight saving; the tests use New York.)

**Customers that can't be served are skipped by every job.**

| The customer is | The jobs |
|---|---|
| PROVISIONING, SUSPENDED or MIGRATION_FAILED | Skipped, seen in the directory without connecting |
| ACTIVE, database behind the app's schema version (T1-4) | Skipped: found by taking one pooled connection before the work (a daily job only when it is due). The pool logs its ERROR once per version found and DEBUG on later rechecks |
| ARCHIVED | Left out silently |
| ACTIVE and served | Runs. A real failure stays an ERROR on every run, for that customer only |

One WARN when a customer starts being skipped (or the reason changes):
`jobs: customer <slug> is skipped by every job, as it is SUSPENDED (logged once; INFO when it is served again)`; one
INFO when it is served again: `jobs: customer <slug> is served again; its jobs run`. The record is in memory, so a
restart logs the WARN once more.

**Cost at 50 customers.** The session sweep runs 50 small updates one after another, about 0.25 to 1 s a minute, on
Spring's scheduler thread (one thread, shared with the pool sweep and the SSE keepalive). Each customer keeps about one
warm connection (50 across 50 databases), so `evict-after` doesn't apply to customers whose sweep runs every minute.
The daily ticks work in memory and connect only for a customer that is due. No change is needed; an unreachable
customer database costs up to `connection-timeout` (10 s) per run. Revisit (a larger scheduler pool, or running
customers in parallel) if the sweep's time grows towards the minute.

**Adding a job.** Always through `CustomerJobs`; a database access with no customer fails
(`NoCurrentCustomerException`).
- A frequent job: `@Scheduled(fixedDelay = ...)` calling `customerJobs.forEachActiveCustomer("<name>", work)`.
- A daily job: a minute tick, `@Scheduled(initialDelay = 60_000, fixedDelay = 60_000)`, calling
  `customerJobs.daily("<name>", <a LocalTime setting>, work)`, and a setting to turn the tick off in the test suite
  (`LoginProtectionTestDefaults`), where tests run it with `MutableClock` set. State in its Javadoc why a late run and
  a missed day are harmless.
- Skipping, the spread, time zones and failure isolation then come with it; don't add a scheduler, table or library.

## 7. Settings

| Setting | Environment variable | Default | Meaning |
|---|---|---|---|
| `app.tenancy.customer-pool.maximum-size` | `APP_TENANCY_CUSTOMERPOOL_MAXIMUMSIZE` | 5 (1 to 50) | Connections per customer database per app instance |
| `app.tenancy.customer-pool.idle-timeout` | `APP_TENANCY_CUSTOMERPOOL_IDLETIMEOUT` | `5m` (at least 10s) | An idle connection closes (minimum idle is 0) |
| `app.tenancy.customer-pool.evict-after` | `APP_TENANCY_CUSTOMERPOOL_EVICTAFTER` | `30m` | A pool nobody used for this long is closed |
| `app.tenancy.customer-pool.connection-timeout` | `APP_TENANCY_CUSTOMERPOOL_CONNECTIONTIMEOUT` | `10s` | How long a request waits for a connection |
| `app.tenancy.database-major-version` | `APP_TENANCY_DATABASEMAJORVERSION` | 12 | The SQL Server version Hibernate assumes in multi mode |

**In Azure:** one Azure SQL database per customer, in an elastic pool.
- **Connections:** the most an app sends to the elastic pool is instances × customers in use × `maximum-size`.
  Check it against the pool tier's session and worker limits, which are per database and per pool.
- **Quiet customers:** idle eviction brings them to zero connections, and the minimum idle is 0, so they cost nothing.
- **If connections run short:** lower `maximum-size` before adding instances.
- **Per-customer logins:** each customer database's logins are its own. T1-4: the app uses the **app login** (read,
  write and execute only), secrets `<slug>-db-login` and `<slug>-db-password`; the migration login (`db_owner`) is
  used only by the migration command (Key Vault from T1-0b).

## 8. How requests get their customer

Since T1-3, from the address they were sent to (`docs/2026-11-25-customer-from-address.md`). T1-2's test-only header
(`TestCustomerFilter`) is gone; the multi-mode tests send a `Host` header. The fixture, `MultiCustomerFixture`, builds
a throwaway platform database and two customer databases from the B116 baseline; `MultiCustomerIntegrationTest` is
the shared multi-mode context. The local stack runs in multi mode by default.

## 9. Limits until later slices

| Slice | What it adds |
|---|---|
| **T1-3** (done) | The customer from the request's address, the exceptions mapped to responses, `tenantId` on every request, the local stack in multi mode: `docs/2026-11-25-customer-from-address.md` |
| **T1-5** (done) | Tokens bound to their customer: `docs/2026-11-25-customer-bound-credentials.md` |
| **T1-4** (done) | Customer database migrations by an operator command, two logins per database, the version check: `docs/2026-11-26-customer-migrations.md` |
| **T1-6** (done) | Background work: daily jobs in each customer's own time zone, the spread, skipping customers that can't be served (section 6a); `docs/2026-11-26-t1-6-customer-jobs-change-report.md` |

## 10. Tests

| Test | Proves |
|---|---|
| `CustomerContextTest` (7) | Set, read and restore (nested, on exceptions); MDC; no inheritance by other threads; no switch inside a transaction |
| `CustomerPoolsIntegrationTests` (9) | Routing; lazy and once under 20 concurrent first uses; closed when idle (not while in use), suspended, archived or moved; collation mismatch refused; credentials from `SecretResolver`, a missing secret named, no password in the output; unknown customer or stale id refused |
| `MultiModeStartupIntegrationTests` (1) | Starts with every customer database offline, no pool made, Hibernate at version 12; no customer is an error; (T1-3) the backend's own address serves `/health` only |
| `CustomerScopedStateTest` (3) | Streams, settings and dashboard cache per customer |
| `MultiCustomerIsolationIntegrationTests` (10) | Written as alpha, never visible as bravo, through JPA, JdbcTemplate, a native query, AppLock, REQUIRES_NEW (`LoginProtectionStore`, `AdminAuthorizationService`), a REQUIRES_NEW `TransactionTemplate` (`AlwaysOnMenuService`) and the dashboard's read-only template; no customer writes nothing anywhere; a request uses the customer it was given |
| `CustomerJobsIntegrationTests` (8) | Once per active customer, failures isolated and logged with the slug, the real jobs per customer, `/health` in multi mode. T1-6: a suspended customer and one whose database is behind skipped with one WARN (and one pool ERROR) over several runs and resumed with one INFO; a daily job at 03:30 local in Johannesburg and New York, once per local date, over two days; a daily failure for one customer not stopping the other nor retried that day; an invalid zone logged once |
| `DailyScheduleTest` (6) | T1-6: two zones at 03:30 local plus the offset, once per local day; the spread (fixed per slug, 0 to 20, different minutes in one zone); clocks going forward (a time in the gap still runs) and back (a time occurring twice runs once); a late run after a restart; a time after 23:39 refused |
| `TenancyModeIntegrationTests` (4, updated) | Multi mode's data source is the routing one; no Hikari pool at start-up |
