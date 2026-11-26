# Customer migrations (T1-4)

In **multi-customer mode** the running app never changes a customer database's schema. An operator command,
`MigrateCustomersCommand`, applies the customer migrations to each customer database, one at a time, before a new
app version starts. The app only checks each customer's schema version, and doesn't serve a customer whose database is
behind. **Single mode is unchanged**: Spring's own Flyway run at start-up (Beacon production today).

## 1. The command

```
java -cp app.jar -Dloader.main=com.beaconculinary.api.ops.MigrateCustomersCommand \
     org.springframework.boot.loader.launch.PropertiesLauncher [--customer SLUG] [--dry-run]
```

**Settings**, from the environment only, never printed:

| Setting | What |
|---|---|
| `APP_TENANCY_PLATFORM_URL`, `_USERNAME`, `_PASSWORD` | The platform database. The URL must not carry credentials. Its own migrations are applied first, as by every operator command |
| `SECRET_<NAME>` | Each customer's **migration** login, by the secret names the platform database stores (section 2). Key Vault in T1-0b, behind the same `SecretResolver` |

**Which customers:**

| Run | Customers |
|---|---|
| Default | Every **ACTIVE** and **MIGRATION_FAILED** customer, by slug |
| `--customer SLUG` | That one. It may also be **SUSPENDED** or **PROVISIONING**; its status is then left as it is |
| Either | **ARCHIVED** is refused (exit 3) |

**For each customer, one at a time:**

1. A `provisioning_runs` row: action `MIGRATE`, outcome `RUNNING`.
2. Connect as its migration login and check the database's collation against `expected_collation`.
3. Flyway with T0-7's settings:
   - locations `db/migration`, plus `db/baseline` for a database built from the baseline;
   - no baseline-on-migrate, clean disabled;
   - Flyway's own logging off.
4. **Success:** outcome `SUCCEEDED`, or `UNCHANGED` when nothing was pending. `last_migrated_version` and
   `last_migrated_at` are filled, and a MIGRATION_FAILED customer goes back to **ACTIVE**.
5. **Failure** (a migration, the login, the collation): outcome `FAILED` with a short message. An ACTIVE customer
   becomes **MIGRATION_FAILED**, which the app answers with 503 `CUSTOMER_UNAVAILABLE`. The command goes on with the
   next customer.
6. **No migration login registered, or a missing secret:** `REFUSED`; nothing is changed.

**Output:** a table of customer, from, to, result and time; then a line for each customer not updated.

| Exit code | Meaning |
|---|---|
| 0 | Every customer updated or unchanged |
| 2 | Usage |
| 3 | Refused: no such customer, or archived |
| 4 | One or more customers failed or were refused |

**Nothing secret** is printed or stored. A driver message that repeats the login or the password has them blanked
(`Login failed for user '[login]'`), and the database URL is never part of a message.

**`--dry-run`:** connects, checks the collation and shows what would be applied (`WOULD APPLY`, `UP TO DATE`). It
writes nothing: no run row, no status, no schema change. Only the platform database's own pending migrations, if any,
are applied first.

**Run twice:** the second run finds nothing pending; every customer is `UNCHANGED`.

**Two copies at the same time:** they take turns on Flyway's lock. On SQL Server, Flyway takes `sp_getapplock` on each
database for each migration (it waits up to an hour) and checks again under it what is still pending. So each migration
is applied by exactly one copy, the other waits and skips it, and both end `SUCCEEDED` or `UNCHANGED`. The schema
history has no duplicate or failed rows. A pipeline should still run one copy at a time.

**A failed migration leaves no trace in the history.** On SQL Server, Flyway runs each migration in a transaction, so a
failure rolls it back. After the cause is fixed, running the command again applies it; no `flyway repair` is needed.
The exception is a migration with statements that can't run in a transaction; avoid those.

## 2. Two logins per customer database

| Login | Rights in that database only | Used by | Secret names (platform database) |
|---|---|---|---|
| **App login** | `db_datareader`, `db_datawriter`, `GRANT EXECUTE`. Not `db_owner`; can't create, alter or drop anything | The running app, only | `login_secret_name`, `password_secret_name`: `<slug>-db-login`, `<slug>-db-password` |
| **Migration login** | `db_owner` | `MigrateCustomersCommand`, only | `migration_login_secret_name`, `migration_password_secret_name` (platform `V2`): `<slug>-db-migration-login`, `<slug>-db-migration-password` |

