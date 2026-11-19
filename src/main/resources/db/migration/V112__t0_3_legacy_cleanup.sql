-- T0-3 legacy clean-up (go-live checklist items 5, 6, 7). Removes what was left of the e-commerce
-- template before new customers are onboarded.
--
-- 1. Guard: addresses and profiles are dropped below. If either holds any row, stop here and change
--    nothing, so data is never dropped by accident. Export or remove the rows deliberately, then re-run.
IF EXISTS (SELECT 1 FROM [addresses]) OR EXISTS (SELECT 1 FROM [profiles])
    THROW 50112, 'T0-3 (V112): addresses or profiles contain rows. Export or remove them deliberately, then re-run. No data was changed.', 1;
GO

-- 2. The USER role was the e-commerce sign-up role (old POST /users), never a staff role.
--    Users are deactivated, never deleted, so history (orders, shifts, audit) keeps their names.
--    Each USER row becomes: role CASHIER, active 0, password NULL, pin_hash NULL. Name and email are
--    kept. CASHIER is PIN-only, so the account can't sign in even if reactivated until an admin sets
--    a PIN. A kept email stays unique (UX_users_email covers inactive users too), so it can't be
--    reused for new staff unless an admin changes or clears it.
--    Every converted user is recorded in legacy_role_conversions, and the ids are printed to the
--    Flyway log.
CREATE TABLE [legacy_role_conversions] (
    [user_id]       BIGINT       NOT NULL CONSTRAINT [PK_legacy_role_conversions] PRIMARY KEY,
    [previous_role] VARCHAR(20)  NOT NULL,
    [new_role]      VARCHAR(20)  NOT NULL,
    [migration]     VARCHAR(10)  NOT NULL,
    [converted_at]  DATETIME2    NOT NULL CONSTRAINT [DF_legacy_role_conversions_converted_at] DEFAULT SYSUTCDATETIME(),
    CONSTRAINT [FK_legacy_role_conversions_users] FOREIGN KEY ([user_id]) REFERENCES [users]([id])
);
GO

-- Recorded first, then converted (OUTPUT INTO can't target a table with a foreign key). Both run in
-- this migration's transaction, so the record and the change can't drift apart.
INSERT INTO [legacy_role_conversions] ([user_id], [previous_role], [new_role], [migration])
SELECT [id], 'USER', 'CASHIER', 'V112' FROM [users] WHERE [role] = 'USER';

UPDATE [users]
SET [role] = 'CASHIER', [active] = 0, [password] = NULL, [pin_hash] = NULL
WHERE [role] = 'USER';

DECLARE @count INT = (SELECT COUNT(*) FROM [legacy_role_conversions] WHERE [migration] = 'V112');
DECLARE @ids NVARCHAR(MAX) = (SELECT STRING_AGG(CAST([user_id] AS NVARCHAR(20)), ',') WITHIN GROUP (ORDER BY [user_id])
                              FROM [legacy_role_conversions] WHERE [migration] = 'V112');
PRINT CONCAT('T0-3 (V112): USER-role users deactivated and changed to CASHIER: ', @count,
             '. Ids: ', COALESCE(@ids, '(none)'));
GO

-- 3. The role column defaulted to 'USER' (V3). With no default, every insert must name a role.
--    Then USER leaves the role CHECK (last rebuilt in V97).
ALTER TABLE [users] DROP CONSTRAINT IF EXISTS [DF_users_role];
ALTER TABLE [users] DROP CONSTRAINT [CK_users_role];
ALTER TABLE [users] WITH CHECK ADD CONSTRAINT [CK_users_role]
    CHECK ([role] IN ('CASHIER', 'ADMIN', 'KITCHEN', 'STOCK_CLERK', 'STOCK_ADMIN', 'HEAD_CHEF', 'BARISTA'));
GO

-- 4. Legacy e-commerce tables from V1. Only their own foreign keys to users exist; no table
--    references them. IX_addresses_user_id goes with its table.
ALTER TABLE [addresses] DROP CONSTRAINT IF EXISTS [FK_addresses_users];
ALTER TABLE [profiles] DROP CONSTRAINT IF EXISTS [FK_profiles_users];
DROP TABLE [addresses];
DROP TABLE [profiles];
