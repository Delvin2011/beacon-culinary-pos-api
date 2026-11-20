# T0-4a customer settings in the database: change report

Branch `feature/t0-4a-site-settings`, from `t0-123-Merge` at `eed057a` (`main` doesn't contain O1–O4
or T0-1/2/3 yet, so it couldn't be the base). Not pushed or merged.

T0-4a moves 15 customer settings from YAML and environment variables into the customer's database.
An admin changes them from the app, and a change applies without a restart or redeploy. With nothing
saved, behaviour is exactly as before (readiness analysis, sections A.2, B.3, B.4 and 4).

- Contract (the frontend builds against it): `docs/2026-11-19-t0-4-site-settings-api-contract.md`
- How it works, what stays in configuration, how to add a setting: `docs/2026-11-20-site-settings.md`

## 1. Summary

| Area | Result |
|---|---|
| Settings moved | 15 (section 3). Entitlements and platform settings stay in configuration. |
| Storage | `site_settings` (key/value, typed by a registry in code) and an insert-only `site_setting_changes` (V113) |
| Reading | One service, `SiteSettings`: the saved value, else the configuration default. Cached 30 s per instance; dropped on this instance when a save commits. |
| Admin API | `GET /admin/settings`, `PUT /admin/settings` (partial, all or nothing), `POST /admin/settings/{key}/reset`, `GET /admin/settings/history`. ADMIN only; sensitive settings need a recent password sign-in. |
| Behaviour changes | The offline grant keeps the account values it was issued with; offline sales keep the VAT the till printed, checked against the rate in force at the sale; `app.business.name` is blank by default. Section 6. |
| Tests | Full suite: 1072 tests, 0 failures, 0 errors, 1 skipped (section 8). |

## 2. Commits

| Step | Commit | What |
|---|---|---|
| 1 | `e3a853e`, `7192315` | API contract (draft, then agreed) |
| 2 | `7ae0b80` | V113 migration |
| 3 | `be2f4b6` | Registry, defaults and their startup check, rules, store, cached `SiteSettings` |
| 4 | `56bf6a6` | Every consumer reads through `SiteSettings`; `DayCloseRules` reads the first trading day per check |
| 5 | `9280fa1` | Admin API, first-trading-day rules, `REAUTH_REQUIRED`, history, cache drop after commit |
| 6 | `60fc465` | Grant account values, offline VAT as printed, blank business name |
| 7 | `6c852cf` | Each setting through its consumer; VAT on new orders only; cache delay |
| 8 | see `git log` | Docs: this report, the site-settings guide, corrections to older docs |

## 3. Settings moved

| Key | Default | Sensitive |
|---|---|---|
| `vat.standard-rate` | 0.15 | yes |
| `reports.close-day.first-trading-day` | 2026-10-12 | yes |
| `dashboard.variance.threshold`, `voidRefund.watchPercent`, `slowMeal.thresholdPercent`, `slowMeal.afterTime`, `openingHours` | 50.00, 3.0, 25, 12:30, 06:00-15:00 | no |
| `board.done-display-minutes` | 10 | no |
| `pos.tills.require-registered` | false | yes |
| `pos.session.idle-lock-minutes`, `idle-expiry-minutes` | 5, 30 | no |
| `pos.offline.max-offline-hours`, `warn-offline-hours` | 6, 5 | no |
| `pos.offline.accounts-enabled`, `account-cap` | true, 150.00 | yes |

Decisions: key/value table with a typed registry rather than a single typed row, so a new setting
needs no migration; the five sensitive settings as agreed. **Not moved** (section 3 of the guide):
entitlements (`pos.tills.max-active`, `pos.offline.enabled`, `dashboard.features.*`) and platform
settings (secrets, keys, CORS, login protection, device trust, `site.*`, offline grant timings, job
switches, `app.business.name`).

## 4. Migration `V113__t0_4a_site_settings.sql`

1. `site_settings`: `setting_key` (PK), `value` (stored text; NULL only for an empty nullable
   setting), `updated_at`, `updated_by`, `updated_by_name`. Nothing seeded.
2. `site_setting_changes`: `id`, `setting_key`, `action` (`SET`/`RESET`), `old_value`,
   `old_is_default`, `new_value`, `new_is_default`, `changed_at`, `changed_by`, `changed_by_name`;
   index on (`setting_key`, `changed_at` DESC, `id` DESC); trigger
   `trg_site_setting_changes_insert_only` refuses UPDATE and DELETE.
3. `offline_grants.accounts_enabled` (BIT NULL) and `account_cap` (DECIMAL(10,2) NULL).

User columns have no foreign key (users are hard-deleted), as in V105/V108. Applied to the test
database only; no applied migration was edited.

## 5. Rules

- **Single values:** VAT 0 to 0.9999 with at most 4 decimals; money ≥ 0 with 2 decimals; percents 0
  to 100; minutes 0/1 to 1440; `HH:mm` times; opening hours start before end.
- **Cross-field:** idle expiry ≥ idle lock; warn hours < max hours ≤ `grant-max-hours` (platform).
  A broken rule names both settings in the 400.
- **First trading day:** not after the earliest Z; not before the earliest order (nor empty once
  there are orders); and, once a Z exists, not so early that a day with shifts but no Z falls
  before the first Z (it would block every later Close Day). Checked on PUT and on reset.
- **All or nothing:** one bad key and nothing is saved; every bad key is listed.
- **Order of checks:** 400 before 403.

## 6. Behaviour changes

| Change | Before | After |
|---|---|---|
| Offline account cap at sync | Checked against the current setting | Checked against the cap stored on the grant when it was issued. A grant issued with accounts off: an ACCOUNT payment is `ACCOUNT_OVER_CAP` with `cap: 0`. Grants from before V113: the current setting, as before. |
| VAT on synced offline sales | The server's rate at sync was stored; a different printed value was flagged | The VAT the till printed (the customer's receipt) is stored when it can be (rate 0–0.9999 with ≤ 4 decimals; amount ≥ 0, ≤ 2 decimals, ≤ total), else the server's. `VAT_MISMATCH` compares with the rate in force at `soldAt`, from the settings history. Its detail keys are unchanged; `serverRate`/`serverVat` now mean "at `soldAt`". |
| Business name on statement reports | Receipt settings' name, else `app.business.name` (default "Beacon Culinary") | Same, but `app.business.name` is blank by default. With both blank, `meta.business` and the "Business" header item are left out. |
| First trading day in Close Day | `DayCloseRules` kept the startup value | Read on each check (needed for a saved value to apply) |

