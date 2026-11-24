# The platform database (T1-1)

One database, separate from every customer database, that lists the customers, the addresses they are reached at
and where their databases are. It is the base the next slices build on: T1-2 opens a connection to the right
customer database, T1-3 picks the customer from the request's host, T1-4 provisions new customers. **T1-1 changes
nothing about what the backend serves:** in both modes it still serves the one database in
`spring.datasource.url` (`ACTIVE_CUSTOMER` on the local stack).

```
                 ┌──────────── platform database (pos_platform) ────────────┐
request host ──► │ tenant_hosts ──► tenants ──► tenant_databases            │ ──► secret NAMES
                 │ (from T1-3)      status      server, port, database       │        │
                 └───────────────────────────────────────────────────────────┘        ▼
                                                              SecretResolver: environment (now), Key Vault (T1-0b)
```

| Part | Here | Production |
|---|---|---|
| Platform database | `pos_platform` on the stack's SQL Server | Its own Azure SQL database |
| Its login | `pos_platform`, its password `PLATFORM_DB_PASSWORD` in `devstack/stack.env` | A dedicated login (or managed identity); password in Key Vault |
| A customer's database login | One per customer (`pos_<customer>`), `db_owner` of that database only | The same |
| Secret values | `SECRET_<NAME>` environment variables, from `stack.env` | Key Vault secrets with the same names (T1-0b) |

## 1. What it holds

The schema is `src/main/resources/db/platform/V1__platform.sql`, with its own Flyway history (location
`classpath:db/platform`), never mixed with the customer migrations in `db/migration`.

| Table | One row per | Rules the database enforces |
|---|---|---|
| `tenants` | Customer | `slug` 3-40 lower-case letters, digits and hyphens, not starting or ending with a hyphen, unique (it is the first label of `<slug>.pos.optimumscs.com`). `status` is `PROVISIONING`, `ACTIVE`, `SUSPENDED`, `MIGRATION_FAILED` or `ARCHIVED` |
| `tenant_hosts` | Address a customer is reached at | Stored lower-case, without a port or trailing dot; one customer per host; at most one primary host per customer (`UX_tenant_hosts_one_primary`) |
| `tenant_databases` | Customer (its database) | Server, port, database name, connection options, and the **names** of the login and password secrets. Two customers never share a database (`UQ_tenant_databases_location`). The options can't hold credentials (no `password`, `pwd`, `user`, `uid=`, `secret`). Secret names follow Key Vault's rule. Also: built from the baseline or full history, the expected collation, and the last migrated version (filled from T1-2) |
| `provisioning_runs` | Change made by an operator (from T1-4: by provisioning) | Action, operator, start and finish, outcome `RUNNING`, `SUCCEEDED`, `UNCHANGED`, `REFUSED` or `FAILED`, and a message that is never a secret |

**No secret value is ever stored here,** only secret names such as `beaconculinary-db-password`. Plans and
entitlements (T2) and platform operators (T3) come later as their own tables.

## 2. Tenancy mode

| `app.tenancy.mode` (`APP_TENANCY_MODE`) | At start-up |
|---|---|
| `single` (default) | Nothing new. The platform settings aren't read; the backend behaves as before T1-1 |
| `multi` | Before the customer data source starts: the platform settings are checked, the platform database is migrated through its own pool, and the customer directory is loaded. Any problem stops start-up, with a message naming the setting (never its value) |

| Setting | Environment variable | Default |
|---|---|---|
| `app.tenancy.platform.url` | `APP_TENANCY_PLATFORM_URL` | (none). A `jdbc:sqlserver://` URL **without** a user or password (refused if it has one) |
| `app.tenancy.platform.username` | `APP_TENANCY_PLATFORM_USERNAME` | (none) |
| `app.tenancy.platform.password` | `APP_TENANCY_PLATFORM_PASSWORD` | (none). A secret: Key Vault in Azure |
| `app.tenancy.platform.pool-size` | `APP_TENANCY_PLATFORM_POOLSIZE` | 3 (1 to 20) |
| `app.tenancy.directory-cache-ttl` | `APP_TENANCY_DIRECTORYCACHETTL` | `30s` |

