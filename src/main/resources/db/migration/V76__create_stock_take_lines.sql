-- Stage 5.2.5: one ingredient's physical count within a stock_takes header. expected_quantity/
-- unit_cost/variance_quantity/variance_value are snapshotted at submission and never
-- recalculated. applied_adjustment_quantity is set only on approval, recomputed against current
-- stock at that moment — it can legitimately differ from variance_quantity if other stock
-- activity happened between submission and review; both are kept, on purpose.
CREATE TABLE [stock_take_lines] (
    [id]                           BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [stock_take_id]                BIGINT         NOT NULL,
    [ingredient_id]                BIGINT         NOT NULL,
    [expected_quantity]            DECIMAL(10, 4) NOT NULL,
    [actual_quantity]              DECIMAL(10, 4) NOT NULL,
    [unit_cost]                    DECIMAL(10, 2) NOT NULL,
    [variance_quantity]            DECIMAL(10, 4) NOT NULL,
    [variance_value]               DECIMAL(10, 2) NOT NULL,
    [applied_adjustment_quantity]  DECIMAL(10, 4) NULL,
    CONSTRAINT [FK_stock_take_lines_stock_take] FOREIGN KEY ([stock_take_id]) REFERENCES [stock_takes]([id]),
    CONSTRAINT [FK_stock_take_lines_ingredient] FOREIGN KEY ([ingredient_id]) REFERENCES [ingredients]([id]),
    CONSTRAINT [CK_stock_take_lines_actual_quantity] CHECK ([actual_quantity] >= 0)
);

CREATE INDEX [IX_stock_take_lines_stock_take_id] ON [stock_take_lines]([stock_take_id]);
