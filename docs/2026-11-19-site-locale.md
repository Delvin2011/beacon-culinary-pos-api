# Site locale (T0-2)

Branch `feature/t0-2-site-locale`. Prepares for multi-tenancy (readiness analysis of 19 Nov 2026,
section A.2): every customer is in South Africa for year 1, but the business time zone, the
currency and the money format now come from one service, `SiteLocale`, so they can later become
per-tenant settings. Behaviour is unchanged: golden outputs recorded before the change are
identical after it.

## 1. Settings

| Setting | Env var | Default | Checked at startup |
|---|---|---|---|
| `site.time-zone` | `SITE_TIME_ZONE` | `Africa/Johannesburg` | A valid IANA zone id, **and** one that maps to South Africa Standard Time (section 4) |
| `site.currency-code` | `SITE_CURRENCY_CODE` | `ZAR` | Three upper-case letters and a real ISO 4217 code |
| `site.currency-symbol` | `SITE_CURRENCY_SYMBOL` | `R` | Not blank |

Values are trimmed. A bad value stops the app with a message naming the setting, for example
`site.currency-code 'XYZ' is not an ISO 4217 currency code.`

Code: `common/locale/SiteLocaleProperties` (binding), `SiteLocale` (the service),
`SiteLocaleConfig` (the bean, which also runs the guard), `SastViewGuard`.

## 2. What SiteLocale provides

| Method | Use |
|---|---|
| `zone()` | The business `ZoneId` |
| `currencyCode()` | `ZAR` |
| `currencySymbol()` | `R` |
| `businessDate(Instant)` | The trading day / business date of an instant ("today" is `businessDate(clock.instant())`) |
| `localDateTime(Instant)` | A local date-time in the business zone |
| `startOfDay(LocalDate)` | The instant a business day starts |
| `formatMoney(BigDecimal)` | Money text: `R 1 240,00`, `-R 10,00`, null as `R 0,00` (2 decimals half-up, space thousands, comma decimals; only the symbol is a setting) |

`ReportRange.startOfDayUtc(day, site)` turns a business day into the stored-UTC `Timestamp` the
SQL queries filter on.

## 3. Where each setting is used

**`site.time-zone`**

- The `Clock` bean (`ClockConfig`): `Clock.system(siteLocale.zone())`.
- The login-attempt retention job's cron zone (`LoginAttemptRetentionJob`):
  `zone = "${site.time-zone:Africa/Johannesburg}"`. An annotation needs a constant, so this is a
  property placeholder; its default must stay equal to the property default.
