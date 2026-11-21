# Site settings (T0-4a)

Branch `feature/t0-4a-site-settings`. Prepares for multi-tenancy (readiness analysis of 19 Nov 2026,
sections A.2, B.3, B.4 and 4): the customer's own settings live in the customer's database, an
admin changes them from the app, and a change applies without a restart or redeploy.

- API: `docs/2026-11-19-t0-4-site-settings-api-contract.md` (endpoints, errors, examples, effects).
- What changed and how it was tested: `docs/2026-11-20-t0-4a-site-settings-change-report.md`.

## 1. Three kinds of setting

| Kind | Who decides | Where it lives | Changed by | Examples |
|---|---|---|---|---|
| **Customer setting** (site setting) | The customer | The customer's database (`site_settings`), default from configuration | An admin, in the app (`/admin/settings`) | VAT rate, go-live day, idle times, offline limits, dashboard thresholds |
| **Entitlement** | What the customer pays for (plan) | Configuration today; the platform database later | Us, per customer | Most active tills, offline selling, dashboard sections |
| **Platform setting** | Us, for every customer | Configuration (`application.yaml`, environment) | Us, by redeploying | Secrets and keys, CORS, login protection, device trust, time zone and currency |

Rule of thumb: if two customers could reasonably want different values and getting it wrong only
affects that customer, it's a customer setting. If it's something they pay for, it's an
entitlement. If it protects the server, carries a secret, or must be the same everywhere, it's a
platform setting.

A customer setting may be **bounded** by an entitlement or a platform setting: the offline limits
only matter while offline selling (an entitlement) is on, and `max-offline-hours` can't exceed
`pos.offline.grant-max-hours` (platform).

## 2. The customer settings

Each key is its configuration property name. With nothing saved, the configuration value is used,
so a fresh database behaves exactly as before T0-4a.

| Key | Default | Default from (env) | Group | Sensitive | Takes effect |
|---|---|---|---|---|---|
| `vat.standard-rate` | 0.15 | yaml only | VAT | yes | Next order, next GRV line, next offline snapshot |
| `reports.close-day.first-trading-day` | 2026-10-12 | `CLOSE_DAY_FIRST_TRADING_DAY` | End of day | yes | Next Close Day, sign-off, report, dashboard request |
| `dashboard.variance.threshold` | 50.00 | yaml only | Dashboard | no | Next dashboard request |
| `dashboard.voidRefund.watchPercent` | 3.0 | yaml only | Dashboard | no | Next dashboard request |
| `dashboard.slowMeal.thresholdPercent` | 25 | yaml only | Dashboard | no | Next dashboard request |
| `dashboard.slowMeal.afterTime` | 12:30 | yaml only | Dashboard | no | Next dashboard request |
| `dashboard.openingHours` | 06:00-15:00 | yaml only | Dashboard | no | Next dashboard request |
| `board.done-display-minutes` | 10 | `BOARD_DONE_DISPLAY_MINUTES` | Board | no | Next board fetch or stream event |
| `pos.tills.require-registered` | false | `POS_TILLS_REQUIREREGISTERED` | Tills | yes | Next PIN sign-in, shift open, sale or adjustment |
| `pos.session.idle-lock-minutes` | 5 | `POS_SESSION_IDLELOCKMINUTES` | Sessions | no | When the till next reads `GET /pos/till-status` |
| `pos.session.idle-expiry-minutes` | 30 | `POS_SESSION_IDLEEXPIRYMINUTES` | Sessions | no | Next POS request; the minute sweep |
| `pos.offline.max-offline-hours` | 6 | `POS_OFFLINE_MAXOFFLINEHOURS` | Offline | no | Next grant, snapshot, till status |
| `pos.offline.warn-offline-hours` | 5 | `POS_OFFLINE_WARNOFFLINEHOURS` | Offline | no | Next grant, snapshot, till status |
| `pos.offline.accounts-enabled` | true | `POS_OFFLINE_ACCOUNTSENABLED` | Offline | yes | Next grant and snapshot (stored on the grant) |
| `pos.offline.account-cap` | 150.00 | `POS_OFFLINE_ACCOUNTCAP` | Offline | yes | Next grant and snapshot (stored on the grant) |
| `auth.invites.expiry-hours` (T0-6a) | 72 | `AUTH_INVITES_EXPIRYHOURS` | Security | yes | Next invite link issued |
| `stock.locations.max-active` (T0-5c) | 10 | yaml only | Stock | no | Next location create or activate |

