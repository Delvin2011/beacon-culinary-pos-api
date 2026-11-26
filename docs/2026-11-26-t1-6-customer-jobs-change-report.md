# T1-6 customer jobs: change report

Branch `feature/t1-6-customer-jobs`, from `feature/infrastructureUpdatesBE`. Not pushed, not merged. How the jobs work
now is in `docs/2026-11-24-routing-data-source.md`, section 6a; this report says what changed and why.

## What it gives

- **Daily jobs at each customer's own local time.** Login-attempt retention runs at 03:30 in the customer's time zone
  (`tenants.time_zone`, else `site.time-zone`), once per local date, safe across daylight saving.
- **A spread.** Each customer has a fixed offset of 0 to 20 minutes from its slug, so a 03:30 job runs 03:30 to 03:50.
- **Customers that can't be served are skipped by every job,** with one WARN when skipping starts and one INFO when
  served again: PROVISIONING, SUSPENDED, MIGRATION_FAILED, or a database behind the app's schema version.
- **Single mode** keeps running each job once; retention still runs daily at 03:30 site time.

## Job inventory

| Job | Before | After |
|---|---|---|
| Login-attempt retention | Cron `0 30 3 * * *` in `site.time-zone`: every customer at the same moment | A minute tick; `CustomerJobs.daily` runs it per customer at 03:30 local plus its offset, once per local date. Settings `retention-time` (replaces `retention-cron`) and `retention-enabled` |
| Session sweep | Every minute, each ACTIVE customer | Unchanged timing; now skips customers that can't be served, with one WARN |
| Customer pool sweep | Every minute, memory and platform database | Unchanged |
| SSE keepalive | Every 25 s, open streams only | Unchanged |
| Directory refresh | Lazy, after its 30 s cache time | Unchanged |

## Steps

| Step | Commit | What | Lines (main / tests) |
|---|---|---|---|
| 1 | `c88ea33` | `tenants.time_zone` (platform `V3`), read by the directory into `Customer` | +13 -3 / +12 -3 |
| 2 | `1ee12e0` | Skipping in `CustomerJobs`; the pool's "behind" ERROR once per version; the multi-mode tests get `MutableClock` | +85 -10 / +94 -5 |
| 3 | `e54b860` | `DailySchedule` and `CustomerJobs.daily`; retention on a minute tick | +161 -5 / +204 -3 |
| 4 | (this commit) | Docs | see below |

## Decisions (approved)

| Decision | Choice |
|---|---|
| Where a customer's time zone lives (the prompt said "the customer's site settings (SiteLocale)", but `SiteLocale` is one app-wide setting and no per-customer zone existed) | Option A: a nullable `tenants.time_zone` in the platform database, any valid IANA id; it times only the daily jobs, the reports stay in SAST |
| "Already ran today" | In memory (job and slug to the last local date). A restart after the time runs the job once, late; a day the app is down is missed. Harmless for retention. A run counts when it starts, so a failing run isn't retried that day |
| The spread window | 0 to 20 minutes, `floorMod(slug.hashCode(), 21)`; daily times 23:39 at the latest |
| Daylight saving | `ZonedDateTime.of`: a time in a gap moves forward by the gap (the day still runs); a time occurring twice is its first occurrence; once per local date (never twice) |
| "Behind" databases (D2) | Found by taking one pooled connection before the work. `CustomerPools` logs its ERROR once per version found and DEBUG on later rechecks (otherwise the minute sweep would log an ERROR every minute) |
| Frequent jobs at 50 customers | No change: about 0.25 to 1 s a minute on the scheduler thread; about one warm connection per customer |

## Where the code differed from the prompt

| Found | Done |
|---|---|
| No per-customer time zone existed (`SiteLocale` is app-wide; `SastViewGuard` allows SAST zones only) | Asked; option A chosen (above) |
| The retention job's new minute tick would run in every test context about a minute after start-up (the old 03:30 cron practically never fired during a run), deleting rows tests insert on purpose | Added `security.login-protection.retention-enabled` (default `true`), set `false` in `LoginProtectionTestDefaults`, like `pos.session.sweep-enabled`; approved with step 3 |
| The pool's "behind" ERROR was logged at every recheck (30 s) | Once per version (D2, approved) |

## Files

Created: `src/main/resources/db/platform/V3__tenant_time_zone.sql`, `tenancy/jobs/DailySchedule.java`,
`tenancy/jobs/DailyScheduleTest.java`, this report.

Modified, main: `tenancy/Customer.java`, `tenancy/CustomerDirectory.java`, `tenancy/jobs/CustomerJobs.java`,
`tenancy/routing/CustomerPools.java`, `auth/protection/LoginAttemptRetentionJob.java`,
`auth/protection/LoginProtectionProperties.java`.

Modified, tests: `tenancy/CustomerDirectoryIntegrationTests.java`, `tenancy/PlatformMigrationIntegrationTests.java`,
`tenancy/web/CustomerResolutionFilterTest.java`, `tenancy/CustomerJobsIntegrationTests.java`,
`support/tenancy/MultiCustomerIntegrationTest.java`, `support/LoginProtectionTestDefaults.java`,
`common/logging/BackgroundLoggingIntegrationTests.java`.

Modified, docs: `2026-11-24-routing-data-source.md` (section 6a, roadmap, tests), `2026-11-24-platform-database.md`,
`2026-11-11-login-protection.md`, `2026-11-19-site-locale.md`, `2026-11-26-customer-migrations.md`.

## Tests

| Test | New or changed |
|---|---|
| `DailyScheduleTest` (6, new) | Two zones, the spread, clocks forward and back, a late run after a restart, a time after 23:39 refused |
| `CustomerJobsIntegrationTests` (5 to 8) | Suspended and behind customers: one WARN over several runs, one pool ERROR, one INFO on resuming, the others run; a daily job in Johannesburg and New York over two days; a daily failure for one customer doesn't stop the other and isn't retried that day; an invalid zone logged once |
| `CustomerDirectoryIntegrationTests` (+1) | The time zone read as stored, null when not set |
| `PlatformMigrationIntegrationTests`, `BackgroundLoggingIntegrationTests`, `CustomerResolutionFilterTest` | Adjusted: `V3` in the history; retention run with the clock set; the new `Customer` field |

The full suite and `check-all.ps1` (multi mode) results, with the commit hash, are in the final report.

## Open items

- `TenantRegisterCommand` has no `--time-zone` option yet; set `tenants.time_zone` with SQL (routing doc, section 6a).
- The reports stay in SAST whatever `tenants.time_zone` says, until per-customer locale
  (`docs/2026-11-19-site-locale.md`, section 5).
- "Already ran today" is in memory: a daily job that must not run late after a restart, or must never miss a day, would
  need a durable record (not built; retention doesn't need one).
- If the session sweep's time per minute grows (many more customers, or slow databases), a larger scheduler pool or
  running customers in parallel.