- Trading days and day windows: `TradingCalendar`, `ReportRange` and the `DashboardFacts`,
  `ReportFacts`, `EndOfDayFacts`, `ItemSalesFacts` and `AccountFacts` range parameters;
  `ReportParamParser` (default dates), `DayCloseRules` (today, and the offline till's "last
  report HH:mm"), `ShiftService` (the day a shift opens on), `ShiftSignoffService`,
  `ShiftAdminService`, `ShiftActivityService` (oversight), `OfflineSaleRecorder` (sale day and
  sale time of an offline sale), `GrvService` (GRV edit window).
- Report definitions: `AccountBalancesReport`, `AccountStatementReport` (the opening row),
  `CashierReadingReport` (shift options and their labels), `CashUpVarianceReport`.
- Report metadata (`ReportService`: `meta.timeZone`) and the dashboard layout (`timeZone`).
- Times shown or exported: dashboard sections (`DashboardFormat.local/time` take the zone),
  `ReportExporter` ("Generated: 2026-10-06 13:00 (Africa/Johannesburg)" and xlsx date-time cells).

**`site.currency-code`**: report metadata (`ReportService`: `meta.currency`).

**`site.currency-symbol`** (through `formatMoney`): the dashboard's money text, i.e. the attention
section's cash-variance item ("Cash variance of -R 10,00 … R 50,00 or more") and the today
section's complimentary tile ("R 45,00 at list price").

**Not changed:**

- The English date formats (`Locale.ENGLISH`) stay, as the UI is English: `DashboardFormat:24-25,96,101`,
  `HeatmapSection:83-84`, `CloseDaySettings:18`, `CashierReadingReport:30-31`,
  `ZReadingReport:43`, `EndOfDayStatements:417`.
- `OrderService:325` "The discount works out to R0.00." is money text in a different style (no
  space, decimal point). Changing it would change an API error message, so it is left; it should
  use `formatMoney` when error texts are next revised.
- Two comments still say "Africa/Johannesburg calendar day" (`AdminShiftController:21`,
  `ShiftDisplayStatus:3`).

## 4. The SQL report views assume SAST, and the startup guard

The report views convert stored UTC times with
`AT TIME ZONE 'UTC') AT TIME ZONE 'South Africa Standard Time'`. Their current definitions:

| View | Column | Defined in |
|---|---|---|
| `v_shift_facts` | `trading_day` (every report's day, via the shift) | V85 |
| `v_order_facts` | `sale_hour` | V111 (earlier V85, V87, V92) |
| `v_complimentary_facts` | `sale_hour` | V92 |
| `v_account_movements` | `entry_day` of a PAYMENT (charges and credits take `v_shift_facts.trading_day`) | V91 |

These are not changed in this slice (applied migrations are never edited). So that the app and
the views can never disagree, `SastViewGuard` refuses to start unless `site.time-zone` is one of
the IANA zones that the Unicode CLDR maps to Windows "South Africa Standard Time" (UTC+2 all
year, no daylight saving):

`Africa/Johannesburg`, `Africa/Maseru`, `Africa/Mbabane`, `Africa/Gaborone`, `Africa/Harare`,
`Africa/Lusaka`, `Africa/Maputo`, `Africa/Blantyre`, `Africa/Kigali`, `Africa/Bujumbura`,
`Africa/Lubumbashi`, `Etc/GMT-2`.

Anything else, for example `Europe/London`, stops the app with:

> site.time-zone 'Europe/London' is not supported yet: the SQL report views (V85, V87, V91, V92,
> V111) assume South Africa Standard Time (UTC+2) for the trading day and sale hour, so reports
> would disagree with the app. Use a zone that maps to South Africa Standard Time, such as
> Africa/Johannesburg. See docs/2026-11-19-site-locale.md.

## 5. What a per-customer time zone would need in the views

1. **A Windows zone name per tenant.** SQL Server's `AT TIME ZONE` takes Windows names
   (`sys.time_zone_info`), not IANA ids. A tenant setting would hold the IANA id for Java and the
   Windows name for SQL (or a CLDR mapping table). The guard would then check that the pair
   agrees.
2. **The views can't take a parameter.** Options, in rough order of preference:
   - *Inline table-valued functions* `f_shift_facts(@tz)` etc. replacing the views, called with
     the tenant's Windows zone. Closest to today's SQL; every `FROM v_…` in the Facts classes
     changes to `FROM f_…(:tz)`.
   - *Store the local values.* Write `trading_day` (and the local sale hour) onto `shifts` /
     `orders` when the row is created, from Java with the tenant's zone. The views then read
     columns, need no zone, and stay indexable. Needs a backfill migration for existing rows.
   - *A view per tenant* (separate schema or database per tenant) generated from a template with
     the zone substituted. Fits a database-per-tenant design only.
3. **Indexes.** `trading_day` is computed per row with `AT TIME ZONE`; with a zone parameter it
   stays non-sargable. Range filters already use the UTC window on `opened_at` (see
   `ReportRange`), which keeps working. Grouping by day/hour would benefit from stored local
   columns (the second option).
4. **Close Day, Z numbering and offline `booked_trading_day`** already take the day from Java;
   they move with `SiteLocale` and need no SQL change.

## 6. Clock usage (task 3)

**Fixed:**

- `OfflineSaleRecorder` stamped the offline sequence's "last seen" with `Instant.now()` (two
  places); it now uses the `Clock` (`UtcTimestamps.nowUtc(clock)`).
- `GrvService`'s "received today" check took the zone from `clock.getZone()` and today from
  `LocalDate.now(clock)`; both sides now come from `SiteLocale` (identical in production).

**Left, with the reason:**

| Where | Why it stays |
|---|---|
| `JwtService:41-42` `new Date()`, `System.currentTimeMillis()` (issued-at, expiry) | JJWT checks `exp` against its own system clock when parsing. Moving issuing to a frozen test clock would make tokens expired or not yet valid; doing it properly means giving the parser the Clock too, a security-sensitive change for its own slice. Times are UTC instants, so not a locale issue. |
| `Jwt:20` `new Date()` (expiry check) | Same reason; `Jwt` is a value object with no access to beans. |
| Database-stamped times (`SYSUTCDATETIME()` defaults, entity columns with `insertable = false`): `shifts.opened_at`, `orders.created_at`, `shift_signoffs.created_at`, `account_payments.created_at`, GRV `received_at`, adjustments, discounts, complimentaries, stock requests/takes, waste and others | Stamped by SQL Server in UTC, so zone-neutral. They ignore the test clock: a test can't open a shift "just before midnight" through the API (the midnight test uses order date and Close Day instead), and `signedOffAt` is the real time. Moving them to the Clock changes how rows are written; out of scope. |
| 26 `LocalDate.now(clock)` / `LocalTime.now(clock)` calls in 14 files (`OrderService`, `AdminAuthorizationService`, `MealCatalogService`, menu, kitchen, bar, board, inventory, offline grant/snapshot) | They use the Clock, whose zone is the site zone in production (`ClockConfig`). In tests the Clock is in the machine's zone (`ClockTestConfig`); building the test clock in `TestLocale.ZONE` would make tests independent of the machine and is a suggested follow-up. New code should prefer `siteLocale.businessDate(clock.instant())`. |

There are no other `LocalDate.now()`, `LocalDateTime.now()`, `Instant.now()` or `new Date()`
calls without a Clock in `src/main`.

## 7. Tests

| Test | What it covers |
|---|---|
| `SiteLocaleTest` (unit) | Defaults; business date, local time and start of day around Johannesburg midnight; money format; trimming; invalid zone, currency code and symbol refused |
| `SiteLocaleStartupTest` (context runner, no database) | Defaults start; SAST zones start; `Europe/London`, `Africa/Cairo`, `Africa/Nairobi`, `UTC`, `Etc/GMT+2` refuse to start with the views message; invalid settings refuse to start; overrides apply |
| `GoldenOutputIntegrationTests` | X reading (before and after the Z), Z reading (at close and latest), cashier readings (closed and open shift), the till's shift reading, dashboard layout and the today / attention / now / account-customers sections, CSV exports (byte for byte), xlsx exports (cell by cell) and two report envelopes, all compared with files recorded on `origin/main` before any change |
| `MidnightBoundaryIntegrationTests` | Order date and Close Day's "today" flip at 22:00 UTC (00:00 Johannesburg) |
| `MoneyFormatGoldenTest` | 15 recorded money strings (rounding, negatives, grouping, null) |

Golden files are in `src/test/resources/golden/t0-2/`. Re-record only on purpose, on code whose
output is known to be right: `./mvnw test -Dtest=GoldenOutputIntegrationTests -Dgolden.record=true`.
A mismatch writes the actual output to `target/golden-actual/` for diffing. Three kinds of run-to-run
noise are rewritten before comparing, and nothing else:

- Database ids become fixture names (`<s1>`, `<o5>`, `<pay1>`).
- JSON keys are sorted, because some response maps are built with `Map.of`, whose iteration
  order changes between JVM runs, so the API's key order is not stable even before this change.
- `signedOffAt` is stamped by the database with the real time.

`src/test/resources/golden/.gitattributes` stops git converting line endings (the CSV exports
are CRLF). Tests use `support/TestLocale` (Johannesburg, ZAR, R spelled out) rather than the app's
defaults, so changing a default fails the tests instead of silently moving them.

## 8. Follow-ups

- Per-tenant locale: move `SiteLocale` behind the tenant context and the views to one of the
  options in section 5; drop the guard once the views take the zone.
- Build the test `Clock` in `TestLocale.ZONE` (section 6).
- `OrderService:325` money text through `formatMoney`.
- JWT issuing and checking on the Clock, as its own change.