Unchanged with nothing saved: every other output. The T0-2 golden outputs are identical (the golden
test sets `app.business.name=Beacon Culinary`, the name they were recorded with).

## 7. Files

**Created**

- `docs/2026-11-19-t0-4-site-settings-api-contract.md`, `docs/2026-11-20-site-settings.md`, this report
- `src/main/resources/db/migration/V113__t0_4a_site_settings.sql`
- `src/main/java/com/beaconculinary/api/settings/`: `SettingType`, `TimeRange`, `SiteSetting`,
  `SiteSettingDefaults`, `SiteSettingRules`, `FirstTradingDayRule`, `SiteSettings`,
  `SiteSettingsStore`, `SiteSettingsAdminService`, `SiteSettingsController`, `SiteSettingsDtos`,
  `SiteSettingsChangedEvent`, `SiteSettingsValidationException`, `UnknownSettingException`,
  `SiteSettingsExceptionHandler`
- Tests: `settings/SiteSettingRegistryTest`, `SiteSettingsCacheTest`, `FirstTradingDayRuleTest`,
  `SiteSettingsStoreIntegrationTests`, `SiteSettingsAdminIntegrationTests`,
  `SiteSettingsEffectsIntegrationTests`, `SiteSettingsFixture`;
  `reports/T04aBlankBusinessNameIntegrationTests`

**Modified (main code)**

- `board/PublicBoardSettings`, `dashboard/DashboardSettings`, `endofday/CloseDaySettings`,
  `inventory/VatSettings`: same methods, values from `SiteSettings`
