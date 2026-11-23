# T0-7 a clean starting point for new customer databases: change report

Branch `feature/infrastructureUpdatesBE` (on top of T0-6a, as agreed; not a separate branch from main). Baseline
`B116__baseline.sql`. Procedure and rules: `docs/2026-11-22-new-customer-database.md`.

## What changed

Before T0-7, a new database replayed all 114 migrations from V1, which brought eight demo users with one published
credential, a demo menu and recipes, demo ingredients and stock, and other history. Now:

- **A baseline at V116** (`db/baseline/B116__baseline.sql`): the schema after V1..V116 with only the REQUIRED
  reference data (Main Store, Kitchen and their assignments, All Day, the three catalogue groups, the receipt
  settings row). Flyway applies it only to an empty database, and only where `db/baseline` is in its locations (the
  `prod` profile and the new-database command). Existing databases (OptimumSCS, dev, test) never run it.
- **An optional starter template** (`db/starter/starter.sql`): Breakfast and Lunch, catalogue subcategories,
  component and count-sheet categories.
- **An operator command and script**: `NewDatabaseCommand` and `scripts/new-customer-database.ps1` take an empty
  database to the current schema (and starter), then run the T0-6a first-admin command.
- **`baseline-on-migrate` off**: a database with tables but no migration history stops the app.
- **Tooling** to generate the baseline from a migrated database, and a test that the baseline and the full path
  give identical schemas.

Dev and test are unchanged: they stay on the full migration path with all the demo data.

## Steps and commits

| Step | Commit | What |
|---|---|---|
| 1 | `76e8055` | Baseline migrations proved on throwaway databases; schema catalogue, generator, throwaway-database helper, required data |
| 2 | `c63282f` | `B116__baseline.sql`; `BaselineSchemaIntegrationTests`; two generator fixes |
| 3 | `091c4ac` | `baseline-on-migrate` off; `db/baseline` in the `prod` locations |
| 4 | `23b45fb` | The starter template |
| 5 | `0ff6fa2` | `NewDatabaseCommand`, `scripts/new-customer-database.ps1`; `NoConsolePrintingTest` allows both operator commands |
| 6 | `a58594a` | `NewDatabaseIntegrationTests`, `BaselineSmokeIntegrationTests` |
| 7 | (this) | Docs; doc references moved to the 2026-11-22 name |

## Step 0 findings

- **Flyway 10.20.1 Community** (managed by Spring Boot 3.4.1) supports baseline (`B`) migrations. Proved on
  throwaway databases: an empty database with the baseline folder gets only `B116` (type `SQL_BASELINE`); V117 then
  applies; a database with history ignores `B116`; without the baseline folder, validation of a baseline-built
  database fails ("applied migration not resolved locally: 116").
- **Highest migration: V116** (114 files). The full path takes about 9 s locally and leaves 63 tables, 7 views,
  7 triggers, 1 computed column and 23 filtered indexes.
- **The test suite** builds `BeaconculinaryTest` (persistent) through the full path and signs in as the demo
  accounts throughout (`AuthTestHelper`, about 105 test files); about 28 files also use the seeded catalogue or
  stock. So the suite stays on the full path.

## Decisions taken during the work

1. **The baseline in its own folder**, not in `db/migration`: otherwise any empty database (a new developer's test
   database too) would get the baseline and the suite's demo accounts would be missing.
2. **REQUIRED data** (four items) justified by what breaks without it; everything an admin can create through the
   API is STARTER. **All Day is REQUIRED**: light meals are offered only under the period that accepts them, which
   the API can't create and which can't be deactivated.
3. **No demo script**: dev and test keep the full path, which already has the demo data.
4. **Generated, then reviewed**: no `sqlpackage` or `mssql-scripter` is installed, so the generator reads the system
   catalogue. Constraints SQL Server named itself get deterministic names (`DF_table_column`, `PK_table`, …), and
   the comparison maps both sides the same way.
5. **PowerShell** for the script (Windows operators); the commands run from the app jar through
   `PropertiesLauncher`, like the first-admin command.
6. **Flyway's own logging is switched off** in `NewDatabaseCommand`: it prints the full JDBC URL (found on the first
   end-to-end run).

## Found, not changed

- The readiness analysis (sections C.5–C.9) isn't in the repository; the classification follows the migrations and
  the code.
- There is no CI pipeline in the repository: the new tests run with the normal `./mvnw test`, and need a test login
  with `CREATE DATABASE` (skipped otherwise).
- Locally the server is SQL Server 2025 (Flyway warns it is newer than it has tested); production is Azure SQL. The
  baseline has no server-specific options, but its first real run on Azure SQL should be watched.
- Azure SQL's default collation (`SQL_Latin1_General_CP1_CI_AS`) differs from the local one; the baseline, like the
  migrations, takes the database's default.
