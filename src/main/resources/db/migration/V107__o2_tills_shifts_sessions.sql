-- O2: tills, per-till shifts, sessions and lock (docs/2026-11-17-o2-tills-shifts-api-contract.md).
--
-- 1. devices.till_code: T1, T2, ... unique among ACTIVE devices. Revoked tills keep their code for
--    history, and the code becomes free for a new till.
-- 2. shifts: the till a shift is open on (device_id; NULL = the shared "unregistered till"), the till's
--    code and name as they were when it opened, force close, and whether the drawer was counted.
-- 3. One open shift per till and one per cashier, replacing V40's one open shift system-wide.
-- 4. orders.device_id: the till a sale was rung up on, copied from its shift, for reporting.
-- 5. user_sessions: one row per sign-in; tokens carry its id (sid).
-- 6. shift_cash_count_events: insert-only audit of force closes, late counts and confirmed no-counts.
-- 7. login_attempts: two new refusal outcomes (not failures).
--
-- Audit user columns have no foreign key to users, like devices (V105): users are hard-deleted.
-- All timestamps are UTC, written by the application clock.
-- Split across GO batches: CREATE TRIGGER must be the only statement in its batch, and new
-- columns must exist before later statements in the same script use them.

-- 1. Till codes -------------------------------------------------------------------------------

ALTER TABLE [devices] ADD [till_code] NVARCHAR(10) NULL;
GO

-- Tills already registered get T1..Tn in the order they were registered (even beyond the active-till
-- limit: the limit only refuses new registrations).
WITH [numbered] AS (
    SELECT [id], ROW_NUMBER() OVER (ORDER BY [registered_at], [id]) AS [n]
    FROM [devices]
    WHERE [status] = 'ACTIVE'
)
UPDATE [d] SET [till_code] = CONCAT('T', [numbered].[n])
FROM [devices] [d] JOIN [numbered] ON [numbered].[id] = [d].[id];

CREATE UNIQUE INDEX [UX_devices_active_till_code] ON [devices]([till_code])
    WHERE [status] = 'ACTIVE' AND [till_code] IS NOT NULL;
GO

-- 2. Shifts per till --------------------------------------------------------------------------

ALTER TABLE [shifts] ADD
    [device_id]          UNIQUEIDENTIFIER NULL CONSTRAINT [FK_shifts_devices] REFERENCES [devices]([id]),
    [till_code]          NVARCHAR(10)  NULL,
    [till_name]          NVARCHAR(100) NULL,
    [force_closed]       BIT           NOT NULL CONSTRAINT [DF_shifts_force_closed] DEFAULT 0,
    [force_close_reason] NVARCHAR(255) NULL,
    [cash_count]         VARCHAR(20)   NOT NULL CONSTRAINT [DF_shifts_cash_count] DEFAULT 'COUNTED'
                             CONSTRAINT [CK_shifts_cash_count]
                             CHECK ([cash_count] IN ('COUNTED', 'UNCOUNTED', 'COUNTED_LATE', 'NO_COUNT_CONFIRMED'));
GO

-- 3. One open shift per till, one per cashier -------------------------------------------------
--
-- SQL Server treats NULLs as equal in a unique index (unlike standard SQL), so under the OPEN filter
-- at most one open shift can have device_id NULL: every unregistered browser shares that one shift,
-- exactly as V40 allowed one open shift in total. Proven by O2TillShiftIndexIntegrationTests.
--
-- The new indexes are created before V40's is dropped. Today at most one shift is open, so both hold.

CREATE UNIQUE INDEX [UX_shifts_open_device] ON [shifts]([device_id]) WHERE [status] = 'OPEN';
CREATE UNIQUE INDEX [UX_shifts_open_cashier] ON [shifts]([cashier_id]) WHERE [status] = 'OPEN';
DROP INDEX [idx_shifts_single_open] ON [shifts];
GO

-- 4. The till a sale was rung up on -----------------------------------------------------------

ALTER TABLE [orders] ADD [device_id] UNIQUEIDENTIFIER NULL CONSTRAINT [FK_orders_devices] REFERENCES [devices]([id]);
GO

-- 5. Sessions ---------------------------------------------------------------------------------
--
-- kind: POS (CASHIER/ADMIN PIN, or any sign-in on a registered till), STATION (KITCHEN, BARISTA,
-- HEAD_CHEF PIN), BACK_OFFICE (password sign-in off a till). method: pwd or pin (the amr claim).
-- device_id and credential_id: the till and browser the sign-in came from, when registered.
-- O3 will hang offline grants off id.