"yaml only": `application.yaml` has no environment placeholder; Spring's relaxed binding still
accepts one (e.g. `VAT_STANDARDRATE`). Limits and cross-field rules: contract section 4.
**Sensitive** settings need a password sign-in within the last 30 minutes (403 `REAUTH_REQUIRED`),
as for till management.

"This server" applies a change at once; other app instances within 30 seconds (section 4).

## 3. What stays in configuration, and why

| Setting | Kind | Why it isn't a customer setting |
|---|---|---|
| `pos.tills.max-active` | Entitlement | Plan limit |
| `pos.offline.enabled` | Entitlement | Plan feature (offline selling) |
| `dashboard.features.*` | Entitlement | Feature gating per stage |
| `spring.jwt.*`, `pos.offline.grant-signing-key` / `grant-key-id` / previous key | Platform | Secrets and keys |
| `pos.offline.grant-max-hours`, `status-stale-minutes` | Platform | Offline protocol; caps `max-offline-hours` |
| Datasource, Flyway, `server.port`, springdoc | Platform | Infrastructure |
| `cors.allowed-origins`, `cookie.secure` | Platform | Security posture of the deployment |
| `security.login-protection.*` | Platform | Infrastructure (trusted proxies, retention) |
| `security.device-trust.*` | Platform | Security posture; `mode` may become a customer setting later (analysis B.3) |
| `site.time-zone`, `site.currency-code`, `site.currency-symbol` | Platform | Locale (T0-2); the SQL report views assume SAST, so the zone can't vary per customer yet |
| `pos.require-client-order-id`, `pos.session.sweep-enabled`, job switches | Platform | Rollout and job switches |
| `reports.export.max-rows`, `dashboard.cache.enabled` | Platform | Protect the server |
| `app.public-base-url` | Platform | The frontend address invite links point at (T0-6a; env `APP_PUBLIC_BASE_URL`); per customer in T1 |
| `security.demo-accounts.block-sign-in` | Platform | Refuses the seeded demo accounts; always on under the prod profile (T0-6a) |
| `app.business.name` | Platform | Fallback only. **Blank by default** (env `APP_BUSINESS_NAME`), so one customer's name is never printed on another's reports. The customer's name is the receipt settings' `businessName`; with both blank, statement reports print no business name. **Onboarding must set the business name in the receipt settings.** |

Hard-coded customer values (meal periods, the `PM` account prefix, location names) are out of scope
here; see analysis A.1.

## 4. How a value is read

Code: `src/main/java/com/beaconculinary/api/settings/`.

| Class | Role |
|---|---|
| `SiteSetting` | The registry: key, type, group, description, sensitive, limits |
| `SiteSettingDefaults` | Each default from configuration; checks them at startup |
| `SiteSettingRules` | Single-value limits and cross-field rules (pure) |
| `FirstTradingDayRule` | The first-trading-day rules, which need the database |
| `SiteSettings` | **What every consumer reads**: typed getters (`vatStandardRate()`, `tillsRequireRegistered()`, …) |
| `SiteSettingsStore` | SQL for `site_settings` and `site_setting_changes` |
| `SiteSettingsAdminService`, `SiteSettingsController` | The admin API |

- **Value:** the saved value, else the default.
- **Cache:** the saved values are read in one query and kept for 30 s (application clock). A save
  drops this instance's cache when its transaction commits (`SiteSettingsChangedEvent`); other
  instances see it within 30 s. The dashboard's section cache is cleared on the same event.
- **Bad saved values:** a saved value that can't be read, is out of its limits (e.g. grant-max-hours
  was lowered below a saved max-offline-hours) or breaks a cross-field rule is ignored with an
  ERROR log line, and the default is used. The app keeps running.
