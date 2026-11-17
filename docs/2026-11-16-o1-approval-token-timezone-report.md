# Approval-token expiry: local time in a UTC table (O1, Step 6)

Report only. **Nothing was changed** in this task.

## What the code does

Manager approvals create two kinds of row:

- a single-use token (`authorization_tokens`, 60 seconds)
- a management session (`authorization_sessions`, 300 seconds)

Both have a `created_at` and an `expires_at`.

| Column | Written by | Time zone of the value |
|---|---|---|
| `created_at` | DB default `SYSUTCDATETIME()` (`db/migration/V38`, `V44`) | **UTC** |
| `expires_at` | `LocalDateTime.now(clock).plusSeconds(...)` (`admin/AdminAuthorizationService.java:51`, `:70`) | **Africa/Johannesburg** (the `Clock` bean, `common/ClockConfig.java:11-15`) |

Expiry is checked against `LocalDateTime.now(clock)`, which is also Johannesburg time (`AdminAuthorizationService.java:86-88`, `:107-109`, `:142-144`).

Every other `DATETIME2` column in the schema holds UTC, and `common/UtcTimestamps.java` exists so that code never writes business-zone time into a UTC column. These two columns are the exception.

## Impact

1. **Approvals work correctly today.** The write and the check both use Johannesburg time, so a token expires after exactly 60 s and a session after 300 s. South Africa has no daylight saving, so there is no repeated or skipped hour. No customer-facing bug exists now.
2. **The stored rows look wrong.** In the table, `expires_at` is about **2 hours and 1 minute** after `created_at` for a token. Anything that compares `expires_at` with UTC (an SQL query, an audit, a support person, a future cleanup job using `SYSUTCDATETIME()`) will treat a token as valid for 2 hours longer than it is. A cleanup written in the obvious way would keep expired rows for 2 hours.
3. **The API returns the value without a zone.** `POST /admin/authorize` and `/admin/authorize-session` return `expiresAt` as a `LocalDateTime` with no offset, e.g. `"2026-11-16T12:31:07"` (`admin/AuthorizeResponse.java:14`, `AuthorizeSessionResponse.java:13`). Other date-times from the same API, such as an order's `createdAt`, use the same format but mean **UTC**.
   - A browser in South Africa that parses this with `new Date(...)` reads it as local time, which is right by accident.
   - A client that treats all API date-times as UTC (as `createdAt` requires) shows the expiry **2 hours late**.
   - The till's countdown for an approval is therefore unreliable depending on how it parses.
4. **It's tied to the `Clock` bean's zone.** If that zone ever changes (configuration, a test clock, a server in another region), every check reads the stored values differently. The rows only live 60–300 s, so the practical risk is a burst of 401 `AUTHORIZATION_INVALID` around such a change.
5. **Related: rows are never cleaned up.**
   - Single-use tokens are deleted when used (`consumeToken`, `deleteIfUnused`), but expired unused tokens stay.
   - Sessions are never deleted.
   - No retention job exists, so both tables grow forever. The growth is small, but any cleanup job must first have this time-zone problem fixed (point 2).

## Proposed fix (separate task)

1. **Write and compare in UTC.** In `AdminAuthorizationService`, use `UtcTimestamps.nowUtc(clock)` for `expires_at` and for the three expiry checks. This matches `shifts.closed_at` and the order status events.
2. **Return an instant.** Change `AuthorizeResponse.expiresAt` and `AuthorizeSessionResponse.expiresAt` to `Instant`, so the JSON reads `"2026-11-16T10:31:07Z"`. It's a format change, so announce it in the frontend contract. A client using `new Date(...)` handles both forms.
3. **Migrate existing rows** in the same release, so tokens that are valid at deploy time stay valid:
   ```sql
   UPDATE [authorization_tokens]   SET [expires_at] = CAST(([expires_at] AT TIME ZONE 'South Africa Standard Time') AT TIME ZONE 'UTC' AS DATETIME2);
   UPDATE [authorization_sessions] SET [expires_at] = CAST(([expires_at] AT TIME ZONE 'South Africa Standard Time') AT TIME ZONE 'UTC' AS DATETIME2);
   ```
   The simpler alternative is to delete all rows at deploy. Anyone approving at that moment would then need to enter the manager PIN again.
4. **Add a retention job** that deletes expired tokens and sessions after a grace period (for example, an hour), using UTC, in the style of `auth/protection/LoginAttemptRetentionJob.java`.
5. **Tests:**
   - `expires_at - created_at` equals the TTL in the database.
   - The response ends in `Z`.
   - A token is refused one second after its UTC expiry, with the mutable test clock.
   - The existing `ManagementSessionIntegrationTests` and `OrderAdjustmentIntegrationTests` still pass. They only check that `expiresAt` exists.

**Deploy note:** with one API instance, the code and the migration go out together, with no gap. With several instances behind a load balancer, an old instance would read converted rows as expired 2 hours early during the rollout. Those approvals would get 401 until the old instance stops, so deploy during a quiet period.