**Why the app login is enough:**
- **Application locks:** `sp_getapplock` is open to every user. That covers `AppLock`, and the settings, meal period
  and location stores.
- **Reporting views and the schema history:** `db_datareader`.
- **Insert-only history tables:** their triggers fire with the writer's statement.
- **Nothing else:** the app creates no temporary tables or other objects and uses no `IDENTITY_INSERT`.
- **Proved by tests:** every multi-mode test runs as such a login, and `CustomerAppLoginIntegrationTests` checks
  each of these.

**Registering the migration login:** `TenantRegisterCommand register ... --migration-login-secret NAME
--migration-password-secret NAME`, the two together. Left out, the stored names are kept. `show` prints the names,
never values. The update command refuses a customer without them.

**The app never resolves the migration login.** Locally the backend container isn't even given those secrets
(`compose.yaml` lists the app logins only).

## 3. The version check in the app (multi mode)

On a customer pool's **first connection**, after the collation check, the app compares the database's schema version
with the newest one it contains: the highest `V…` or `B…` file in `db/migration` and `db/baseline` on its classpath.

| The database is | What happens |
|---|---|
| **The same** | Served (INFO: `pool opened …, schema at version 116`) |
| **Behind**, or has no history | **Not served:** **503 `CUSTOMER_UNAVAILABLE`**, the same answer as MIGRATION_FAILED, and an ERROR: `tenancy: customer <slug> database <name> is at schema version 115, behind the app's 116: not served until the migration command updates it`. For `app.tenancy.customer-pool.schema-recheck` (default **30 s**) its requests are refused **without connecting**; after that the next request checks again. So the customer comes back by itself once the command has run, with no restart. Other customers are unaffected |
| **Ahead** (a newer app version updated it) | **Served**, with a WARN: `… ahead of the app's …: served (migrations stay backward compatible)` |

At a customer whose database is behind, `/health` reports that customer's database as down.

## 4. The migration rule: backward compatible with the previous app version

During an update the command runs **before** the new app version starts. For a short time, and longer if a release is
rolled back, the **previous** app version runs against the **new** schema. So every migration must keep the previous
app version working.

**Add first, remove later:**
- **Release N:** add the new column or table. Write to both old and new if needed; read the old.
- **Release N+1:** switch the reads to the new one.
- **Release N+2:** remove the old one.
- **Never rename** a column or table in one step. Add the new one, copy, then remove the old one later.

**The reviewer's checklist** for every new `src/main/resources/db/migration/V*.sql`:

| Check | Fails if |
|---|---|
| It only adds, or removes something no released version still uses | It drops or renames a column, table, view or index that the current release reads or writes |
| A new NOT NULL column has a default | Inserts from the previous version would fail |
| Constraints are not tightened on data the previous version writes | A new CHECK, UNIQUE or foreign key the previous version can violate |
| It is idempotent where it touches data, and safe on both kinds of database | It assumes demo data (full history) or its absence (baseline) |
| It runs in a transaction | Statements that can't (e.g. `ALTER DATABASE`, full-text index changes) |
| Applied migrations are never edited | Any change to a `V*.sql` already merged; that includes the baseline `B116__baseline.sql` |

## 5. A release (multi mode)

1. Build the new app version.
2. **Run `MigrateCustomersCommand`** with the new version's jar: every customer database is brought to the new schema,
   one at a time. Read the table; a non-zero exit code means a customer wasn't updated.
3. **Start the new app version.** Customers whose update failed are MIGRATION_FAILED and answer 503; every other
   customer is served. The previous version, still running until the switch, keeps working: section 4.
4. Fix any failed customer (section 6).

**A customer the command missed** (for example SUSPENDED during the run) is behind once it is active again. The app
refuses it until `MigrateCustomersCommand --customer <slug>` has run.

## 6. A failed customer: find, fix, retry

- **Find it:**
  - the command's table and exit code 4;
  - `provisioning_runs` (action `MIGRATE`, outcome `FAILED`, the message);
  - the customer's status `MIGRATION_FAILED`;
  - locally, `status.ps1`.
- **Fix the cause:** a wrong or missing migration login, a database object in the way, the collation, a network
  rule. The database has no half-applied migration (section 1).