- `endofday/DayCloseRules`: first trading day per check
- `tills/CurrentTill`, `tills/TillStatusService`, `devices/DeviceGate`, `devices/DeviceService`,
  `sessions/SessionService`: till and session settings from `SiteSettings`
- `offline/OfflineGrantService`, `offline/OfflineGrantStore`, `offline/OfflineSnapshotService`,
  `offline/sync/OfflineSaleRecorder`: offline settings from `SiteSettings`; grant account values;
  offline VAT
- `dashboard/DashboardCache`: cleared on `SiteSettingsChangedEvent`
- `receipts/BusinessName`, `reports/EndOfDayStatements`, `reports/ReportService`: blank business name
- `application.yaml`: `app.business.name: ${APP_BUSINESS_NAME:}`

**Modified (tests)**

- `board/PublicBoardSettingsTest`, `dashboard/DashboardServiceTest`: built from `SiteSettingsFixture`
- `locale/GoldenOutputIntegrationTests`, `reports/EndOfDayIntegrationTests`,
  `reports/O2bReportBusinessNameIntegrationTests`: `app.business.name=Beacon Culinary`
- `offline/O4OfflineSyncIntegrationTests`: VAT kept as printed (one test rewritten by design), the
  rate at `soldAt`, the grant's account values; clears settings and history around each test

**Modified (docs, step 8)**: the O2, O2b, O3 and O4 contracts, the go-live checklist and the README
(notes pointing to the site settings).

## 8. Tests

| Test | What it covers |
|---|---|
| `SiteSettingRegistryTest` (unit) | Registry, types, limits and messages, cross-field rules, defaults and their startup check |
| `SiteSettingsCacheTest` (unit) | Saved over default; 30 s cache; invalidation; a backwards clock; bad saved values ignored |
| `FirstTradingDayRuleTest` (unit) | The three rules |
| `SiteSettingsStoreIntegrationTests` | V113 tables, insert-only trigger, last change per key, paging, value at a time, the grant columns |
| `SiteSettingsAdminIntegrationTests` | The four endpoints: defaults, ADMIN only, all or nothing, every validation message, cross-field rules, `REAUTH_REQUIRED` (PUT and reset), history, dashboard cache cleared, first-trading-day rules on real data |
| `SiteSettingsEffectsIntegrationTests` | Each setting through its consumer: default, saved without a restart, reset. VAT on new orders only and in the offline snapshot. Another instance's save seen after 30 s, not before. |
| `O4OfflineSyncIntegrationTests` (added) | Printed VAT kept; rate at `soldAt`; unstorable values; grant cap; accounts off; pre-V113 grant |
| `T04aBlankBusinessNameIntegrationTests` | No business name anywhere: none printed |
| `GoldenOutputIntegrationTests` | T0-2 golden outputs unchanged with nothing saved |

**Full suite:** 1072 tests, 0 failures, 0 errors, 1 skipped. The skip is
`StaffManagementIntegrationTests`' assumption that the seeded admin is the only active admin; the
test database holds another active admin, most likely left by the interrupted run below.

**Test database incident.** One full run was stopped by Claude Code for low system memory. The test
it was in never cleaned up, so the next run failed in 107 tests across unrelated areas (left-over
menu rows, an open shift, missing complimentary rows). That run's own clean-up repaired the
database; the following run passed. If a run is interrupted again, run it twice, or add a reset of
the test database at the start of the suite.

## 9. Risks and follow-ups

- **30 s across instances:** another instance may use an old value for up to 30 s, including the
  VAT rate on an online order.
- **Scheduled VAT changes:** not supported; a change applies when saved.
- **History matters:** offline VAT checks read `site_setting_changes`. It is insert-only in
  production; never edit it.
- **Older docs:** the settings tables in the O2 and O3 contracts now describe the defaults; notes
  point to the site settings.
- **Onboarding:** the business name must be set in the receipt settings (go-live checklist).
- **Merging into `main`:** this branch depends on `t0-123-Merge`.
