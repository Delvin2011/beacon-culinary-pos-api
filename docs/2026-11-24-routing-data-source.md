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
| Spring Boot's Flyway run | A no-op migration strategy; it logs `tenancy multi: customer database migrations are not run at start-up` | Customer databases are migrated per customer: T1-4. Until then, whoever provisions them does it (the local stack's `up.ps1` migrates every customer database before the backend starts) |
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
| Scheduled jobs: login-attempt retention (daily 03:30), session sweep (every minute) | `tenancy/jobs/CustomerJobs`: once per ACTIVE customer, inside its context, each failure logged as `job <name> failed for customer <slug>; the other customers' runs go on` |
| Demo-account report | Per customer, on its pool's first connection (not at start-up) |
| `GET /health` | `database` is the **platform** database probe. It adds `customerPools: {open, failing}`: counts from memory, with no customer named and no customer database touched. Single mode's answer is unchanged |
| Pool sweep, directory refresh | Platform database only |
| `@TransactionalEventListener`s (kitchen, bar, board, dashboard and settings caches) | Run on the request's thread after its commit, as its customer |
| Ops commands (`NewDatabaseCommand`, `FirstAdminCommand`, `TenantRegisterCommand`) | Separate processes with their own connections; not affected |

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
- **Per-customer logins:** each customer database's login is its own, `db_owner` of that database only. The secrets
  are named `<slug>-db-login` and `<slug>-db-password` (Key Vault from T1-0b).

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
| **T1-4** | Customer database migrations per customer (provisioning), and `tenant_databases.last_migrated_version` |
| **T1-6** | Background work completed: per-customer time zones for the daily jobs, spreading runs, other jobs as they come |

## 10. Tests

| Test | Proves |
|---|---|
| `CustomerContextTest` (7) | Set, read and restore (nested, on exceptions); MDC; no inheritance by other threads; no switch inside a transaction |
| `CustomerPoolsIntegrationTests` (9) | Routing; lazy and once under 20 concurrent first uses; closed when idle (not while in use), suspended, archived or moved; collation mismatch refused; credentials from `SecretResolver`, a missing secret named, no password in the output; unknown customer or stale id refused |
| `MultiModeStartupIntegrationTests` (1) | Starts with every customer database offline, no pool made, Hibernate at version 12; no customer is an error; (T1-3) the backend's own address serves `/health` only |
| `CustomerScopedStateTest` (3) | Streams, settings and dashboard cache per customer |
| `MultiCustomerIsolationIntegrationTests` (10) | Written as alpha, never visible as bravo, through JPA, JdbcTemplate, a native query, AppLock, REQUIRES_NEW (`LoginProtectionStore`, `AdminAuthorizationService`), a REQUIRES_NEW `TransactionTemplate` (`AlwaysOnMenuService`) and the dashboard's read-only template; no customer writes nothing anywhere; a request uses the customer it was given |
| `CustomerJobsIntegrationTests` (5) | Once per active customer, suspended left out, failures isolated and logged with the slug, the real jobs per customer, `/health` in multi mode |
| `TenancyModeIntegrationTests` (4, updated) | Multi mode's data source is the routing one; no Hikari pool at start-up |
