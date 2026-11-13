-- WS1-1b (Phase 3): POS till registration.
--
-- devices: one physical POS machine, identified by its normalised serial number.
-- device_credentials: one browser on that machine; only the SHA-256 hash of its token is stored.
--
-- Audit columns (registered_by, revoked_by, created_by) have no foreign key to users, like
-- login_attempts.user_id: DELETE /users/{id} hard-deletes users, and a foreign key would make that
-- fail for any admin who registered or revoked a till. The *_name columns keep a snapshot of who
-- acted, so the admin page still shows it if the user is later deleted (go-live checklist item 12).
-- device_credentials.device_id references devices (no cascade: devices are never deleted).
--
-- Serial numbers and asset tags are unique among ACTIVE devices only, so a revoked machine can be
-- registered again. All timestamps are UTC, written by the application clock.

CREATE TABLE [devices] (
    [id]                 UNIQUEIDENTIFIER NOT NULL CONSTRAINT [PK_devices] PRIMARY KEY
                             CONSTRAINT [DF_devices_id] DEFAULT NEWID(),
    [name]               NVARCHAR(100) NOT NULL,
    [device_type]        VARCHAR(20)   NOT NULL CONSTRAINT [CK_devices_type] CHECK ([device_type] = 'POS'),
    [serial_number]      NVARCHAR(100) NULL,
    [asset_tag]          NVARCHAR(100) NULL,
    [status]             VARCHAR(20)   NOT NULL CONSTRAINT [CK_devices_status] CHECK ([status] IN ('ACTIVE', 'REVOKED')),
    [registered_by]      BIGINT        NOT NULL,
    [registered_by_name] NVARCHAR(255) NULL,
    [registered_at]      DATETIME2     NOT NULL,
    [revoked_by]         BIGINT        NULL,
    [revoked_by_name]    NVARCHAR(255) NULL,
    [revoked_at]         DATETIME2     NULL,
    [revoke_reason]      NVARCHAR(255) NULL
);
GO

CREATE UNIQUE INDEX [UX_devices_active_serial] ON [devices]([serial_number])
    WHERE [status] = 'ACTIVE' AND [serial_number] IS NOT NULL;
CREATE UNIQUE INDEX [UX_devices_active_asset_tag] ON [devices]([asset_tag])
    WHERE [status] = 'ACTIVE' AND [asset_tag] IS NOT NULL;
GO

CREATE TABLE [device_credentials] (
    [id]               UNIQUEIDENTIFIER NOT NULL CONSTRAINT [PK_device_credentials] PRIMARY KEY
                           CONSTRAINT [DF_device_credentials_id] DEFAULT NEWID(),
    [device_id]        UNIQUEIDENTIFIER NOT NULL CONSTRAINT [FK_device_credentials_devices] REFERENCES [devices]([id]),
    [token_hash]       VARBINARY(32) NOT NULL CONSTRAINT [UX_device_credentials_token_hash] UNIQUE,
    [browser_label]    NVARCHAR(100) NULL,
    [status]           VARCHAR(20)   NOT NULL CONSTRAINT [CK_device_credentials_status] CHECK ([status] IN ('ACTIVE', 'REVOKED')),
    [created_by]       BIGINT        NOT NULL,
    [created_by_name]  NVARCHAR(255) NULL,
    [created_at]       DATETIME2     NOT NULL,
    [last_seen_at]     DATETIME2     NULL,
    [last_seen_ip]     VARCHAR(45)   NULL,
    [revoked_by]       BIGINT        NULL,
    [revoked_by_name]  NVARCHAR(255) NULL,
    [revoked_at]       DATETIME2     NULL,
    [revoke_reason]    NVARCHAR(255) NULL
);
GO

CREATE INDEX [IX_device_credentials_device_status] ON [device_credentials]([device_id], [status]);
GO

-- Sign-in attempts: the browser that made the attempt (device_id, from V103, holds the machine).
ALTER TABLE [login_attempts] ADD [device_credential_id] NVARCHAR(100) NULL;
GO

-- New outcome: refused because the request needs a registered POS till. Not a failure.
ALTER TABLE [login_attempts] DROP CONSTRAINT [CK_login_attempts_outcome];
ALTER TABLE [login_attempts] ADD CONSTRAINT [CK_login_attempts_outcome]
    CHECK ([outcome] IN ('SUCCESS', 'BAD_CREDENTIAL', 'UNKNOWN_USER', 'DISABLED',
                         'LOCKED', 'RATE_LIMITED', 'VALIDATION', 'DEVICE_NOT_REGISTERED'));
GO

-- For the DEVICE_PIN rule and PIN_PAIR keyed by till.
CREATE INDEX [IX_login_attempts_method_device] ON [login_attempts]([method], [device_id], [attempted_at]);
