# T1-7 change report: each customer's own details and links

Branch `feature/t1-7-customer-details`, from `feature/infrastructureUpdatesBE` (`cd66752`). Not pushed or merged.
Design and contract: docs/2026-11-25-customer-from-address.md section 11.

## 1. What changed

- **`GET /api/public/branding`** (backend `/public/branding`), no sign-in: 200 `{name, logoUrl: null}` with
  `Cache-Control: public, max-age=300`. Suspended, unavailable and unknown addresses get T1-3's answers.
- **The business name** (`BusinessName`, also printed on the X, Z and Cashier readings): the customer's receipt
  settings' `businessName`, else its name in the customer directory (`tenants.display_name`), else none (`""` on the
  endpoint). `app.business.name` (`APP_BUSINESS_NAME`) is removed.
- **Invite links** are `https://<the customer's primary host>/invite#t=<token>`, from the customer directory.
  `app.public-base-url` (`APP_PUBLIC_BASE_URL`) is removed; `FirstAdminCommand` takes `--base-url` only.
- **No customer name in `src/main`:** `spring.application.name` is `pos-api`; request attributes `pos.tenantId` and
  `pos.tenantMismatch`; application locks `pos-first-admin` and `pos-platform-register`; Javadoc examples use `acme`.
  `NoBeaconInMainTest` keeps it that way.

## 2. Audit

| # | Where | What | Done |
|---|---|---|---|
| A1 | 701 files | Package `com.beaconculinary` (and `COM_BEACONCULINARY` in logging-level variables) | Kept (D1); open item |
| A2 | `BeaconCulinaryApiApplication` | Main class | Kept (D1); open item |
| A3 | `application.yaml:3` | `spring.application.name: beacon-culinary-api` | `pos-api` |
| A4 | `application.yaml:39,43` | Logging key `com.beaconculinary` and its env example | Kept (package, A1) |
| A5 | `RequestContextFilter:47`, `TenantBinding:29` | Request attributes `beacon.tenantId`, `beacon.tenantMismatch` | `pos.*` |
| A6 | `FirstAdminCommand:171,180`, `TenantRegisterCommand:64`, platform doc | Application locks `beacon-first-admin`, `beacon-platform-register` | `pos-*` |
| A7 | `TenantRegisterCommand:34-38`, `HostNames:21`, `SecretNames:10,14,39`, `SecretResolver:4` | Javadoc examples | `acme` |
| A8 | `application.yaml`, compose, `_common.ps1`, `.env.example` | `app.public-base-url` (`APP_PUBLIC_BASE_URL`): one address for every customer | Removed; links on the primary host |
| A9 | `FirstAdminCommand` | Fell back to `APP_PUBLIC_BASE_URL` | Removed; `--base-url` only |
| A10 | `application.yaml`, `.env.example` | `app.business.name` (`APP_BUSINESS_NAME`): a platform-wide name | Removed (D3) |
| A11 | V13, V14, V32, V34, V116 | Demo data (`@canteen.local`) in applied migrations | Unchanged (never edited; the guard skips `db/migration`) |
| A12 | `OrderController`, `ShiftController` | `Location` headers | Unchanged: built from the request, so already on the customer's address |
| - | - | Emails, templates, printed receipts | None in the backend (the frontend prints receipts from the receipt settings) |

The business name already had a home in each customer's database (`receipt_settings.business_name`), so no setting
was added.

## 3. Decisions

| | Decision |
|---|---|
| D1 | The guard ignores only the package `com.beaconculinary`, `COM_BEACONCULINARY` and `BeaconCulinaryApiApplication`; everything else renamed. The package rename is an open item. |
| D2 | The branding name falls back to the customer directory's `display_name`, else `""`. |
| D3 | `app.business.name` removed; `BusinessName` uses the same order as D2, so reports and branding agree. |
| - | Single mode: no customer directory, so the link is `null` (the frontend builds it, as before) and the name is the receipt settings' or `""`. No single-mode-only code. |
| - | `Cache-Control` is set by the controller as `public, max-age=300` exactly (Spring Security leaves a set header alone). |
| - | The guard also skips the applied migrations (`src/main/resources/db/migration`): they hold the demo data and are never edited. None contains "beacon" today. |

## 4. Behaviour changes

- Multi mode: a customer with no receipt name now has its directory name on statement reports, instead of none.
- Invite links from the staff screen (multi mode) are on the customer's primary host instead of `null`.
- `APP_PUBLIC_BASE_URL` and `APP_BUSINESS_NAME` have no effect; `FirstAdminCommand` no longer reads
  `APP_PUBLIC_BASE_URL`.