- **Retry:** `MigrateCustomersCommand --customer <slug>`. The default run takes MIGRATION_FAILED customers too.
  Success puts it back to ACTIVE, and the app serves it again within the directory's cache time (30 s).

## 7. The local stack

| Script | What it does |
|---|---|
| `up.ps1` | Registers both logins of each customer, then runs the command **before the backend starts**. A failure is reported, and the stack still starts |
| `reset.ps1`, `new-customer.ps1` | Run the command after registering |
| `status.ps1` | Each customer's schema version and last update; a failure counts as a problem |

**Where the command runs:** in a throwaway `eclipse-temurin:17-jre-jammy` container on the stack network, as a job
would in Azure. The database addresses in the platform database are the stack's own (`sql`). The platform password and
the migration logins reach it as `-e NAME`, never as values on a command line.

**An empty beaconculinary** is created empty and filled by the command: every migration from V1, with the demo data.
The stack's own Flyway container is gone.

**Settings** in `devstack/stack.env`: for each customer, `SECRET_<NAME>_DB_MIGRATION_LOGIN` (e.g.
`pos_beaconculinary_migrate`, not the app login, not `sa`) and `SECRET_<NAME>_DB_MIGRATION_PASSWORD`.

**Proving a failure on the stack** is a manual step; nothing for it ships. Run the command once with a wrong
migration password for one customer, held in the PowerShell session's memory only:

```powershell
. .\devstack\scripts\_common.ps1
$config = Get-StackConfig
$wrong = $config.Values.Clone(); $wrong['SECRET_TESTCUSTOMER_DB_MIGRATION_PASSWORD'] = 'Wr0ng-Pw-Check!'
$bad = $config.PSObject.Copy(); $bad.Values = $wrong
Invoke-MigrateCustomers $bad @('--customer', 'testcustomer')    # FAILED, exit 4, ACTIVE -> MIGRATION_FAILED
# within 30 s: https://testcustomer.pos.localhost/api/... answers 503; beaconculinary is unaffected; status.ps1 reports it
Invoke-MigrateCustomers $config @('--customer', 'testcustomer') # UNCHANGED, MIGRATION_FAILED -> ACTIVE; 200 again
```

## 8. In Azure

| Part | How |
|---|---|
| **The command** | A pipeline step before the deployment, or a **Container Apps job** run by the release (T1-0e), with the same image as the app. Its exit code gates the release |
| **The migration login's secrets** | In Key Vault (T1-0b). **Only the migration job's identity** may read `<slug>-db-migration-*`; the app's identity reads the `<slug>-db-login`/`-db-password` secrets and the platform login's |
| **Logins in Azure SQL** | Per customer database, created at provisioning: an app user with the three rights above, and a migration user as `db_owner` (contained users or server logins) |
| **Single mode** (Beacon production until it moves, T1-11) | Unchanged: Spring's Flyway at start-up, one login |

## 9. Tests

| Test | Proves |
|---|---|
| `MigrateCustomersCommandIntegrationTests` (7) | A full-history database (from empty) and a baseline-built one go to the latest, and a second run does nothing. A failure at A doesn't stop B; A becomes MIGRATION_FAILED with a FAILED run, exit 4, and a retry after the fix brings it back. `--customer` and `--dry-run` (nothing written). Which statuses are taken. The collation check. Two copies at once apply each migration exactly once. No secret in the output or the runs |
| `CustomerPoolsIntegrationTests` (+2) | A database behind is refused without connecting until the recheck, then served once updated; one ahead is served with a WARN |
| `CustomerSchemaBehindIntegrationTests` | End to end: 503 `CUSTOMER_UNAVAILABLE` and the ERROR while another customer is served; after the command, served again without a restart |
| `CustomerAppLoginIntegrationTests` (2) | The app's connection is the app login, not an owner. Locks, writes, the insert-only history, the views. Create, alter, drop and create view refused |
| Every multi-mode test | Runs as the restricted app login (`MultiCustomerFixture`) |
| `PlatformMigrationIntegrationTests`, `TenantRegisterCommandIntegrationTests` | V2's columns, both or neither; register, keep and show the migration secret names |
| `devstack.tests.ps1` (+7) | The migration login settings, each login's rights, the command's container call carrying secrets by name only |

The test-only migration `src/test/resources/db/t1-4-probe/V117__t1_4_probe.sql` never reaches the jar; only the tests
pass its location to the command.
