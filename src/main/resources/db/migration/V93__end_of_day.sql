-- Reports R3: end of day. Shift sign-off log, numbered Z readings, and receipt reprints.
-- Split across GO batches: CREATE TRIGGER must be the only statement in its batch.

-- Append-only: a shift's sign-off state is its latest row (SIGNED_OFF or REVOKED).
CREATE TABLE [shift_signoffs] (
    [id]       BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [shift_id] BIGINT        NOT NULL,
    [action]   NVARCHAR(12)  NOT NULL,
    [done_by]  BIGINT        NOT NULL,
    [done_at]  DATETIME2     NOT NULL CONSTRAINT [DF_shift_signoffs_done_at] DEFAULT SYSUTCDATETIME(),
    [note]     NVARCHAR(255) NULL,
    CONSTRAINT [FK_shift_signoffs_shift] FOREIGN KEY ([shift_id]) REFERENCES [shifts]([id]),
    CONSTRAINT [FK_shift_signoffs_done_by] FOREIGN KEY ([done_by]) REFERENCES [users]([id]),
    CONSTRAINT [CK_shift_signoffs_action] CHECK ([action] IN ('SIGNED_OFF', 'REVOKED'))
);
CREATE INDEX [idx_shift_signoffs_shift] ON [shift_signoffs]([shift_id], [id]);

-- One per closed trading day, numbered without gaps. snapshot is the full Z envelope as JSON;
-- the Z Reading report returns it as stored, never recalculated.
CREATE TABLE [z_readings] (
    [id]                 BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [z_number]           INT            NOT NULL,
    [trading_day]        DATE           NOT NULL,
    [closed_by]          BIGINT         NOT NULL,
    [closed_at]          DATETIME2      NOT NULL CONSTRAINT [DF_z_readings_closed_at] DEFAULT SYSUTCDATETIME(),
    [definition_version] INT            NOT NULL,
    [gross_sales]        DECIMAL(12, 2) NOT NULL,
    [net_sales]          DECIMAL(12, 2) NOT NULL,
    [snapshot]           NVARCHAR(MAX)  NOT NULL,
    CONSTRAINT [UQ_z_readings_z_number] UNIQUE ([z_number]),
    CONSTRAINT [UQ_z_readings_trading_day] UNIQUE ([trading_day]),
    CONSTRAINT [FK_z_readings_closed_by] FOREIGN KEY ([closed_by]) REFERENCES [users]([id])
);

CREATE TABLE [receipt_reprints] (
    [id]           BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [order_id]     BIGINT    NOT NULL,
    [copy_number]  INT       NOT NULL,
    [reprinted_by] BIGINT    NOT NULL,
    [reprinted_at] DATETIME2 NOT NULL CONSTRAINT [DF_receipt_reprints_reprinted_at] DEFAULT SYSUTCDATETIME(),
    CONSTRAINT [FK_receipt_reprints_order] FOREIGN KEY ([order_id]) REFERENCES [orders]([id]),
    CONSTRAINT [FK_receipt_reprints_reprinted_by] FOREIGN KEY ([reprinted_by]) REFERENCES [users]([id]),
    CONSTRAINT [UQ_receipt_reprints_copy] UNIQUE ([order_id], [copy_number])
);
CREATE INDEX [idx_receipt_reprints_order] ON [receipt_reprints]([order_id]);
GO

-- A Z reading is a fiscal record: once written it can't be changed or removed.
CREATE TRIGGER [trg_z_readings_insert_only] ON [z_readings]
AFTER UPDATE, DELETE
AS
BEGIN
    THROW 51000, 'Z readings are insert-only.', 1;
END;
