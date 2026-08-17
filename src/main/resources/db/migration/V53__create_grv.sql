-- Stage 5 Part D: goods received. Creates a RECEIVED ingredient_stock_movement; an ingredient's
-- "current cost" is derived as the most recent grv's cost_per_unit, never overwritten directly
-- onto ingredients — preserving full cost history rather than losing it to an overwrite.
CREATE TABLE [grv] (
    [id]              BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [ingredient_id]   BIGINT         NOT NULL,
    [quantity]        DECIMAL(10, 4) NOT NULL,
    [cost_per_unit]   DECIMAL(10, 2) NOT NULL,
    [supplier_name]   NVARCHAR(100)  NOT NULL,
    [note]            NVARCHAR(255)  NULL,
    [received_by]     BIGINT         NOT NULL,
    [received_at]     DATETIME2      NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT [FK_grv_ingredient] FOREIGN KEY ([ingredient_id]) REFERENCES [ingredients]([id]),
    CONSTRAINT [FK_grv_received_by] FOREIGN KEY ([received_by]) REFERENCES [users]([id]),
    CONSTRAINT [CK_grv_quantity] CHECK ([quantity] > 0)
);

CREATE INDEX [IX_grv_ingredient_id] ON [grv]([ingredient_id]);