- Log lines show `[pos-api]`; the two application locks have new names (only matters if an old and a new version of
  the same command run at the same moment).

## 5. Commits

| Step | Commit | What |
|---|---|---|
| 1 | `f2e0423` | Invite links on the primary host; `app.public-base-url` removed; `FirstAdminCommand --base-url` only |
| 2 | `126ccb3` | `/public/branding`; `BusinessName` order (D2); `app.business.name` removed (D3) |
| 3 | `9c605e9` | No customer name in `src/main`; `NoBeaconInMainTest` |
| 3 fix | `552b27d` | `InviteLinks`' Javadoc no longer names `FirstAdminCommand` (`NoConsolePrintingTest`) |
| 4 | this commit | `smoke.ps1` checks the branding at both customers; docs; this report |

## 6. Lines added and removed (steps 1-3)

| | Added | Removed |
|---|---|---|
| Main code (`src/main`) | 126 | 62 |
| Tests (`src/test`) | 267 | 39 |
| Docs | 1 | 1 |
| Devstack and `.env.example` | 5 | 14 |

Step 4 adds no main code or tests: see its own commit for the script and doc lines.

## 7. Files

Main: `auth/invite/InviteLinks`, `auth/invite/InviteAdminDtos` (comment), `ops/FirstAdminCommand`,
`ops/TenantRegisterCommand`, `receipts/BusinessName`, `receipts/PublicBrandingController` (new),
`receipts/PublicBrandingSecurityRules` (new), `auth/TenantBinding`, `common/logging/RequestContextFilter`,
`tenancy/HostNames`, `tenancy/secrets/SecretNames`, `tenancy/secrets/SecretResolver`, `application.yaml`.

Tests: `tenancy/web/CustomerBrandingIntegrationTests` (new), `tenancy/web/CustomerLinksIntegrationTests` (new),
`receipts/PublicBrandingIntegrationTests` (new), `common/NoBeaconInMainTest` (new), `auth/invite/InviteTokensTest`,
`auth/invite/InviteIntegrationTests`, `ops/FirstAdminCommandIntegrationTests`, `ops/BaselineSmokeIntegrationTests`,
`locale/GoldenOutputIntegrationTests`, `reports/EndOfDayIntegrationTests`, `reports/O2bReportBusinessNameIntegrationTests`,
`reports/T04aBlankBusinessNameIntegrationTests`.

Devstack and config: `devstack/compose.yaml`, `devstack/scripts/_common.ps1`, `devstack/scripts/smoke.ps1`,
`.env.example`.

Docs: `2026-11-25-customer-from-address.md` (section 11, section 9, tests), `2026-11-20-site-settings.md`,
`2026-11-21-first-admin-and-invites.md`, `2026-11-21-t0-6-invites-api-contract.md`, `2026-11-11-go-live-checklist.md`,
`2026-11-17-o2b-receipt-data-api-contract.md`, `2026-11-24-platform-database.md`, this report. Earlier change reports
are left as they were.

## 8. Tests

| Test | Proves |
|---|---|
| `CustomerBrandingIntegrationTests` (2) | alpha's name from its receipt settings, bravo's from the customer directory; exact JSON, `logoUrl` null, the cache header, no sign-in; SUSPENDED 503 `CUSTOMER_SUSPENDED`, MIGRATION_FAILED 503 `CUSTOMER_UNAVAILABLE`, unknown 404 empty |
| `CustomerLinksIntegrationTests` (1) | Invites issued at alpha and bravo link to `https://alpha.pos.localhost` and `https://bravo.pos.localhost` |
| `PublicBrandingIntegrationTests` (1, single mode) | The receipt settings' name, else `""`; the cache header |
| `NoBeaconInMainTest` (2) | No "beacon" (any case) in `src/main` outside the D1 exceptions; the guard itself |
| `smoke.ps1` | `/api/public/branding` at beaconculinary ("Beacon Culinary") and testcustomer ("Test Customer") through the gateway |

`check-all.ps1` runs on the commit that adds this report; its result and hash are in the final report.

## 9. Open items

- **Package and main class rename** (`com.beaconculinary`, `BeaconCulinaryApiApplication`), and the Maven artifact
  `beacon-culinary-api` (the jar name in the Dockerfile, devstack scripts and docs). A separate change.
- **Logo:** `logoUrl` is always `null` until a logo upload exists.
- **Local stack on a port other than 443:** the primary host has no port, so invite links lack it.