- **Startup:** an invalid default stops the app with a message naming the property, e.g.
  `vat.standard-rate '1.5' is not valid: Must be between 0 and 0.9999 with at most 4 decimals.`
  The bean validation on `TillProperties` and `OfflineProperties` still runs too.
- **History:** every change (SET, RESET) is recorded in `site_setting_changes` with the effective
  value before and after, who and when. A trigger refuses UPDATE and DELETE.
- **Value at a time:** `SiteSettings.valueAt(setting, instant)` reads the history. Offline sales use
  it to check the printed VAT against the rate in force when the sale was made.

Consumers (each reads through `SiteSettings`, directly or via a facade that keeps its old methods):

| Setting | Read by |
|---|---|
| VAT rate | `VatSettings` (orders, GRV, offline snapshot, `GET /admin/settings/vat`); `OfflineSaleRecorder` (rate at `soldAt`) |
| First trading day | `CloseDaySettings` (sign-off, reports, dashboard, `GET /admin/settings/close-day`); `DayCloseRules` (per check) |
| Dashboard thresholds, hours | `DashboardSettings` (every section) |
| Board minutes | `PublicBoardSettings` |
| Require registered | `CurrentTill`, `DeviceGate`, `DeviceService` (device config), `TillStatusService` |
| Idle lock | `TillStatusService` |
| Idle expiry | `SessionService` (requests and the sweep) |
| Offline hours | `OfflineGrantService`, `OfflineSnapshotService`, `TillStatusService` |
| Offline accounts, cap | `OfflineGrantService` (stored on the grant), `OfflineSnapshotService`, `OfflineSaleRecorder` (pre-V113 grants only) |

## 5. How to add a customer setting

1. **Decide the kind** (section 1). Only a customer setting goes here.
2. **Configuration default:** add the property to `application.yaml`, with an environment
   placeholder if operators may need to change the default.
3. **Registry:** add an entry to `SiteSetting` (key = the property name, type, group, sensitive,
   description, limits). Sensitive if a wrong value costs money, breaks the end of day, or weakens
   security.
4. **Default:** read the property in `SiteSettingDefaults` (an `@Value` parameter, or a getter on a
   `@ConfigurationProperties` class read on each call).
5. **Rules:** a cross-field rule goes in `SiteSettingRules.crossRules`, naming every setting it
   involves; a rule that needs the database gets its own class, like `FirstTradingDayRule`.
6. **Getter:** add a typed getter to `SiteSettings` and use it in the consumer. Never keep the value
   in a field: read it on each use, or a change won't apply until a restart.
7. **Caches:** if a consumer caches results computed from the setting, clear them on
   `SiteSettingsChangedEvent` (see `DashboardCache`).
8. **Tests:** `SiteSettingRegistryTest` (count, sensitive list, limits) and a default/saved/reset
   test through the consumer in `SiteSettingsEffectsIntegrationTests`.
9. **Docs:** a row in the contract's section 2 and effects table, and in section 2 of this file.

No migration is needed: a new key simply has no row until someone saves it.

## 6. Operations

- **What's saved:**
  `SELECT setting_key, value, updated_at, updated_by_name FROM site_settings;`
  No row = the default.
- **Who changed what:** `GET /admin/settings/history`, or
  `SELECT * FROM site_setting_changes ORDER BY changed_at DESC, id DESC;`
- **Changing a default** (environment variable or yaml) affects only customers who haven't saved a
  value for it. GET `/admin/settings` shows both the value and the default.
- **Never edit or delete history rows.** The trigger refuses it, and offline sync reads the VAT
  history. Only the test suite lifts the trigger, on the test database.
- **Onboarding a customer:** set the business name in the receipt settings; set the first trading
  day (go-live day) before the first Z; review the VAT rate and the offline limits.

## 7. Known limits and follow-ups

- **Scheduled changes:** a VAT change applies when saved. There is no "from midnight on 1 April"; an
  admin saves it at the time. Another instance may use the old rate for up to 30 s.
- **Per database:** `site_settings` has no tenant column; with one database per customer it needs
  none. Merging databases would need one (as for `receipt_settings`).
- **Device-trust mode** and the login-tile switches are candidates for later customer settings.
- **Time zone and currency** stay platform settings until the report views can take a zone
  (site-locale doc, section 5).
