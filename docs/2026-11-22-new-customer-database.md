# New customer database

How a new customer's database is set up from a clean starting point: the schema without demo users, demo menu,
demo stock or other history. Introduced in T0-7. First admin and invite links: `docs/2026-11-21-first-admin-and-invites.md`.

## 1. Two kinds of database

| | Full history | From the baseline |
|---|---|---|
| Who | OptimumSCS, dev, test (everything that exists today) | Every new customer |
| How it was built | Every migration from V1 (`db/migration`) | `B116__baseline.sql` (`db/baseline`), then the migrations after V116 |
| Data it starts with | Demo users, a demo menu and stock, and the reference data | Only the REQUIRED reference data (section 3); the starter if chosen |
| `flyway_schema_history` | 114 rows of type `SQL` | One row of type `SQL_BASELINE` (version 116), then the later ones |

Both have the same schema (`BaselineSchemaIntegrationTests` proves it). Every future migration applies to both,
unchanged.

**How Flyway decides.** A `B` (baseline) migration runs only on an empty database, and only when its folder is in
Flyway's locations. Databases with history ignore it.

| Where | `spring.flyway.locations` | An empty database gets |
|---|---|---|
| `prod` profile (`application-prod.yaml`) | `db/migration`, `db/baseline` | The baseline |
| Everywhere else (`application.yaml`; dev, tests) | `db/migration` | Every migration from V1, with the demo data the tests use |
| `NewDatabaseCommand` | `db/migration`, `db/baseline` | The baseline |

A database built from the baseline **needs `db/baseline` in the locations** to validate: run it under the `prod`
profile. Without it, Flyway stops the app with "Detected applied migration not resolved locally: 116".

`baseline-on-migrate` is **off** everywhere. A database that has tables but no `flyway_schema_history` now stops
the app instead of being marked as migrated.

## 2. Setting up a new customer

**Before you start**

- An **empty** Azure SQL database for the customer (created in the portal or by T1 provisioning) and a login that
  can create tables in it.
- An Azure SQL firewall rule for your workstation's address (remove it afterwards).
- The app jar of the version being deployed (`target/beacon-culinary-api-<version>.jar`), Java 17, PowerShell.
- The first admin's name and email, and the address staff will open the frontend at.
- Run everything from **your own workstation**, not from the Azure container's console (its output is collected into
  the logs, and the first admin's link would end up there).

**Run**

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:sqlserver://<server>.database.windows.net:1433;databaseName=<db>;encrypt=true'
$env:SPRING_DATASOURCE_USERNAME = '<operator login>'
.\scripts\new-customer-database.ps1 -Jar .\beacon-culinary-api-<version>.jar `
    -Name 'Thandi Mokoena' -Email 'thandi@customer.co.za' -BaseUrl 'https://pos.customer.co.za' -Starter
```

The script asks for the database password (hidden) unless `SPRING_DATASOURCE_PASSWORD` is set, passes it to the two
commands through its own environment only, and clears it at the end. Nothing about the connection is printed.

| Step | What it does |
|---|---|
| 1. `NewDatabaseCommand [--starter]` | Refuses a database that isn't empty. Applies the baseline and every migration after it; with `-Starter`, the starter template (section 4). Prints the version reached and the time taken |
| 2. `FirstAdminCommand` | The first admin, without credentials, and their one-time invite link, printed in this window only (T0-6a runbook, section 3) |

Locally the whole procedure takes about 4 seconds (under a second for the database). Send the link privately.

**Exit codes** (the script stops at the first non-zero one and says which step):

| Code | NewDatabaseCommand | FirstAdminCommand |
|---|---|---|
| 0 | Done | Done |
| 2 | Usage: an unknown option, or `SPRING_DATASOURCE_URL` missing | Usage |
| 3 | Refused: the database isn't empty | Refused (see the T0-6a runbook) |
| 4 | Failed: database error (the message gives the SQL state) | Failed |

When step 2 fails after step 1 succeeded, the database is ready: fix the problem and run `FirstAdminCommand` on its
own (T0-6a runbook, section 3).

**After**

1. Point the customer's app at the database and start it with `SPRING_PROFILES_ACTIVE=prod` (it includes
   `db/baseline`; see section 1). The startup log says `Demo accounts: none active; demo guard on`.
2. The first admin accepts their link and does the onboarding settings (go-live checklist item 15): receipt
   settings and business name, VAT, first trading day, meal periods, stock locations (rename Main Store and
   Kitchen), then staff by invite link.
3. Remove the firewall rule.

The commands can also be run on their own:

```
java -cp <jar> -Dloader.main=com.beaconculinary.api.ops.NewDatabaseCommand \
     org.springframework.boot.loader.launch.PropertiesLauncher [--starter]
```

## 3. What a new database contains

Each seed on the full migration path, classified:

| Data | Class | Why |
|---|---|---|
| Main Store (PRIMARY) and Kitchen (SECONDARY), and the four location assignments to Kitchen | **REQUIRED** | Every delivery, issue and light-meal sale resolves its location through them (T0-5c), and a trigger refuses a table without exactly one active primary. Renamed at onboarding |
| The All Day meal period (always open, accepts light meals) | **REQUIRED** | Light meals are offered under "the period that accepts LIGHT_MEALS". The admin API only creates timed set-meal periods, and All Day can't be deactivated (`ALL_DAY_PROTECTED`). Without it, light meals are never on the menu |
| The three top-level catalogue groups: Set meals, Light meals, Beverages | **REQUIRED** | The groups are fixed: no API adds them, and every subcategory and meal sits under one |
| The `receipt_settings` row (id 1), blank | **REQUIRED** | Receipt reads use row 1; saving re-creates it only as a safeguard |
| Site settings | none | Defaults live in configuration (T0-4a); nothing is stored until an admin saves |
| Breakfast and Lunch periods | STARTER | Admins create timed periods (`POST /admin/meal-periods`) |
| Catalogue subcategories (Breakfast, Lunch, Burgers, Wraps, Sandwiches, Chips & sides, Hot and Cold beverages) | STARTER | Admins create subcategories |
| Component categories (Meat … Desserts & fruit, 7) | STARTER | Optional on components; admins create them |
| Count-sheet categories (POULTRY … CATERING WATER, 28) | STARTER | Every ingredient needs one, but admins create them; without the starter, the first step is to add one |
| The eight demo users (V13, V14, V32, V69, V89, V98) | DEMO | One shared, published credential |
| The demo menu and recipes (V27, V29, V59) | DEMO | |
| The demo ingredients, deliveries and stock movements (V58) | DEMO | |

The legacy tables T0-3 and earlier migrations dropped are not in the schema at V116, so they are not in the baseline.

Row ids differ between the two kinds of database (for example All Day is id 1 in a new database, 3 on the full
path). No code depends on fixed ids; `BaselineSmokeIntegrationTests` trades on a new database to prove it.

## 4. The starter template

`db/starter/starter.sql`, applied only with `-Starter` / `--starter`, after the migrations: the STARTER rows above.
No menu, prices, stock or users. It is not a migration (it isn't recorded in `flyway_schema_history`) and adds only
rows that are missing, so running it twice changes nothing. `NewDatabaseIntegrationTests` applies it to a fresh
baseline database on every test run, so it can't fall behind the schema.

**Dev and test** stay on the full migration path, which has all the demo data the suite and local development use;
there is no separate demo script.

## 5. Rules for migrations and the baseline

1. **Every migration applies to both kinds of database.** A migration must not depend on data only the full path has
   (a demo user, a demo meal, a row id). V58 looking up `admin@canteen.local` is the example of what not to do. Rely
   only on the REQUIRED rows, and on their kinds and roles rather than their names or ids.
2. **Never edit a baseline once a customer database has been built from it**: its checksum is in that database's
   history, and validation would fail. (B116 is final from the first customer onwards.)
3. **Re-baseline only deliberately**, for example once a year or after a large clean-up, by adding a new
   `B<N>__baseline.sql`; earlier baselines stay. Flyway uses the latest baseline for a new database. See section 6.
4. **Never edit applied migrations, never `flyway:clean`** (as before).

## 6. Re-baselining

1. Update `src/test/resources/baseline/required-data.sql` if the REQUIRED data changed.
2. Generate: `./mvnw test -Dtest=GenerateBaselineRun -Dbaseline.generate=true`. It migrates a throwaway database
   through every migration, reads its schema and writes `target/baseline/B<N>__baseline.sql`.
3. **Review** the file (below), then copy it to `src/main/resources/db/baseline/`.
4. Run `BaselineSchemaIntegrationTests`, `NewDatabaseIntegrationTests` and `BaselineSmokeIntegrationTests`.

**Review checklist**

- [ ] The header names the right version, and no section is empty that shouldn't be.
- [ ] Only the REQUIRED rows are inserted (`INSERT INTO` appears only in the reference-data section).
- [ ] No demo data: no `canteen.local`, no password hashes (`$2a$`), no menu, stock or user rows.
- [ ] No `USE`, database name, filegroup, `COLLATE` or server-specific option (it must run on Azure SQL).
- [ ] No Flyway placeholder (`${`).
- [ ] Views and triggers start with `CREATE`, each in its own `GO` batch.
- [ ] No name SQL Server generated (`DF__`, `PK__`, `UQ__`, `CK__`, `FK__` followed by a hash).
- [ ] `BaselineSchemaIntegrationTests` passes.

## 7. Tests

| Test | What it proves |
|---|---|
| `BaselineSchemaIntegrationTests` | A baseline-built database and a fully migrated one have identical schemas: tables, columns (type, nullability, identity, computed, default), keys, checks, foreign keys, indexes with filters, views and triggers. Fails with both sides listed |
| `NewDatabaseIntegrationTests` | Only the REQUIRED rows; no demo data or legacy tables; the starter optional and safe twice; a later migration applies on top of both kinds; a non-empty database refused; no connection details printed |
| `BaselineSmokeIntegrationTests` | The app trades on a new database: first admin, invite, sign-in, plan a meal, open a shift, sell |

They create and drop their own throwaway databases (`T07…Test`) on the test server, so the test login needs
`CREATE DATABASE`; without it they are skipped. The rest of the suite stays on the full migration path.

There is no CI pipeline in the repository yet; these run with the normal `./mvnw test`.

## 8. One-time check for existing databases

`baseline-on-migrate` is now off. Before deploying this version to an existing database (OptimumSCS on Azure),
confirm it has its migration history:

```sql
SELECT MAX(CAST([version] AS int)) AS [version], SUM(CASE WHEN [success] = 0 THEN 1 ELSE 0 END) AS [failed]
FROM [flyway_schema_history];
```

Expected: the current highest version (116 or later) and 0 failed. Local dev and test databases were checked when
T0-7 was built (114 rows, version 116, none failed).
