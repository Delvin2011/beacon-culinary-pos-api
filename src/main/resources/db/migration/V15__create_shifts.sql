CREATE TABLE [shifts] (
    [id] BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [cashier_id] BIGINT NOT NULL FOREIGN KEY REFERENCES [users]([id]),
    [opening_float] DECIMAL(10,2) NOT NULL,
    [opened_at] DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    [closed_at] DATETIME2 NULL,
    [status] NVARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK ([status] IN ('OPEN','CLOSED'))
);

CREATE INDEX [idx_shifts_cashier_status] ON [shifts]([cashier_id], [status]);
