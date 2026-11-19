-- T0-4a: customer settings in the database (docs/2026-11-19-t0-4-site-settings-api-contract.md).
--
-- 1. site_settings: one row per setting the customer has changed. No row = the configuration
--    default (application.yaml / environment), so nothing is seeded and behaviour is unchanged.
--    The key list, types and limits live in code (settings/SiteSetting.java); value is the
--    setting's canonical text. NULL only for a nullable setting saved as empty.
-- 2. site_setting_changes: insert-only history of every SET and RESET, with the effective value
--    before and after and the user's name at the time.
-- 3. offline_grants.accounts_enabled / account_cap: the offline account values given to the till
--    when the grant was issued; sync checks the cap against these. NULL on grants issued before
--    V113 (sync then uses the current setting, as before).
--
-- Audit user columns have no foreign key to users, like devices (V105): users are hard-deleted.
-- All timestamps are UTC, written by the application clock.
-- Split across GO batches: CREATE TRIGGER must be the only statement in its batch.

-- 1. Saved settings -------------------------------------------------------------------------------

CREATE TABLE [site_settings] (
    [setting_key]     NVARCHAR(100) NOT NULL CONSTRAINT [PK_site_settings] PRIMARY KEY,
    [value]           NVARCHAR(200) NULL,
    [updated_at]      DATETIME2     NOT NULL,
    [updated_by]      BIGINT        NOT NULL,
    [updated_by_name] NVARCHAR(255) NOT NULL
);
GO

-- 2. History (insert-only) ------------------------------------------------------------------------

CREATE TABLE [site_setting_changes] (
    [id]              BIGINT IDENTITY(1, 1) NOT NULL CONSTRAINT [PK_site_setting_changes] PRIMARY KEY,
    [setting_key]     NVARCHAR(100) NOT NULL,
    [action]          NVARCHAR(10)  NOT NULL
                          CONSTRAINT [CK_site_setting_changes_action] CHECK ([action] IN ('SET', 'RESET')),
    [old_value]       NVARCHAR(200) NULL,
    [old_is_default]  BIT           NOT NULL,
    [new_value]       NVARCHAR(200) NULL,
    [new_is_default]  BIT           NOT NULL,
    [changed_at]      DATETIME2     NOT NULL,
    [changed_by]      BIGINT        NOT NULL,
    [changed_by_name] NVARCHAR(255) NOT NULL
);
GO

-- Newest first per key (GET history?key=, last change per setting, VAT rate in force at a time).
CREATE INDEX [IX_site_setting_changes_key_changed_at]
    ON [site_setting_changes] ([setting_key], [changed_at] DESC, [id] DESC);
GO

CREATE TRIGGER [trg_site_setting_changes_insert_only] ON [site_setting_changes]
AFTER UPDATE, DELETE
AS
BEGIN
    THROW 51000, 'Site setting changes are insert-only.', 1;
END;
GO

-- 3. Offline account values on the grant ---------------------------------------------------------

ALTER TABLE [offline_grants] ADD
    [accounts_enabled] BIT           NULL,
    [account_cap]      DECIMAL(10, 2) NULL;
GO