The platform pool (Hikari, named `platform`) is deliberately not a Spring `DataSource` bean: a second one would
switch off Spring Boot's customer data source and its Flyway. Start-up lines in multi mode:

```
tenancy multi: platform database at version 1 (0 migration(s) applied now)
tenancy multi: customer directory loaded, 2 customer(s), 2 active
```

## 3. The customer directory

`tenancy/CustomerDirectory` (multi mode only) answers from a snapshot of the platform database:

| Method | Gives |
|---|---|
| `findByHost(host)` | The customer the host belongs to, any status. Case, a port (`:8443`) and a trailing dot are ignored (`HostNames.normalise`); an unknown or malformed host gives empty |
| `findBySlug(slug)` | The customer with that slug, any status |
| `listActive()`, `listAll()` | The customers, by slug |
| `refresh()` | Reads the platform database now |

Each `Customer` has its id, slug, name, status, hosts (primary first) and `DatabaseLocation` (server, port, database,
options, the two secret names, built from baseline, collation). The snapshot is read again after
`directory-cache-ttl` (30 seconds), so a change made with `TenantRegisterCommand` reaches a running backend within
that time. If a read fails, the last good snapshot is kept, a warning is logged, and the read is tried again after
another TTL. Only `ACTIVE` customers will be served (T1-3); the directory reports the others with their status.

## 4. Secrets

`tenancy/secrets/SecretResolver` turns a secret's name into its value. Today it reads the environment
(`EnvironmentSecretResolver`); T1-0b adds Key Vault behind the same interface, with the same names.

| Rule | |
|---|---|
| A name | Lower-case letters and digits in groups joined by single hyphens, at most 127 characters (`beaconculinary-db-password`): Key Vault's rule |
| Its environment variable | `SECRET_` + the name in upper case with `-` as `_`: `SECRET_BEACONCULINARY_DB_PASSWORD` |
| Missing | `MissingSecretException`: `Secret 'beaconculinary-db-password' is not set (environment variable SECRET_BEACONCULINARY_DB_PASSWORD)`. It names the secret, never a value |

The resolver logs and caches nothing; callers use a value at once and keep no reference to it.

## 5. Registering and changing customers: `TenantRegisterCommand`

An operator's command, run from the backend jar like `NewDatabaseCommand` and `FirstAdminCommand`:

```powershell
java -cp target\beacon-culinary-api-1.0.0.jar -Dloader.main=com.beaconculinary.api.ops.TenantRegisterCommand `
     org.springframework.boot.loader.launch.PropertiesLauncher <action> [options]
```

It connects with `APP_TENANCY_PLATFORM_URL`, `APP_TENANCY_PLATFORM_USERNAME` and `APP_TENANCY_PLATFORM_PASSWORD`
from the environment (never a command-line option), and applies the platform migrations first.

| Action | Does |
|---|---|
| `migrate` | Applies the platform migrations and prints the version |
| `register --slug S --name "Name" --host H [--host H ...] --db-server S [--db-port 1433] --db-name D [--db-options "encrypt=true"] --login-secret NAME --password-secret NAME --built-from-baseline true\|false [--collation C] [--status ACTIVE]` | Creates or updates the customer. Declarative: the hosts given become the customer's hosts (the first is the primary); the same values again change nothing (`UNCHANGED`). Status defaults to `ACTIVE`; a customer can be `ACTIVE` only with a database and a primary host |
| `suspend --slug S`, `activate --slug S` | Changes the status |
| `add-host --slug S --host H [--primary]`, `remove-host --slug S --host H` | One address. The primary host is never removed: make another host primary first (`add-host --primary`) |
| `show [--slug S]` | Lists customers, hosts and database locations (secret names only) |

A host or database that already belongs to another customer is refused. Each change runs in one transaction under
an application lock (`beacon-platform-register`) and records a `provisioning_runs` row, refusals included. Nothing
secret is printed or stored. Exit codes: 0 done or unchanged, 2 usage, 3 refused, 4 failed.

## 6. On the local stack

`devstack/stack.env` needs, besides the T1-L0 settings (every password: 8+ characters, 3 of upper case, lower
case, digits and symbols, no `'`, used nowhere else):

