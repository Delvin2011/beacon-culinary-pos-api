-- O3: offline foundation (docs/2026-11-17-o3-offline-foundation-api-contract.md).
--
-- 1. offline_grants: signed offline permissions, one active per till and shift. Revoked and expired
--    rows are kept: O4 accepts sales made under a grant before it was revoked or expired.
--    cashier_id has no foreign key (users can be hard-deleted, as for devices in V105); the name is kept.
-- 2. offline_sequences: the highest offline sale number the server has seen per till and trading
--    day. O4 writes it; the grant returns last_seq + 1 as nextOfflineSeq.
-- 3. offline_till_status and offline_till_pending: each till's latest status report (heartbeat) and
--    its unsynced sales per shift and trading day. Each report replaces the previous one.
-- 4. day_close_overrides: Close Day run despite OFFLINE_SALES_PENDING, with who, when and why.
--    Insert-only, like shift_cash_count_events (V107).
--
-- Split across GO batches: CREATE TRIGGER must be the only statement in its batch.

-- 1. Offline grants ------------------------------------------------------------------------------

CREATE TABLE [offline_grants] (
    [id]             UNIQUEIDENTIFIER NOT NULL CONSTRAINT [PK_offline_grants] PRIMARY KEY,
    [device_id]      UNIQUEIDENTIFIER NOT NULL CONSTRAINT [FK_offline_grants_device] REFERENCES [devices] ([id]),
    [credential_id]  UNIQUEIDENTIFIER NOT NULL CONSTRAINT [FK_offline_grants_credential] REFERENCES [device_credentials] ([id]),
    [shift_id]       BIGINT           NOT NULL CONSTRAINT [FK_offline_grants_shift] REFERENCES [shifts] ([id]),
    [cashier_id]     BIGINT           NOT NULL,
    [cashier_name]   NVARCHAR(255)    NOT NULL,
    [session_id]     UNIQUEIDENTIFIER NOT NULL,
    [issued_at]      DATETIME2        NOT NULL,
    [expires_at]     DATETIME2        NOT NULL,
    [revoked_at]     DATETIME2        NULL,
    [revoke_reason]  NVARCHAR(30)     NULL,
    [replaced_by]    UNIQUEIDENTIFIER NULL,
    CONSTRAINT [CK_offline_grants_revoke_reason] CHECK ([revoke_reason] IN
        ('REPLACED', 'SHIFT_CLOSED', 'SHIFT_FORCE_CLOSED', 'TILL_REVOKED', 'BROWSER_REVOKED', 'CASHIER_DEACTIVATED')),
    CONSTRAINT [CK_offline_grants_revoked] CHECK (([revoked_at] IS NULL AND [revoke_reason] IS NULL)
        OR ([revoked_at] IS NOT NULL AND [revoke_reason] IS NOT NULL)),
    CONSTRAINT [CK_offline_grants_expiry] CHECK ([expires_at] > [issued_at])
);

CREATE UNIQUE INDEX [UX_offline_grants_active] ON [offline_grants] ([device_id], [shift_id]) WHERE [revoked_at] IS NULL;
CREATE INDEX [IX_offline_grants_shift] ON [offline_grants] ([shift_id]);
CREATE INDEX [IX_offline_grants_credential] ON [offline_grants] ([credential_id]);
CREATE INDEX [IX_offline_grants_cashier_active] ON [offline_grants] ([cashier_id]) WHERE [revoked_at] IS NULL;

-- 2. Offline numbering ---------------------------------------------------------------------------

CREATE TABLE [offline_sequences] (
    [device_id]    UNIQUEIDENTIFIER NOT NULL CONSTRAINT [FK_offline_sequences_device] REFERENCES [devices] ([id]),
    [trading_day]  DATE             NOT NULL,
    [last_seq]     INT              NOT NULL,
    [updated_at]   DATETIME2        NOT NULL,
    CONSTRAINT [PK_offline_sequences] PRIMARY KEY ([device_id], [trading_day]),
    CONSTRAINT [CK_offline_sequences_seq] CHECK ([last_seq] >= 0)
);

-- 3. Till status (heartbeat) -----------------------------------------------------------------------

CREATE TABLE [offline_till_status] (
    [device_id]               UNIQUEIDENTIFIER NOT NULL CONSTRAINT [PK_offline_till_status] PRIMARY KEY
                                  CONSTRAINT [FK_offline_till_status_device] REFERENCES [devices] ([id]),
    [credential_id]           UNIQUEIDENTIFIER NOT NULL,
    [pending_count]           INT              NOT NULL CONSTRAINT [CK_offline_till_status_pending] CHECK ([pending_count] >= 0),
    [oldest_pending_sold_at]  DATETIME2        NULL,
    [last_sync_at]            DATETIME2        NULL,
    [offline_since]           DATETIME2        NULL,
    [till_clock_utc]          DATETIME2        NOT NULL,
    [received_at]             DATETIME2        NOT NULL,
    [clock_skew_ms]           BIGINT           NOT NULL,
    [app_version]             NVARCHAR(50)     NULL
);

CREATE TABLE [offline_till_pending] (
    [device_id]       UNIQUEIDENTIFIER NOT NULL CONSTRAINT [FK_offline_till_pending_device] REFERENCES [devices] ([id]),
    [shift_id]        BIGINT           NOT NULL,
    [trading_day]     DATE             NOT NULL,
    [pending_count]   INT              NOT NULL CONSTRAINT [CK_offline_till_pending_count] CHECK ([pending_count] >= 1),
    [oldest_sold_at]  DATETIME2        NULL,
    CONSTRAINT [PK_offline_till_pending] PRIMARY KEY ([device_id], [shift_id], [trading_day])
);

CREATE INDEX [IX_offline_till_pending_day] ON [offline_till_pending] ([trading_day]);
CREATE INDEX [IX_offline_till_pending_shift] ON [offline_till_pending] ([shift_id]);

-- 4. Close Day overrides -------------------------------------------------------------------------

CREATE TABLE [day_close_overrides] (
    [id]                  BIGINT IDENTITY (1, 1) NOT NULL CONSTRAINT [PK_day_close_overrides] PRIMARY KEY,
    [trading_day]         DATE           NOT NULL,
    [blocker_code]        NVARCHAR(40)   NOT NULL,
    [reason]              NVARCHAR(500)  NOT NULL,
    [detail]              NVARCHAR(MAX)  NOT NULL,
    [overridden_by]       BIGINT         NOT NULL,
    [overridden_by_name]  NVARCHAR(255)  NOT NULL,
    [overridden_at]       DATETIME2      NOT NULL CONSTRAINT [DF_day_close_overrides_at] DEFAULT SYSUTCDATETIME()
);

CREATE INDEX [IX_day_close_overrides_day] ON [day_close_overrides] ([trading_day]);
GO

CREATE TRIGGER [trg_day_close_overrides_insert_only] ON [day_close_overrides]
AFTER UPDATE, DELETE
AS
BEGIN
    THROW 51000, 'Day close overrides are insert-only.', 1;
END;
GO
