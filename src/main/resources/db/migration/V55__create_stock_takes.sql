-- Stage 5 Part F: physical stock counts, reconciled against the derived ledger total — the same
-- expected-vs-counted variance pattern already proven for cash in Stage 2.5, applied to raw
-- stock. Same "dedicated source table" reasoning as waste_entries (V54): note/variance have no
-- home on the ledger row itself. Creates a STOCK_TAKE_ADJUSTMENT ingredient_stock_movement.
CREATE TABLE [stock_takes] (
    [id]                BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [ingredient_id]     BIGINT         NOT NULL,
    [counted_quantity]  DECIMAL(10, 4) NOT NULL,
    [variance]          DECIMAL(10, 4) NOT NULL,
    [note]              NVARCHAR(255)  NULL,
    [recorded_by]       BIGINT         NOT NULL,
    [created_at]        DATETIME2      NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT [FK_stock_takes_ingredient] FOREIGN KEY ([ingredient_id]) REFERENCES [ingredients]([id]),
    CONSTRAINT [FK_stock_takes_recorded_by] FOREIGN KEY ([recorded_by]) REFERENCES [users]([id]),
    CONSTRAINT [CK_stock_takes_counted_quantity] CHECK ([counted_quantity] >= 0)
);

CREATE INDEX [IX_stock_takes_ingredient_id] ON [stock_takes]([ingredient_id]);