CREATE TABLE [user_sessions] (
    [id]            UNIQUEIDENTIFIER NOT NULL CONSTRAINT [PK_user_sessions] PRIMARY KEY,
    [user_id]       BIGINT           NOT NULL,
    [device_id]     UNIQUEIDENTIFIER NULL,
    [credential_id] UNIQUEIDENTIFIER NULL,
    [kind]          VARCHAR(12)      NOT NULL CONSTRAINT [CK_user_sessions_kind]
                        CHECK ([kind] IN ('POS', 'STATION', 'BACK_OFFICE')),
    [method]        VARCHAR(3)       NOT NULL CONSTRAINT [CK_user_sessions_method] CHECK ([method] IN ('pwd', 'pin')),
    [started_at]    DATETIME2        NOT NULL,
    [last_seen_at]  DATETIME2        NOT NULL,
    [ended_at]      DATETIME2        NULL,
    [end_reason]    VARCHAR(20)      NULL CONSTRAINT [CK_user_sessions_end_reason]
                        CHECK ([end_reason] IN ('LOGOUT', 'LOCK', 'REPLACED', 'IDLE', 'ADMIN', 'SHIFT_FORCE_CLOSED', 'EXPIRED')),
    CONSTRAINT [CK_user_sessions_ended]
        CHECK (([ended_at] IS NULL AND [end_reason] IS NULL) OR ([ended_at] IS NOT NULL AND [end_reason] IS NOT NULL))
);
GO

-- Active sessions only: a user's POS sessions (R8), a till's sessions (lock, replace, revoke), and
-- the idle and expiry sweep.
CREATE INDEX [IX_user_sessions_user_active] ON [user_sessions]([user_id], [kind]) WHERE [ended_at] IS NULL;
CREATE INDEX [IX_user_sessions_device_active] ON [user_sessions]([device_id]) WHERE [ended_at] IS NULL;
CREATE INDEX [IX_user_sessions_active_seen] ON [user_sessions]([last_seen_at]) INCLUDE ([kind], [started_at])
    WHERE [ended_at] IS NULL;
GO

-- 6. Cash-count audit (insert-only) -----------------------------------------------------------

CREATE TABLE [shift_cash_count_events] (
    [id]                   BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT [PK_shift_cash_count_events] PRIMARY KEY,
    [shift_id]             BIGINT        NOT NULL CONSTRAINT [FK_shift_cash_count_events_shift] REFERENCES [shifts]([id]),
    [action]               VARCHAR(20)   NOT NULL CONSTRAINT [CK_shift_cash_count_events_action]
                               CHECK ([action] IN ('FORCE_CLOSED', 'LATE_COUNT', 'NO_COUNT_CONFIRMED')),
    [counted_cash]         DECIMAL(10,2) NULL,
    [expected_cash]        DECIMAL(10,2) NULL,
    [variance]             DECIMAL(10,2) NULL,
    [variance_reason_code] NVARCHAR(30)  NULL,
    [note]                 NVARCHAR(255) NULL,
    [done_by]              BIGINT        NOT NULL,
    [done_by_name]         NVARCHAR(255) NULL,
    [done_at]              DATETIME2     NOT NULL
);
GO

CREATE INDEX [IX_shift_cash_count_events_shift] ON [shift_cash_count_events]([shift_id], [id]);
GO

CREATE TRIGGER [trg_shift_cash_count_events_insert_only] ON [shift_cash_count_events]
AFTER UPDATE, DELETE
AS
BEGIN
    THROW 51000, 'Shift cash-count events are insert-only.', 1;
END;
GO

-- 7. Sign-ins refused by the till rules (not failures, never counted toward lockout) ----------

ALTER TABLE [login_attempts] DROP CONSTRAINT [CK_login_attempts_outcome];
ALTER TABLE [login_attempts] ADD CONSTRAINT [CK_login_attempts_outcome]
    CHECK ([outcome] IN ('SUCCESS', 'BAD_CREDENTIAL', 'UNKNOWN_USER', 'DISABLED',
                         'LOCKED', 'RATE_LIMITED', 'VALIDATION', 'DEVICE_NOT_REGISTERED',
                         'TILL_LOCKED', 'SHIFT_OPEN_ON_OTHER_TILL'));
GO
