-- Stage 4 Part B: money received against an account's outstanding balance — full or partial,
-- recorded by an admin. Never updates accounts directly; the balance is always derived.
CREATE TABLE [account_payments] (
    [id]           BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [account_id]   BIGINT         NOT NULL,
    [amount]       DECIMAL(10, 2) NOT NULL CHECK ([amount] > 0),
    [note]         NVARCHAR(255)  NULL,
    [recorded_by]  BIGINT         NOT NULL,
    [created_at]   DATETIME2      NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT [FK_account_payments_account] FOREIGN KEY ([account_id]) REFERENCES [accounts]([id]),
    CONSTRAINT [FK_account_payments_recorded_by] FOREIGN KEY ([recorded_by]) REFERENCES [users]([id])
);