- B116 was edited once after step 2 (two comment lines, to the 2026-11-22 doc name) before any database used it.
  From the first customer database it is final.

## Tests

- New: `BaselineSchemaIntegrationTests` (1), `NewDatabaseIntegrationTests` (4), `BaselineSmokeIntegrationTests` (1);
  `GenerateBaselineRun` (on demand only).
- Adjusted: `NoConsolePrintingTest` (both operator commands).
- Full suite: **1174 tests, 0 failures, 0 errors, 1 skipped** (`StaffManagementIntegrationTests`, skipped before).
- **Timing**: an empty database to the current schema in about 0.9 s (`NewDatabaseCommand`, locally; the baseline
  itself 0.33 s), against about 9 s for the full path. The whole script, first admin included, about 4 s.
- Manual: the script run end to end against a throwaway database (with the starter), then again on the same
  database (refused, exit 3); throwaway databases dropped.

## Follow-ups before merge

| # | Commit | What |
|---|---|---|
| 1 | `5291dd8` | `app.flyway.include-baseline` (`FlywayBaselineLocation`) adds `db/baseline` under any profile; true in `prod`, false by default and in the tests. Replaces the `prod` profile's locations override |
| 2 | `56a63f5` | `NewDatabaseCommand` refuses a database whose collation isn't the expected one (`SQL_Latin1_General_CP1_CI_AS`, or `--collation` / `NEW_DATABASE_COLLATION`); the script's `-Collation`; customer test databases created with that collation |
| 3 | `af236bb` | `scripts/tests/new-customer-database.tests.ps1` and `NewCustomerDatabaseScriptTest`: the password the script asked for is cleared on every exit path (PowerShell runs `finally` on `exit`; proved, and a copy without the cleanup fails 4 cases); the script warns when transcription is on by policy |
| 4 | `a493386` | The 18 users `O2SessionIntegrationTests` and `O2LockIdleTillStatusIntegrationTests` had left in the test database (5 active admins) removed; their `tearDown` now cleans staff in `finally`; `StaffManagementIntegrationTests` switches other admins off for its last-admin check instead of skipping |
| 5 | (this) | Docs |

**Collations found:** local server, `master` and `BeaconculinaryTest`: `Latin1_General_CI_AS`; local dev `Beaconculinary181026`: `SQL_Latin1_General_CP1_CI_AS`; Azure SQL default: `SQL_Latin1_General_CP1_CI_AS`; OptimumSCS: to be checked (query in the new-customer doc, section 8).

**Full suite after the follow-ups: 1179 tests, 0 failures, 0 errors, 0 skipped.**

## Files

**Created**
- `src/main/resources/db/baseline/B116__baseline.sql`, `src/main/resources/db/starter/starter.sql`
- `src/main/java/com/beaconculinary/api/common/FlywayBaselineLocation.java` (follow-up 1)
- `scripts/tests/new-customer-database.tests.ps1` (follow-up 3)
- Tests: `common/FlywayBaselineLocationTest`, `ops/NewCustomerDatabaseScriptTest` (follow-ups 1, 3)
- `src/main/java/com/beaconculinary/api/ops/NewDatabaseCommand.java`
- `scripts/new-customer-database.ps1`
- `src/test/java/com/beaconculinary/api/support/baseline/`: `SchemaCatalog`, `BaselineScriptGenerator`,
  `ThrowawayDatabase`, `GenerateBaselineRun`, `BaselineSchemaIntegrationTests`
- `src/test/resources/baseline/required-data.sql`
- `src/test/java/com/beaconculinary/api/ops/`: `NewDatabaseIntegrationTests`, `BaselineSmokeIntegrationTests`
- Docs: `2026-11-22-new-customer-database.md`, this report

**Modified**
- `src/main/resources/application.yaml`, `src/main/resources/application-prod.yaml`, `.env.example`
- Follow-ups: `ops/NewDatabaseCommand`, `scripts/new-customer-database.ps1`, `support/baseline/ThrowawayDatabase`,
  `ops/NewDatabaseIntegrationTests`, `ops/BaselineSmokeIntegrationTests`, `support/LoginProtectionTestDefaults`,
  `sessions/O2SessionIntegrationTests`, `tills/O2LockIdleTillStatusIntegrationTests`,
  `staff/StaffManagementIntegrationTests`, `docs/2026-11-20-site-settings.md`
- `src/test/java/com/beaconculinary/api/common/logging/NoConsolePrintingTest.java`
- Docs: `README.md`, `docs/2026-11-11-go-live-checklist.md`, `docs/2026-11-21-first-admin-and-invites.md`,
  `docs/2026-11-11-running-flyway-locally.md`
