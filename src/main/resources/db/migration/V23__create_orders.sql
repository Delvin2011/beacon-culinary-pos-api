CREATE TABLE [orders] (
    [id]              BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [order_number]    INT            NOT NULL,
    [order_date]      DATE           NOT NULL DEFAULT CAST(SYSUTCDATETIME() AS DATE),
    [shift_id]        BIGINT         NOT NULL,
    [cashier_id]      BIGINT         NOT NULL,
    [status]          NVARCHAR(20)   NOT NULL DEFAULT 'CONFIRMED' CHECK ([status] IN ('CONFIRMED')),
    [payment_method]  NVARCHAR(10)   NOT NULL DEFAULT 'CASH' CHECK ([payment_method] IN ('CASH')),
    [amount_tendered] DECIMAL(10, 2) NOT NULL,
    [change_due]      DECIMAL(10, 2) NOT NULL,
    [subtotal]        DECIMAL(10, 2) NOT NULL,
    [total]           DECIMAL(10, 2) NOT NULL,
    [created_at]      DATETIME2      NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT [FK_orders_shifts] FOREIGN KEY ([shift_id]) REFERENCES [shifts]([id]),
    CONSTRAINT [FK_orders_cashier] FOREIGN KEY ([cashier_id]) REFERENCES [users]([id])
);

CREATE UNIQUE INDEX [IX_orders_date_number] ON [orders]([order_date], [order_number]);
