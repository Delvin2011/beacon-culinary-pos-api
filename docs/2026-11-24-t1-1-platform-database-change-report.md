# T1-1 platform database: change report

**Branch:** `feature/t1-1-platform-database`, from `feature/infrastructureUpdatesBE`. Not pushed, not merged.
**Doc:** `docs/2026-11-24-platform-database.md`.

## What it gives

- **Part A:** `devstack/scripts/check-all.ps1`, one command that runs everything before a merge: the backend
  suite, the frontend's lint, types, unit tests, build and e2e tests, then the stack (`up`, `status`, `smoke`).
- **Part B:** the platform database, a database of its own that lists the customers, their hosts and where their
  databases are (secret names only, never values), with:
  - `app.tenancy.mode` `single` (default, nothing changes) or `multi` (the platform database is migrated through its
    own pool and the customer directory loaded, before the customer data source starts);
  - the `CustomerDirectory` (find by host or slug, list active; a 30-second snapshot);
  - the `SecretResolver` (a secret's name to its value; the environment now, Key Vault from T1-0b);
  - `TenantRegisterCommand`, the operator's command to register and change customers;
  - the local stack: `pos_platform`, its own login and one login per customer database; `up` registers every
    customer, `status` lists them, `reset` recreates them, `new-customer` registers the new one.

**What the backend serves is unchanged:** one database, in both modes, until T1-2 and T1-3.

## Decisions (confirmed before the work)

| Decision | Why |
|---|---|
| Real logins locally: `pos_platform` for the platform database and one per customer database (`db_owner` of that database only), passwords in `stack.env` | The same shape as production, so T1-2 is proven with real per-customer credentials, not `sa` |
| `check-all` always runs `up.ps1` | It is idempotent and rebuilds the backend, so the smoke check tests the code being merged |
| Directory snapshot for 30 s (`APP_TENANCY_DIRECTORYCACHETTL`), `refresh()` for at once | A register or suspend reaches a running backend within 30 s without a database read per request |
| `register` defaults to `ACTIVE` | The operator registers a customer whose database exists; `PROVISIONING` belongs to T1-4 |
| Secret names follow Key Vault's rule; locally `SECRET_` + upper case, `-` as `_` | The stored names stay the same when Key Vault arrives |
| The platform pool is not a `DataSource` bean | A second `DataSource` bean would switch off Spring Boot's customer data source and its Flyway |

No place was found where the code contradicted the prompt.

## Commits

| Step | Commit | What |
|---|---|---|
| A | `d2e3361` | `check-all.ps1`, `.gitignore` (`devstack/.logs/`), a script test, the local stack doc |
| B1 | `98e3ef6` | `db/platform/V1__platform.sql`; `PlatformMigrationIntegrationTests` |
| B2 | `d9bb679` | `TenancyMode`, `TenancyProperties`, `PlatformDatabase`, `TenancyConfig`; `application.yaml`, `.env.example`; `TenancyModeIntegrationTests` |
| B3 | `59edad0` | `SecretResolver`, `SecretNames`, `MissingSecretException`, `EnvironmentSecretResolver`; its test |
| B4 | `d677210` | `HostNames`, `Customer`, `CustomerStatus`, `CustomerDirectory`; their tests |
| B5 | `badcc22` | `TenantRegisterCommand`; its tests; `NoConsolePrintingTest` allows it |
| B6 | `b8cd3fb` | The local stack: compose, `stack.env.example`, `_common.ps1`, `up`, `status`, `reset`, `new-customer`, script tests |
| B7 | this commit | `docs/2026-11-24-platform-database.md`, this report; the local stack doc, the logging doc, `.env.example` |

## Tests

| Test | Result |
|---|---|
| `PlatformMigrationIntegrationTests` (9): slug and host rules, one primary host, one customer per host and per database, no credentials in options, secret-name format, statuses, outcomes | Pass |
| `TenancyModeIntegrationTests` (4): single is the default with no platform database; multi migrates before the customer data source and loads the directory; incomplete or unsafe settings stop start-up, named without values; an unknown mode is refused | Pass |
| `HostNamesTest` (2), `CustomerDirectoryIntegrationTests` (5): case, port and trailing dot ignored; unknown and malformed hosts; suspended customer found with its status, not active; snapshot used for the TTL, then read again, and at once on `refresh()`; a customer without a database | Pass |
| `EnvironmentSecretResolverTest` (5): name to variable, the naming rule, a missing secret named without a value, bad names refused, nothing printed | Pass |
| `TenantRegisterCommandIntegrationTests` (9): migrate, register, idempotent re-register, update, clashes refused and recorded, suspend, activate and hosts, `show` with secret names only, bad input changes nothing, the password never printed | Pass |
| `devstack.tests.ps1` (through `DevstackScriptsTest`): the new settings checks (missing, weak, `sa` or the platform login as a customer login, bad tenancy mode), defaults, the login password never on a docker command line nor left in the environment, `check-all` with everything skipped | All cases pass |
| **Full suite** (`mvnw test`, 2026-11-24) | **1227 tests, 0 failures, 0 errors, 0 skipped** (1193 before T1-1, plus 34 new). Single mode, the default, passes every existing test |

Throwaway databases (`T07…Test`) only; none left behind. No `flyway:clean`, no applied migration edited.

**On the running stack** (2026-11-24):

| Check | Result |
|---|---|
| `up.ps1` | `pos_platform` created and migrated to version 1; the logins created; `beaconculinary` (full history) and `testcustomer` (from the baseline) registered `ACTIVE`; backend healthy |
| `status.ps1` | The "Platform database" section lists both customers with their hosts and databases; "All good." |
| Reset | After a reset: snapshot; then testcustomer suspended, a host added, smoke run; the compare reported every difference; `reset.ps1 -Force`; compare: beaconculinary, testcustomer and pos_platform all the same as the snapshot, both customers `ACTIVE` with one host |
| `smoke.ps1` | 7 of 7 passed (before and after the reset, and back in single mode) |
| `up.ps1 -TenancyMode multi` | Log: `tenancy multi: platform database at version 1 (0 migration(s) applied now)` and `tenancy multi: customer directory loaded, 2 customer(s), 2 active`; then back to single |
| Secrets | None of the five secret values (`PLATFORM_DB_PASSWORD`, both customer passwords, `SQL_PASSWORD`, `JWT_SECRET`) in any of the 34 script and backend logs scanned |
| `check-all.ps1` | **PASS, 9 of 9 steps, in 17 min:** backend 1227 tests, 0 failures, 0 errors, 0 skipped; frontend lint 0 errors and 0 warnings, 0 type errors, unit 1204 of 1204, build, e2e 2 passed; stack up (platform registration unchanged), status all good, smoke 7 of 7 |

## Open items

- The first `check-all` run (step A) had 34 frontend unit and 2 e2e failures from the frontend repository's work in
  progress at the time (T1-0d); the final run passed them all. This branch doesn't touch the frontend.
- T1-2: connect to each customer's database through the directory and `SecretResolver`; T1-3: pick the customer
  from the request's host and fill `tenantId` in the logs; T1-0b: Key Vault behind `SecretResolver`.

## Follow-up (after T1-1 was approved)

- The open test: `CustomerDirectoryIntegrationTests.whenAReadFails_theLastGoodSnapshotAnswers_withOneWarning_andTheNextGoodReadRecovers`.
- The frontend image (T1-0d in the frontend repository): `compose.yaml` no longer passes `NEXT_PUBLIC_AUTH_API_URL`
  and tags one image, `beacon-devstack/frontend`, for every customer; switching customer reuses it.
- `check-all.ps1`: a step `frontend check:build` right after the frontend build (ten steps), run with `-Quick` and
  `-SkipE2E` too.
- The local stack doc: the frontend container and host mode without an API address.