| Setting | What |
|---|---|
| `PLATFORM_DB_PASSWORD` | The `pos_platform` login's password |
| `SECRET_BEACONCULINARY_DB_LOGIN`, `SECRET_BEACONCULINARY_DB_PASSWORD` | beaconculinary's own login, e.g. `pos_beaconculinary` (never `sa` or `pos_platform`), and its password |
| `SECRET_TESTCUSTOMER_DB_LOGIN`, `SECRET_TESTCUSTOMER_DB_PASSWORD` | The same for testcustomer |
| `SECRET_<NAME>_DB_LOGIN`, `SECRET_<NAME>_DB_PASSWORD` | For each customer made with `new-customer.ps1 <name>` |
| `PLATFORM_DATABASE`, `TENANCY_MODE` | Optional: default `pos_platform` and `single` |

The scripts check these before they change anything, and name a missing or weak setting, never its value.

| Script | T1-1 adds |
|---|---|
| `up.ps1` | Step "Platform database": creates `pos_platform` with the collation and its login, migrates it, creates or updates each customer's login (`db_owner` of its database only), and registers every customer as `<name>.pos.localhost`. `-TenancyMode multi` runs the backend in multi mode for that run |
| `status.ps1` | Section "Platform database": its version and each customer's status, hosts and database. `-Snapshot` and `-Compare` include it |
| `reset.ps1` | A full reset also drops and recreates `pos_platform` and registers every customer again; `-Customer <name>` registers that customer again |
| `new-customer.ps1` | Checks the new customer's secrets first, then registers it |

Passwords reach `sqlcmd` as environment variables (`-e`), never on a command line or in a file. The backend
container gets the `SECRET_*` values, ready for T1-2; in Azure they become Key Vault references.

Look at it in SSMS (server `127.0.0.1,14333`, the `sa` login), or ask the command:

```powershell
.\devstack\scripts\status.ps1                       # "Platform database" section
.\devstack\scripts\up.ps1 -TenancyMode multi        # then: docker compose -p beacon-devstack logs backend | Select-String tenancy
```

## 7. Production (later slices)

- The platform database is its own Azure SQL database, collation `SQL_Latin1_General_CP1_CI_AS`, backed up like the
  customer databases. Its login and how migrations are applied there
  (the backend at start-up, or only the operator's `migrate`) are decided with the Azure set-up.
- Each customer database has its own login (or, later, a managed identity), never the platform's.
- `SECRET_*` environment variables become Key Vault secrets with the same names (T1-0b); the stored names don't change.
- Hosts become `<slug>.pos.optimumscs.com` and any custom domain a customer adds.

## 8. Tests

| Test | Proves |
|---|---|
| `PlatformMigrationIntegrationTests` (9) | Every constraint above: slug and host rules, one primary, one customer per host and per database, no credentials in options, secret-name format, statuses and outcomes |
| `TenancyModeIntegrationTests` (4) | Single mode doesn't touch the platform settings; multi migrates the platform database before the customer data source and loads the directory; incomplete or unsafe settings stop start-up, named without values |
| `HostNamesTest` (2), `CustomerDirectoryIntegrationTests` (5) | Case, port and trailing dot ignored; unknown and malformed hosts; suspended customers reported, not active; the cache read again after the TTL and at once on `refresh()`; a customer without a database yet listed without one. (Keeping the last good snapshot when a read fails is in the code, not yet covered by a test) |
| `EnvironmentSecretResolverTest` (5) | Name to variable, missing secret named without a value, invalid names refused |
| `TenantRegisterCommandIntegrationTests` (9) | Register, idempotent re-register, clashes refused, suspend and activate, hosts, every change recorded, no secret printed |
| `devstack.tests.ps1` | The stack's settings checks, the login password never on a command line or left in the environment |
